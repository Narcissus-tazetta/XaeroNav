package net.prason.xaeronav.pathfinding.flight;

import net.prason.xaeronav.pathfinding.astar.SearchLimits;

/**
 * 空中経路の探索を1回動かすのに要る調整値。
 *
 * <p>1つにまとめてあるのは、この3つが<b>互いに引き合う</b>ため。格子を粗くすると同じ体積のセル数が
 * 減って予算内で遠くまで届くが、狭い通路は通れなくなる。重みを上げても遠くまで届くが、遠回りが
 * 混じる。予算を上げれば届くが1回の計算が長くなる。別々の引数として散らすと、どれかを触ったときに
 * 残りを見直す動機が消える。
 *
 * @param cellBlocks            格子の一辺（ブロック）。<b>遠くまで届かせる一番効く手</b>——
 *                              セル数は一辺の3乗に反比例する。同時に線の周りの余白そのものでもある
 * @param clearancePenaltyTicks 26近傍が完全に塞がったセルへ入るときの割増（tick）。0で無効
 * @param limits                展開数・時間の上限とヒューリスティックの重み
 */
public record FlightTuning(int cellBlocks, double clearancePenaltyTicks, SearchLimits limits) {
}
