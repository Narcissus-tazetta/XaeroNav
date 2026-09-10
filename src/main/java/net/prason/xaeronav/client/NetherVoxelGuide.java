package net.prason.xaeronav.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
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

    /** 1回目の読みで測る、床のあるYの範囲。箱の高さをここから決める。 */
    private static final class FloorRange implements XaeroMapReader.FloorVisitor {
        private int lowest = Integer.MAX_VALUE;
        private int highest = Integer.MIN_VALUE;

        @Override
        public void floor(int x, int z, int floorTopY, boolean lava) {
            lowest = Math.min(lowest, floorTopY);
            highest = Math.max(highest, floorTopY);
        }
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
        boolean mayAttempt = !key.equals(attempted) || System.currentTimeMillis() >= nextAttemptMillis;
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
        nextAttemptMillis = System.currentTimeMillis() + MIN_REBUILD_INTERVAL_MILLIS;
        if (!XaeroPresence.mapPresent()) {
            return;
        }
        BlockPos goal = key.goal();
        long began = System.currentTimeMillis();
        int minX = Math.min(player.getX(), goal.getX()) - VoxelTerrain.MARGIN_BLOCKS;
        int minZ = Math.min(player.getZ(), goal.getZ()) - VoxelTerrain.MARGIN_BLOCKS;
        int sizeX = Math.max(player.getX(), goal.getX()) + VoxelTerrain.MARGIN_BLOCKS - minX + 1;
        int sizeZ = Math.max(player.getZ(), goal.getZ()) + VoxelTerrain.MARGIN_BLOCKS - minZ + 1;
        int referenceY = (player.getY() + goal.getY()) / 2;
        // 要求しないと、Xaeroが既にメモリへ載せているリージョンしか読めない。要求は非同期なので
        // この回には間に合わないが、次の組み直しで効く
        XaeroMapReader.requestLoad(minX >> 4, minZ >> 4,
                ((minX + sizeX - 1) >> 4) - (minX >> 4) + 1,
                ((minZ + sizeZ - 1) >> 4) - (minZ >> 4) + 1, referenceY);

        // 1回目は<b>床のある高さを測るだけ</b>。箱のYを次元の全高に取ると、天井より上の空きが
        // 格子の半分を占めて「天井の上を橋で走る」ガイドになる（VoxelTerrain#boxFor）
        FloorRange range = new FloorRange();
        int floors = XaeroMapReader.forEachCaveFloor(minX, minZ, sizeX, sizeZ, referenceY,
                SAMPLE_STEP, range);
        if (floors == 0) {
            // この範囲の地図をXaeroがまだ持っていない。床が1枚も無い格子から作る表は
            // 直線距離を一定倍しただけのもので、幾何ヒューリスティックと同じことしか言わない
            LOGGER.debug("XaeroNav: 3D粗層のもとになる地図がありません ({}, {})", minX, minZ);
            return;
        }
        SearchBounds box = VoxelTerrain.boxFor(level, player, goal, range.lowest, range.highest);
        VoxelTerrain terrain = VoxelTerrain.of(box, key.lavaPassable());
        if (terrain == null) {
            // 目的地が遠すぎて、いちばん粗い格子でも収まらない
            LOGGER.debug("XaeroNav: 3D粗層の箱が大きすぎます ({})", box);
            return;
        }
        XaeroMapReader.forEachCaveFloor(minX, minZ, sizeX, sizeZ, referenceY, SAMPLE_STEP,
                terrain::markFloor);
        long read = System.currentTimeMillis() - began;

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
                                    + "地図{}ms, Dijkstra{}ms)",
                            floors, terrain.breakdown(), terrain.cellCount(), terrain.cellBlocks(),
                            round(inflation(guide, player, goal)), box,
                            read, System.currentTimeMillis() - began - read);
                });
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
    }
}
