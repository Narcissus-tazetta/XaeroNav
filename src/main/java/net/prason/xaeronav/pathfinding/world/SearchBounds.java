package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * 探索範囲の制限（design doc §4-3）。開始地点と目的地を含むバウンディングボックス+マージン。
 */
public record SearchBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * 始点と終点を含むバウンディングボックスに水平マージンを足し、さらに始点を中心とする
     * {@code maxRadius}の正方形で切り取る。
     *
     * <p>切り取るのは、遠いゴールを一度に解こうとしても意味がないため。読み込み済みチャンクの外は
     * そもそも読めないので、そこまで探索範囲を広げても未ロード扱いのセルを舐めるだけになる。
     * 範囲を切ると経路はゴール手前で打ち切られるが、プレイヤーが進めば次の区間が計算し直される
     * （design doc §4-4の暫定経路と同じ扱い）。
     */
    public static SearchBounds around(LevelHeightAccessor level, BlockPos start, BlockPos goal,
                                       int horizontalMargin, int verticalMargin, int maxRadius) {
        int minX = Math.max(start.getX() - maxRadius, Math.min(start.getX(), goal.getX()) - horizontalMargin);
        int maxX = Math.min(start.getX() + maxRadius, Math.max(start.getX(), goal.getX()) + horizontalMargin);
        int minZ = Math.max(start.getZ() - maxRadius, Math.min(start.getZ(), goal.getZ()) - horizontalMargin);
        int maxZ = Math.min(start.getZ() + maxRadius, Math.max(start.getZ(), goal.getZ()) + horizontalMargin);
        int minY = Math.max(level.getMinBuildHeight(), Math.min(start.getY(), goal.getY()) - verticalMargin);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, Math.max(start.getY(), goal.getY()) + verticalMargin);
        return new SearchBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
