package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * 探索中の1ノード。座標ごとに1つだけ生成し、コスト・経路・ヒープ位置をすべてここに持たせる。
 *
 * <p>gScore・cameFrom・closedを別々のMapに分けると、ノード1つあたり複数回のハッシュ計算と
 * エントリ確保が発生する。1オブジェクトに集約することで、ノードのハッシュ引きは
 * {@link AStarPathfinder}の座標→ノードのMap1本だけになる。
 */
final class PathNode {

    final int x;
    final int y;
    final int z;

    /** ゴールまでの推定コスト。座標とゴールが決まれば不変なので生成時に1度だけ計算する。 */
    final double estimatedCostToGoal;

    double cost = ActionCosts.INFEASIBLE;
    double combinedCost;

    PathNode previous;
    MoveKind kind;

    /** {@link BinaryHeapOpenSet}内での位置。decrease-keyに必要。-1はオープンセット外を表す。 */
    int heapPosition = -1;

    PathNode(int x, int y, int z, double estimatedCostToGoal) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.estimatedCostToGoal = estimatedCostToGoal;
    }

    boolean isOpen() {
        return heapPosition != -1;
    }
}
