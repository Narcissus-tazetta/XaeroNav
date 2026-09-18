package net.prason.xaeronav.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;

/**
 * 天井のある次元で使う3D粗層の作りかけ・出来上がりを持つ。
 *
 * <p>役割は<b>2つのスレッドの境目を1か所に閉じ込めること</b>。Xaeroの地図はメインスレッドから
 * しか読めず（{@link XaeroMapReader}のスレッド契約）、逆に逆向きDijkstraは数百ミリ秒かかるので
 * メインスレッドで回すとゲームが固まる。そこで<b>地図読み＝メインスレッド、Dijkstra＝ワーカー</b>で
 * 割る。組み上がるまでの数百ミリ秒は従来どおりガイド無しで探索する（線が出ないよりはよい）。
 *
 * <p>組み直すのは目的地・次元が変わったとき、プレイヤーが箱から出かかったとき、
 * {@link #REBUILD_MOVE_BLOCKS}歩いたとき、そして<b>探索が前進できなかったとき</b>。
 * <b>探索のたびに組むのは論外</b>——面積に比例した確保とDijkstraを毎回払うことになるので、
 * どの引き金も{@link #MIN_REBUILD_INTERVAL_MILLIS}で間引く。
 */
final class NetherVoxelGuide {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 地図を何ブロックおきに読むか。格子のセル辺の半分——1セルにつき数点入る粒度で見ないと、
     * 幅の狭い通路が抜け落ちる。
     */
    private static final int SAMPLE_STEP = VoxelTerrain.DEFAULT_CELL_BLOCKS / 2;

    /** 箱の縁からこれだけ内側にいる限りは組み直さない（{@code FLIGHT_COARSE_RECALC}と同じ考え方）。 */
    private static final int REBUILD_INSET_BLOCKS = 32;

    /**
     * 組んだ場所からこれだけ歩いたら組み直す。<b>時間ではなく距離で計る</b>のが要点——
     * 地図が育つのは歩いたぶんだけで、その場に立っている間に組み直しても同じ表しかできない。
     *
     * <p><b>これだけでは詰まったときに組み直せない</b>（{@link #noteStalled}）。詰まっている
     * ときこそ「箱を出た」も「歩いた」も立たないので、前進できなかったことを別の引き金にする。
     */
    private static final double REBUILD_MOVE_BLOCKS = 128.0;

    /**
     * 組み直しの下限間隔。地図読みはメインスレッドなので、条件が何度も立っても
     * これより短い間隔では払わない。
     */
    private static final long MIN_REBUILD_INTERVAL_MILLIS = 15_000L;

    /** Dijkstra専用の1本。探索用のワーカーを塞がないよう分ける。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XaeroNav 3D粗層");
        thread.setDaemon(true);
        return thread;
    });

    /** 世代。組み上がった結果が今も求められているものかを見る。 */
    private final AtomicLong generation = new AtomicLong();

    private volatile Built built;
    private volatile boolean building;
    // 直近の探索が前進できなかった。ワーカースレッド（whenComplete）が立て、forGoalが落とす
    private volatile boolean stalled;
    /** 直前に組もうとした条件。同じ条件で失敗し続けても、地図読みを毎回払わないため。 */
    private Key attempted;
    private long nextAttemptMillis;

    /**
     * この目的地について<b>これまでに地図から見えた</b>床のYの範囲。箱の高さはここから決める
     * （{@link FloorRange}の1回ぶんではなく、積み上げたもの）。
     *
     * <p><b>1回の読みで決めてはいけない。</b>{@code forEachCaveFloor}が見るのは
     * 「そのときXaeroがメモリに載せているレイヤー」で、その集合は歩いている間に入れ替わる——
     * 実機（2026-09-18 15:07〜15:10、プレイヤーは60ブロックしか動いていない）では
     * 床の報告数が3.4万→13.3万→3.4万→8.9万と4倍で往復し、深いレイヤーが載った回では
     * 箱が{@code Y=19..98}から{@code Y=0..89}へ<b>ずり下がって</b>、y90台の歩ける回廊が
     * まるごと箱の外へ出た（格子の歩けるセルが8801→4310）。ガイドはそのたびに丸ごと
     * 差し替わるので、東西どちらの回廊を選ぶかが約20秒ごとに振り直される＝実機の「ぐるぐる」。
     *
     * <p>地図から分かることは歩くほど増えるだけなので、<b>範囲は広がる一方にする</b>。
     * 落下・崖登りでプレイヤーのYが跳んでも{@link VoxelTerrain#boxFor}が広げる側にしか
     * 効かないため、箱は縮まない。
     */
    private Key floorRangeKey;
    private int floorLowest = Integer.MAX_VALUE;
    private int floorHighest = Integer.MIN_VALUE;

    /**
     * これまでに地図から読めた床そのもの（{@link #packFloor}で1本のlongに詰めたもの）。
     * 格子へ流すのはこれで、その回の読みだけではない。
     *
     * <p><b>箱を固定しただけでは足りない。</b>読むレイヤーが振れるのは変わらないので、箱の中身が
     * 入れ替わる——実機（2026-09-18 22:38〜22:39）では溶岩が6,464→602→477、歩けるセルが
     * 6,054→11,614、膨らみが2.22→3.16→1.4で振れ、その直後に経路が64ステップ（目的地まで229）から
     * 259ステップ（目的地まで252）へ<b>遠回りに切り替わっている</b>。
     *
     * <p>模型で測った差（レイヤーの集合を歩きながら振らせた3本）:
     * そのつど組むと最適の1.700/1.286/1.331倍、覚えておくと<b>1.060/1.127/1.205倍</b>で、
     * 地図が完全なときの歩き通しとほぼ一致する＝<b>揺れで失っていた質はほぼ全部戻る</b>。
     *
     * <p>箱の外へ出たものは捨てる（{@link #forgetOutside}）。箱は目的地へ近づくほど縮むので、
     * 覚えている量は歩いても際限なく増えない。
     */
    private final LongOpenHashSet rememberedFloors = new LongOpenHashSet();

    /**
     * 1回の読みで見えた床を全部覚えつつ、Yの範囲も測る。範囲は{@link #rememberFloors}で
     * 積み上げてから箱に使い、床そのものは{@link #rememberedFloors}へ入れて格子へ流す。
     */
    private static final class FloorRange implements XaeroMapReader.FloorVisitor {
        private final LongOpenHashSet into;
        private int lowest = Integer.MAX_VALUE;
        private int highest = Integer.MIN_VALUE;

        FloorRange(LongOpenHashSet into) {
            this.into = into;
        }

        @Override
        public void floor(int x, int z, int floorTopY, boolean lava) {
            lowest = Math.min(lowest, floorTopY);
            highest = Math.max(highest, floorTopY);
            into.add(packFloor(x, z, floorTopY, lava));
        }
    }

    /**
     * 床1つを1本のlongに詰める。X・Zは26ビット（ネザーの座標上限±3.75Mに足りる）、Yは10ビット
     * （{@code -64..959}）、最後の1ビットが溶岩。
     */
    static long packFloor(int x, int z, int floorTopY, boolean lava) {
        return ((long) (x & 0x3FF_FFFF) << 37) | ((long) (z & 0x3FF_FFFF) << 11)
                | ((long) ((floorTopY + 64) & 0x3FF) << 1) | (lava ? 1L : 0L);
    }

    static int unpackX(long floor) {
        return (int) (floor << 1 >> 38);
    }

    static int unpackZ(long floor) {
        return (int) (floor << 27 >> 38);
    }

    static int unpackY(long floor) {
        return (int) ((floor >>> 1) & 0x3FF) - 64;
    }

    static boolean unpackLava(long floor) {
        return (floor & 1L) != 0L;
    }

    /** どの条件に対する表か。ここが変われば、間隔を待たずに組み直す。 */
    private record Key(ResourceKey<Level> dimension, BlockPos goal, boolean lavaPassable) {
    }

    /** 組み上がった表と、それを組んだときの箱・立っていた場所。 */
    private record Built(Key key, SearchBounds box, BlockPos from, CostToGo costToGo) {
    }

    /**
     * 今の目的地のガイド。無ければ組み始めて{@code null}を返す（呼び出し側は従来どおり進む）。
     * <b>メインスレッドから呼ぶこと。</b>
     *
     * @param goal <b>最終目的地</b>。中間目標を渡してはいけない——ガイドの起点が動くと、
     *             区間ごとに別方向を指す表になる
     */
    CostToGo forGoal(LevelHeightAccessor level, ResourceKey<Level> dimension, BlockPos player,
                      BlockPos goal, boolean lavaPassable) {
        Key key = new Key(dimension, goal, lavaPassable);
        Built current = built;
        boolean usable = current != null && current.key().equals(key);
        boolean stale = !usable || stalled || !insideBox(current.box(), player)
                || horizontal(current.from(), player) >= REBUILD_MOVE_BLOCKS;
        // 条件が変わったときだけ間隔を飛ばす。同じ条件のまま失敗し続けるとき、間隔が無いと
        // 探索のたびにメインスレッドで地図を読み直すことになる
        boolean mayAttempt = !key.equals(attempted) || MonotonicTime.millis() >= nextAttemptMillis;
        if (stale && !building && mayAttempt) {
            start(level, key, player);
        }
        // 組み直し中でも、同じ目的地の古い表は使い続ける（無ガイドへ落とすより良い）
        return usable ? current.costToGo() : null;
    }

    /**
     * 直近の探索が前進できなかったことを伝える。次の{@link #forGoal}で組み直しの引き金になる
     * （{@link #MIN_REBUILD_INTERVAL_MILLIS}の間引きは掛かったまま）。
     *
     * <p><b>詰まっているときこそ組み直したい。</b>{@link #start}が撃つ{@code requestLoad}は
     * 非同期でその回には効かないので、薄い地図で組んだ表は「読み込みが済んだ」だけでは
     * 更新されない。実機（2026-09-09）では未読み込みリージョン33本が3秒後に届いていたのに、
     * その場で詰まっているせいで距離の引き金が立たず、43秒後まで薄い表を使い続けていた。
     *
     * <p><b>ワーカースレッドから呼ばれる</b>（探索の{@code whenComplete}）。
     */
    void noteStalled() {
        stalled = true;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = (double) a.getX() - b.getX();
        double dz = (double) a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean insideBox(SearchBounds box, BlockPos player) {
        return player.getX() >= box.minX() + REBUILD_INSET_BLOCKS
                && player.getX() <= box.maxX() - REBUILD_INSET_BLOCKS
                && player.getZ() >= box.minZ() + REBUILD_INSET_BLOCKS
                && player.getZ() <= box.maxZ() - REBUILD_INSET_BLOCKS;
    }

    /**
     * 地図をメインスレッドで格子へ写し、Dijkstraだけワーカーへ投げる。
     *
     * <p>地図は<b>2回読む</b>。1回目は床のあるYの範囲を測るだけで、箱の高さをそこから決める
     * （{@link VoxelTerrain#boxFor}）。実機の地図読みは13msなので、2回でも安い。
     *
     * <p>目的地は{@code StanceFinder}へ通していない生の座標。ガイドの起点は上下24・左右16まで
     * 探して決める（{@code VoxelCostToGo}）ので、数ブロックのずれは吸収される。
     */
    private void start(LevelHeightAccessor level, Key key, BlockPos player) {
        stalled = false;
        attempted = key;
        nextAttemptMillis = MonotonicTime.millis() + MIN_REBUILD_INTERVAL_MILLIS;
        if (!XaeroPresence.mapPresent()) {
            return;
        }
        BlockPos goal = key.goal();
        long began = MonotonicTime.millis();
        int minX = Math.min(player.getX(), goal.getX()) - VoxelTerrain.MARGIN_BLOCKS;
        int minZ = Math.min(player.getZ(), goal.getZ()) - VoxelTerrain.MARGIN_BLOCKS;
        int sizeX = Math.max(player.getX(), goal.getX()) + VoxelTerrain.MARGIN_BLOCKS - minX + 1;
        int sizeZ = Math.max(player.getZ(), goal.getZ()) + VoxelTerrain.MARGIN_BLOCKS - minZ + 1;
        int referenceY = (player.getY() + goal.getY()) / 2;
        // 実際の床範囲で作る箱は、少なくとも始点・目的地とその余白を含む。この最小の箱でさえ
        // 上限へ収まらないなら、地図を何百万セル走査しても最後に必ず捨てることになる。
        SearchBounds minimumBox = VoxelTerrain.boxFor(level, player, goal,
                Math.min(player.getY(), goal.getY()), Math.max(player.getY(), goal.getY()));
        if (VoxelTerrain.cellBlocksFor(minimumBox) == 0) {
            LOGGER.debug("XaeroNav: 3D粗層の範囲が大きすぎるため地図読みを省略します ({})", minimumBox);
            return;
        }
        // 要求しないと、Xaeroが既にメモリへ載せているリージョンしか読めない。要求は非同期なので
        // この回には間に合わないが、次の組み直しで効く
        XaeroMapReader.requestLoad(minX >> 4, minZ >> 4,
                ((minX + sizeX - 1) >> 4) - (minX >> 4) + 1,
                ((minZ + sizeZ - 1) >> 4) - (minZ >> 4) + 1, referenceY);

        // 地図は<b>1回だけ</b>読む。見えた床はそのまま覚えておき、箱が決まってから覚えている
        // ぶんを格子へ流す。箱のYを次元の全高に取ると、天井より上の空きが格子の半分を占めて
        // 「天井の上を橋で走る」ガイドになる（VoxelTerrain#boxFor）ので、高さは床から決める
        forgetOutside(key, minX, minZ, sizeX, sizeZ);
        FloorRange range = new FloorRange(rememberedFloors);
        int floors = XaeroMapReader.forEachCaveFloor(minX, minZ, sizeX, sizeZ, referenceY,
                SAMPLE_STEP, range);
        if (floors == 0 && rememberedFloors.isEmpty()) {
            // この範囲の地図をXaeroがまだ持っていない。床が1枚も無い格子から作る表は
            // 直線距離を一定倍しただけのもので、幾何ヒューリスティックと同じことしか言わない
            LOGGER.debug("XaeroNav: 3D粗層のもとになる地図がありません ({}, {})", minX, minZ);
            return;
        }
        rememberFloors(key, range);
        SearchBounds box = VoxelTerrain.boxFor(level, player, goal, floorLowest, floorHighest);
        VoxelTerrain terrain = VoxelTerrain.of(box, key.lavaPassable());
        if (terrain == null) {
            // 目的地が遠すぎて、いちばん粗い格子でも収まらない
            LOGGER.debug("XaeroNav: 3D粗層の箱が大きすぎます ({})", box);
            return;
        }
        LongIterator remembered = rememberedFloors.iterator();
        while (remembered.hasNext()) {
            long floor = remembered.nextLong();
            terrain.markFloor(unpackX(floor), unpackZ(floor), unpackY(floor), unpackLava(floor));
        }
        long read = MonotonicTime.millis() - began;
        int rememberedCount = rememberedFloors.size();

        building = true;
        long myGeneration = generation.incrementAndGet();
        CompletableFuture.supplyAsync(() ->
                        VoxelCostToGo.build(terrain, goal, () -> generation.get() != myGeneration), worker)
                .whenComplete((guide, error) -> {
                    building = false;
                    if (generation.get() != myGeneration) {
                        return;
                    }
                    if (error != null) {
                        LOGGER.error("XaeroNav: 3D粗層の作成に失敗しました", error);
                        return;
                    }
                    if (guide == null) {
                        // 黙ってガイド無しへ落とさない。遠距離ネザーで線が出ないのはまさにこれ
                        LOGGER.info("XaeroNav: 3D粗層の起点を決められませんでした (目的地={}, 箱={})",
                                goal.toShortString(), box);
                        return;
                    }
                    built = new Built(key, box, player, guide);
                    // 膨らみ＝始点の見積もり÷直線距離。<b>この層が効いているかはここだけで分かる</b>
                    // ——1倍付近なら幾何ヒューリスティックと同じことしか言っていない。
                    // 箱も出す: Yの範囲が歩ける高さより広いと、格子の大半が天井の上の空きになる
                    LOGGER.info("XaeroNav: 3D粗層 (床={}, {}, セル={}, 辺={}, 膨らみ{}倍, 箱={}, "
                                    + "今回の床Y={}, 覚えている床={}, 地図{}ms, Dijkstra{}ms)",
                            floors, terrain.breakdown(), terrain.cellCount(), terrain.cellBlocks(),
                            round(inflation(guide, player, goal)), box,
                            floors == 0 ? "読めず" : range.lowest + ".." + range.highest, rememberedCount,
                            read, MonotonicTime.millis() - began - read);
                });
    }

    /**
     * 目的地が変わったら覚えている床を捨て、そうでなければ今度の走査範囲の外にあるものを捨てる。
     *
     * <p>走査範囲は目的地へ近づくほど縮むので、これだけで覚えている量は頭打ちになる。
     */
    private void forgetOutside(Key key, int minX, int minZ, int sizeX, int sizeZ) {
        if (!key.equals(floorRangeKey)) {
            rememberedFloors.clear();
            return;
        }
        LongIterator floors = rememberedFloors.iterator();
        while (floors.hasNext()) {
            long floor = floors.nextLong();
            int x = unpackX(floor);
            int z = unpackZ(floor);
            if (x < minX || x >= minX + sizeX || z < minZ || z >= minZ + sizeZ) {
                floors.remove();
            }
        }
    }

    /**
     * 今回見えた床のYを、この目的地についての範囲へ足す。目的地が変われば数え直す。
     *
     * <p>広げる側にしか動かさないのが要点（{@link #floorRangeKey}）。
     */
    private void rememberFloors(Key key, FloorRange range) {
        if (!key.equals(floorRangeKey)) {
            floorRangeKey = key;
            floorLowest = Integer.MAX_VALUE;
            floorHighest = Integer.MIN_VALUE;
        }
        floorLowest = Math.min(floorLowest, range.lowest);
        floorHighest = Math.max(floorHighest, range.highest);
    }

    /** 始点での見積もりが直線距離の何倍か。1倍付近なら、この層は何も足していない。 */
    private static double inflation(VoxelCostToGo guide, BlockPos player, BlockPos goal) {
        double straight = Heuristic.estimate(player.getX(), player.getY(), player.getZ(),
                goal.getX(), goal.getY(), goal.getZ());
        return straight <= 0.0 ? 0.0
                : guide.estimate(player.getX(), player.getY(), player.getZ()) / straight;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 目的地が変わった・案内を止めた。次に要求されたら組み直す。 */
    void clear() {
        generation.incrementAndGet();
        built = null;
        building = false;
        stalled = false;
        attempted = null;
        nextAttemptMillis = 0L;
        floorRangeKey = null;
        floorLowest = Integer.MAX_VALUE;
        floorHighest = Integer.MIN_VALUE;
        rememberedFloors.clear();
    }
}
