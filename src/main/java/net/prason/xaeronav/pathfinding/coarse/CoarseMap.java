package net.prason.xaeronav.pathfinding.coarse;

/**
 * 長距離ルート用の粗い地形。1セル＝1チャンク（16×16ブロック）で、地形の種別と代表の高さだけを持つ。
 *
 * <p>読み込み済みチャンクの中しか見られない詳細探索に対して、こちらはXaeroが保存している
 * 訪問済み領域の地図から作る。目的が「海や溶岩を避けてどちら回りで行くか」を決めることなので、
 * 1マス単位の通行可否は持たない（幅1マスの橋は表現できない）。実際に辿る経路は、
 * ここが出した中間目標に向けて詳細探索が引き直す。
 *
 * <p>生成後は不変。メインスレッドで組み立ててワーカースレッドから読む前提で、
 * 可変フィールドを持たせないこと。
 */
public final class CoarseMap {

    public static final byte NO_DATA = 0;
    public static final byte LAND = 1;
    public static final byte WATER = 2;
    public static final byte LAVA = 3;

    /** データが無いセルの高さ。 */
    public static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    private final byte[] kind;
    private final short[] height;
    private final short[] minHeight;
    private final short[] maxHeight;
    private final int knownCells;

    CoarseMap(int minChunkX, int minChunkZ, int chunksX, int chunksZ,
              byte[] kind, short[] height, short[] minHeight, short[] maxHeight, int knownCells) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.kind = kind;
        this.height = height;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.knownCells = knownCells;
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

    /** データが読めたセルの数。0なら、この範囲はXaeroの地図に無い（未訪問）。 */
    public int knownCells() {
        return knownCells;
    }

    public int totalCells() {
        return chunksX * chunksZ;
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        return localX >= 0 && localX < chunksX && localZ >= 0 && localZ < chunksZ;
    }

    public byte kindAtChunk(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return NO_DATA;
        }
        return kind[index(chunkX, chunkZ)];
    }

    /** そのセルの代表の高さ。水の場合は水底ではなく水面の高さ。データが無ければ{@link #UNKNOWN_HEIGHT}。 */
    public short heightAtChunk(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return UNKNOWN_HEIGHT;
        }
        return height[index(chunkX, chunkZ)];
    }

    /**
     * セル内で観測できた最小・最大の高さ。平均だけでは崖のあるチャンクと緩斜面のチャンクを
     * 区別できないので、この差（{@code maxHeightAtChunk - minHeightAtChunk}）を崖の目安に使う。
     */
    public short minHeightAtChunk(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return UNKNOWN_HEIGHT;
        }
        return minHeight[index(chunkX, chunkZ)];
    }

    public short maxHeightAtChunk(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return UNKNOWN_HEIGHT;
        }
        return maxHeight[index(chunkX, chunkZ)];
    }

    public byte kindAtBlock(int blockX, int blockZ) {
        return kindAtChunk(blockX >> 4, blockZ >> 4);
    }

    public short heightAtBlock(int blockX, int blockZ) {
        return heightAtChunk(blockX >> 4, blockZ >> 4);
    }

    private int index(int chunkX, int chunkZ) {
        return (chunkZ - minChunkZ) * chunksX + (chunkX - minChunkX);
    }
}
