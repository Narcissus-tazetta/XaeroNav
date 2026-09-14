package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.world.MovementOptions;

/**
 * 探索を投げる直前にまとめて読む設定値。{@code PathfindingState}の4つの探索呼び出し箇所
 * （通常探索・合流・繋ぎ目の解き直し・継ぎ足し）が同じ並びで{@code XaeroNavConfig.INSTANCE}を
 * 個別に参照していたので、1回でまとめて読む形にする。
 *
 * <p>ここに含まれないもの（{@code deviationThresholdBlocks}・{@code recalcIntervalTicks}等）は
 * 毎tickの閾値判定に使う値で、設定変更を即座に反映すべき性質のもの。意図的にsnapshot化しない。
 */
public record NavigationTuning(int searchHorizontalMargin, MovementOptions movementOptions,
                                SearchLimits searchLimits, boolean costToGoGuideEnabled) {
}
