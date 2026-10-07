package net.prason.xaeronav.pathfinding.flight;

/**
 * 経路の点（プレイヤーの足）を基準に、空いていなければならない箱（ブロック）。
 *
 * <p>エリトラは{@link #NONE}——格子の粗さそのものが余白になっていて（{@link AirGrid}参照）、
 * プレイヤーの体はセルより小さい。乗り物は体がセルからはみ出しうるので箱を足す。
 *
 * @param halfWidth 水平の半幅
 * @param below     足から下へ
 * @param above     足から上へ
 */
public record FlightBody(double halfWidth, double below, double above) {

    public static final FlightBody NONE = new FlightBody(0.0, 0.0, 0.0);

    public boolean isNone() {
        return halfWidth <= 0.0 && below <= 0.0 && above <= 0.0;
    }
}
