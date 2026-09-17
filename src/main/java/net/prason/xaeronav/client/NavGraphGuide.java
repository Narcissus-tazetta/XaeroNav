package net.prason.xaeronav.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * 天井の無い次元（現世・エンド）で使う航法グラフのガイドの作りかけ・出来上がりを持つ。
 *
 * <p>読み込み済みの窓の中を、探索と同じ移動生成でセクションごとに組み（{@link NavGraph}）、目的地までの残りコストを
 * 逆Dijkstraで作る（{@link WindowField}）。窓の中は正確なので、探索は目的地をそのまま重み1.0で狙える
 * （歩き通しの実測: 広域長距離1.017/1.030倍・エンド1.014/1.032倍、層1の中間目標へ寄る現行は1.067/1.165・1.122/未到達）。
 *
 * <p>スレッドの境目は{@link NetherVoxelGuide}と同じ形——メインスレッドでチャンクの参照だけを集め、組むのはワーカー。
 * 組み上がるまでは{@code null}を返し、呼び出し側は従来の探索（中間目標へ寄る）で進む。
 */
final class NavGraphGuide {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 窓の半径（ブロック）。描画距離がこれより広くてもここで切る。
     *
     * <p>質と値段を測ったのがこの幅だけで、広げると辺の数が面積に比例して増える（この幅で広域の窓に辺2,700万本・
     * 150〜210MB）。探索の箱も同じ幅で切ること——窓の外ではガイドが層1か幾何の推定に落ちる。
     */
    static final int WINDOW_BLOCKS = 160;

    /**
     * 組んだ中心からこれだけ歩いたら組み直す。
     *
     * <p>歩き通しの模型は区間ごとに今の位置で組み直していて（1区間で歩くのは16ブロック）、測った質はその前提。
     */
    private static final int REBUILD_MOVE_BLOCKS = 16;

    /** 詰まったときに捨てるチャンクの半径。掘る・置くはたいてい自分の足元で起きる。 */
    private static final int STALL_INVALIDATE_CHUNKS = 1;

    /**
     * 詰まったことによる組み直しの下限間隔。詰まりは同じ場所で何度も続くので、間引かないと
     * 足元の数百セクションを探索のたびに組み直すことになる。
     */
    private static final long STALL_REBUILD_INTERVAL_MILLIS = 15_000L;

    /** 並列度。メインスレッド（描画）に1コア残す。 */
    private static final int WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    /** 実機のログを出す間隔。組み直しは歩くたびに走るので、毎回出すと洪水になる。 */
    private static final long LOG_INTERVAL_MILLIS = 10_000L;

    /** 組み立ての段取りを回す1本。探索用のワーカーを塞がないよう分ける。 */
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "XaeroNav 航法グラフ");
        thread.setDaemon(true);
        return thread;
    });

    /** セクションを並べて組む手。段取りの1本も手を動かすので、これは1本少ない。 */
    private final @Nullable ExecutorService pool = WORKERS <= 1 ? null
            : Executors.newFixedThreadPool(WORKERS - 1, new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger();

                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "XaeroNav 航法グラフ-" + count.incrementAndGet());
                    thread.setDaemon(true);
                    // 描画より後に回す。組み上がりが遅れても探索は従来どおり進むが、フレームが落ちると遊べない
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });

    private final AtomicLong generation = new AtomicLong();
    private final ChangeGate<Boolean> logGate = new ChangeGate<>();

    /**
     * どの条件に対するグラフか。ここが変われば捨てて組み直す——辺は掘れるか・置けるかで変わり
     * （{@link ChunkView}が移動生成に渡す）、奈落の上の橋は目的地へ向かう向きにしか張られない。
     */
    private record Key(ResourceKey<Level> dimension, BlockPos goal, MovementOptions options, boolean canPlaceBlocks,
                       int window) {
    }

    private record Built(Key key, BlockPos center, WindowField field) {
    }

    private volatile @Nullable Built built;
    private volatile boolean building;
    // 直近の探索が前進できなかった。ワーカースレッド（whenComplete）が立て、forGoalが落とす
    private volatile boolean stalled;
    private long nextStallRebuildMillis;

    /** 段取りの1本だけが触る。 */
    private @Nullable NavGraph graph;
    private @Nullable Key graphKey;
    private @Nullable CoarseMap farSource;
    private FarField far = FarField.UNKNOWN;

    /**
     * 今の目的地のガイド。無ければ組み始めて{@code null}を返す。<b>メインスレッドから呼ぶこと。</b>
     *
     * @param coarseMap 窓の外の推定に使う層1の地図。無ければ{@code null}（窓の外は幾何下限）
     * @return 組み直し中でも、同じ条件の古いガイドがあればそれ
     */
    @Nullable WindowField forGoal(Level level, Player player, BlockPos goal, int renderRadius, MovementOptions options,
                               @Nullable CoarseMap coarseMap) {
        BlockPos at = player.blockPosition();
        int window = Math.min(WINDOW_BLOCKS, renderRadius);
        Key key = new Key(level.dimension(), goal, options, canPlaceBlocks(player, options), window);
        Built current = built;
        boolean usable = current != null && current.key().equals(key);
        boolean moved = !usable || Math.max(Math.abs(at.getX() - current.center().getX()),
                Math.abs(at.getZ() - current.center().getZ())) >= REBUILD_MOVE_BLOCKS;
        boolean stallRebuild = stalled && MonotonicTime.millis() >= nextStallRebuildMillis;
        if ((moved || stallRebuild) && !building) {
            start(level, player, key, at, coarseMap);
        }
        return usable ? current.field() : null;
    }

    /** 持ち物は毎回見る。置けるブロックを拾った・使い切ったで橋の辺が生えたり消えたりする。 */
    private static boolean canPlaceBlocks(Player player, MovementOptions options) {
        return options.bridgingEnabled()
                && (player.getAbilities().instabuild || ChunkView.countPlaceableBlocks(player) > 0);
    }

    /**
     * 直近の探索が前進できなかったことを伝える。足元のチャンクを捨てて組み直す——ブロック更新を拾っていないので、
     * 掘った・置いた場所の辺は古いまま残る（自分が歩いた跡の掘削・設置は、歩き通しの模型でも古いまま測って質は落ちていない）。
     *
     * <p><b>ワーカースレッドから呼ばれる</b>（探索の{@code whenComplete}）。
     */
    void noteStalled() {
        stalled = true;
    }

    private void start(Level level, Player player, Key key, BlockPos at, @Nullable CoarseMap coarseMap) {
        boolean invalidateAround = stalled && MonotonicTime.millis() >= nextStallRebuildMillis;
        if (invalidateAround) {
            nextStallRebuildMillis = MonotonicTime.millis() + STALL_REBUILD_INTERVAL_MILLIS;
        }
        stalled = false;
        int window = key.window();
        SearchBounds bounds = new SearchBounds(at.getX() - window, level.getMinBuildHeight(), at.getZ() - window,
                at.getX() + window, level.getMaxBuildHeight() - 1, at.getZ() + window);
        ChunkView view = ChunkView.capture(level, player, bounds, key.options());
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        // 層1を窓の外の推定に使うのは現世だけ。エンドの層1は奈落と島を2.5Dの床で持つだけで、窓の境界に置くと
        // 幾何下限より悪い（実測: 1.197倍に対して1.009倍）
        CoarseMap farMap = level.dimension() == Level.END ? null : coarseMap;
        building = true;
        long myGeneration = generation.incrementAndGet();
        CompletableFuture.supplyAsync(() -> refresh(key, view, at, minY, maxY, farMap, invalidateAround,
                        () -> generation.get() != myGeneration), coordinator)
                .whenComplete((refreshed, error) -> {
                    if (generation.get() != myGeneration) {
                        // 新しい組み立てが始まっている。その印を落とすと、組み立てが重なる
                        return;
                    }
                    building = false;
                    if (error != null) {
                        LOGGER.error("XaeroNav: 航法グラフの作成に失敗しました", error);
                        return;
                    }
                    if (refreshed == null) {
                        return;
                    }
                    built = new Built(key, at, refreshed.field());
                    if (logGate.changed(true, MonotonicTime.millis(), LOG_INTERVAL_MILLIS)) {
                        NavGraph current = graph;
                        LOGGER.info("XaeroNav: 航法グラフ (組んだセクション={}, 構築{}ms, ガイド{}ms, 辺={}, ノード={}, "
                                        + "グラフ{}MB, ガイド{}MB, 並列{}, 窓の外={})",
                                refreshed.sectionsBuilt(), refreshed.buildMillis(), refreshed.field().buildMillis(),
                                refreshed.field().edges(), refreshed.field().nodes(),
                                current == null ? 0 : current.bytes() >> 20, refreshed.field().bytes() >> 20,
                                WORKERS, farMap == null ? "直線距離" : "層1");
                    }
                });
    }

    /** 段取りの1本で走る。 */
    private NavGraph.@Nullable Refreshed refresh(Key key, ChunkView view, BlockPos at, int minY, int maxY,
                                                 @Nullable CoarseMap farMap, boolean invalidateAround,
                                                 BooleanSupplier cancelled) {
        NavGraph current = graph;
        if (current == null || !key.equals(graphKey)) {
            // 条件が変わったグラフを残して差分で組み直すことはできない（辺そのものが条件に依存する）
            current = new NavGraph(key.goal(), minY, maxY);
            graph = current;
            graphKey = key;
            // 外の推定も目的地に対するもの
            far = FarField.UNKNOWN;
            farSource = null;
        } else if (invalidateAround) {
            int chunkX = at.getX() >> 4;
            int chunkZ = at.getZ() >> 4;
            for (int dx = -STALL_INVALIDATE_CHUNKS; dx <= STALL_INVALIDATE_CHUNKS; dx++) {
                for (int dz = -STALL_INVALIDATE_CHUNKS; dz <= STALL_INVALIDATE_CHUNKS; dz++) {
                    current.invalidateChunk(chunkX + dx, chunkZ + dz);
                }
            }
        }
        if (farMap != farSource || far == FarField.UNKNOWN) {
            far = farMap == null ? FarField.straightLineTo(key.goal())
                    : FarField.of(CoarseRouter.costToGo(farMap, key.goal(), false, CoarseRouter.BridgePolicy.BRIDGE));
            farSource = farMap;
        }
        int window = key.window();
        return current.refresh(view::forGraphBuild, at.getX(), at.getZ(), window,
                LoadedArea.chunks(at.getX(), at.getZ(), window, view::chunkLoaded), far, pool, WORKERS, cancelled);
    }

    /** 目的地が変わった・案内を止めた。組みかけは打ち切り、覚えていたグラフも手放す。 */
    void clear() {
        generation.incrementAndGet();
        built = null;
        building = false;
        stalled = false;
        nextStallRebuildMillis = 0L;
        coordinator.execute(() -> {
            graph = null;
            graphKey = null;
            farSource = null;
            far = FarField.UNKNOWN;
        });
    }
}
