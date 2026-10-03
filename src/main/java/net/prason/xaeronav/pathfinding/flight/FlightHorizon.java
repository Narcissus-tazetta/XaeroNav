package net.prason.xaeronav.pathfinding.flight;

/**
 * 空中経路の探索を打ち切ってよい水平の円。円の外へ出たセルは目的地に着いたのと同じに扱う。
 *
 * <p>読み込み済みチャンクの外にある目的地は、そこを点で狙うと<b>原理的に届かない</b>。届かないことを
 * 確かめるために毎回ノード上限を使い切っていた（現世・エンドの最初の1本は15万ノード・約0.6秒）。
 * 手前に中間の点を置く形も、その点が山や岩の中に落ちると同じことになる。
 *
 * <p>そこで目的地は本物のまま、見積もりは本物の目的地へ向けたままにして、読める範囲の縁を<b>出口</b>にする。
 * 見積もりが目的地へ引っ張るので、探索は目的地の方角の縁から出ていく。出る高度も探索が選ぶ。
 *
 * @param radius 中心からの水平距離（ブロック）。{@link Double#POSITIVE_INFINITY}なら出口なし
 */
public record FlightHorizon(double centerX, double centerZ, double radius) {

    public static final FlightHorizon NONE = new FlightHorizon(0.0, 0.0, Double.POSITIVE_INFINITY);

    boolean outside(double x, double z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return dx * dx + dz * dz >= radius * radius;
    }
}
