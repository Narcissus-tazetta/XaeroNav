package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 「いま経路のどこにいるか」を1tickに1度だけ求めて共有する。
 *
 * <p>再計算の要否（{@link PathfindingState}）・案内表示（{@link NavGuidance}）・描画の切り詰め
 * （{@link PathRenderer}）はどれも同じ問いへの答えを必要とする。別々に求めると、同じフレームでも
 * 3者が違うステップを指しうる（案内は次の角を出しているのに線は手前から描かれる、など）。
 *
 * <p>探すのは直前の対応づけの周りだけにする。経路全体から最も近い点を選ぶと、経路が自分自身の
 * 近くを通る地形（洞窟の折り返し階段など）で遠くの区間へ飛び移ってしまう。
 */
final class PathProgress {

    static final PathProgress INSTANCE = new PathProgress();

    private static final int WINDOW_AHEAD = 32;
    private static final int WINDOW_BEHIND = 8;

    /** 窓の中に近い点が無ければ経路から外れたとみなし、全体を探し直す（ブロック）。 */
    private static final double FULL_SCAN_DISTANCE = 8.0;

    private PathResult source;
    private int index;
    private double distance = Double.MAX_VALUE;

    private PathProgress() {
    }

    void update(PathResult result, Vec3 position) {
        if (result == null || result.steps().isEmpty()) {
            source = null;
            index = 0;
            distance = Double.MAX_VALUE;
            return;
        }
        List<PathStep> steps = result.steps();
        if (result != source) {
            source = result;
            index = 0;
        }
        int from = Math.max(0, index - WINDOW_BEHIND);
        int to = Math.min(steps.size() - 1, index + WINDOW_AHEAD);
        int best = nearest(steps, position, from, to);
        if (distanceSq(steps.get(best).pos(), position) > FULL_SCAN_DISTANCE * FULL_SCAN_DISTANCE) {
            best = nearest(steps, position, 0, steps.size() - 1);
        }
        index = best;
        distance = Math.sqrt(distanceSq(steps.get(best).pos(), position));
    }

    /** {@code result}に対応づけ済みのステップ。違う経路なら先頭。 */
    int indexFor(PathResult result) {
        return result == source ? index : 0;
    }

    /**
     * 末尾に区間を継ぎ足しただけの経路へ、対応づけをそのまま引き継ぐ。継ぎ足しは手前のステップの
     * 添字を変えないので、いま指している位置はそのまま通用する。
     *
     * <p>これを呼ばずに新しい{@link PathResult}を渡すと、{@link #update}が別経路とみなして
     * 添字を0に戻し、窓の外なので全体走査に落ちる。全体走査は経路が自分自身の近くを通る地形
     * （洞窟の折り返し階段）で遠くの区間へ飛び移る——先読みで経路が長くなるほど確率が上がる。
     */
    void carryOver(PathResult extended) {
        if (source == null) {
            return;
        }
        source = extended;
    }

    /** 直近に測った経路までの距離（ブロック）。対応づけが無ければ{@link Double#MAX_VALUE}。 */
    double distance() {
        return distance;
    }

    private static int nearest(List<PathStep> steps, Vec3 position, int from, int to) {
        int best = from;
        double bestDistance = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double distance = distanceSq(steps.get(i).pos(), position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** ステップはブロック座標、プレイヤーは連続座標。マスの中心とプレイヤーの足元で比べる。 */
    private static double distanceSq(BlockPos step, Vec3 position) {
        double dx = step.getX() + 0.5 - position.x;
        double dy = step.getY() - position.y;
        double dz = step.getZ() + 0.5 - position.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
