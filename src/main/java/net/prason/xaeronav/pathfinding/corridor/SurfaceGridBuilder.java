package net.prason.xaeronav.pathfinding.corridor;

import java.util.Arrays;

/**
 * {@link SurfaceGrid}を1ブロック列ずつ埋めていく。{@code CoarseMapBuilder}のブロック解像度版。
 */
public final class SurfaceGridBuilder {

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final byte[] kind;
    private final short[] groundHeight;
    private final short[] surfaceHeight;

    public SurfaceGridBuilder(int minX, int minZ, int sizeX, int sizeZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        int cells = sizeX * sizeZ;
        this.kind = new byte[cells];
        this.groundHeight = new short[cells];
        this.surfaceHeight = new short[cells];
        Arrays.fill(groundHeight, SurfaceGrid.UNKNOWN_HEIGHT);
        Arrays.fill(surfaceHeight, SurfaceGrid.UNKNOWN_HEIGHT);
    }

    /** 水面の高さが地表と同じ場合（陸・溶岩）。 */
    public void put(int x, int z, byte cellKind, int groundHeightValue) {
        put(x, z, cellKind, groundHeightValue, groundHeightValue);
    }

    /** 範囲外の座標は黙って捨てる。読み出し側はタイル単位で走るので、範囲の縁で必ずはみ出す。 */
    public void put(int x, int z, byte cellKind, int groundHeightValue, int surfaceHeightValue) {
        int localX = x - minX;
        int localZ = z - minZ;
        if (localX < 0 || localX >= sizeX || localZ < 0 || localZ >= sizeZ) {
            return;
        }
        int index = localZ * sizeX + localX;
        kind[index] = cellKind;
        groundHeight[index] = (short) groundHeightValue;
        surfaceHeight[index] = (short) surfaceHeightValue;
    }

    public SurfaceGrid build() {
        return new SurfaceGrid(minX, minZ, sizeX, sizeZ, kind, groundHeight, surfaceHeight);
    }
}
