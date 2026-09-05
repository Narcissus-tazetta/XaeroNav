package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * <b>実機と同じ組み立て方で経路を作る</b>——層1のcost-to-goガイド・{@link #DETAIL_HORIZON}
 * ごとの区間分割・末端からの継ぎ足し。オフラインで「実運用の経路」を再現する唯一の手段で、
 * 1回のA*で全区間を解いた経路とは別物になる。
 *
 * <p>{@code ProgressiveDiscoveryTest}（窓を動かして歩く）と{@code LongRouteOptimalityTest}
 * （全視界と窓の取り分を分ける）の両方が使う。定数は{@code PathfindingState}の既定値と揃える。
 */
final class ProgressiveWalk {

    private static final BooleanSupplier NEVER = () -> false;

    /** {@code PathfindingState#detailHorizonBlocks}の既定。 */
    static final int DETAIL_HORIZON = 96;

    /** {@code PathfindingState#MIN_DETAIL_REACH_BLOCKS}。これだけ先へ窓が届いていれば継ぎ足す。 */
    static final int MIN_DETAIL_REACH = 24;

    /** {@code PathfindingState#INTERPOLATED_GOAL_RADIUS_BLOCKS}。補間した中間目標は領域で狙う。 */
    static final int INTERPOLATED_GOAL_RADIUS = 16;

    /** 1回の計画のあいだにプレイヤーが歩く距離（ブロック）。 */
    static final int WALK_PER_TICK = 16;

    /**
     * 窓を掛けない＝世界が丸ごと見えている状態を表す半径。どのフィクスチャの箱よりも大きいので、
     * {@link WindowedCells}が何も隠さず、継ぎ足しも一度に目的地まで届く。
     */
    static final int NO_WINDOW = 4096;

    /** 1区間の探索に渡す予算（{@code PathfindingState}の既定）。 */
    private static final int LEG_NODE_BUDGET = 100_000;

    /** 最初の探索の取り分（{@code PathfindingExecutor#FIRST_PASS_PERCENT}）。 */
    private static final int FIRST_PASS_NODE_BUDGET = LEG_NODE_BUDGET * 40 / 100;

    /** 届かなかったときに順に試す重み（{@code PathfindingExecutor#GREEDY_RETRY_WEIGHTS}）。 */
    private static final double[] GREEDY_RETRY_WEIGHTS = {2.5, 3.0};

    /** 基準の探索に渡す予算。 */
    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;

    private ProgressiveWalk() {
    }

    static double cost(List<PathStep> steps) {
        return steps.stream().mapToDouble(PathStep::cost).sum();
    }

    static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 目的地そのもの、または遠すぎるならその方向へ{@code reach}だけ進んだ点（実装と同じ）。 */
    private static BlockPos aimToward(BlockPos from, BlockPos goal, int reach) {
        double distance = horizontal(from, goal);
        if (distance <= reach) {
            return goal;
        }
        double t = reach / distance;
        return new BlockPos(from.getX() + (int) Math.round((goal.getX() - from.getX()) * t),
                from.getY() + (int) Math.round((goal.getY() - from.getY()) * t),
                from.getZ() + (int) Math.round((goal.getZ() - from.getZ()) * t));
    }

    /**
     * 1区間を解く。届かなければ重みを上げて引き直すのは{@code PathfindingExecutor#retryGreedier}と
     * 同じで、<b>これが無いとジ・エンドの島渡りは1本も返らない</b>（実測6本中5本）。
     */
    private static PathResult leg(CellSource view, BlockPos from, BlockPos goal) {
        BlockPos aim = aimToward(from, goal, DETAIL_HORIZON);
        int radius = aim.equals(goal) ? 0 : INTERPOLATED_GOAL_RADIUS;
        CoarseMap map = LiveCoarseSampler.sample(view, view.bounds(), from.getY(), NEVER);
        CostToGo guide = CoarseRouter.costToGo(map, aim, false, CoarseRouter.BridgePolicy.BRIDGE);
        PathResult first = search(view, guide, from, aim, radius,
                AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, FIRST_PASS_NODE_BUDGET);
        if (first.complete()) {
            return first;
        }
        for (double weight : GREEDY_RETRY_WEIGHTS) {
            PathResult attempt = search(view, guide, from, aim, radius, weight, LEG_NODE_BUDGET);
            if (attempt.complete()) {
                return attempt;
            }
        }
        return first;
    }

    private static PathResult search(CellSource view, CostToGo guide, BlockPos from, BlockPos aim,
                                     int radius, double weight, int budget) {
        return new AStarPathfinder(view, new SearchLimits(budget, 30_000, weight), guide)
                .search(from, aim, NEVER, Carryover.NONE, radius);
    }

    /**
     * 窓を動かしながら目的地まで歩き通し、実際に歩いた経路のコストを返す。
     * 届かなければ{@link Double#POSITIVE_INFINITY}。
     *
     * @param radius    読み込み済みの窓の半径。{@link #NO_WINDOW}なら全視界
     * @param extending trueなら実装どおり末端から継ぎ足す。falseなら計画のたびに手前を捨てて
     *                  プレイヤーから引き直す。<b>歩き方は両方で同じ</b>にしてある
     */
    static double walkToGoal(FakeCells all, BlockPos start, BlockPos goal, int radius,
                             boolean extending) {
        List<PathStep> walked = new ArrayList<>();
        List<PathStep> planned = new ArrayList<>();
        BlockPos player = start;
        for (int tick = 0; tick < 400; tick++) {
            CellSource view = new WindowedCells(all, player, radius);
            if (!extending) {
                planned = new ArrayList<>();
            }
            BlockPos end = planned.isEmpty() ? player : planned.get(planned.size() - 1).pos();
            while (horizontal(player, end) <= radius - MIN_DETAIL_REACH && horizontal(end, goal) > 1) {
                PathResult result = leg(view, end, goal);
                if (result.steps().isEmpty()) {
                    break;
                }
                planned.addAll(result.steps());
                BlockPos next = planned.get(planned.size() - 1).pos();
                if (next.equals(end)) {
                    break;
                }
                end = next;
            }
            if (planned.isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }
            int walkTo = 0;
            while (walkTo < planned.size()
                    && horizontal(player, planned.get(walkTo).pos()) < WALK_PER_TICK) {
                walkTo++;
            }
            walkTo = Math.max(1, Math.min(walkTo, planned.size()));
            walked.addAll(planned.subList(0, walkTo));
            planned = new ArrayList<>(planned.subList(walkTo, planned.size()));
            player = walked.get(walked.size() - 1).pos();
            if (horizontal(player, goal) <= 1) {
                return cost(walked);
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /** 全視界・重み1.0・ガイド無しの1回の探索。届かなければ{@link Double#POSITIVE_INFINITY}。 */
    static double fullVisibilityBest(FakeCells all, BlockPos start, BlockPos goal) {
        PathResult result = new AStarPathfinder(all,
                new SearchLimits(UNLIMITED_NODE_BUDGET, 120_000, 1.0)).search(start, goal, NEVER);
        return result.complete() ? cost(result.steps()) : Double.POSITIVE_INFINITY;
    }
}
