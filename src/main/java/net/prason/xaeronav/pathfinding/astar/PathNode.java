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

    /**
     * ボートに乗った状態か。<b>座標と並ぶノードの同一性の一部</b>で、
     * {@link AStarPathfinder}は乗っている状態と乗っていない状態を別のノードとして持つ。
     *
     * <p>{@link #bridgeRun}や{@link #submergedRun}のように非キーの近似にできない。乗る手間は
     * 1手に集中する大きな一時コストで、A*は安い辺から展開するため、同一ノードに集約すると
     * <b>必ず泳ぎ側が先に確定して{@link #closed}になり、ボートの枝が二度と改善できない</b>——
     * 総コストでどれだけ有利でも選ばれなくなる。
     */
    final boolean boating;

    /** ゴールまでの推定コスト。座標とゴールが決まれば不変なので生成時に1度だけ計算する。 */
    final double estimatedCostToGoal;

    double cost = ActionCosts.INFEASIBLE;
    double combinedCost;

    PathNode previous;
    MoveKind kind;

    /**
     * ここまで連続した{@link MoveKind#BRIDGE}のブロック数。橋以外の移動を1つでも挟めば0に戻る
     * （足場を1マスでも踏めば数え直し）。{@link AStarPathfinder#addBridge}が上限判定に使う。
     *
     * <p><b>このノードの同一性には含まれない</b>（キーは座標のみ）。同じセルへ短い橋で来た経路と
     * 長い橋で来た経路は同じノードに集約され、先に最安で確定した方の連続長が残る。辺コスト自体は
     * 連続長に依存しない（上限を超えた橋を作らないだけ）ので経路のコストは歪まないが、
     * 上限の判定は「最安で到達した経路の連続長」に基づく近似になる——飛び石を挟めば安く渡れる
     * 地形で、その飛び石経由の可能性を取りこぼしうる。取りこぼした結果「範囲内に道が無い」に
     * なった場合は{@link AStarPathfinder#bridgeRunCapBlocked()}を見て上限を外して探し直す。
     */
    int bridgeRun;

    /**
     * ここまで頭が水に浸かったまま続いたブロック数。水面に顔を出すか陸に上がれば0に戻る。
     * {@link AStarPathfinder#relax}が上限判定に使う。
     *
     * <p>空気は{@code AIR_SUPPLY_TICKS}で尽きるので、これは「息が続くか」そのもの。数える基準が
     * <b>頭のセル</b>なのはバニラに合わせたため——{@code LivingEntity#baseTick}は
     * {@code isEyeInFluid(WATER)}で空気を減らすので、腰まで浸かっていても顔が出ていれば減らない。
     *
     * <p>{@link #bridgeRun}と同じく<b>ノードの同一性には含まれない</b>（キーは座標のみ）。
     * 同じセルへ短い潜水で来た経路と長い潜水で来た経路は同じノードに集約され、先に最安で
     * 確定した方の連続長が残る。上限のせいで範囲内に道が無くなった場合は
     * {@link AStarPathfinder#submergedRunCapBlocked()}を見て上限を外して探し直す。
     *
     * <p>始点の連続長は常に0から数える。潜り始めに空気が満タンとは限らないぶんは、上限側に
     * 余裕を持たせて吸収している。
     */
    int submergedRun;

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

    PathNode(int x, int y, int z, boolean boating, double estimatedCostToGoal) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.boating = boating;
        this.estimatedCostToGoal = estimatedCostToGoal;
    }

    boolean isOpen() {
        return heapPosition != -1;
    }
}
