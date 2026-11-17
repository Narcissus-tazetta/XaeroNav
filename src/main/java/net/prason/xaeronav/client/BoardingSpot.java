package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.pathfinding.world.BlockRegistryCompat;

/**
 * 電車に乗るために立つ場所。MTRが教えるホームの位置は線路の中点で、床の上ではない。
 *
 * <p>MTRのホームのブロック（{@code mtr:platform…}）の上を最優先にする。電車の扉が開くのはこのブロックがある側。
 * 無ければ線路より高い床、それも無ければ一番近い立てる所へ落とす。
 */
final class BoardingSpot {

    /** 線路の中点から探す水平の範囲。線路の幅の半分＋ホームの縁までを覆う値で、実測ではない。 */
    private static final int REACH = 4;
    private static final int BELOW = 1;
    private static final int ABOVE = 3;

    private BoardingSpot() {
    }

    static boolean loaded(Level level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /** {@code railMiddle}の周りで立つ場所。立てる所が1つも無ければ{@code railMiddle}のまま。読み込み済みの所で呼ぶこと。 */
    static BlockPos find(Level level, BlockPos railMiddle) {
        BlockPos best = railMiddle;
        long bestRank = Long.MAX_VALUE;
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                for (int dy = -BELOW; dy <= ABOVE; dy++) {
                    int x = railMiddle.getX() + dx;
                    int y = railMiddle.getY() + dy;
                    int z = railMiddle.getZ() + dz;
                    if (!PathfindingState.standableAt(level, x, y, z)) {
                        continue;
                    }
                    int kind = platformBlock(level, x, y - 1, z) ? 0 : dy > 0 ? 1 : 2;
                    long rank = ((long) kind << 32) | ((long) (dx * dx + dz * dz) << 8) | Math.abs(dy);
                    if (rank < bestRank) {
                        bestRank = rank;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    private static boolean platformBlock(Level level, int x, int y, int z) {
        return BlockRegistryCompat.keyOf(level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString()
                .startsWith("mtr:platform");
    }
}
