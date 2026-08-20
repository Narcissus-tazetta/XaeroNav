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
 *     遠回りの方が安いことが原理的にありうる（{@link FlightCosts}参照）</li>
 * </ol>
 *
 * <p><b>採否はA*とまったく同じコスト関数で決めること</b>（狭さの割増を含む）。片方だけに入れると、
 * A*が広い所へ迂回した経路を平滑化が狭い所へ引き戻す——実際に踏んだ。{@link Clearance}参照。
 *
 * <h2>ここは探索より重くなりうる</h2>
 *
 * 実機のネザーで、探索が時間上限(2秒)で打ち切られた後の平滑化に<b>6.5秒</b>かかっていた。素朴な
 * string pullは全ての(from, to)を試すのでO(n^2)、しかも1回の判定が{@link Clearance#alongLine}で
 * 線上の全セルを舐め、セルごとに26近傍の飛行可否を要求する——探索が一度も触っていない領域を
 * 大量に評価することになる。次の2つで抑えている:
 *
 * <ul>
 * <li>元の折れ線のコストは<b>累積和で一度だけ</b>求める。置き換え候補ごとに区間を歩き直さない</li>
 * <li>近道の探索範囲を{@link #LOOKAHEAD_POINTS}点に限る。長い直線は数回に分けて畳まれるだけで、
 *     見た目はほとんど変わらない</li>
 * </ul>
 *
 * <p>加えて期限を渡す。過ぎたら以降は畳まずそのまま返す——折れの残った線は見た目が少し悪いだけだが、
 * 飛んでいる相手に何秒も線を出せない方が困る。
 */
final class FlightSmoother {

    /** 1点から先、何点先までを近道の候補にするか。 */
    private static final int LOOKAHEAD_POINTS = 64;

    private FlightSmoother() {
    }

    static List<Vec3> smooth(List<Vec3> points, AirGrid grid, boolean rockets,
                              double clearancePenaltyTicks, long deadline) {
        if (points.size() < 3) {
            return points;
        }
        // 元の折れ線のコストの累積和。置き換え候補ごとに区間を歩き直すと、これだけでO(n^2)になる
        double[] prefix = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            prefix[i] = prefix[i - 1]
                    + segmentTicks(grid, points.get(i - 1), points.get(i), rockets, clearancePenaltyTicks);
        }

        List<Vec3> result = new ArrayList<>();
        result.add(points.get(0));
        int from = 0;
        while (from < points.size() - 1) {
            int next = from + 1;
            if (System.currentTimeMillis() < deadline) {
                int limit = Math.min(points.size() - 1, from + LOOKAHEAD_POINTS);
                // 遠い方から試す。最初に見つかったものが最も多くの折れを畳める
                for (int to = limit; to > from + 1; to--) {
                    if (!grid.clearLine(points.get(from), points.get(to))) {
                        continue;
                    }
                    if (segmentTicks(grid, points.get(from), points.get(to), rockets, clearancePenaltyTicks)
                            > prefix[to] - prefix[from]) {
                        continue;
                    }
                    next = to;
                    break;
                }
            }
            result.add(points.get(next));
            from = next;
        }
        return result;
    }

    private static double segmentTicks(AirGrid grid, Vec3 from, Vec3 to, boolean rockets,
                                        double clearancePenaltyTicks) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return FlightCosts.segmentTicks(Math.sqrt(dx * dx + dz * dz), to.y - from.y, rockets)
                + Clearance.alongLine(grid, from, to, clearancePenaltyTicks);
    }
}
