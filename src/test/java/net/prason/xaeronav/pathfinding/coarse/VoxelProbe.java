package net.prason.xaeronav.pathfinding.coarse;

import net.minecraft.core.BlockPos;

/**
 * 使い捨ての計測用。{@link VoxelTerrain}がブロック座標をどの種別のセルとして持っているかを、
 * パッケージの外（計測テスト）から読めるようにするだけの薄い口。
 */
public final class VoxelProbe {

    private VoxelProbe() {
    }

    /** {@code 床} / {@code 溶岩} / {@code 空洞} / {@code 箱の外}。 */
    public static String kindAt(VoxelTerrain terrain, BlockPos pos) {
        if (!terrain.contains(pos.getX(), pos.getY(), pos.getZ())) {
            return "箱の外";
        }
        return switch (terrain.kindAt(terrain.indexOfBlock(pos.getX(), pos.getY(), pos.getZ()))) {
            case VoxelTerrain.STANDABLE -> "床";
            case VoxelTerrain.LAVA -> "溶岩";
            default -> "空洞";
        };
    }
}
