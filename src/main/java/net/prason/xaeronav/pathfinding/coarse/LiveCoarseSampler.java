package net.prason.xaeronav.pathfinding.coarse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * 読み込み済みチャンクの実データから{@link CoarseMap}を組む。層3の予算切れ時の再挑戦
 * （粗い経由地チェーン）が使う——Xaeroの地図に依存しないので、Xaero未導入でも動く。
 */
public final class LiveCoarseSampler {

    /** 1チャンク内で何ブロックおきに見るか。{@code XaeroMapReader}の値と揃える。 */
    private static final int SAMPLE_STEP = 4;
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

    /**
     * 1チャンク内の異なる列で見つかった床同士を「同じ床」とみなす高さの許容差（ブロック）。
     * Xaeroの洞窟レイヤーは{@code CAVE_MODE_DEPTH}(30)ブロック単位で分かれる——実在の別レイヤーは
     * まず30ブロック以上離れる。一方、同じ床でもチャンク内の地形の起伏で数ブロックはずれうる。
     * その間を取った値で、緩い坂は同じ床にまとめつつ、別レイヤーは分けるのを狙う。
     */
    private static final int FLOOR_CLUSTER_THRESHOLD_BLOCKS = 12;

    private LiveCoarseSampler() {
    }

    /** 参照Yを指定しない版。探索範囲の中央を使い、打ち切りも見ない（テスト・診断用）。 */
    public static CoarseMap sample(CellSource view, SearchBounds bounds) {
        return sample(view, bounds, (bounds.minY() + bounds.maxY()) / 2, () -> false);
    }

    /**
     * @param referenceY 1セルの床が{@link CoarseMap#MAX_FLOORS}を超えたときに、どの高さ帯を残すかの基準。
     *                   探索の始点のYを渡すこと——始点の床を落とすと、そのセルの{@code nearestFloor}が
     *                   別の階層を返し、粗い地図全体が現に立っている場所とは無関係なものになる
     * @param cancelled  途中で打ち切ってよいか。真になった時点で、そこまでの地図を返す
     *                   （呼び出し側も同じ合図で結果を捨てるので、部分的な地図が使われることはない）
     */
    public static CoarseMap sample(CellSource view, SearchBounds bounds, int referenceY,
                                    BooleanSupplier cancelled) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;

        CoarseMapBuilder builder = new CoarseMapBuilder(minChunkX, minChunkZ, chunksX, chunksZ);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            // 走査は探索範囲の全チャンク×16列×最大128セルに達する。新しい探索に追い出された
            // ジョブがこれを完走すると、生きているジョブと同じだけのセル読みを丸ごと二重に払う
            if (cancelled.getAsBoolean()) {
                return builder.build();
            }
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                sampleChunk(view, bounds, chunkX, chunkZ, referenceY, builder);
            }
        }
        return builder.build();
    }

    /**
     * このチャンク内の16列それぞれで見つかった床を、高さが近いもの同士でまとめてから
     * {@link CoarseMapBuilder}へ渡す。1列につき複数の床がありうる（天井のある次元で
     * 上下に独立した通路が重なる場合）ので、単純な1列1値の集計では表現できない。
     */
    private static void sampleChunk(CellSource view, SearchBounds bounds, int chunkX, int chunkZ,
                                     int referenceY, CoarseMapBuilder builder) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        List<FloorAccumulator> floors = new ArrayList<>(CoarseMap.MAX_FLOORS);
        // 床が1つも無いと確かめられた列の数。走査が未ロードで打ち切られた列とは区別する
        int voidColumns = 0;

        for (int dx = 0; dx < 16; dx += SAMPLE_STEP) {
            for (int dz = 0; dz < 16; dz += SAMPLE_STEP) {
                ColumnScan scan = sampleColumnFloors(view, bounds, baseX + dx, baseZ + dz);
                if (scan.voidColumn()) {
                    voidColumns++;
                }
                for (ColumnSample sample : scan.floors()) {
                    accumulate(floors, sample);
                }
            }
        }
        if (floors.isEmpty()) {
            // 床のある列が1つも無い。空気だけの列を実際に読めていたなら「まだ知らない」ではなく
            // 「床が無い」——XaeroMapReader#markVoidCellsと同じ区別を、ライブ読み取り側でも守る
            if (voidColumns > 0) {
                builder.putFloor(chunkX, chunkZ, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                        CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
            }
            return;
        }
        // 1列あたりはMAX_FLOORSで頭打ちだが、16列ぶんのクラスタを合わせると簡単に超える
        // （ネザーでは探索範囲が全高になるので常態）。溢れたぶんの取捨をCoarseMapBuilderへ
        // 委ねてはいけない——あちらは常に「最も高い床」を追い出すので、プレイヤーが立っている
        // 一番上の回廊がそのまま消える。ここで参照Yに近い順に残す
        if (floors.size() > CoarseMap.MAX_FLOORS) {
            floors.sort(Comparator.comparingInt(floor -> Math.abs(floor.approxHeight() - referenceY)));
            floors.subList(CoarseMap.MAX_FLOORS, floors.size()).clear();
        }
        for (FloorAccumulator floor : floors) {
            floor.emit(chunkX, chunkZ, builder);
        }
    }

    /** 高さが近い既存の集計へ足す。無ければ新しい集計を作る（クラスタ数に上限は無い。{@link #sampleChunk}が絞る）。 */
    private static void accumulate(List<FloorAccumulator> floors, ColumnSample sample) {
        FloorAccumulator closest = null;
        int closestDistance = FLOOR_CLUSTER_THRESHOLD_BLOCKS + 1;
        for (FloorAccumulator candidate : floors) {
            int distance = Math.abs(candidate.approxHeight() - sample.height);
            if (distance <= FLOOR_CLUSTER_THRESHOLD_BLOCKS && distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        if (closest == null) {
            closest = new FloorAccumulator();
            floors.add(closest);
        }
        closest.add(sample);
    }

    /**
     * 列(x,z)にある立てる床をすべて探す。上端が固形でも、そこから真の地面までは掘らず
     * 素通りする（{@link CellData#passableEmpty}が続く限りしか床と認めない——探索範囲の上端が
     * 岩の中の場合、それを地面と誤読すると足元の溶岩の海が地図から消える）。
     *
     * <p>1つの床を記録したら、次の床を認めるには再び空気を見る必要がある——同じ固まりの
     * 中で連続する固体セルを複数の床として二重に数えないため。
     *
     * <p>床が無かったとき、その理由を{@link ColumnScan#voidColumn()}で区別する。<b>床0には3通りの
     * 意味がある</b>——空気しか無かった（奈落）、上から下まで固体で詰まっていた（岩の内部）、
     * 未ロードで走査を打ち切った（分からない）。奈落だけを{@link CoarseMap#VOID}に落とすので、
     * 「空気を実際に見た」ことまで確かめる必要がある。岩で詰まった列を奈落に倒すと、地中の
     * セルが橋を架けて渡る対象になってしまう。
     */
    private static ColumnScan sampleColumnFloors(CellSource view, SearchBounds bounds, int x, int z) {
        int top = Math.min(view.openSkyY(x, z), bounds.maxY());
        int bottom = Math.max(bounds.minY(), top - MAX_SCAN_DEPTH);
        List<ColumnSample> floors = new ArrayList<>(CoarseMap.MAX_FLOORS);
        boolean airSeen = false;
        boolean anyAir = false;
        boolean anySolid = false;
        for (int y = top; y >= bottom && floors.size() < CoarseMap.MAX_FLOORS; y--) {
            long cell = view.cell(x, y, z);
            if (!CellData.present(cell)) {
                // 未ロードチャンク。この先は分からないので打ち切る（範囲外はbottomで止まる）
                return new ColumnScan(floors, false);
            }
            if (CellData.passableEmpty(cell)) {
                airSeen = true;
                anyAir = true;
                continue;
            }
            anySolid = true;
            if (airSeen) {
                floors.add(new ColumnSample(kindOf(cell), y));
                airSeen = false;
            }
        }
        return new ColumnScan(floors, anyAir && !anySolid);
    }

    /**
     * 1列の走査結果。{@code voidColumn}は「読み切ったうえで空気しか無かった」＝床が無いと
     * 確かめられた列か。
     */
    private record ColumnScan(List<ColumnSample> floors, boolean voidColumn) {
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

    /** 同じ床とみなされた複数列ぶんのサンプルを、{@code XaeroMapReader#readTile}と同じ規則で集計する。 */
    private static final class FloorAccumulator {
        private int waterSamples;
        private int lavaSamples;
        private int heightSum;
        private int heightSamples;
        private int minHeight = Integer.MAX_VALUE;
        private int maxHeight = Integer.MIN_VALUE;
        // 溶岩面の高さの平均。samplesが全部溶岩だった場合だけ使う
        private int lavaHeightSum;
        private int samples;

        void add(ColumnSample sample) {
            samples++;
            if (sample.kind() == CoarseMap.LAVA) {
                lavaSamples++;
                lavaHeightSum += sample.height();
                // 溶岩面は代表高さから除く（XaeroMapReader#readTileと同じ理由:
                // 溶岩は立てないので混ぜるとwaypointが溶岩面に落ちる）
                return;
            }
            if (sample.kind() == CoarseMap.WATER) {
                waterSamples++;
            }
            heightSum += sample.height();
            heightSamples++;
            minHeight = Math.min(minHeight, sample.height());
            maxHeight = Math.max(maxHeight, sample.height());
        }

        /** クラスタリング中に使う代表高さ。まだ溶岩しか無ければ溶岩面の高さ。 */
        int approxHeight() {
            return heightSamples > 0 ? heightSum / heightSamples : lavaHeightSum / lavaSamples;
        }

        void emit(int chunkX, int chunkZ, CoarseMapBuilder builder) {
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
            int averageHeight = approxHeight();
            int representativeMin = heightSamples > 0 ? minHeight : averageHeight;
            int representativeMax = heightSamples > 0 ? maxHeight : averageHeight;
            builder.putFloor(chunkX, chunkZ, kind, averageHeight, representativeMin, representativeMax);
        }
    }
}
