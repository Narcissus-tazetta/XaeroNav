package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

/**
 * @param steps    始点を含まない、ゴールまで（または打ち切り時点でゴールに最も近づけた地点まで）の経路
 * @param complete ゴールに到達できたか。falseの場合はexpansion/time上限による打ち切り（design doc §4-4）
 */
public record PathResult(List<PathStep> steps, boolean complete, int expandedNodes) {
}
