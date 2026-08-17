package net.prason.xaeronav.pathfinding.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathSafetyChecker;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;

/**
 * design doc §4-5/§4-6。ワーカースレッドでA*を実行する。新しいリクエストが来たら
 * 実行中(または未着手)の古いジョブをキャンセルし、常に最新のリクエストだけが結果を返す。
 *
 * <p>{@link CellSource}の構築（メインスレッドでのチャンク参照集め）は呼び出し側の責務。
 * このクラスはA*の実行と、そのキャンセル制御、危険箇所の注釈付けまでを担当する。
 * 注釈付けをここに置くのは、経路を求めたビューと注釈に使うビューを取り違えないようにするため。
 */
public final class PathfindingExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-pathfinding");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * {@link #submitCoarseGuided}の区間ごとの探索時間上限（ミリ秒）。層2の廊下
     * （{@code CorridorLegSolver.LEG_TIME_LIMIT_MILLIS=300}）より長めにしてある——こちらは
     * 掘削込みのフル解像度探索でノード単価が重いため。実機での調整が前提の初期値。
     */
    private static final long COARSE_GUIDED_LEG_TIME_LIMIT_MILLIS = 800;

    private final AtomicReference<PathfindingJob> currentJob = new AtomicReference<>();

    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits) {
        return submit(view, start, goal, limits, true);
    }

    /**
     * {@code costToGoGuideEnabled}を明示的に指定する版。既定（引数無しの{@link #submit}）はtrue——
     * 層1のcost-to-go（{@link #buildCostToGoGuide}）を幾何学的なHeuristicと併用する。設定で
     * 切れるようにする理由は{@code XaeroNavConfig#costToGoGuideEnabled}を参照。
     *
     * <p>この設定値をここで{@code XaeroNavConfig}から直接読まないのは、{@link PathfindingExecutor}が
     * 単体テスト対象（{@code PathfindingExecutorCoarseGuidedTest}）で、NeoForgeの設定システムが
     * ロードされていない環境からも呼べる必要があるため。読み出しは呼び出し側
     * （{@code PathfindingState}）の責務にする。
     */
    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                                 boolean costToGoGuideEnabled) {
        return submit(cancelled -> {
            CostToGo costToGo = costToGoGuideEnabled ? buildCostToGoGuide(view, goal) : null;
            return search(view, limits, cancelled, costToGo, (pathfinder, c) ->
                    // 立てない座標のまま探索すると経路が1本も伸びない。ブロックを読める場所での
                    // 寄せ直しなので、メインスレッドへ戻さずここで行う
                    pathfinder.search(StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal),
                            c));
        });
    }

    /**
     * {@link LiveCoarseSampler}で組んだ粗い地図から、このゴールへのcost-to-goガイドを作る。
     * {@code view.bounds()}の箱に限れば{@link CoarseRouter}の逆向きDijkstra1回は数msで終わる
     * （描画距離32相当で65×65セル、最大4床）。Xaeroの地図に依存せず読み込み済みチャンクの
     * 生データだけを見るので、ワーカースレッド上で完結できる（メインスレッド境界を動かさない）。
     *
     * <p>ボート所持の有無は見ない（{@code false}固定）。ガイドは{@code AStarPathfinder}側で
     * 幾何学的なヒューリスティックとのmaxを取って使うだけなので、多少粗くても実害が無い——
     * 損をするのは「ボートがあるのに引き締めが甘くなる」程度で、非許容にはならない。
     */
    private static CostToGo buildCostToGoGuide(CellSource view, BlockPos goal) {
        CoarseMap coarseMap = LiveCoarseSampler.sample(view, view.bounds());
        CoarseRouter.LavaPolicy lavaPolicy = view.lavaBridgingEnabled()
                ? CoarseRouter.LavaPolicy.BRIDGE : CoarseRouter.LavaPolicy.ALLOW;
        return CoarseRouter.costToGo(coarseMap, goal, false, lavaPolicy);
    }

    /**
     * {@link #submit}と違い、{@link StanceFinder}による寄せ直しと{@link PathSafetyChecker}による
     * 危険箇所の注釈付けを行わない薄い版。呼び出し側が始点・終点をすでに立てる座標へ解決済みで、
     * 結果を実際に歩く経路としてではなく中間データ（waypoint選定など）として使う場合に使う。
     */
    public CompletableFuture<PathResult> submitRaw(CellSource view, BlockPos start, BlockPos goal,
                                                    SearchLimits limits) {
        return submit(cancelled -> new AStarPathfinder(view, limits).search(start, goal, cancelled));
    }

    /**
     * 地下から地上へ出る経路を、目的地の真下ではなく「y &gt;= surfaceY の空の下」を探して求める
     * （design doc外・地上優先ナビ。{@link net.prason.xaeronav.client.PathfindingState}参照）。
     *
     * <p>まず{@code onFoot}（掘削を禁じたビュー）で探し、地上まで届かなかったときだけ{@code digging}で
     * 探し直す。掘削を許したまま1度で済ませると、石を含めて分岐が桁違いに増え、展開数の上限が
     * 数十ブロック先で尽きてしまう。そこで返るのは「その場から少し掘り上がる」だけの未到達な経路で、
     * 辿っても地上には出られない。掘削を切れば通れるのは既存の空洞だけになり、同じ展開数で
     * 洞窟や坑道を遥かに遠くまで辿れる。
     */
    public CompletableFuture<PathResult> submitToSurface(CellSource onFoot, CellSource digging, BlockPos start,
                                                          int surfaceY, SearchLimits limits) {
        return submit(cancelled -> {
            PathResult walked = search(onFoot, limits, cancelled, (pathfinder, c) ->
                    pathfinder.searchToSurface(StanceFinder.resolveStart(onFoot, start), surfaceY, c));
            if (walked.complete()) {
                return walked;
            }
            return search(digging, limits, cancelled, (pathfinder, c) ->
                    pathfinder.searchToSurface(StanceFinder.resolveStart(digging, start), surfaceY, c));
        });
    }

    /**
     * 詳細探索が展開ノード数の上限に当たって未到達だったときの再挑戦（design doc外・層3の局所障害
     * 対策）。読み込み済みチャンクの生データから粗い地図を組み立て（{@link LiveCoarseSampler}）、
     * その上で{@link CoarseRouter}が引いた経由地を1区間ずつ詳細A*で辿る。1回の長い探索より
     * 短い区間の連続の方が、同じ予算でも局所的な崖・湖を迂回しやすい。
     *
     * <p>{@link LiveCoarseSampler}は{@code CellSource}を読むだけ（Xaeroの地図とは無関係）なので、
     * 粗い地図の組み立てから区間ごとの探索まですべてこのワーカースレッド上で完結できる
     * （層2の廊下精緻化と違い、メインスレッドへ戻す必要がない）。
     */
    public CompletableFuture<PathResult> submitCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start,
                                                             BlockPos goal, SearchLimits limits) {
        return submitCoarseGuided(view, bounds, start, goal, limits, true);
    }

    /** {@code costToGoGuideEnabled}を明示的に指定する版。{@link #submit(CellSource, BlockPos, BlockPos,
     * SearchLimits, boolean)}と同じ理由で、設定の読み出しは呼び出し側に委ねる。 */
    public CompletableFuture<PathResult> submitCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start,
                                                             BlockPos goal, SearchLimits limits,
                                                             boolean costToGoGuideEnabled) {
        return submit(cancelled -> solveCoarseGuided(view, bounds, start, goal, limits, cancelled,
                costToGoGuideEnabled));
    }

    private static PathResult solveCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                                 SearchLimits limits, BooleanSupplier cancelled,
                                                 boolean costToGoGuideEnabled) {
        CoarseMap coarseMap = LiveCoarseSampler.sample(view, bounds);
        // 橋を架けられるなら粗い側でも溶岩を通す。ここを一律ALLOWにすると、溶岩の海の縁では
        // 出発点自身のセルがLAVA＝通行不能になって区間分割が1つも作れず、溶岩の海を1回の探索で
        // 渡ろうとして予算を焼き切る（実機で踏んだ: ステップ数0のまま20万ノード）
        CoarseRouter.LavaPolicy lavaPolicy = view.lavaBridgingEnabled()
                ? CoarseRouter.LavaPolicy.BRIDGE : CoarseRouter.LavaPolicy.ALLOW;
        CoarseRouter.Route route = CoarseRouter.findRoute(coarseMap, start, goal, false, lavaPolicy);
        // 粗い地図が空のまま「経路あり」になるのが最悪の失敗（全セルNO_DATAは通行可能なので、
        // 溶岩を無視した直線が引けてしまう）。知られたセル数を出しておかないと、
        // 「区間分割が下手」なのか「そもそも地形が見えていない」のかを切り分けられない
        LOGGER.info("XaeroNav: 粗い経由地チェーンの地図 (既知セル={}/{}, 中間目標={}個, 溶岩={})",
                coarseMap.knownCells(), coarseMap.totalCells(), route.waypoints().size(), lavaPolicy);
        if (route.waypoints().isEmpty()) {
            // 粗い側でも道が見つからない（孤立した地形等）。直接探索と同じ結果に留める
            CostToGo directCostToGo = costToGoGuideEnabled
                    ? CoarseRouter.costToGo(coarseMap, goal, false, lavaPolicy) : null;
            return search(view, limits, cancelled, directCostToGo, (pathfinder, c) ->
                    pathfinder.search(StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal),
                            c));
        }

        List<BlockPos> rawLegGoals = new ArrayList<>(route.waypoints());
        rawLegGoals.add(goal);
        // 展開数の上限は区間数で割らずに満額渡す。SearchLimitsが言うとおりこれは「届かなかった
        // ときに打ち切る天井」であって払うコストではなく、区間が短いほど実際の展開数は少なく済む。
        // 割ると、届くはずの区間が手前で切れるだけになる（実機で30000÷3区間=10000となり山岳地形の
        // 1区間目すら届かなかった）。
        //
        // 代わりにチェーン全体を、単一探索1回分と同じ時間で縛る。区間ごとの上限しか無いと
        // 区間数×COARSE_GUIDED_LEG_TIME_LIMIT_MILLISまで伸びてしまい、これの代替手段であるはずの
        // チェーンだけが青天井になる
        SearchLimits legLimits = new SearchLimits(limits.maxExpandedNodes(), COARSE_GUIDED_LEG_TIME_LIMIT_MILLIS,
                limits.heuristicWeight());
        long chainDeadline = System.currentTimeMillis() + limits.timeLimitMillis();

        List<PathStep> steps = new ArrayList<>();
        boolean complete = false;
        int totalExpanded = 0;
        int totalDistinct = 0;
        // チェーン全体の打ち切り理由は最後に解いた区間のもの。全区間が「範囲内に道が無い」で
        // 終わったときだけEXHAUSTEDのまま残り、本物の詰みとして呼び出し側に伝わる
        PathResult.Termination termination = PathResult.Termination.EXHAUSTED;
        BlockPos legStart = StanceFinder.resolveStart(view, start);
        for (int i = 0; i < rawLegGoals.size(); i++) {
            long remainingMillis = chainDeadline - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                termination = PathResult.Termination.TIME_LIMIT;
                break;
            }
            SearchLimits thisLegLimits = remainingMillis >= COARSE_GUIDED_LEG_TIME_LIMIT_MILLIS
                    ? legLimits
                    : new SearchLimits(legLimits.maxExpandedNodes(), remainingMillis, legLimits.heuristicWeight());
            BlockPos legGoal = StanceFinder.resolveGoal(view, rawLegGoals.get(i));
            BlockPos currentLegStart = legStart;
            // 区間ごとのゴールに向けたガイド。同じcoarseMapを使い回すので逆向きDijkstraだけを
            // ゴールの数だけ繰り返す（地図の読み取りは1回で済んでいる）
            CostToGo legCostToGo = costToGoGuideEnabled
                    ? CoarseRouter.costToGo(coarseMap, legGoal, false, lavaPolicy) : null;
            PathResult legResult = search(view, thisLegLimits, cancelled, legCostToGo,
                    (pathfinder, c) -> pathfinder.search(currentLegStart, legGoal, c));
            totalExpanded += legResult.expandedNodes();
            totalDistinct += legResult.distinctNodes();
            boolean lastLeg = i == rawLegGoals.size() - 1;
            if (!legResult.complete()) {
                termination = legResult.termination();
            }

            if (legResult.complete()) {
                steps.addAll(legResult.steps());
                if (!legResult.steps().isEmpty()) {
                    legStart = legResult.steps().get(legResult.steps().size() - 1).pos();
                }
                if (lastLeg) {
                    complete = true;
                }
            } else if (lastLeg) {
                // 最後の区間は本来の目的地。届かなくても拾えた分はそのまま使う（暫定経路の思想）
                steps.addAll(legResult.steps());
            }
            // 中間の経由地に届かなかったときは、そこで諦めずに同じ地点から次の経由地を狙う。
            // 粗い地図は溶岩以外に「通行不能」を表現できず、チャンクを埋める垂直な壁は起伏0の
            // 平坦な台地に見えるため、経由地が壁の天面のような到達不能な点に落ちることがある。
            // 1つ届かないだけでチェーンごと捨てると、そういう地形で直接探索より悪くなる——
            // 最後の区間は必ず本来の目的地なので、経由地が全滅しても直接探索と同じ結果に落ち着く。
            // 部分経路を継ぎ足さないのは、次の区間を同じ地点から引き直す以上そこで経路が飛ぶため
        }
        return new PathResult(steps, complete ? PathResult.Termination.REACHED_GOAL : termination,
                totalExpanded, totalDistinct);
    }

    private static PathResult search(CellSource view, SearchLimits limits, BooleanSupplier cancelled, SearchCall run) {
        return search(view, limits, cancelled, null, run);
    }

    private static PathResult search(CellSource view, SearchLimits limits, BooleanSupplier cancelled,
                                     CostToGo costToGo, SearchCall run) {
        AStarPathfinder pathfinder = new AStarPathfinder(view, limits, costToGo);
        return PathSafetyChecker.annotate(view, run.search(pathfinder, cancelled));
    }

    private CompletableFuture<PathResult> submit(Function<BooleanSupplier, PathResult> work) {
        PathfindingJob job = new PathfindingJob();
        PathfindingJob previous = currentJob.getAndSet(job);
        if (previous != null) {
            previous.cancel();
        }

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                PathResult result = work.apply(job::isCancelled);
                if (job.isCancelled()) {
                    future.cancel(false);
                } else {
                    future.complete(result);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @FunctionalInterface
    private interface SearchCall {
        PathResult search(AStarPathfinder pathfinder, BooleanSupplier cancelled);
    }
}
