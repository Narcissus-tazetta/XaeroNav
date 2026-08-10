package net.prason.xaeronav.pathfinding.astar;

/** design doc §4-1。Phase 1ではTraverse/Ascend/Descendのみ扱う（Diagonal/Fall/Pillarは後回し）。 */
public enum MovementType {
    TRAVERSE,
    ASCEND,
    DESCEND
}
