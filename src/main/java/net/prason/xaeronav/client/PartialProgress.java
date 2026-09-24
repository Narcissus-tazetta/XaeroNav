package net.prason.xaeronav.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;

/**
 * 途中までの経路2本の、末端から目的地までの残り。どちらが目的地の近くまで引けているかを比べる。
 *
 * <p>物差しは、両方の末端が窓の中にあるときだけ航法グラフのガイドを使う。窓の外の値は推定で尺度が揃わず
 * （{@link WindowField#measuredInWindow}）、引き算すると推定のずれがそのまま結論になる。そのときは
 * 目的地までの水平距離で比べる。どちらの物差しでも、比べる2つは同じ種類の値どうしにする。
 *
 * @param oldAhead 表示中の経路の方が、目的地の近くまで引けているか。同じなら新しい結果を採る
 */
record PartialProgress(double oldLeft, double newLeft, String yardstick, boolean oldAhead) {

    static PartialProgress compare(PathResult shown, PathResult replacement, BlockPos start, BlockPos goal,
            @Nullable WindowField guide) {
        BlockPos oldEnd = PathfindingState.endOf(shown, start);
        BlockPos newEnd = PathfindingState.endOf(replacement, start);
        if (guide != null && guide.measuredInWindow(oldEnd.getX(), oldEnd.getZ())
                && guide.measuredInWindow(newEnd.getX(), newEnd.getZ())) {
            double oldLeft = guide.estimate(oldEnd.getX(), oldEnd.getY(), oldEnd.getZ());
            double newLeft = guide.estimate(newEnd.getX(), newEnd.getY(), newEnd.getZ());
            return new PartialProgress(oldLeft, newLeft, "ガイド", oldLeft < newLeft);
        }
        double oldLeft = PathfindingState.horizontalDistance(oldEnd, goal);
        double newLeft = PathfindingState.horizontalDistance(newEnd, goal);
        return new PartialProgress(oldLeft, newLeft, "距離", oldLeft < newLeft);
    }
}
