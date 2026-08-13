package net.prason.xaeronav.pathfinding.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-pathfinding");
        thread.setDaemon(true);
        return thread;
    });

    /** {@link #submitCoarseGuided}が区間に割り振る展開ノード数の下限。区間数が多いときの頭打ち防止。 */
    private static final int MIN_LEG_EXPANDED_NODES = 10_000;

    /**
     * {@link #submitCoarseGuided}の区間ごとの探索時間上限（ミリ秒）。層2の廊下
     * （{@code CorridorLegSolver.LEG_TIME_LIMIT_MILLIS=300}）より長めにしてある——こちらは
     * 掘削込みのフル解像度探索でノード単価が重いため。実機での調整が前提の初期値。
     */
    private static final long COARSE_GUIDED_LEG_TIME_LIMIT_MILLIS = 800;

    private final AtomicReference<PathfindingJob> currentJob = new AtomicReference<>();

    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits) {
        return submit(cancelled -> search(view, limits, cancelled, (pathfinder, c) ->
                // 立てない座標のまま探索すると経路が1本も伸びない。ブロックを読める場所での
                // 寄せ直しなので、メインスレッドへ戻さずここで行う
                pathfinder.search(StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal), c)));
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
        return submit(cancelled -> solveCoarseGuided(view, bounds, start, goal, limits, cancelled));
    }

    private static PathResult solveCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                                 SearchLimits limits, BooleanSupplier cancelled) {
        CoarseMap coarseMap = LiveCoarseSampler.sample(view, bounds);
        CoarseRouter.Route route = CoarseRouter.findRoute(coarseMap, start, goal, false);
        if (route.waypoints().isEmpty()) {
            // 粗い側でも道が見つからない（孤立した地形等）。直接探索と同じ結果に留める
            return search(view, limits, cancelled, (pathfinder, c) ->
                    pathfinder.search(StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal),
                            c));
        }

        List<BlockPos> rawLegGoals = new ArrayList<>(route.waypoints());
        rawLegGoals.add(goal);
        // 分割した意味は「1区間を軽くする」こと。そのまま満額を各区間に与えると、最悪ケースで
        // 区間数倍の計算時間になってしまう
        int legBudget = Math.max(MIN_LEG_EXPANDED_NODES, limits.maxExpandedNodes() / rawLegGoals.size());
        SearchLimits legLimits = new SearchLimits(legBudget, COARSE_GUIDED_LEG_TIME_LIMIT_MILLIS,
                limits.heuristicWeight());

        List<PathStep> steps = new ArrayList<>();
        boolean complete = true;
        int totalExpanded = 0;
        int totalDistinct = 0;
        BlockPos legStart = StanceFinder.resolveStart(view, start);
        for (BlockPos rawLegGoal : rawLegGoals) {
            BlockPos legGoal = StanceFinder.resolveGoal(view, rawLegGoal);
            BlockPos currentLegStart = legStart;
            PathResult legResult = search(view, legLimits, cancelled,
                    (pathfinder, c) -> pathfinder.search(currentLegStart, legGoal, c));
            steps.addAll(legResult.steps());
            totalExpanded += legResult.expandedNodes();
            totalDistinct += legResult.distinctNodes();
            if (!legResult.complete()) {
                // 暫定経路の思想どおり、辿り着けた分はそのまま使う。以降の区間は始点が
                // 定まらないので続けない
                complete = false;
                break;
            }
            legStart = legResult.steps().isEmpty() ? legStart : legResult.steps().get(legResult.steps().size() - 1).pos();
        }
        return new PathResult(steps, complete, totalExpanded, totalDistinct);
    }

    private static PathResult search(CellSource view, SearchLimits limits, BooleanSupplier cancelled, SearchCall run) {
        AStarPathfinder pathfinder = new AStarPathfinder(view, limits);
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
