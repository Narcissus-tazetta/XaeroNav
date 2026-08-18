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

    /**
     * ここまで連続した、溶岩に隣接する{@link MoveKind#BRIDGE}のブロック数。橋以外の移動や
     * 溶岩に隣接しない橋では0に戻る。{@link AStarPathfinder#addBridge}が
     * {@link net.prason.xaeronav.pathfinding.cost.ActionCosts#lavaBridgeRunPenalty}の
     * 閾値判定に使う。
     */
    int bridgeRun;

    /**
     * ここまで連続して昇降の向きが反転した回数。登った直後に降りる、その逆を繰り返すたびに増え、
     * 反転しない移動（同方向の継続・水平移動など）で0に戻る。{@link AStarPathfinder}が
     * 反転のたびにこの回数へ比例したペナルティを課すための状態——1回だけの上下動（谷越えなど）は
     * 軽いまま、同じ高さへ登り降りを繰り返す往復だけを重くする。
     */
    int reversalStreak;

    /** {@link BinaryHeapOpenSet}内での位置。decrease-keyに必要。-1はオープンセット外を表す。 */
    int heapPosition = -1;

    /**
     * 一度展開したか。{@link #heapPosition}では「今オープンセットに入っているか」しか分からず、
     * 展開済みのノードは未発見のノードと見分けがつかない。
     *
     * <p>ヒューリスティックに重みを掛けると一貫性が崩れ、展開済みのノードのコストが後から改善しうる。
     * そのたびにオープンセットへ戻すと同じセルを何度も展開し直す（実測で1セルあたり6回超、
     * 到達に必要な展開数が異なるセル数の6倍に膨らんでいた）。戻さない代わりに、経路のコストは
     * 最適の{@code heuristicWeight}倍以内に収まる（重み付きA*の保証）。
     */
    boolean closed;

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
