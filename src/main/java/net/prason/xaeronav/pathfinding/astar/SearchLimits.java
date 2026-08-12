package net.prason.xaeronav.pathfinding.astar;

/**
 * 1回の探索の打ち切り条件と、ヒューリスティックの重み。
 *
 * <p>3つは互いに引き合う。重みを上げると同じ展開数でより遠くまで届くので、展開数を増やすより
 * 先に重みを見る方が費用対効果が高い（展開数を10倍にするより、重みを2.0にする方が到達距離が伸びる）。
 */
public record SearchLimits(int maxExpandedNodes, long timeLimitMillis, double heuristicWeight) {

    public static final SearchLimits DEFAULT = new SearchLimits(
            AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
            AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
}
