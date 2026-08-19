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
     * 溶岩は立てる場所が無いので{@code null}（{@code groundHeightAt}が返すのは溶岩面の高さであって、
     * その1つ上は溶岩の中か水没した空気でしかない）。データが無ければ同じく{@code null}。
     */
    public BlockPos resolveStandable(int x, int z) {
        byte kind = kindAt(x, z);
        if (kind == CoarseMap.WATER) {
            short surface = surfaceHeightAt(x, z);
            return surface == UNKNOWN_HEIGHT ? null : new BlockPos(x, surface, z);
        }
        if (kind == CoarseMap.LAVA) {
            return null;
        }
        short ground = groundHeightAt(x, z);
        return ground == UNKNOWN_HEIGHT ? null : new BlockPos(x, ground + 1, z);
    }

    /**
     * {@link #resolveStandable}が{@code null}だった端点を、廊下内の最寄りの立てる列へ寄せて解決する。
     * ネザーの溶岩の海の縁ではwaypointがそのまま溶岩列に落ちることが珍しくなく、そこで層2の廊下
     * 精緻化を丸ごと諦めるのは惜しい——数ブロック隣に陸があるだけのことが多い。
     *
     * <p>{@code maxRadius}内で最も近い列を返す（同着はスキャン順で先着＝小さいZ・小さいXを優先、
     * 呼び出しごとに結果が変わらないようにするため）。見つからなければ{@code null}。
     */
    public BlockPos resolveNearestStandable(int x, int z, int maxRadius) {
        BlockPos direct = resolveStandable(x, z);
        if (direct != null) {
            return direct;
        }
        BlockPos best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        int minSearchX = Math.max(minX, x - maxRadius);
        int maxSearchX = Math.min(minX + sizeX - 1, x + maxRadius);
        int minSearchZ = Math.max(minZ, z - maxRadius);
        int maxSearchZ = Math.min(minZ + sizeZ - 1, z + maxRadius);
        for (int candidateZ = minSearchZ; candidateZ <= maxSearchZ; candidateZ++) {
            for (int candidateX = minSearchX; candidateX <= maxSearchX; candidateX++) {
                long dx = candidateX - x;
                long dz = candidateZ - z;
                long distanceSq = dx * dx + dz * dz;
                if (distanceSq > (long) maxRadius * maxRadius || distanceSq >= bestDistanceSq) {
                    continue;
                }
                BlockPos resolved = resolveStandable(candidateX, candidateZ);
                if (resolved != null) {
                    best = resolved;
                    bestDistanceSq = distanceSq;
                }
            }
        }
        return best;
    }
}
