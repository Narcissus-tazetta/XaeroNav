package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 1ブロック分の探索用データを{@code long}に詰めた表現。
 *
 * <p>レコードとして持つと、1セルあたりMapのエントリ込みで100バイト近くかかる。longに詰めることで
 * fastutilのプリミティブMapへそのまま格納でき、キャッシュ1件あたりのアロケーションがゼロになる。
 *
 * <p>掘削コストは{@code float}精度で保持する。値はtick数（数十〜数千）なので有効桁は十分で、
 * {@link net.prason.xaeronav.pathfinding.cost.ActionCosts#INFEASIBLE}（正の無限大）も
 * floatとの往復で正確に保存される。
 */
public final class CellData {

    /**
     * 探索範囲外・未ロードチャンクを表す。{@link CellData}のどの述語も{@code false}を返すため、
     * 「触れない・立てない・掘れない」＝経路が伸びない、という安全側の扱いになる。
     */
    public static final long ABSENT = 0L;

    // フラグはパッケージ内公開に留める。同じパッケージのテスト（FakeCells）が
    // BlockStateを介さずにセルを組み立てられるようにするため —
    // flagsOf(BlockState)はMinecraftのレジストリ起動を要求するので単体テストからは呼べない。
    static final long PRESENT = 1L;
    static final long PASSABLE_EMPTY = 1L << 1;
    static final long WATER = 1L << 2;
    static final long LAVA = 1L << 3;
    static final long STANDABLE = 1L << 4;
    static final long FALLING_BLOCK = 1L << 5;
    static final long UNRESOLVED_SHAPE = 1L << 6;
    static final long CLIMBABLE = 1L << 7;
    static final long OPENABLE = 1L << 8;
    static final long COBWEB = 1L << 9;
    static final long HAZARD = 1L << 10;
    /**
     * その上を進むにはスニークが要る床。マグマブロックだけ——踏むとダメージを受けるが、
     * バニラの{@code isSteppingCarefully}（スニーク中）なら無傷で渡れる。通行可否ではなく
     * 「案内に一言添える必要があるか」の印なので{@link #HAZARD}とは分けてある。
     */
    static final long SNEAK_REQUIRED = 1L << 11;
    /**
     * ここへブロックを置けるか（バニラの{@code BlockBehaviour.BlockStateBase#canBeReplaced}）。
     *
     * <p>当たり判定が無いことと、そこへ置けることは別。しだれツタ・ねじれツタ・洞窟のツタ・
     * 松明・レール・花は体が通り抜けられるが<b>replaceableではない</b>ので、狙って置いても
     * {@code BlockPlaceContext#getClickedPos}が隣のセルを返す——案内した位置には絶対に置かれない。
     * 普通のツタ({@code vine})だけはreplaceableなので置ける。
     */
    static final long REPLACEABLE = 1L << 12;

    private static final long OCCUPIABLE = PASSABLE_EMPTY | WATER | CLIMBABLE;

    /**
     * 移動速度の倍率（{@link #travelSpeedFactor}）を100倍した値を置く位置。
     * 0は「未設定＝等速」を表す（{@link #ABSENT}のセルもここが0になるので辻褄が合う）。
     */
    private static final int SPEED_FACTOR_SHIFT = 16;
    private static final long SPEED_FACTOR_MASK = 0xFFL << SPEED_FACTOR_SHIFT;

    /** 当たり判定の境界がセルの端に接しているかを見るときの許容誤差。 */
    private static final double EDGE_EPSILON = 1.0E-7;

    /**
     * 「滑る床」とみなす摩擦の下限。普通のブロックは0.6、氷・氷塊・青氷だけが0.98以上になる。
     * スライムブロック(0.8)は跳ねるだけで速くはならないので、この間に線を引いて外す。
     */
    private static final float SLIPPERY_FRICTION = 0.9f;

    /** 氷の上を進むときの速度倍率（{@link #travelSpeedFactor}）。 */
    private static final float ICE_SPEED_FACTOR = 1.2f;

    /**
     * マグマブロックの上を進むときの速度倍率（{@link #travelSpeedFactor}）。バニラの
     * {@code isSteppingCarefully}（スニーク中）はダメージを受けない代わりに移動速度が
     * 疾走の約0.3倍まで落ちる——通行不能にはせず、その遅さをそのままコストにする。
     */
    private static final float MAGMA_SPEED_FACTOR = 0.3f;

    private CellData() {
    }

    /**
     * {@link BlockState}から掘削コスト以外のフラグを判定する。形状・流体の問い合わせは
     * {@code BlockState}側のキャッシュを読むだけでlevelを参照しないため、どのスレッドからでも呼べる。
     *
     * <p>探索（ワーカースレッド）と経路の再確認（メインスレッド）で同じ判定を共有するためのもの。
     * ここが食い違うと、探索が通した経路を再確認が即座に無効と判断して再計算が止まらなくなる。
     *
     * <p>戻り値に掘削コストは含まれない（{@link #digTicks}は0を返す）。
     * 探索に使う完全なセルデータは{@link #withDigTicks}で組み立てること。
     */
    public static long flagsOf(BlockState state) {
        // 危険の判定を先に置くのは、パウダースノーのように「形状が動的（革のブーツで変わる）」でも
        // あり「入ると危険」でもあるブロックを、より意味の近いHAZARDとして扱うため。
        // どちらも進入不可という結論は同じなので、探索の挙動は変わらない
        if (harmful(state)) {
            // 触れた時点で事故になるセル。進入も足場も許さず、掘って通す対象にもしない
            return PRESENT | HAZARD;
        }

        if (state.getBlock().hasDynamicShape()) {
            // 形状の解決に実際のlevelを要求するブロック。ワーカースレッドからは正しく評価できないので、
            // 通ることも立つことも掘ることもできない障害物として扱う。
            return PRESENT | UNRESOLVED_SHAPE;
        }

        FluidState fluid = state.getFluidState();
        boolean water = fluid.is(FluidTags.WATER);
        boolean lava = fluid.is(FluidTags.LAVA);
        // waterloggedな階段・ハーフブロック・フェンスは「流体は水」でありながら当たり判定を持つ。
        // 流体だけを見てWATERを立てると、固体を泳いで通り抜ける経路ができてしまう
        VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        boolean collisionEmpty = collision.isEmpty();
        boolean openable = openableByHand(state);
        // 開いたドア・フェンスゲート・トラップドアは薄い板の当たり判定が残るので当たり判定は空にならない。
        // バニラのモブ経路探索と同じ判定（levelを参照しないのでワーカースレッドから呼べる）でくぐれるかを見る
        boolean passable = collisionEmpty
                || openable && state.isPathfindable(PathComputationType.LAND);

        long flags = PRESENT;
        if (water && passable) {
            flags |= WATER;
        }
        if (lava) {
            flags |= LAVA;
        }
        if (!water && !lava && passable) {
            flags |= PASSABLE_EMPTY;
        }
        if (openable && !passable) {
            flags |= OPENABLE;
        }
        if (state.is(BlockTags.CLIMBABLE)) {
            flags |= CLIMBABLE;
        }
        if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP) || walkableTop(collision)) {
            flags |= STANDABLE;
        }
        if (state.getBlock() instanceof FallingBlock) {
            flags |= FALLING_BLOCK;
        }
        if (state.getBlock() instanceof WebBlock) {
            flags |= COBWEB;
        }
        if (state.getBlock() instanceof MagmaBlock) {
            flags |= SNEAK_REQUIRED;
        }
        if (state.canBeReplaced()) {
            // 引数無しの版はreplaceableフラグを読むだけでlevelを参照しないので、
            // ワーカースレッドから呼べる（BlockPlaceContextを取る版は「同じブロックを
            // 手に持っているとき」の話なので、普通のブロックを置く判定には使わない）
            flags |= REPLACEABLE;
        }
        return flags | speedFactorBits(state);
    }

    /**
     * 上に立てる床か。{@link BlockState#isFaceSturdy}だけでは足りない。
     *
     * <p>{@code isFaceSturdy}は「セル境界(y=1)の上面が完全な1×1か」を見るため、実際には普通に
     * 歩ける床の多くが外れてしまう — 階段・ハーフブロック・農地・土の道・葉・ホッパー・大釜が
     * すべて「立てない」になり、村の道も家の階段も通れなくなる（足場が無い場所へは移動そのものが
     * 生成されないので、コストがずれるのではなく経路が消える）。
     *
     * <p>そこで当たり判定が水平方向に1マスを覆っているかで判定する。合わせて「自分のセルより上へ
     * はみ出さない」ことを求め、柵・塀・フェンスゲート（高さ1.5）を除く — これらは上に立てはするが、
     * 下から普通のジャンプ（1.25マス）では登れないので、登る移動を作らせてはいけない。
     */
    private static boolean walkableTop(VoxelShape collision) {
        if (collision.isEmpty()) {
            return false;
        }
        AABB bounds = collision.bounds();
        return bounds.minX <= EDGE_EPSILON && bounds.maxX >= 1.0 - EDGE_EPSILON
                && bounds.minZ <= EDGE_EPSILON && bounds.maxZ >= 1.0 - EDGE_EPSILON
                && bounds.maxY <= 1.0 + EDGE_EPSILON;
    }

    /**
     * 触れた時点で事故になるブロック。当たり判定が無く探索器からは「空気と同じ」に見えるものが
     * 多いが、入れば燃える・凍える・別次元へ飛ばされる。
     *
     * <p>掘って通す対象にもしない。掘っている間ずっと隣に立ち続けることになるので、
     * 迂回した方が安全でたいてい安い。
     */
    private static boolean harmful(BlockState state) {
        Block block = state.getBlock();
        // MagmaBlockはここに含めない。当たり判定を持つ完全な足場で、スニークすれば無傷で歩ける
        // （バニラの{@code isSteppingCarefully}）——遅いが安全な道として通行可にし、
        // {@link #travelSpeedFactor}でそのぶんの遅さをコストに反映する
        return block instanceof BaseFireBlock                       // 火・魂の火。当たり判定が無い
                || block instanceof SweetBerryBushBlock             // 棘のダメージ＋大幅な減速
                || block instanceof WitherRoseBlock                 // 接触で衰弱
                || block instanceof PowderSnowBlock                 // 落ちると凍える。雪原では地面と見分けがつかない
                || block instanceof SculkShriekerBlock              // 踏むとウォーデンを呼ぶ
                || block instanceof BigDripleafBlock                // 乗ると傾いて下へ落とされる
                || (block instanceof CampfireBlock && state.getValue(CampfireBlock.LIT))
                // 通り抜けた瞬間に別次元へ送られる。エンドポータルは戻る手段も無い
                || block instanceof NetherPortalBlock
                || block instanceof EndPortalBlock
                || block instanceof EndGatewayBlock
                || state.is(Blocks.LAVA_CAULDRON)
                || state.is(Blocks.POWDER_SNOW_CAULDRON);
    }

    /** このブロックの上を進むときの速度倍率を100倍して詰める。等速（1.0）なら詰めない。 */
    private static long speedFactorBits(BlockState state) {
        return speedFactorBits(travelSpeedFactor(state));
    }

    private static long speedFactorBits(float factor) {
        if (factor == 1.0f) {
            return 0L;
        }
        // 0に丸めると「未設定＝等速」と区別が付かなくなるので、下は1（0.01倍）で止める
        long scaled = Math.max(1L, Math.round(factor * 100.0f));
        return (scaled << SPEED_FACTOR_SHIFT) & SPEED_FACTOR_MASK;
    }

    /**
     * このブロックの上を進むときの速度倍率（1.0で等速）。
     *
     * <p>遅くなる側は{@code Block#getSpeedFactor}をそのまま使う（ソウルサンド・蜂蜜ブロックの0.4）。
     *
     * <p>速くなる側は氷だけを見る。氷の速さは速度係数ではなく摩擦（既定0.6に対して0.98〜0.989）から
     * 来ていて、走るだけの定常速度はほぼ変わらない一方、走り幅跳びを続けると着地のたびの減速が
     * 小さいぶん明確に速くなる。加速の途中経過まで正しく再現するには区間の長さを見る必要があるので、
     * ここは「氷はいくらか速い」という一定倍率の近似にとどめる。値は素の疾走(5.6m/s)と
     * 平地の走り幅跳び(7.1m/s)の間に収まる控えめな側に置いてある。
     */
    private static float travelSpeedFactor(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof MagmaBlock) {
            return MAGMA_SPEED_FACTOR;
        }
        float speedFactor = block.getSpeedFactor();
        if (speedFactor < 1.0f) {
            return speedFactor;
        }
        return block.getFriction() >= SLIPPERY_FRICTION ? ICE_SPEED_FACTOR : 1.0f;
    }

    /** レッドストーンを使わず手で開け閉めできるドア・フェンスゲート・トラップドアか。 */
    private static boolean openableByHand(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand();
        }
        if (block instanceof FenceGateBlock) {
            // レッドストーン専用のフェンスゲートは存在しない
            return true;
        }
        if (block instanceof TrapDoorBlock) {
            // TrapDoorBlock#getType()がprotectedなのでBlockSetTypeを直接見られない。
            // バニラでレッドストーンでしか開かないトラップドアは鉄製だけ
            return !state.is(Blocks.IRON_TRAPDOOR);
        }
        return false;
    }

    public static long withDigTicks(long flags, double digTicks) {
        return flags | (Integer.toUnsignedLong(Float.floatToRawIntBits((float) digTicks)) << 32);
    }

    /** 速度倍率を差し込む。実データは{@link #flagsOf}が詰めるので、これはテストの地形記述用。 */
    static long withSpeedFactor(long flags, float factor) {
        return flags | speedFactorBits(factor);
    }

    public static boolean present(long cell) {
        return (cell & PRESENT) != 0L;
    }

    public static boolean passableEmpty(long cell) {
        return (cell & PASSABLE_EMPTY) != 0L;
    }

    public static boolean water(long cell) {
        return (cell & WATER) != 0L;
    }

    public static boolean lava(long cell) {
        return (cell & LAVA) != 0L;
    }

    public static boolean standable(long cell) {
        return (cell & STANDABLE) != 0L;
    }

    public static boolean fallingBlock(long cell) {
        return (cell & FALLING_BLOCK) != 0L;
    }

    /** ワーカースレッドから形状を評価できなかったブロックか。掘削対象にしてはならない。 */
    public static boolean unresolvedShape(long cell) {
        return (cell & UNRESOLVED_SHAPE) != 0L;
    }

    /**
     * 蜘蛛の巣か。当たり判定が無いので{@link #passableEmpty}としては空気と区別がつかないが、
     * 実際には移動量に0.25が掛かる（{@code WebBlock#entityInside} → {@code Entity#move}）。
     */
    public static boolean cobweb(long cell) {
        return (cell & COBWEB) != 0L;
    }

    /** 梯子・ツタ・足場など、掴んで上下できるか。 */
    public static boolean climbable(long cell) {
        return (cell & CLIMBABLE) != 0L;
    }

    /**
     * 入ると害があるセルか（炎・マグマ・パウダースノー・ウィザーローズ・ポータルなど）。
     * 進入も足場も不可で、掘削もできない（{@code ChunkView}が掘削コストを無限大にする）。
     */
    public static boolean hazard(long cell) {
        return (cell & HAZARD) != 0L;
    }

    /**
     * このセルの上を進むときの速度倍率（1.0で等速。ソウルサンド・蜂蜜ブロックは0.4、氷は1.2）。
     * バニラは足元のセルの係数を使い、それが1.0なら1つ下のブロックを見る（{@code Entity#getBlockSpeedFactor}）。
     */
    /** ここへブロックを置けるか。{@link #REPLACEABLE}参照。 */
    public static boolean replaceable(long cell) {
        return (cell & REPLACEABLE) != 0;
    }

    /** その上を進むにはスニークが要る床か（マグマブロック）。 */
    public static boolean sneakRequired(long cell) {
        return (cell & SNEAK_REQUIRED) != 0;
    }

    public static double speedFactor(long cell) {
        long raw = (cell & SPEED_FACTOR_MASK) >>> SPEED_FACTOR_SHIFT;
        return raw == 0L ? 1.0 : raw / 100.0;
    }

    /** 閉じているが手で開けて通れるか（ドア・フェンスゲート・トラップドア）。壊す対象ではない。 */
    public static boolean openable(long cell) {
        return (cell & OPENABLE) != 0L;
    }

    /** 掘削なしでプレイヤーの体が占有できるか（空気、当たり判定を持たない水、梯子・ツタ）。 */
    public static boolean occupiableWithoutDigging(long cell) {
        return (cell & OCCUPIABLE) != 0L;
    }

    public static double digTicks(long cell) {
        return Float.intBitsToFloat((int) (cell >>> 32));
    }
}
