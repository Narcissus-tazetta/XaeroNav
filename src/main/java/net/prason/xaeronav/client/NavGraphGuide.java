package net.prason.xaeronav.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
 * 航法グラフのガイドの作りかけ・出来上がりを持つ。
 *
 * <p>読み込み済みの窓の中を、探索と同じ移動生成でセクションごとに組み（{@link NavGraph}）、目的地までの残りコストを
 * 逆Dijkstraで作る（{@link WindowField}）。窓の中は正確なので、探索は目的地をそのまま重み1.0で狙える
 * （歩き通しの実測: 広域長距離1.016/1.030倍・エンド1.013/1.029倍・ネザー1.013/1.023倍。層1の中間目標へ寄る探索は
 * 1.067/1.165・1.122/未到達、3D粗層だけのネザーは1.048/1.104）。
 *
 * <p>スレッドの境目は{@link NetherVoxelGuide}と同じ形——メインスレッドでチャンクの参照だけを集め、組むのはワーカー。
 * 組み上がるまでは{@code null}を返し、呼び出し側は従来の探索で進む。
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
     * 組んだ中心からこれだけ歩いたら組み直す。組み直しは帯の組み足し（0.02〜0.3秒）とガイド作り（0.5〜1.3秒）で、
     * 組んでいる間は次を始めないので、実際の遅れはこれに組み直しの間に歩く分が足される。
     *
     * <p>歩き通しの模型（広域長距離4本）では8ブロックで遅れ無しと同じ経路になった。16ブロックでは1本が1.030→1.101倍に落ち、
     * 32ブロックでは戻る——窓の縁が区間の始点と噛み合う位相で外れるので、間隔を詰めて噛み合う幅を小さくしておく。
     */
    private static final int REBUILD_MOVE_BLOCKS = 8;

    /** 詰まったときに捨てるチャンクの半径。掘る・置くはたいてい自分の足元で起きる。 */
    private static final int STALL_INVALIDATE_CHUNKS = 1;

    /**
     * 詰まったことによる組み直しの下限間隔。詰まりは同じ場所で何度も続くので、間引かないと
     * 足元の数百セクションを探索のたびに組み直すことになる。
     */
    private static final long STALL_REBUILD_INTERVAL_MILLIS = 15_000L;

    /** 初回の並列度。メインスレッド（描画）に1コア残す。組み上がるまでは案内が出ないので、待たせる時間を優先する。 */
    private static final int WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    /**
     * 歩きながらの組み直しの並列度。{@link Thread#MIN_PRIORITY}はmacOS・Linuxでは効かないので、全コアで組むと描画と
     * 内蔵サーバーのスレッドを押しのける（実機: 10コア（高性能4）で8ブロックごとに9本が張り付き、歩いていて重かった）。
     * 組み直しの間も古いガイドで探せるので、半分で遅れても案内は途切れない。
     */
    private static final int REBUILD_WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2 - 1);

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
                       int window, int minY, int maxY) {
    }

    private record Built(Key key, BlockPos center, WindowField field) {
    }

    /**
     * 窓の外の推定の出どころ。{@code source}が同じ間は作り直さない（層1の逆Dijkstraは地図全体を回すので、組み直しのたびには払わない）。
     *
     * @param name ログに出す名前
     * @param make 段取りの1本で呼ぶ
     */
    record Far(String name, Object source, Supplier<FarField> make) {
    }

    /**
     * 3D粗層を窓の外の推定に使うときに掛ける倍率。3D粗層は真の残りの0.77倍前後に縮んでいて、窓の中の正確な値と尺度が食い違う。
     *
     * <p>実測（ネザー4本、平均/最悪）: 1.0倍で1.016/1.035、1.3倍で1.013/1.023（3D粗層だけの現行は1.048/1.104）。
     */
    static final double VOXEL_FAR_SCALE = 1.3;

    private volatile @Nullable Built built;
    private volatile boolean building;
    // 直近の探索が前進できなかった。ワーカースレッド（whenComplete）が立て、forGoalが落とす
    private volatile boolean stalled;
    private long nextStallRebuildMillis;

    /** 段取りの1本だけが触る。 */
    private @Nullable NavGraph graph;
    private @Nullable Key graphKey;
    private @Nullable Object farSource;
    private FarField far = FarField.UNKNOWN;

    /**
     * 今の目的地のガイド。無ければ組み始めて{@code null}を返す。<b>メインスレッドから呼ぶこと。</b>
     *
     * @param far 窓の外の推定。{@code null}なら目的地までの直線距離（目的地が窓の外のときだけ置く）
     * @return 組み直し中でも、同じ条件の古いガイドがあればそれ
     */
    @Nullable WindowField forGoal(Level level, Player player, BlockPos goal, int renderRadius, MovementOptions options,
                                  @Nullable Far far) {
        BlockPos at = player.blockPosition();
        int window = Math.min(WINDOW_BLOCKS, renderRadius);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int logicalTop = minY + level.dimensionType().logicalHeight() - 1;
        if (level.dimensionType().hasCeiling() && at.getY() <= logicalTop && goal.getY() <= logicalTop) {
            // ネザーの岩盤の天井より上は、下から掘って入れない（岩盤は掘れない）。そこを組むと窓のセクションが倍になり、
            // 天井の上の平らな岩盤一面がノードになる（実機: 7,056セクション・初回構築7.2秒）
            maxY = logicalTop;
        }
        Key key = new Key(level.dimension(), goal, options, canPlaceBlocks(player, options), window, minY, maxY);
        Built current = built;
        boolean usable = current != null && current.key().equals(key);
        boolean moved = !usable || Math.max(Math.abs(at.getX() - current.center().getX()),
                Math.abs(at.getZ() - current.center().getZ())) >= REBUILD_MOVE_BLOCKS;
        boolean stallRebuild = stalled && MonotonicTime.millis() >= nextStallRebuildMillis;
        if ((moved || stallRebuild) && !building) {
            start(level, player, key, at, far);
        }
        return usable ? current.field() : null;
    }

    /**
     * いま出来上がっている、この目的地のガイド。組み直しは始めない。{@link #forGoal}と違って条件（持ち物・設定）は照合しないので、
     * 引いてある経路を見直すことにだけ使う。
     */
    @Nullable WindowField latest(BlockPos goal) {
        Built current = built;
        return current != null && current.key().goal().equals(goal) ? current.field() : null;
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

    private void start(Level level, Player player, Key key, BlockPos at, @Nullable Far farMap) {
        boolean invalidateAround = stalled && MonotonicTime.millis() >= nextStallRebuildMillis;
        if (invalidateAround) {
            nextStallRebuildMillis = MonotonicTime.millis() + STALL_REBUILD_INTERVAL_MILLIS;
        }
        stalled = false;
        int window = key.window();
        SearchBounds bounds = new SearchBounds(at.getX() - window, key.minY(), at.getZ() - window,
                at.getX() + window, key.maxY(), at.getZ() + window);
        ChunkView view = ChunkView.capture(level, player, bounds, key.options());
        int minY = key.minY();
        int maxY = key.maxY();
        // この目的地のガイドがまだ無い＝案内を待たせている間だけ全力で組む
        int workers = built != null && built.key().equals(key) ? REBUILD_WORKERS : WORKERS;
        building = true;
        long myGeneration = generation.incrementAndGet();
        CompletableFuture.supplyAsync(() -> refresh(key, view, at, minY, maxY, farMap, invalidateAround, workers,
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
                                workers, farMap == null ? "直線距離" : farMap.name());
                    }
                });
    }

    /** 段取りの1本で走る。 */
    private NavGraph.@Nullable Refreshed refresh(Key key, ChunkView view, BlockPos at, int minY, int maxY,
                                                 @Nullable Far farMap, boolean invalidateAround, int workers,
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
        Object source = farMap == null ? null : farMap.source();
        if (source != farSource || far == FarField.UNKNOWN) {
            far = farMap == null ? FarField.straightLineTo(key.goal()) : farMap.make().get();
            farSource = source;
        }
        int window = key.window();
        return current.refresh(view::forGraphBuild, at.getX(), at.getZ(), window,
                LoadedArea.chunks(at.getX(), at.getZ(), window, view::chunkLoaded), far, pool, workers, cancelled);
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
