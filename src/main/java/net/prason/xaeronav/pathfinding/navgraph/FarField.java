package net.prason.xaeronav.pathfinding.navgraph;

import net.prason.xaeronav.pathfinding.astar.CostToGo;

/**
 * 窓（読み込み範囲）の外の、目的地までの残りコストの推定。窓の境界の種にだけ使う。
 *
 * <p><b>分からない点は{@link Double#POSITIVE_INFINITY}を返すこと。</b>質の悪い値は何も無いより有害で
 * （実測: エンドで層1を外の値にすると1.197倍、何も置かなければ1.009倍）、0を返すとそこへ探索を吸い寄せる。
 */
@FunctionalInterface
public interface FarField {

    FarField UNKNOWN = (x, y, z) -> Double.POSITIVE_INFINITY;

    double at(int x, int y, int z);

    /**
     * 「情報が無ければ0」の約束で作られたガイド（{@code CoarseRouter#costToGo}など）を包む。
     * 0以下は不明として扱う——目的地そのものは窓の中で別に種になるので、ここで0を失っても困らない。
     */
    static FarField of(CostToGo guide) {
        return (x, y, z) -> {
            double value = guide.estimate(x, y, z);
            return value > 0.0 ? value : Double.POSITIVE_INFINITY;
        };
    }
}
