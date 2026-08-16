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
    /** {@code XaeroMapReader}と同じ3段階の溶岩分類にする（層1と層3で同じ地形が違って見えないように）。 */
    private static final int LAVA_MIXED_NUMERATOR = 4;

    /**
     * 列(x,z)の地表を探す下方向の走査深さの上限。{@link CellSource#openSkyY}はハイトマップ由来の
     * 目安でしかない（水面かどうかでずれうる）ので、そこから実セルで下へ辿って裏取りする
     * （{@code AStarPathfinder#firstNonAirBelow}と同じ考え方）。
     *
     * <p>実際の走査は探索範囲の下端で止まるので、これは病的に背の高い範囲への保険。
     * 以前は32固定だったが、それでは探索範囲の下にある溶岩の海（ネザーで範囲上端74・溶岩面31）に
     * 届かず、海そのものが地図に載らなかった。
     */
    private static final int MAX_SCAN_DEPTH = 128;

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
                sampleChunk(view, bounds, chunkX, chunkZ, builder);
            }
        }
        return builder.build();
    }

    private static void sampleChunk(CellSource view, SearchBounds bounds, int chunkX, int chunkZ,
                                     CoarseMapBuilder builder) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        int waterSamples = 0;
        int lavaSamples = 0;
        int heightSum = 0;
        int heightSamples = 0;
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        // 溶岩面の高さの平均。samplesが全部溶岩だった稀なケース（下記）だけで使う
        int lavaHeightSum = 0;
        int samples = 0;

        for (int dx = 0; dx < 16; dx += SAMPLE_STEP) {
            for (int dz = 0; dz < 16; dz += SAMPLE_STEP) {
                ColumnSample sample = sampleColumn(view, bounds, baseX + dx, baseZ + dz);
                if (sample == null) {
                    continue;
                }
                samples++;
                if (sample.kind == CoarseMap.LAVA) {
                    lavaSamples++;
                    lavaHeightSum += sample.height;
                    // 溶岩面は代表高さから除く（XaeroMapReader#readTileと同じ理由:
                    // 溶岩は立てないので混ぜるとwaypointが溶岩面に落ちる）
                    continue;
                }
                if (sample.kind == CoarseMap.WATER) {
                    waterSamples++;
                }
                heightSum += sample.height;
                heightSamples++;
                minHeight = Math.min(minHeight, sample.height);
                maxHeight = Math.max(maxHeight, sample.height);
            }
        }

        if (samples == 0) {
            return;
        }
        byte kind;
        if (lavaSamples * 2 >= samples) {
            kind = CoarseMap.LAVA;
        } else if (lavaSamples * LAVA_MIXED_NUMERATOR >= samples) {
            kind = CoarseMap.LAVA_MIXED;
        } else if (waterSamples * 2 >= samples) {
            kind = CoarseMap.WATER;
        } else {
            kind = CoarseMap.LAND;
        }
        // heightSamples==0はサンプル全部が溶岩のときだけ（＝kindは必ずLAVA）。ここは溶岩面の高さで
        // 正しい——LavaPolicy.BRIDGEでこのセルを渡るとき、足場を置くのがまさにその高さになる
        int averageHeight = heightSamples > 0 ? heightSum / heightSamples : lavaHeightSum / lavaSamples;
        int representativeMin = heightSamples > 0 ? minHeight : averageHeight;
        int representativeMax = heightSamples > 0 ? maxHeight : averageHeight;
        builder.put(chunkX, chunkZ, kind, averageHeight, representativeMin, representativeMax);
    }

    /**
     * 列(x,z)で最初に当たる地面。見つからなければ{@code null}。
     *
     * <p>走査開始を<b>探索範囲の上端で頭打ちにする</b>のが要点。{@link CellSource#openSkyY}は
     * ハイトマップ由来で、天井のある次元では岩盤天井（ネザーならY≒128）を指す。そのまま始点に
     * すると探索範囲の外を読むことになり、{@code cell}が{@link CellData#ABSENT}を返して
     * <b>全列がnull＝粗い地図が1セルも埋まらない</b>。空の地図は全セルNO_DATA（通行可能）なので、
     * 粗いルートは溶岩を無視した直線を引き、その中間目標へ詳細探索が延々と予算を焼く。
     */
    private static ColumnSample sampleColumn(CellSource view, SearchBounds bounds, int x, int z) {
        int top = Math.min(view.openSkyY(x, z), bounds.maxY());
        int bottom = Math.max(bounds.minY(), top - MAX_SCAN_DEPTH);
        for (int y = top; y >= bottom; y--) {
            long cell = view.cell(x, y, z);
            if (!CellData.present(cell)) {
                // 未ロードチャンク。この列は分からないものとして扱う（範囲外はbottomで止まる）
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
