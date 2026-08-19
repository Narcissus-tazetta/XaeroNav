package net.prason.xaeronav.pathfinding.flight;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 空中経路のための粗いボクセル格子。1セルは{@code cellBlocks}ブロック角で、
 * <b>含むブロックが1つ残らず空虚なときだけ</b>飛行可とみなす。
 *
 * <p>粗さそのものがクリアランスになっているのが要点。エリトラは秒速30マス級で飛ぶので、
 * 1ブロックの隙間を狙って通せる案内には意味がない。格子の粒度で「余裕を持って抜けられる空間」だけを
 * 経路の候補にしておけば、線の周りに自然と数ブロックの余白が残る。ユーザーが求めた「許容範囲を
 * 大きく」は、表示や逸脱判定だけでなくここでも表現されている。
 *
 * <p><b>事前構築はしない</b>。レンダー半径192・ネザーの全高を4ブロック角で覆うと約29万セル＝
 * 1900万回のブロック参照になり、事前に埋めるのは成立しない。A*が触ったセルだけを計算して
 * memoする（{@code ChunkView}がブロック状態ごとの判定を memo しているのと同じ手）。
 *
 * <p><b>スレッド契約:</b> memoを可変フィールドに持つので、単一のワーカースレッドが占有すること
 * （下敷きの{@link CellSource}が元々同じ制約を持つ）。
 */
public final class AirGrid {

    private static final byte UNKNOWN = 0;
    private static final byte FLYABLE = 1;
    private static final byte BLOCKED = 2;

    private final CellSource view;
    private final int cellBlocks;
    private final Long2ByteOpenHashMap known = new Long2ByteOpenHashMap();

    public AirGrid(CellSource view, int cellBlocks) {
        this.view = view;
        this.cellBlocks = cellBlocks;
        this.known.defaultReturnValue(UNKNOWN);
    }

    public int cellBlocks() {
        return cellBlocks;
    }

    /** ブロック座標を含むセルの座標。 */
    public int toCell(double blockCoordinate) {
        return Math.floorDiv((int) Math.floor(blockCoordinate), cellBlocks);
    }

    /** セルの中心のブロック座標。 */
    public double toBlockCenter(int cell) {
        return cell * (double) cellBlocks + cellBlocks / 2.0;
    }

    public Vec3 center(int cellX, int cellY, int cellZ) {
        return new Vec3(toBlockCenter(cellX), toBlockCenter(cellY), toBlockCenter(cellZ));
    }

    /**
     * そのセルを飛行に使ってよいか。
     *
     * <p>範囲外・未ロードチャンクは{@link CellData#ABSENT}＝{@code present}が偽なので、自動的に
     * 飛行不可になる。{@code FlightLineRouter}は逆に未ロードを素通りさせているが、あちらは方角を
     * 示すだけの線の話で、こちらは実際に辿らせる経路——<b>未知の中へ経路を引いてはいけない</b>。
     * 読める範囲の外は点線が引き受ける。
     */
    public boolean flyable(int cellX, int cellY, int cellZ) {
        long key = BlockPos.asLong(cellX, cellY, cellZ);
        byte cached = known.get(key);
        if (cached != UNKNOWN) {
            return cached == FLYABLE;
        }
        boolean flyable = computeFlyable(cellX, cellY, cellZ);
        known.put(key, flyable ? FLYABLE : BLOCKED);
        return flyable;
    }

    private boolean computeFlyable(int cellX, int cellY, int cellZ) {
        int fromX = cellX * cellBlocks;
        int fromY = cellY * cellBlocks;
        int fromZ = cellZ * cellBlocks;
        for (int x = fromX; x < fromX + cellBlocks; x++) {
            for (int y = fromY; y < fromY + cellBlocks; y++) {
                for (int z = fromZ; z < fromZ + cellBlocks; z++) {
                    long cell = view.cell(x, y, z);
                    if (!CellData.present(cell) || !CellData.passableEmpty(cell)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 2点を結ぶ直線が、飛行可なセルだけを通るか。平滑化が近道を採ってよいかの判定に使う。
     *
     * <p><b>ブロック解像度ではなく格子解像度で見る</b>のが要点。1本の光線をブロック単位で見ると、
     * 壁にぴったり沿った線でも「当たっていない」ことになり、格子で確保したクリアランスが消える。
     */
    public boolean clearLine(Vec3 from, Vec3 to) {
        double scale = 1.0 / cellBlocks;
        return VoxelRay.traverse(from.scale(scale), to.scale(scale), this::flyable);
    }

    /**
     * {@code around}を含むセルから始めて、飛行可なセルを外側へ{@code maxCellRadius}まで探す。
     * 見つからなければ{@code null}。
     *
     * <p>始点も目的地も、たいてい格子の目に乗っていない——プレイヤーは岩の角をかすめる位置に
     * いるかもしれないし、目的地はそもそも着地する地面（＝飛行不可）であることの方が多い。
     */
    public long nearestFlyable(Vec3 around, int maxCellRadius) {
        int centerX = toCell(around.x);
        int centerY = toCell(around.y);
        int centerZ = toCell(around.z);
        for (int radius = 0; radius <= maxCellRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        // 殻の上だけを見る（内側は前の半径で済んでいる）
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != radius) {
                            continue;
                        }
                        if (flyable(centerX + dx, centerY + dy, centerZ + dz)) {
                            return BlockPos.asLong(centerX + dx, centerY + dy, centerZ + dz);
                        }
                    }
                }
            }
        }
        return NONE;
    }

    /** {@link #nearestFlyable}が何も見つけられなかったことを表す番兵。 */
    public static final long NONE = Long.MIN_VALUE;
}
