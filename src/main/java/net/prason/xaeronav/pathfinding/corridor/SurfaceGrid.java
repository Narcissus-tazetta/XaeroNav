package net.prason.xaeronav.pathfinding.corridor;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;

/**
 * 長距離ルート層2（廊下限定のブロック解像度地表グラフ）が使う、1ブロック列(x,z)ごとの地表データ。
 *
 * <p>{@link CoarseMap}のブロック解像度版。1チャンク=1セルではなく1ブロック=1セルで持つ代わりに、
 * 対象範囲を廊下（層1のwaypoint間の線分±マージン程度）に絞ることで配列サイズを抑える。
 *
 * <p>生成後は不変。{@link SurfaceGridBuilder}が組み立てる。
 */
public final class SurfaceGrid {

    public static final short UNKNOWN_HEIGHT = CoarseMap.UNKNOWN_HEIGHT;

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final byte[] kind;
    private final short[] groundHeight;
    private final short[] surfaceHeight;

    SurfaceGrid(int minX, int minZ, int sizeX, int sizeZ,
                byte[] kind, short[] groundHeight, short[] surfaceHeight) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.kind = kind;
        this.groundHeight = groundHeight;
        this.surfaceHeight = surfaceHeight;
    }

    public boolean containsColumn(int x, int z) {
        int localX = x - minX;
        int localZ = z - minZ;
        return localX >= 0 && localX < sizeX && localZ >= 0 && localZ < sizeZ;
    }

    public byte kindAt(int x, int z) {
        if (!containsColumn(x, z)) {
            return CoarseMap.NO_DATA;
        }
        return kind[index(x, z)];
    }

    /** 地表の高さ。水なら水底、陸・溶岩ならその表面。データが無ければ{@link #UNKNOWN_HEIGHT}。 */
    public short groundHeightAt(int x, int z) {
        if (!containsColumn(x, z)) {
            return UNKNOWN_HEIGHT;
        }
        return groundHeight[index(x, z)];
    }

    /** 水面の高さ。水以外は{@link #groundHeightAt}と同じ値。 */
    public short surfaceHeightAt(int x, int z) {
        if (!containsColumn(x, z)) {
            return UNKNOWN_HEIGHT;
        }
        return surfaceHeight[index(x, z)];
    }

    private int index(int x, int z) {
        return (z - minZ) * sizeX + (x - minX);
    }

    /**
     * この列(x,z)で実際に立てる高さへ解決する。陸は地面の1つ上、水は水面そのもの
     * （{@code SurfaceCellSource#cell}が水面をWATERセルとして扱うため、+1すると空気に解決されてしまう）。
     * データが無ければ{@code null}。
     */
    public BlockPos resolveStandable(int x, int z) {
        byte kind = kindAt(x, z);
        if (kind == CoarseMap.WATER) {
            short surface = surfaceHeightAt(x, z);
            return surface == UNKNOWN_HEIGHT ? null : new BlockPos(x, surface, z);
        }
        short ground = groundHeightAt(x, z);
        return ground == UNKNOWN_HEIGHT ? null : new BlockPos(x, ground + 1, z);
    }
}
