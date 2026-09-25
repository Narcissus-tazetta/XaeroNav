package net.prason.xaeronav.pathfinding.navgraph;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.Heuristic;

/**
 * 窓（読み込み範囲）の外の、目的地までの残りコストの推定。窓の境界の種にだけ使う。
 *
 * <p><b>分からない点は{@link Double#POSITIVE_INFINITY}を返すこと。</b>質の悪い値は何も無いより有害で
 * （実測: エンドで層1を外の値にすると1.197倍、何も置かなければ1.009倍）、0を返すとそこへ探索を吸い寄せる。
 */
@FunctionalInterface
public interface FarField {

    FarField UNKNOWN = (x, y, z) -> Double.POSITIVE_INFINITY;

    /**
     * 目的地までの幾何下限。推定の材料が無いときの外の値。
     *
     * <p>{@link #UNKNOWN}を窓の縁に置くと、目的地が窓の外にある限り種が1つも無く、ガイドが丸ごと使えない
     * （実測: エンドで目的地が窓の外に出るルートが1.022→1.235倍、従来の区間へ落ちた）。
     */
    static FarField straightLineTo(BlockPos goal) {
        return straightLineTo(goal, 1.0);
    }

    /** 目的地までの幾何下限の{@code scale}倍。 */
    static FarField straightLineTo(BlockPos goal, double scale) {
        return new FarField() {
            @Override
            public double at(int x, int y, int z) {
                return scale * Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
            }

            @Override
            public boolean onlyWhenGoalOutside() {
                return true;
            }
        };
    }

    /**
     * 目的地が窓の中にあるときは使わない（{@link #UNKNOWN}として扱う）か。
     *
     * <p>幾何下限は窓の外の地形を何も知らないので、目的地が窓の中にあっても縁の点に「そこから直線で着く」という
     * 過小な値を置き、探索を縁へ吸い寄せる。
     */
    default boolean onlyWhenGoalOutside() {
        return false;
    }

    double at(int x, int y, int z);

    /**
     * {@code (x, y, z)}（窓の中心に立つプレイヤー）より、この推定で目的地から遠い点を{@link Double#POSITIVE_INFINITY}にする。
     *
     * <p>窓の中は実コスト、外は推定なので、推定が実際より安い地形（ジ・エンドの奈落の渡り）では、窓の中で渡るより
     * 「後ろの縁から窓の外へ出て、推定の安い値段で渡り直す」方が安く見える。実機では島の突端に着くたびに来た道の縁へ
     * 案内が戻った（層1の値で、西の縁913+18265に対し東へ実際に渡る4366+15874）。推定そのものの上で遠ざかる縁は
     * 正しい出口になりえない——推定の最短経路が回り込むなら、その先の縁は値が下がっていくので残る。
     */
    static FarField forwardOf(FarField far, int x, int y, int z) {
        double limit = far.at(x, y, z);
        if (!Double.isFinite(limit)) {
            return far;
        }
        return new FarField() {
            @Override
            public double at(int px, int py, int pz) {
                double value = far.at(px, py, pz);
                return value < limit ? value : Double.POSITIVE_INFINITY;
            }

            @Override
            public boolean onlyWhenGoalOutside() {
                return far.onlyWhenGoalOutside();
            }
        };
    }

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
