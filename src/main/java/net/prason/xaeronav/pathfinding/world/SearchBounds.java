package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;

/**
 * 探索範囲の制限（design doc §4-3）。開始地点と目的地を含むバウンディングボックス+マージン。
 */
public record SearchBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    private static final int WORLD_MIN_Y = -64;
    private static final int WORLD_MAX_Y = 320;

    public boolean contains(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    public static SearchBounds around(BlockPos start, BlockPos goal, int horizontalMargin, int verticalMargin) {
        int minX = Math.min(start.getX(), goal.getX()) - horizontalMargin;
        int maxX = Math.max(start.getX(), goal.getX()) + horizontalMargin;
        int minZ = Math.min(start.getZ(), goal.getZ()) - horizontalMargin;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + horizontalMargin;
        int minY = Math.max(WORLD_MIN_Y, Math.min(start.getY(), goal.getY()) - verticalMargin);
        int maxY = Math.min(WORLD_MAX_Y, Math.max(start.getY(), goal.getY()) + verticalMargin);
        return new SearchBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
