package net.prason.xaeronav.util;

import net.minecraft.core.BlockPos;

public final class BlockDistance {

    private BlockDistance() {
    }

    public static double horizontal(BlockPos a, BlockPos b) {
        return Math.sqrt(horizontalSq(a, b));
    }

    public static double horizontalSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
