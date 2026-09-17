package net.prason.xaeronav.pathfinding.astar;

/**
 * {@link AStarPathfinder}が生成した移動（辺）を、採否に関わらず全部受け取る口。
 *
 * <p>航法グラフのクラスタ構築と、その正しさを測る完璧なcost-to-goの閉包が使う。
 * <b>経路探索と同じ移動生成から辺を得る</b>ことに意味がある——別のコストモデルで辺を張り直すと、
 * 層1・層2と同じく「真のコストを粗くしたもの」ではなく「別の推測」になる。
 *
 * <p>報告は{@link AStarPathfinder#relax}の入口で行う。改善しない辺も捨てる前に報告するので、
 * 閉包を回し切れば到達したノードから出る辺は全部そろう。ただし移動の生成が経路に依存する上限
 * （橋の連続長・設置総数・潜水時間）で捨てた辺は、そこへ最安で到達した状態から見た分しか出ない。
 *
 * <p>{@code cost}は水中の割増（{@code ActionCosts#SUBMERGED_TRAVEL_PENALTY}）を掛ける前の値。
 * 割増は到達経路の息の勘定に依存するので、辺の値段として一意に決まらない。
 */
@FunctionalInterface
interface EdgeSink {

    void edge(int fromX, int fromY, int fromZ, boolean fromBoating, int toX, int toY, int toZ, boolean toBoating,
              double cost, MoveKind kind);
}
