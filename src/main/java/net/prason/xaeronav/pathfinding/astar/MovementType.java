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
    /** 梯子・ツタの昇降。水中と同じく足場を要求しない。 */
    CLIMB
}
