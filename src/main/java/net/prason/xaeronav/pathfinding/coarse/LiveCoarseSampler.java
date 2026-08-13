package net.prason.xaeronav.pathfinding.coarse;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * {@link CoarseMap}を、Xaeroの地図ではなく{@link CellSource}(読み込み済みチャンクの生ブロックデータ)
 * から組み立てる。描画距離の内側では層3(詳細A*)の方が層2(Xaero地図)より詳細な情報を持つため、
 * 局所的な崖・湖で詳細A*が頭打ちになったときの再挑戦に、この粗い地図を使う
 * （design doc外・{@link net.prason.xaeronav.pathfinding.async.PathfindingExecutor#submitCoarseGuided}）。
 *
 * <p>集計方法は{@code XaeroMapReader#readTile}と同じ（1チャンクを4×4=16点サンプリングし、水/溶岩の
 * 比率で種別を、最小・最大・平均高さを集計する）。{@link CoarseMap}/{@link CoarseMapBuilder}/
 * {@link CoarseRouter}は読み出し元を知らない設計なので、そのまま流用できる。
 */
public final class LiveCoarseSampler {

    /** 1チャンク内で何ブロックおきに見るか。{@code XaeroMapReader}の値と揃える。 */
    private static final int SAMPLE_STEP = 4;
    private static final int SAMPLES_PER_CHUNK = (16 / SAMPLE_STEP) * (16 / SAMPLE_STEP);
    private static final int LAVA_SAMPLE_THRESHOLD = SAMPLES_PER_CHUNK / 4;

    /**
     * 列(x,z)の地表を探す下方向の走査深さ。{@link CellSource#openSkyY}はハイトマップ由来の
     * 目安でしかない（水面かどうかでずれうる）ので、そこから実セルで下へ辿って裏取りする
     * （{@code AStarPathfinder#firstNonAirBelow}と同じ考え方）。
     */
    private static final int MAX_SCAN_DEPTH = 32;

    private LiveCoarseSampler() {
    }

    public static CoarseMap sample(CellSource view, SearchBounds bounds) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;

        CoarseMapBuilder builder = new CoarseMapBuilder(minChunkX, minChunkZ, chunksX, chunksZ);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                sampleChunk(view, chunkX, chunkZ, builder);
            }
        }
        return builder.build();
    }

    private static void sampleChunk(CellSource view, int chunkX, int chunkZ, CoarseMapBuilder builder) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        int waterSamples = 0;
        int lavaSamples = 0;
        int heightSum = 0;
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        int samples = 0;

        for (int dx = 0; dx < 16; dx += SAMPLE_STEP) {
            for (int dz = 0; dz < 16; dz += SAMPLE_STEP) {
                ColumnSample sample = sampleColumn(view, baseX + dx, baseZ + dz);
                if (sample == null) {
                    continue;
                }
                samples++;
                if (sample.kind == CoarseMap.LAVA) {
                    lavaSamples++;
                } else if (sample.kind == CoarseMap.WATER) {
                    waterSamples++;
                }
                heightSum += sample.height;
                minHeight = Math.min(minHeight, sample.height);
                maxHeight = Math.max(maxHeight, sample.height);
            }
        }

        if (samples == 0) {
            return;
        }
        byte kind;
        if (lavaSamples >= LAVA_SAMPLE_THRESHOLD) {
            kind = CoarseMap.LAVA;
        } else if (waterSamples * 2 >= samples) {
            kind = CoarseMap.WATER;
        } else {
            kind = CoarseMap.LAND;
        }
        builder.put(chunkX, chunkZ, kind, heightSum / samples, minHeight, maxHeight);
    }

    /** {@code openSkyY}を起点に下へ実セルを辿り、最初に見つかる非空気セルの種別と高さを返す。 */
    private static ColumnSample sampleColumn(CellSource view, int x, int z) {
        int top = view.openSkyY(x, z);
        if (top == Integer.MAX_VALUE) {
            return null;
        }
        for (int y = top; y > top - MAX_SCAN_DEPTH; y--) {
            long cell = view.cell(x, y, z);
            if (!CellData.present(cell)) {
                return null;
            }
            if (!CellData.passableEmpty(cell)) {
                return new ColumnSample(kindOf(cell), y);
            }
        }
        return null;
    }

    private static byte kindOf(long cell) {
        if (CellData.lava(cell)) {
            return CoarseMap.LAVA;
        }
        if (CellData.water(cell)) {
            return CoarseMap.WATER;
        }
        return CoarseMap.LAND;
    }

    private record ColumnSample(byte kind, int height) {
    }
}
