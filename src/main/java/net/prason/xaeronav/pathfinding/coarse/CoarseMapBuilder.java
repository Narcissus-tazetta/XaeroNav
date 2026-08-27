package net.prason.xaeronav.pathfinding.coarse;

import java.util.Arrays;

/**
 * {@link CoarseMap}を1セル（＝1チャンク）ずつ埋めていく。
 *
 * <p>地図データの読み出し元（Xaero・ライブ読み取り）を知らずに済ませるために分けてある。
 * 読み出し側は1チャンク分を集計して{@link #putFloor}を呼ぶだけでよく、
 * こちらは配列の添字計算と床の並び替え・上限だけを持つ。
 */
public final class CoarseMapBuilder {

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    private final byte[] floorCount;
    private final byte[] kind;
    private final short[] height;
    private final short[] minHeight;
    private final short[] maxHeight;
    private int knownCells;

    public CoarseMapBuilder(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        int cells = chunksX * chunksZ;
        this.floorCount = new byte[cells];
        int floorSlots = cells * CoarseMap.MAX_FLOORS;
        this.kind = new byte[floorSlots];
        this.height = new short[floorSlots];
        this.minHeight = new short[floorSlots];
        this.maxHeight = new short[floorSlots];
        Arrays.fill(this.height, CoarseMap.UNKNOWN_HEIGHT);
        Arrays.fill(this.minHeight, CoarseMap.UNKNOWN_HEIGHT);
        Arrays.fill(this.maxHeight, CoarseMap.UNKNOWN_HEIGHT);
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

    /** 代表の高さだけを知っていて内部の起伏が分からない場合。平坦（min=max=height）として扱う。 */
    public void putFloor(int chunkX, int chunkZ, byte cellKind, int cellHeight) {
        putFloor(chunkX, chunkZ, cellKind, cellHeight, cellHeight, cellHeight);
    }

    /**
     * このセルに床を1つ追加する。範囲外の座標は黙って捨てる（読み出し側はリージョン単位で走るので、
     * 範囲の縁で必ずはみ出す）。
     *
     * <p>床は高さ昇順を保って挿入する。同じ高さ帯（{@link net.prason.xaeronav.xaero.XaeroMapReader}の
     * 洞窟レイヤー1枚ぶん）を2回書いた場合は上書きにする——同じ参照Yの読み直しで同じレイヤーが
     * 再度渡されても床が増殖しないようにするため。{@link CoarseMap#MAX_FLOORS}を超える分は、
     * 最後に追加された最遠の床を捨てる（呼び出し側は参照Yに近い順に渡す想定なので、捨てるのは
     * 常に最も遠かった床になる）。
     */
    public void putFloor(int chunkX, int chunkZ, byte cellKind, int cellHeight, int cellMinHeight,
                          int cellMaxHeight) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        if (localX < 0 || localX >= chunksX || localZ < 0 || localZ >= chunksZ) {
            return;
        }
        int cellIndex = localZ * chunksX + localX;
        int base = cellIndex * CoarseMap.MAX_FLOORS;
        int count = floorCount[cellIndex];

        int insertAt = 0;
        while (insertAt < count && height[base + insertAt] < cellHeight) {
            insertAt++;
        }
        // 同じ高さの床が既にあれば上書き（新規追加ではなく差し替え）
        if (insertAt < count && height[base + insertAt] == cellHeight) {
            kind[base + insertAt] = cellKind;
            minHeight[base + insertAt] = (short) cellMinHeight;
            maxHeight[base + insertAt] = (short) cellMaxHeight;
            return;
        }

        if (count == 0) {
            knownCells++;
        }
        int newCount = Math.min(count + 1, CoarseMap.MAX_FLOORS);
        // 上限を超える場合は「最も高い床」を捨てる（挿入位置が末尾なら新しい床自体がそれに当たる）。
        // ここには高さの基準が無いので、これ以上のことは決められない——どの高さ帯を残すかに
        // 意味があるなら、呼び出し側が渡す前にMAX_FLOORS個へ絞ること（LiveCoarseSamplerはそうしている）
        if (count == CoarseMap.MAX_FLOORS && insertAt == count) {
            return;
        }
        for (int i = Math.min(count, CoarseMap.MAX_FLOORS - 1); i > insertAt; i--) {
            kind[base + i] = kind[base + i - 1];
            height[base + i] = height[base + i - 1];
            minHeight[base + i] = minHeight[base + i - 1];
            maxHeight[base + i] = maxHeight[base + i - 1];
        }
        kind[base + insertAt] = cellKind;
        height[base + insertAt] = (short) cellHeight;
        minHeight[base + insertAt] = (short) cellMinHeight;
        maxHeight[base + insertAt] = (short) cellMaxHeight;
        floorCount[cellIndex] = (byte) newCount;
    }

    /**
     * このセルに積まれている床の数。読み出し側が「どのレイヤーからも床が得られなかったセル」を
     * 全レイヤーを読み終えてから判定するために要る。
     */
    public int floorCount(int chunkX, int chunkZ) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        if (localX < 0 || localX >= chunksX || localZ < 0 || localZ >= chunksZ) {
            return 0;
        }
        return floorCount[localZ * chunksX + localX];
    }

    /** 代表の高さだけを知っていて内部の起伏が分からない場合。平坦（min=max=height）として扱う。 */
    public void replaceCell(int chunkX, int chunkZ, byte cellKind, int cellHeight) {
        replaceCell(chunkX, chunkZ, cellKind, cellHeight, cellHeight, cellHeight);
    }

    /**
     * このセルの床をすべて消し、単一の床で置き換える。{@link #putFloor}は「この高さ帯にはこの
     * データがある」を積み増していく操作なので、既存の床と高さが違う新しいデータは（同じ物理的
     * セルの更新のつもりでも）別の階層として追加されてしまう。呼び出し側が「このセルの真実は
     * これで全部」と確信しているとき（診断・テストでの地形の作り直しなど）はこちらを使うこと。
     */
    public void replaceCell(int chunkX, int chunkZ, byte cellKind, int cellHeight, int cellMinHeight,
                             int cellMaxHeight) {
        int localX = chunkX - minChunkX;
        int localZ = chunkZ - minChunkZ;
        if (localX < 0 || localX >= chunksX || localZ < 0 || localZ >= chunksZ) {
            return;
        }
        int cellIndex = localZ * chunksX + localX;
        if (floorCount[cellIndex] == 0) {
            knownCells++;
        }
        floorCount[cellIndex] = 1;
        int base = cellIndex * CoarseMap.MAX_FLOORS;
        kind[base] = cellKind;
        height[base] = (short) cellHeight;
        minHeight[base] = (short) cellMinHeight;
        maxHeight[base] = (short) cellMaxHeight;
    }

    public CoarseMap build() {
        return new CoarseMap(minChunkX, minChunkZ, chunksX, chunksZ, floorCount, kind, height, minHeight, maxHeight,
                knownCells);
    }
}
