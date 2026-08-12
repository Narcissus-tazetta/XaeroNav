package net.prason.xaeronav.pathfinding.coarse;

import java.util.Arrays;

/**
 * {@link CoarseMap}を1セル（＝1チャンク）ずつ埋めていく。
 *
 * <p>地図データの読み出し元（Xaero）を知らずに済ませるために分けてある。読み出し側は
 * 1チャンク分を集計して{@link #put}を呼ぶだけでよく、こちらは配列の添字計算だけを持つ。
 */
public final class CoarseMapBuilder {

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    private final byte[] kind;
    private final short[] height;
    private int knownCells;

    public CoarseMapBuilder(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        int cells = chunksX * chunksZ;
        this.kind = new byte[cells];
        this.height = new short[cells];
        Arrays.fill(this.height, CoarseMap.UNKNOWN_HEIGHT);
    }

    public int minChunkX() {
        return minChunkX;
    }

    public int minChunkZ() {
        return minChunkZ;
    }

    public int chunksX() {
        return chunksX;
    }

    public int chunksZ() {
        return chunksZ;
    }

    /** 範囲外の座標は黙って捨てる。読み出し側はリージョン単位で走るので、範囲の縁で必ずはみ出す。 */
    public void put(int chunkX, int chunkZ, byte cellKind, int cellHeight) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        if (localX < 0 || localX >= chunksX || localZ < 0 || localZ >= chunksZ) {
            return;
        }
        int index = localZ * chunksX + localX;
        if (kind[index] == CoarseMap.NO_DATA && cellKind != CoarseMap.NO_DATA) {
            knownCells++;
        }
        kind[index] = cellKind;
        height[index] = (short) cellHeight;
    }

    public CoarseMap build() {
        return new CoarseMap(minChunkX, minChunkZ, chunksX, chunksZ, kind, height, knownCells);
    }
}
