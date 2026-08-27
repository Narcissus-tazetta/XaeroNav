package net.prason.xaeronav.pathfinding.flight;

import net.prason.xaeronav.pathfinding.coarse.CoarseMap;

/**
 * 空中の長距離ルート用の粗い地形。1セル＝1チャンクで、そのセルで<b>飛べる高度帯</b>だけを持つ。
 *
 * <p>{@link CoarseMap}（歩行の層1が使う、チャンクごと最大4層の床）から導く。床そのものではなく
 * 床と床の<b>あいだ</b>が飛行に使える空間なので、ここで持ち替える。
 *
 * <p><b>この近似が成り立つ根拠</b>: Xaeroの洞窟レイヤーは{@code caveStart}から下向きに走査して
 * 最初の不透明ブロックを記録する。開けた空間の上に岩の天井があれば、その天井自身が別のレイヤーの
 * 床として記録される——つまり高さ順に並んだ床は、実際に開いた空間を挟んでいる。
 *
 * <p><b>抜けられる保証は無い</b>。チャンク解像度なので、帯の中に幅1チャンク未満の壁があっても
 * 見えない。歩行の層1とまったく同じ契約で、確実な区間は読み込み済みチャンクを見る{@link AirGrid}が
 * 受け持つ。
 *
 * <p>生成後は不変。メインスレッドで組み立ててワーカースレッドから読む。
 */
public final class CoarseAirMap {

    /** 1セルが持てる高度帯の上限。床がN層あれば帯はN個（最上段は次元の天井まで）。 */
    public static final int MAX_BANDS = CoarseMap.MAX_FLOORS;

    /**
     * 床のすぐ上は帯に含めない余白（ブロック）。床の高さはチャンク内の平均なので、
     * 実際には数ブロック高い出っ張りが普通にある。
     */
    private static final int FLOOR_MARGIN = 4;

    /** 天井のすぐ下も同じ理由で余白を取る。 */
    private static final int CEILING_MARGIN = 4;

    /** これより薄い帯は捨てる（ブロック）。エリトラが余裕を持って通れる厚みの下限。 */
    private static final int MIN_BAND_THICKNESS = 8;

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    private final int minY;
    private final int maxY;
    /** セルごとの帯の数（0〜{@link #MAX_BANDS}）。 */
    private final byte[] bandCount;
    /**
     * 元の{@link CoarseMap}に床があったか。<b>帯が0であることと、データが無いことは別</b>——
     * 床が天井近くまで詰まっていて飛べる厚みが残らないセルも帯0になる。区別せずに「帯0＝未知＝
     * 通行可」にすると、実際には塞がっている列を素通りする経路が出る。
     */
    private final boolean[] known;
    private final short[] bottom;
    private final short[] top;

    private CoarseAirMap(int minChunkX, int minChunkZ, int chunksX, int chunksZ, int minY, int maxY,
                          byte[] bandCount, boolean[] known, short[] bottom, short[] top) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.minY = minY;
        this.maxY = maxY;
        this.bandCount = bandCount;
        this.known = known;
        this.bottom = bottom;
        this.top = top;
    }

    /**
     * 床の並びから高度帯を導く。
     *
     * @param minY 飛べる高さの下限（次元の底＋余白）
     * @param maxY 飛べる高さの上限。ネザーなら岩盤天井の<b>下</b>にすること——天井は不透明なので
     *             洞窟レイヤーの床としては記録されず、ここで頭打ちにしないと最上段の帯が
     *             岩の中まで伸びる
     */
    public static CoarseAirMap from(CoarseMap map, int minY, int maxY) {
        int cells = map.chunksX() * map.chunksZ();
        byte[] bandCount = new byte[cells];
        boolean[] known = new boolean[cells];
        short[] bottom = new short[cells * MAX_BANDS];
        short[] top = new short[cells * MAX_BANDS];

        for (int localZ = 0; localZ < map.chunksZ(); localZ++) {
            for (int localX = 0; localX < map.chunksX(); localX++) {
                int chunkX = map.minChunkX() + localX;
                int chunkZ = map.minChunkZ() + localZ;
                int cell = localZ * map.chunksX() + localX;
                int floors = map.floorCount(chunkX, chunkZ);
                known[cell] = floors > 0;
                int count = 0;
                // 奈落のセルは床が1枚あるが高さを持たない（CoarseMap.VOID）。歩く側では通行不能でも
                // 飛ぶ側では上から下まで全部が空なので、次元の全高を1本の帯にする。
                // heightAtFloorの番兵（UNKNOWN_HEIGHT）をそのまま足してminYへクランプさせても
                // 同じ値にはなるが、番兵の値に依存した偶然に見えるので明示的に分ける
                if (floors == 1 && map.kindAtFloor(chunkX, chunkZ, 0) == CoarseMap.VOID) {
                    bottom[cell * MAX_BANDS] = (short) minY;
                    top[cell * MAX_BANDS] = (short) maxY;
                    bandCount[cell] = 1;
                    continue;
                }
                for (int floor = 0; floor < floors; floor++) {
                    int bandBottom = map.heightAtFloor(chunkX, chunkZ, floor) + FLOOR_MARGIN;
                    int bandTop = floor + 1 < floors
                            ? map.heightAtFloor(chunkX, chunkZ, floor + 1) - CEILING_MARGIN
                            : maxY;
                    bandBottom = Math.max(bandBottom, minY);
                    bandTop = Math.min(bandTop, maxY);
                    if (bandTop - bandBottom < MIN_BAND_THICKNESS) {
                        continue;
                    }
                    bottom[cell * MAX_BANDS + count] = (short) bandBottom;
                    top[cell * MAX_BANDS + count] = (short) bandTop;
                    count++;
                }
                bandCount[cell] = (byte) count;
            }
        }
        return new CoarseAirMap(map.minChunkX(), map.minChunkZ(), map.chunksX(), map.chunksZ(),
                minY, maxY, bandCount, known, bottom, top);
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

    public boolean containsChunk(int chunkX, int chunkZ) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        return localX >= 0 && localX < chunksX && localZ >= 0 && localZ < chunksZ;
    }

    /** そのセルの飛べる高度帯の数。 */
    public int bandCount(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return 0;
        }
        return bandCount[cellIndex(chunkX, chunkZ)];
    }

    /**
     * Xaeroの地図にデータが無いセルか。未訪問なだけで飛べないとは限らないので、範囲全体を1つの
     * 帯とみなす（歩行の層1が{@code NO_DATA}を通行可にしているのと同じ考え方）。
     */
    public boolean unknown(int chunkX, int chunkZ) {
        return containsChunk(chunkX, chunkZ) && !known[cellIndex(chunkX, chunkZ)];
    }

    /**
     * データはあるのに飛べる帯が1つも無いセルか。床が天井近くまで詰まっている＝<b>壁</b>。
     * 粗い層が「通行不能」を表現できる唯一の形。
     */
    public boolean blocked(int chunkX, int chunkZ) {
        return !containsChunk(chunkX, chunkZ)
                || (known[cellIndex(chunkX, chunkZ)] && bandCount[cellIndex(chunkX, chunkZ)] == 0);
    }

    /** 探索の状態数。未知セルは「範囲全体」1つ、壁は0。 */
    public int stateBands(int chunkX, int chunkZ) {
        if (blocked(chunkX, chunkZ)) {
            return 0;
        }
        return unknown(chunkX, chunkZ) ? 1 : bandCount(chunkX, chunkZ);
    }

    public int bandBottom(int chunkX, int chunkZ, int band) {
        return unknown(chunkX, chunkZ) ? minY : bottom[cellIndex(chunkX, chunkZ) * MAX_BANDS + band];
    }

    public int bandTop(int chunkX, int chunkZ, int band) {
        return unknown(chunkX, chunkZ) ? maxY : top[cellIndex(chunkX, chunkZ) * MAX_BANDS + band];
    }

    /** その帯の中で{@code y}に最も近い高さ。帯の中なら{@code y}そのもの。 */
    public int clampToBand(int chunkX, int chunkZ, int band, int y) {
        return Math.clamp(y, bandBottom(chunkX, chunkZ, band), bandTop(chunkX, chunkZ, band));
    }

    /** {@code y}を含む帯。無ければ最も近い帯。セルに帯が1つも無ければ0（未知セル扱い）。 */
    public int bandAt(int chunkX, int chunkZ, int y) {
        int count = bandCount(chunkX, chunkZ);
        if (count == 0) {
            return 0;
        }
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int band = 0; band < count; band++) {
            int distance = Math.abs(clampToBand(chunkX, chunkZ, band, y) - y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = band;
            }
        }
        return best;
    }

    private int cellIndex(int chunkX, int chunkZ) {
        return (chunkZ - minChunkZ) * chunksX + (chunkX - minChunkX);
    }
}
