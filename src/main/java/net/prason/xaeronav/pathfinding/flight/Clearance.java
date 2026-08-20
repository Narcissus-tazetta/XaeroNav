package net.prason.xaeronav.pathfinding.flight;

import net.minecraft.world.phys.Vec3;

/**
 * 狭い所を通ることへの割増（tick）。
 *
 * <p><b>探索と平滑化の両方がここを通すこと</b>が要点。片方だけに入れると、A*が正しく広い所へ
 * 迂回した経路を、平滑化が「地形には当たっていないし安い」と判断して狭い所へ引き戻す——
 * 実際に踏んだ（割増を120tick/セルまで上げてもトンネルを通る線が出続けた）。
 * 平滑化の採否は探索と同じコスト関数で決めなければ、探索の意図をそのまま壊す。
 */
final class Clearance {

    /**
     * 26近傍のうち、塞がっていても「狭い」とみなさないセル数。
     *
     * <p>9は平面1枚ぶん——地表や天井の上を余裕を持って飛んでいるだけの状態で、狭くはない。
     * ここを0にすると開けた場所でも床が近いだけで割増になり、経路が理由もなく高い所を通る
     * （滑空は降下が無料なので、その2つが正面から衝突する）。
     */
    private static final int FREE_BLOCKED_NEIGHBOURS = 9;

    private static final int NEIGHBOUR_COUNT = 26;

    private Clearance() {
    }

    /** そのセルへ入る割増。周りが塞がっているほど高い。 */
    static double cell(AirGrid grid, int cellX, int cellY, int cellZ, double penaltyTicks) {
        if (penaltyTicks <= 0.0) {
            return 0.0;
        }
        int excess = grid.blockedNeighbours(cellX, cellY, cellZ) - FREE_BLOCKED_NEIGHBOURS;
        return excess <= 0 ? 0.0
                : penaltyTicks * excess / (NEIGHBOUR_COUNT - FREE_BLOCKED_NEIGHBOURS);
    }

    /** 線分が跨ぐ全セルぶんの割増の合計。 */
    static double alongLine(AirGrid grid, Vec3 from, Vec3 to, double penaltyTicks) {
        if (penaltyTicks <= 0.0) {
            return 0.0;
        }
        double scale = 1.0 / grid.cellBlocks();
        double[] total = {0.0};
        VoxelRay.traverse(from.scale(scale), to.scale(scale), (x, y, z) -> {
            total[0] += cell(grid, x, y, z, penaltyTicks);
            return true;
        });
        return total[0];
    }
}
