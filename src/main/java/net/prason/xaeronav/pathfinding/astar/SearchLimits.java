package net.prason.xaeronav.pathfinding.astar;

/**
 * 1回の探索の打ち切り条件と、ヒューリスティックの重み。
 *
 * <p>展開数の上限は「届かなかったときに打ち切る天井」で、経路が見つかればそこで探索は終わる。
 * 上げても届く経路の計算時間は変わらず、下げると届くはずの経路が手前で切れる。
 * 重みを上げると同じ展開数で到達距離が伸びるが、遠回りな経路が混じりうる。
 */
public record SearchLimits(int maxExpandedNodes, long timeLimitMillis, double heuristicWeight) {

    public static final SearchLimits DEFAULT = new SearchLimits(
            AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
            AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
}
