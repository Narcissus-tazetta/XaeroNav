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
import net.prason.xaeronav.pathfinding.astar.CostToGo;
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
     * ヒープに余裕があるときの窓の半径（ブロック）。描画距離がこれより広くてもここで切る。
     *
     * <p>経路の見直し（{@link net.prason.xaeronav.pathfinding.navgraph.RouteReview}）は目的地が窓に入ってから走るので、
     * 窓が狭いと遠回りに気づくのが遅れる（実機のネザーで目的地まで約130ブロックで初めて気づき、余計に653tick）。
     * 歩き通しの模型で160・192・224・240を測った。
     * <ul>
     * <li>224は、ネザーが平均1.044→1.001・最悪1.147→1.003倍、現世が1.018→1.009倍。実機の保存地形の罠では6207→4177tick（真値3941）</li>
     * <li>192は、ネザーの最悪が1.229倍で、160より悪い。広げるほど単調に良くなるわけではない</li>
     * <li>240は、エンド外側の島の1本で経路が出なくなる</li>
     * </ul>
     * 辺の数は面積に比例して増える。224で辺は最大7,000万本、グラフとガイドは合わせて最大約610MB（160では約330MB）。
     * ガイド1回の最大は1.3→2.1秒になる。
     *
     * <p>ヒープが{@link #WIDE_WINDOW_MIN_HEAP_BYTES}未満なら{@link #NARROW_WINDOW_BLOCKS}に落とす。
     */
    private static final int WIDE_WINDOW_BLOCKS = 224;

    /** ヒープが小さいときの窓。間の192はネザーの最悪が160より悪いので選ばない。 */
    private static final int NARROW_WINDOW_BLOCKS = 160;

    /**
     * 窓224を使うのに要るヒープ。公式ランチャーの既定の2GBでは、本体の分と窓224の最大約610MBが重なって足りなくなり得る。
     *
     * <p>{@code -Xmx3G}を指定した人は224にしたいが、SerialGC・ParallelGCの{@link Runtime#maxMemory}は生存領域1つ分を
     * 引いて返す（実測: {@code -Xmx3G}で2,969MB・2,731MB、{@code -Xmx2G}で1,979MB・1,820MB）ので、間の2.5GBで切る。
     */
    private static final long WIDE_WINDOW_MIN_HEAP_BYTES = 2560L << 20;

    private static final int WINDOW_BLOCKS = Runtime.getRuntime().maxMemory() >= WIDE_WINDOW_MIN_HEAP_BYTES
            ? WIDE_WINDOW_BLOCKS : NARROW_WINDOW_BLOCKS;

    /**
     * 窓の半径（ブロック）。探索の箱もこれで切ること——窓の外ではガイドが層1か幾何の推定に落ちるので、
     * 箱と窓がずれると測っていない探索になる。
     */
    static int window(int renderRadius) {
        return Math.min(WINDOW_BLOCKS, renderRadius);
    }

    /**
     * 組み立てに失敗したら、これだけ組み直さない。失敗する条件（メモリ不足など）はすぐには変わらないので、待たずにやり直すと
     * 案内を待っている間は毎tick、チャンク集め（メインスレッド）と窓全体の組み立てを繰り返す。
     */
    private static final long FAILURE_BACKOFF_MILLIS = 30_000L;

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

    /** 負荷の集計（{@link Load}）を出す間隔。 */
    private static final long LOAD_LOG_INTERVAL_MILLIS = 30_000L;

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

        /** 辺が同じになるか。目的地の高さは辺に効かない（{@link NavGraph#retarget}）。 */
        boolean sameEdges(@Nullable Key other) {
            return other != null && dimension.equals(other.dimension) && goal.getX() == other.goal.getX()
                    && goal.getZ() == other.goal.getZ() && options.equals(other.options)
                    && canPlaceBlocks == other.canPlaceBlocks && window == other.window && minY == other.minY
                    && maxY == other.maxY;
        }
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
    // 高さの寄せ直しの後。経路は出たままなので、ガイドが無くても全力で組む理由が無い
    private volatile boolean retargeted;
    // 直近の探索が前進できなかった。ワーカースレッド（whenComplete）が立て、forGoalが落とす
    private volatile boolean stalled;
    private long nextStallRebuildMillis;
    // 組み立ての失敗から立ち直るまでの時刻と、続けて失敗した回数。完了を受けるスレッドが書き、forGoalが読む
    private volatile long retryAfterMillis;
    private volatile int failures;

    /** 段取りの1本だけが触る。 */
    private final Load load = new Load();

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
        int window = window(renderRadius);
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
        if ((moved || stallRebuild) && !building && !failedRecently()) {
            start(level, player, key, at, far);
        }
        return usable ? current.field() : null;
    }

    /** 組み立てに失敗して、組み直しを見合わせている間か。この間は組み上がりを待たずに従来の探索で進めること。 */
    boolean failedRecently() {
        return MonotonicTime.millis() < retryAfterMillis;
    }

    /**
     * いま出来上がっている、この目的地のガイド。組み直しは始めない。{@link #forGoal}と違って条件（持ち物・設定）は照合しないので、
     * 引いてある経路を見直すことにだけ使う。
     */
    @Nullable WindowField latest(BlockPos goal) {
        Built current = built;
        return current != null && current.key().goal().equals(goal) ? current.field() : null;
    }

    /**
     * {@code from}のガイドの値がどこから来たか（{@link WindowField#descend}）を1語で。経路の向きを決めたのが
     * 窓の中の実費か、窓の縁で読んだ外の推定かを、実機のログで見分けるためのもの。
     */
    static String origin(CostToGo guide, BlockPos from) {
        if (!(guide instanceof WindowField field)) {
            return "航法グラフ以外";
        }
        WindowField.Descent descent = field.descend(from.getX(), from.getY(), from.getZ());
        if (descent == null) {
            return "ノードでない";
        }
        if (descent.reachedGoal()) {
            return "目的地(窓の中%d)".formatted(Math.round(descent.inside()));
        }
        BlockPos exit = descent.exit();
        BlockPos goal = field.goal();
        return "縁%s(窓の中%d+外の推定%d, 縁から目的地まで直線%d)".formatted(exit.toShortString(),
                Math.round(descent.inside()), Math.round(descent.outside()),
                Math.round(Math.hypot(exit.getX() - goal.getX(), exit.getZ() - goal.getZ())));
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
        long captureBegan = MonotonicTime.millis();
        ChunkView view = ChunkView.capture(level, player, bounds, key.options());
        long captureMillis = MonotonicTime.millis() - captureBegan;
        int minY = key.minY();
        int maxY = key.maxY();
        // この目的地のガイドがまだ無い＝案内を待たせている間だけ全力で組む
        int workers = retargeted || built != null && built.key().equals(key) ? REBUILD_WORKERS : WORKERS;
        building = true;
        long myGeneration = generation.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
                    long began = MonotonicTime.millis();
                    NavGraph.Refreshed refreshed = refresh(key, view, at, minY, maxY, farMap, invalidateAround, workers,
                            () -> generation.get() != myGeneration);
                    load.record(began, MonotonicTime.millis(), captureMillis, refreshed);
                    return refreshed;
                }, coordinator)
                .whenComplete((refreshed, error) -> {
                    if (error != null) {
                        // 打ち切りは例外でなくnullで返るので、世代が古い回でもこれは本物の失敗
                        fail(error);
                    }
                    if (generation.get() != myGeneration) {
                        // 新しい組み立てが始まっている。その印を落とすと、組み立てが重なる
                        return;
                    }
                    building = false;
                    if (refreshed == null) {
                        return;
                    }
                    failures = 0;
                    built = new Built(key, at, refreshed.field());
                    retargeted = false;
                    if (logGate.changed(true, MonotonicTime.millis(), LOG_INTERVAL_MILLIS)) {
                        NavGraph current = graph;
                        LOGGER.info("XaeroNav: 航法グラフ (組んだセクション={}, 構築{}ms, ガイド{}ms, 辺={}, ノード={}, "
                                        + "グラフ{}MB, ガイド{}MB, 窓{}(ヒープ上限{}MB), 並列{}, 窓の外={}, 中心{}の値の出どころ={})",
                                refreshed.sectionsBuilt(), refreshed.buildMillis(), refreshed.field().buildMillis(),
                                refreshed.field().edges(), refreshed.field().nodes(),
                                current == null ? 0 : current.bytes() >> 20, refreshed.field().bytes() >> 20,
                                key.window(), Runtime.getRuntime().maxMemory() >> 20, workers,
                                farMap == null ? "直線距離" : farMap.name(), at.toShortString(), origin(refreshed.field(), at));
                    }
                });
    }

    /**
     * 組み立てが失敗した。しばらく組み直さず、出来上がっていたガイドとグラフも手放す——メモリ不足の後に数百MBを
     * 抱えたままにしないためと、途中で落ちた回の組み立て用の配列を次の回に使い回さないため。
     */
    private void fail(Throwable error) {
        failures++;
        built = null;
        retryAfterMillis = MonotonicTime.millis() + FAILURE_BACKOFF_MILLIS;
        LOGGER.error("XaeroNav: 航法グラフの作成に失敗しました（{}回続けて）。{}秒は組み直さず、航法グラフ無しで案内します",
                failures, FAILURE_BACKOFF_MILLIS / 1000, error);
        // 完了済みの回にwhenCompleteを付けるとメインスレッドで呼ばれるので、グラフは段取りの1本で手放す
        coordinator.execute(this::forgetGraph);
    }

    /** 段取りの1本で呼ぶ。 */
    private void forgetGraph() {
        graph = null;
        graphKey = null;
        farSource = null;
        far = FarField.UNKNOWN;
    }

    /** 段取りの1本で走る。 */
    private NavGraph.@Nullable Refreshed refresh(Key key, ChunkView view, BlockPos at, int minY, int maxY,
                                                 @Nullable Far farMap, boolean invalidateAround, int workers,
                                                 BooleanSupplier cancelled) {
        NavGraph current = graph;
        if (current == null || !key.sameEdges(graphKey)) {
            // 条件が変わったグラフを残して差分で組み直すことはできない（辺そのものが条件に依存する）
            current = new NavGraph(key.goal(), minY, maxY);
            graph = current;
            graphKey = key;
            // 外の推定も目的地に対するもの
            far = FarField.UNKNOWN;
            farSource = null;
        } else if (!key.equals(graphKey)) {
            current.retarget(key.goal());
            graphKey = key;
            far = FarField.UNKNOWN;
            farSource = null;
        }
        if (invalidateAround) {
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

    /**
     * 目的地の高さだけが変わった。組みかけのガイドは古い高さへのものなので打ち切るが、組んだセクションは次の{@link #forGoal}で
     * 使い回す——作り直すと窓全体（約9,000セクション）を組むことになる。
     */
    void retarget() {
        generation.incrementAndGet();
        built = null;
        building = false;
        retargeted = true;
        logGate.reset();
    }

    /** 目的地が変わった・案内を止めた。組みかけは打ち切り、覚えていたグラフも手放す。 */
    void clear() {
        generation.incrementAndGet();
        built = null;
        building = false;
        stalled = false;
        retargeted = false;
        nextStallRebuildMillis = 0L;
        retryAfterMillis = 0L;
        failures = 0;
        logGate.reset();
        coordinator.execute(() -> {
            load.flush(MonotonicTime.millis());
            forgetGraph();
        });
    }

    /**
     * 歩いている間に組み直しがどれだけ回っているか。重さの報告（#54）を実機で切り分けるための集計で、
     * {@link #LOAD_LOG_INTERVAL_MILLIS}ごとにまとめて出す。<b>段取りの1本だけが触る。</b>
     */
    private static final class Load {

        private long since;
        private long busyMillis;
        private long buildMillis;
        private long guideMillis;
        private long maxMillis;
        private long maxCaptureMillis;
        private int runs;
        private int cancelled;
        private long gcSince;

        void record(long began, long ended, long captureMillis, NavGraph.@Nullable Refreshed refreshed) {
            if (runs == 0) {
                since = began;
                gcSince = TickLaps.gcPauseMillis();
            }
            runs++;
            busyMillis += ended - began;
            maxMillis = Math.max(maxMillis, ended - began);
            maxCaptureMillis = Math.max(maxCaptureMillis, captureMillis);
            if (refreshed == null) {
                cancelled++;
            } else {
                buildMillis += refreshed.buildMillis();
                guideMillis += refreshed.field().buildMillis();
            }
            if (ended - since >= LOAD_LOG_INTERVAL_MILLIS) {
                flush(ended);
            }
        }

        void flush(long now) {
            if (runs == 0) {
                return;
            }
            long span = Math.max(1L, now - since);
            Runtime runtime = Runtime.getRuntime();
            LOGGER.info("XaeroNav: 航法グラフの負荷 (直近{}秒, 組み直し{}回(打ち切り{}), 段取りの稼働率{}%, 構築計{}ms, ガイド計{}ms, "
                            + "1回最大{}ms, チャンク集め最大{}ms(メインスレッド), GC{}ms, ヒープ{}/{}MB)",
                    span / 1000, runs, cancelled, 100 * busyMillis / span, buildMillis, guideMillis, maxMillis,
                    maxCaptureMillis, TickLaps.gcPauseMillis() - gcSince, (runtime.totalMemory() - runtime.freeMemory()) >> 20,
                    runtime.maxMemory() >> 20);
            runs = 0;
            cancelled = 0;
            busyMillis = 0;
            buildMillis = 0;
            guideMillis = 0;
            maxMillis = 0;
            maxCaptureMillis = 0;
        }
    }
}
