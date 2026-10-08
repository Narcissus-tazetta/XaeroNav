package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 今の目的地（区間の終点）より先に通る地点の列。最後の要素が最終目的地で、それより前が経由地。
 *
 * <p>探索・航法グラフは区間の終点だけを解く。区間をまたいだ最適化はせず、区間が終わるたびに次の地点を目的地に設定し直す。
 * 地点は指定されたままの座標で持つ——立てる高さへの寄せは、その地点が区間の終点になったときに{@link PathfindingState}が行う。
 */
final class RouteStops {
    static final int MAX_STOPS = 10;

    private final List<BlockPos> ahead = new ArrayList<>();
    private int passed;

    boolean isEmpty() {
        return ahead.isEmpty();
    }

    /** 今の目的地より先の地点。最後が最終目的地。 */
    List<BlockPos> ahead() {
        return ahead;
    }

    /** まだ通っていない経由地の数（今の目的地が経由地ならそれも数える）。 */
    int remainingStops() {
        return ahead.size();
    }

    /** これまでに通った（または飛ばした）経由地の数。 */
    int passed() {
        return passed;
    }

    boolean full() {
        return ahead.size() >= MAX_STOPS;
    }

    void clear() {
        ahead.clear();
        passed = 0;
    }

    void insert(int index, BlockPos pos) {
        ahead.add(index, pos);
    }

    void append(BlockPos pos) {
        ahead.add(pos);
    }

    void remove(int index) {
        ahead.remove(index);
    }

    /** 先頭の地点を取り出す。通ったことにはしない（経由地を外したとき）。 */
    BlockPos takeFirst() {
        return ahead.remove(0);
    }

    /**
     * {@code ahead}の{@code index}番目を次の区間の終点として取り出す。今の目的地（経由地）と、その間の地点は通ったことにする。
     */
    BlockPos advanceTo(int index) {
        passed += index + 1;
        BlockPos next = ahead.get(index);
        ahead.subList(0, index + 1).clear();
        return next;
    }

    /**
     * {@code route}（今の目的地から最終目的地まで）のどこへ{@code stop}を入れると、{@code from}から辿る直線距離の合計の
     * 増え方が一番小さいか。返す添字の位置へ入れる（0なら今の目的地の手前）。最終目的地の後ろには入れない。
     */
    static int cheapestInsertion(BlockPos from, List<BlockPos> route, BlockPos stop) {
        int best = 0;
        double bestDelta = Double.POSITIVE_INFINITY;
        for (int i = 0; i < route.size(); i++) {
            BlockPos prev = i == 0 ? from : route.get(i - 1);
            BlockPos next = route.get(i);
            double delta = distance(prev, stop) + distance(stop, next) - distance(prev, next);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = i;
            }
        }
        return best;
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }
}
