package net.prason.xaeronav.pathfinding.astar;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * 軸別ヒューリスティック（design doc §4-2、修正版）。
 * 水平・上昇・下降それぞれについて実コストを下回らない下限値を積算する。
 * Diagonal未対応（Phase 1）のため水平距離はマンハッタン距離を使う。
 */
public final class Heuristic {

    private Heuristic() {
    }

    public static double estimate(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        int dy = to.getY() - from.getY();

        double horiz = (dx + dz) * ActionCosts.SPRINT_ONE_BLOCK;
        double vertical = dy > 0
                ? dy * ActionCosts.JUMP_ONE_BLOCK
                : -dy * ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK;
        return horiz + vertical;
    }
}
