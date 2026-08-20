package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/**
 * 格子A*が返す階段状の折れ線を、通せる限り真っ直ぐに伸ばす（string pull）。
 *
 * <p>格子の目に沿った経路をそのまま線として出すと、実際には一直線に飛べる場所でも数ブロックごとに
 * 折れた線になる。飛んでいる人間が追うのは線の<b>向き</b>なので、この折れがそのまま「機首をどこへ
 * 向ければいいのか分からない」になる。
 *
 * <p>近道は2つの条件を両方満たしたときだけ採る:
 * <ol>
 * <li>{@link AirGrid#clearLine}——跨ぐ<b>格子セル</b>が全て飛行可。ブロック解像度で見ないのは、
 *     壁にぴったり沿った線が「当たっていない」ことになってクリアランスが消えるため</li>
 * <li>置き換える区間より<b>高くつかない</b>こと。滑空は水平距離ぶんの降下を無料で使えるので、
 *     遠回りの方が安いことが原理的にありうる（{@link FlightCosts}参照）。真っ直ぐにした結果
 *     A*が選んだ経路より高くなるなら、それはもう平滑化ではなく改悪になる</li>
 * </ol>
 *
 * <p><b>採否はA*とまったく同じコスト関数で決めること</b>（狭さの割増を含む）。片方だけに入れると、
 * A*が広い所へ迂回した経路を平滑化が狭い所へ引き戻す——実際に踏んだ。{@link Clearance}参照。
 */
final class FlightSmoother {

    private FlightSmoother() {
    }

    static List<Vec3> smooth(List<Vec3> points, AirGrid grid, boolean rockets,
                              double clearancePenaltyTicks) {
        if (points.size() < 3) {
            return points;
        }
        List<Vec3> result = new ArrayList<>();
        result.add(points.get(0));
        int from = 0;
        while (from < points.size() - 1) {
            int next = from + 1;
            // 遠い方から試す。最初に見つかったものが最も多くの折れを畳める
            for (int to = points.size() - 1; to > from + 1; to--) {
                if (!grid.clearLine(points.get(from), points.get(to))) {
                    continue;
                }
                if (segmentTicks(grid, points.get(from), points.get(to), rockets, clearancePenaltyTicks)
                        > pathTicks(grid, points, from, to, rockets, clearancePenaltyTicks)) {
                    continue;
                }
                next = to;
                break;
            }
            result.add(points.get(next));
            from = next;
        }
        return result;
    }

    private static double pathTicks(AirGrid grid, List<Vec3> points, int from, int to, boolean rockets,
                                     double clearancePenaltyTicks) {
        double total = 0.0;
        for (int i = from; i < to; i++) {
            total += segmentTicks(grid, points.get(i), points.get(i + 1), rockets, clearancePenaltyTicks);
        }
        return total;
    }

    private static double segmentTicks(AirGrid grid, Vec3 from, Vec3 to, boolean rockets,
                                        double clearancePenaltyTicks) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return FlightCosts.segmentTicks(Math.sqrt(dx * dx + dz * dz), to.y - from.y, rockets)
                + Clearance.alongLine(grid, from, to, clearancePenaltyTicks);
    }
}
