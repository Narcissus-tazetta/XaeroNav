package net.prason.xaeronav.client;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;

/**
 * まだ経路が分かっていない区間の所要時間の見積もり（tick）。実線の終点、経路がまだ無ければ現在地から目的地まで。
 *
 * <p>航法グラフのガイドがあればその値を使う。探索が向きを決めるのに使っている値そのもので、窓の中は実際に辿れる道の値段、
 * 窓の外は推定。窓の外の推定は過小に出るので、この道のりで学んだ倍率（{@link FarScaleCalibration}）を掛ける。
 * ガイドがまだ無い間（目的地を決めた直後の数秒、ガイドを切る設定）は、地図の点線の長さをスプリントで走った時間にする。
 *
 * <p>ガイドを下る（{@link WindowField#descend}）のは数百ノードを辿る処理で、HUDは毎フレーム描かれるので、
 * 入力が変わらない間は前の値を返す。
 */
final class GoalEta {

    private @Nullable BlockPos cachedFrom;
    private @Nullable BlockPos cachedGoal;
    private @Nullable WindowField cachedField;
    private double cachedScale;
    private @Nullable List<BlockPos> cachedWaypoints;
    private double cachedTicks;

    /**
     * @param waypoints まだ通っていない長距離ルートの中間目標（{@link PathfindingState.NavigationView#coarseRouteWaypoints}）
     */
    double ticks(BlockPos from, BlockPos goal, List<BlockPos> waypoints) {
        WindowField field = PathfindingState.INSTANCE.guideForDisplay(goal);
        double scale = PathfindingState.INSTANCE.guideFarScaleForDisplay();
        if (from.equals(cachedFrom) && goal.equals(cachedGoal) && field == cachedField && scale == cachedScale
                && (field != null || waypoints == cachedWaypoints)) {
            return cachedTicks;
        }
        double guided = field == null ? Double.NaN : fromGuide(field, scale, from);
        cachedTicks = Double.isFinite(guided) ? guided
                : alongDots(from, goal, waypoints) * ActionCosts.SPRINT_ONE_BLOCK;
        cachedFrom = from;
        cachedGoal = goal;
        cachedField = field;
        cachedScale = scale;
        cachedWaypoints = waypoints;
        return cachedTicks;
    }

    private static double fromGuide(WindowField field, double scale, BlockPos from) {
        WindowField.Descent descent = field.descend(from.getX(), from.getY(), from.getZ());
        if (descent != null) {
            return descent.inside() + scale * descent.outside();
        }
        // ノードでない点（経路の終点が崩れた足場の上など）。近くのノードか外の推定の値になる
        double value = field.estimate(from.getX(), from.getY(), from.getZ());
        return field.measuredInWindow(from.getX(), from.getZ()) ? value : scale * value;
    }

    /**
     * 地図に描く点線（{@link MapPathOverlay}）と同じ折れ線の長さ（水平、ブロック）。通過済みの中間目標は
     * 点線と同じ規則で読み飛ばす。
     */
    static double alongDots(BlockPos from, BlockPos goal, List<BlockPos> waypoints) {
        double length = 0.0;
        int x = from.getX();
        int z = from.getZ();
        if (!waypoints.isEmpty()) {
            for (int i = MapPathOverlay.firstAheadWaypoint(waypoints, x, z); i < waypoints.size(); i++) {
                BlockPos next = waypoints.get(i);
                length += Math.hypot(next.getX() - x, next.getZ() - z);
                x = next.getX();
                z = next.getZ();
            }
        }
        return length + Math.hypot(goal.getX() - x, goal.getZ() - z);
    }
}
