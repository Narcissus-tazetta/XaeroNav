package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;

/**
 * 「いま空中経路のどこにいるか」を1tickに1度だけ求めて共有する（歩行の{@link PathProgress}と同じ役目）。
 *
 * <p><b>点ではなく線分への距離で測る</b>のが歩行との決定的な違い。歩行のステップは1ブロック刻みなので
 * 最寄りの点までの距離がそのまま経路までの距離になるが、空中経路は平滑化した後の折れ線で、頂点どうしが
 * 数十ブロック離れる。点で測ると、線の上をぴったり飛んでいても「20ブロック外れている」ことになり、
 * 広い許容を設けた意味がまるごと消える。
 *
 * <p>ずれは水平と垂直に分けて持つ。エリトラの上下のぶれは水平より大きいので、同じ幅で縛ると
 * 高度が数ブロック違うだけで引き直しが走り続ける。
 */
final class FlightProgress {

    static final FlightProgress INSTANCE = new FlightProgress();

    /** 垂直のずれを水平の何倍まで許すか。 */
    static final double VERTICAL_TOLERANCE_FACTOR = 1.5;

    /** 直前の区間の周りだけを見る幅（区間数）。経路が自分の近くへ戻ってくる地形で遠くへ飛ばないため。 */
    private static final int WINDOW_AHEAD = 4;
    private static final int WINDOW_BEHIND = 1;

    private FlightRoute source;
    private int segment;
    private double horizontal = Double.MAX_VALUE;
    private double vertical = Double.MAX_VALUE;

    private FlightProgress() {
    }

    void update(FlightRoute route, Vec3 position) {
        if (route == null || route.isEmpty()) {
            source = null;
            segment = 0;
            horizontal = Double.MAX_VALUE;
            vertical = Double.MAX_VALUE;
            return;
        }
        List<Vec3> points = route.points();
        if (route != source) {
            source = route;
            segment = 0;
        }
        int last = points.size() - 2;
        int from = Math.max(0, segment - WINDOW_BEHIND);
        int to = Math.min(last, segment + WINDOW_AHEAD);
        int best = nearest(points, position, from, to);
        // 窓の中がどれも遠いなら、そもそも別の場所を飛んでいる。全体から取り直す
        if (offsetOf(points, best, position).lengthSqr() > FULL_SCAN_DISTANCE * FULL_SCAN_DISTANCE) {
            best = nearest(points, position, 0, last);
        }
        segment = best;
        Vec3 offset = offsetOf(points, best, position);
        horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        vertical = Math.abs(offset.y);
    }

    /** 窓の中に近い区間が無ければ全体を探し直す境界（ブロック）。 */
    private static final double FULL_SCAN_DISTANCE = 48.0;

    /**
     * 末尾に区間を継ぎ足しただけの経路へ、対応づけをそのまま引き継ぐ。継ぎ足しは手前の点の
     * 添字を変えないので、いま指している区間はそのまま通用する。
     *
     * <p>これを呼ばずに新しい{@link FlightRoute}を渡すと、{@link #update}が別経路とみなして
     * 添字を0に戻す。点線の切り詰めがその添字を使っているので、伸ばした瞬間だけ通過済みの区間が
     * 描き直される（歩行の{@code PathProgress.carryOver}と同じ理由）。
     */
    void carryOver(FlightRoute extended) {
        if (source == null) {
            return;
        }
        source = extended;
    }

    /** {@code route}に対応づけ済みの区間。違う経路なら先頭。 */
    int segmentFor(FlightRoute route) {
        return route == source ? segment : 0;
    }

    /** 直近に測った経路までの水平のずれ（ブロック）。 */
    double horizontalOffset() {
        return horizontal;
    }

    /** 直近に測った経路までの垂直のずれ（ブロック）。 */
    double verticalOffset() {
        return vertical;
    }

    /**
     * 許容の外へ出たか。球ではなく<b>楕円体</b>で見る——垂直だけを別々の閾値で比べると、
     * 水平にも垂直にも中途半端にずれている状態がどちらの判定にも掛からずに素通りする。
     */
    boolean deviated(double horizontalThreshold) {
        if (horizontal == Double.MAX_VALUE) {
            return false;
        }
        double verticalThreshold = horizontalThreshold * VERTICAL_TOLERANCE_FACTOR;
        double h = horizontal / horizontalThreshold;
        double v = vertical / verticalThreshold;
        return h * h + v * v > 1.0;
    }

    private static int nearest(List<Vec3> points, Vec3 position, int from, int to) {
        int best = from;
        double bestDistance = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double distance = offsetOf(points, i, position).lengthSqr();
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** 区間{@code index}上の最寄り点から見たプレイヤーの位置。 */
    private static Vec3 offsetOf(List<Vec3> points, int index, Vec3 position) {
        Vec3 from = points.get(index);
        Vec3 to = points.get(index + 1);
        Vec3 along = to.subtract(from);
        double lengthSq = along.lengthSqr();
        double t = lengthSq > 0.0 ? position.subtract(from).dot(along) / lengthSq : 0.0;
        return position.subtract(from.add(along.scale(Math.clamp(t, 0.0, 1.0))));
    }
}
