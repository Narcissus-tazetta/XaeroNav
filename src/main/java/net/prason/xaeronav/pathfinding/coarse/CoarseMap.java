package net.prason.xaeronav.pathfinding.coarse;

/**
 * 長距離ルート用の粗い地形。1セル＝1チャンク（16×16ブロック）で、地形の種別と代表の高さだけを持つ。
 *
 * <p>読み込み済みチャンクの中しか見られない詳細探索に対して、こちらはXaeroが保存している
 * 訪問済み領域の地図から作る。目的が「海や溶岩を避けてどちら回りで行くか」を決めることなので、
 * 1マス単位の通行可否は持たない（幅1マスの橋は表現できない）。実際に辿る経路は、
 * ここが出した中間目標に向けて詳細探索が引き直す。
 *
 * <p>1セルは<b>最大{@value #MAX_FLOORS}層の床</b>を持つ（床＝高さ昇順に並んだ{kind, height,
 * minHeight, maxHeight}の組）。天井のある次元（ネザー）ではXaeroの地図がY帯ごとの洞窟レイヤーに
 * 分かれており、同じXZに複数の独立した通路が上下に重なりうる。それを1つの高さへ潰す
 * （旧実装）と、垂直に分断された通路が「安い段差」として繋がって見えたり、waypointが
 * 到達不能な階層へ落ちたりする。地上・ジ・エンドは常に床数1になるので、既存の2.5D的な
 * 挙動はそのまま保たれる。
 *
 * <p>生成後は不変。メインスレッドで組み立ててワーカースレッドから読む前提で、
 * 可変フィールドを持たせないこと。
 */
public final class CoarseMap {

    public static final byte NO_DATA = 0;
    public static final byte LAND = 1;
    public static final byte WATER = 2;
    public static final byte LAVA = 3;

    /**
     * 溶岩が混じるが、まだ歩いて抜けられるセル。
     *
     * <p>{@link #LAVA}と分けるのは、チャンクの一部が溶岩というだけで通行不能にすると
     * ネザーの地形の過半数が壁になるため（実測: 既知セルの58%が溶岩判定になった）。
     * 1マス単位で溶岩を避けられるかどうかは粗い地図には分からないので、ここでは
     * 「通れるが高い」に留めて、実際に抜けられるかの判断は層2・層3へ渡す。
     */
    public static final byte LAVA_MIXED = 4;

    /** データが無いセルの高さ。 */
    public static final short UNKNOWN_HEIGHT = Short.MIN_VALUE;

    /**
     * 1セルが持てる床の上限。Xaeroの参照Y付近の洞窟レイヤーを最大4枚まで読む
     * （{@code XaeroMapReader#MAX_CAVE_LAYERS}）のに合わせてある——それ以上の階層が
     * 同じセルに実在しても、読む対象自体を絞っているので床には現れない。
     */
    public static final int MAX_FLOORS = 4;

    private final int minChunkX;
    private final int minChunkZ;
    private final int chunksX;
    private final int chunksZ;
    /** セルごとの床数（0〜{@link #MAX_FLOORS}）。長さ{@code chunksX*chunksZ}。 */
    private final byte[] floorCount;
    /** 長さ{@code chunksX*chunksZ*MAX_FLOORS}。セル内は高さ昇順。 */
    private final byte[] kind;
    private final short[] height;
    private final short[] minHeight;
    private final short[] maxHeight;
    private final int knownCells;

    CoarseMap(int minChunkX, int minChunkZ, int chunksX, int chunksZ, byte[] floorCount,
              byte[] kind, short[] height, short[] minHeight, short[] maxHeight, int knownCells) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunksX = chunksX;
        this.chunksZ = chunksZ;
        this.floorCount = floorCount;
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

    /** このセルが持つ床の数。範囲外・データ無しなら0。 */
    public int floorCount(int chunkX, int chunkZ) {
        if (!containsChunk(chunkX, chunkZ)) {
            return 0;
        }
        return floorCount[cellIndex(chunkX, chunkZ)];
    }

    public byte kindAtFloor(int chunkX, int chunkZ, int floor) {
        return kind[floorIndex(chunkX, chunkZ, floor)];
    }

    /** その床の代表の高さ。水の場合は水底ではなく水面の高さ。 */
    public short heightAtFloor(int chunkX, int chunkZ, int floor) {
        return height[floorIndex(chunkX, chunkZ, floor)];
    }

    /**
     * その床の内部で観測できた最小・最大の高さ。平均だけでは崖のあるチャンクと緩斜面のチャンクを
     * 区別できないので、この差（{@code maxHeightAtFloor - minHeightAtFloor}）を崖の目安に使う。
     */
    public short minHeightAtFloor(int chunkX, int chunkZ, int floor) {
        return minHeight[floorIndex(chunkX, chunkZ, floor)];
    }

    public short maxHeightAtFloor(int chunkX, int chunkZ, int floor) {
        return maxHeight[floorIndex(chunkX, chunkZ, floor)];
    }

    /**
     * {@code fromFloorHeight}に最も近い高さの床を選ぶ。垂直遷移（同じセル内で階層をまたぐ）の
     * 着地点選びと、waypoint座標の解決に使う。床が無ければ-1。
     */
    public int nearestFloor(int chunkX, int chunkZ, int fromFloorHeight) {
        int count = floorCount(chunkX, chunkZ);
        if (count == 0) {
            return -1;
        }
        int bestFloor = 0;
        int bestDistance = Math.abs(heightAtFloor(chunkX, chunkZ, 0) - fromFloorHeight);
        for (int floor = 1; floor < count; floor++) {
            int distance = Math.abs(heightAtFloor(chunkX, chunkZ, floor) - fromFloorHeight);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestFloor = floor;
            }
        }
        return bestFloor;
    }

    private int cellIndex(int chunkX, int chunkZ) {
        return (chunkZ - minChunkZ) * chunksX + (chunkX - minChunkX);
    }

    private int floorIndex(int chunkX, int chunkZ, int floor) {
        return cellIndex(chunkX, chunkZ) * MAX_FLOORS + floor;
    }
}
