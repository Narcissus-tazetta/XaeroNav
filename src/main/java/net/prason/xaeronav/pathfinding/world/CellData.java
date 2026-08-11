package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * 1ブロック分の探索用データを{@code long}に詰めた表現（design doc §4-5）。
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

    private static final long PRESENT = 1L;
    private static final long PASSABLE_EMPTY = 1L << 1;
    private static final long WATER = 1L << 2;
    private static final long LAVA = 1L << 3;
    private static final long STANDABLE = 1L << 4;
    private static final long FALLING_BLOCK = 1L << 5;
    private static final long UNRESOLVED_SHAPE = 1L << 6;
    private static final long CLIMBABLE = 1L << 7;
    private static final long OPENABLE = 1L << 8;
    private static final long COBWEB = 1L << 9;

    private static final long OCCUPIABLE = PASSABLE_EMPTY | WATER | CLIMBABLE;

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
        boolean collisionEmpty = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
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
        if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)) {
            flags |= STANDABLE;
        }
        if (state.getBlock() instanceof FallingBlock) {
            flags |= FALLING_BLOCK;
        }
        if (state.getBlock() instanceof WebBlock) {
            flags |= COBWEB;
        }
        return flags;
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
