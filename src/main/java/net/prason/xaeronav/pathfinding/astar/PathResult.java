package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

/**
 * @param steps         始点を含まない、ゴールまで（または打ち切り時点でゴールに最も近づけた地点まで）の経路
 * @param complete      ゴールに到達できたか。falseの場合はexpansion/time上限による打ち切り（design doc §4-4）
 * @param distinctNodes 探索が触れた異なるセルの数。{@code expandedNodes}がこれを大きく上回るときは、
 *                      同じセルを何度も展開し直している（重み付きヒューリスティックで確定済みノードが
 *                      openへ戻る）。両者を並べないと、この空回りと純粋な探索範囲の広さを区別できない
 */
public record PathResult(List<PathStep> steps, boolean complete, int expandedNodes, int distinctNodes) {
}
