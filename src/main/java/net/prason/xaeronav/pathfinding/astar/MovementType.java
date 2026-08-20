package net.prason.xaeronav.pathfinding.astar;

/**
 * design doc §4-1。同一高度での斜め移動はTRAVERSEとして扱う（{@code AStarPathfinder#addDiagonalTraverse}）。
 * Fall/Pillarは後回し。
 */
public enum MovementType {
    TRAVERSE,
    ASCEND,
    DESCEND,
    /** 水中の移動（水面を泳ぐ・潜る・水底を歩く）。足場が無くても成立する点が他と違う。 */
    SWIM,
    /** ボートで水面を渡る区間。足場も泳ぎも要らないかわりに、出す・乗る・降りる手間が入口に乗る。 */
    BOAT,
    /** 梯子・ツタの昇降。水中と同じく足場を要求しない。 */
    CLIMB,
    /** 1マスの隙間を飛び越える区間（{@code AStarPathfinder#addJumpGap}）。他と違い掘削も設置も伴わない。 */
    JUMP,
    /** 落下ダメージを受けて降りる区間（設定で許可した場合のみ）。実際に体力が減る。 */
    FALL_DAMAGE,
    /** 着地寸前に水バケツを置いて落下ダメージを消す区間。タイミング操作が要る。 */
    FALL_MLG
}
