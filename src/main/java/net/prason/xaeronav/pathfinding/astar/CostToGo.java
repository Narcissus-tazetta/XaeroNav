package net.prason.xaeronav.pathfinding.astar;

/**
 * ゴールまでの残りコストの見積もり。{@link AStarPathfinder}へ注入する
 * ヒューリスティックの差し替え口——既定は{@link Heuristic}（幾何学的な下限、admissible）だが、
 * 層1の粗い地図から作った{@code costToGo}テーブル（{@code CoarseRouter#costToGo}）を
 * 併用すると、壁や溶岩の海を回避した「実際の地形に沿った」見積もりに近づく。
 *
 * <p>ゴール座標を引数に含めないのは、実装側に閉じ込めるため——{@link AStarPathfinder}の
 * ゴールはコンストラクタではなく{@link AStarPathfinder#search}で決まるので、コンストラクタで
 * 座標を渡す形にすると「テーブルのゴール」と「探索のゴール」の食い違いを防げない。
 */
@FunctionalInterface
public interface CostToGo {

    double estimate(int x, int y, int z);
}
