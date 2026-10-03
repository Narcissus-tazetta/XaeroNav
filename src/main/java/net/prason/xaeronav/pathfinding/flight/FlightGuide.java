package net.prason.xaeronav.pathfinding.flight;

/**
 * 空中経路の探索に足す、目的地までの残りコストの見積もり（tick）。直線の下限より大きいときだけ効く。
 * 分からない所は{@link Double#NaN}（直線の下限で見積もる）、目的地へ繋がらないと分かっている所は
 * {@link Double#POSITIVE_INFINITY}を返す。
 */
@FunctionalInterface
public interface FlightGuide {

    FlightGuide NONE = (x, y, z) -> Double.NaN;

    double estimate(double x, double y, double z);
}
