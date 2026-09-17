package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 1セクションから出る辺。出発点（ノード）はセクション内の位置のビットで持ち、番号は位置の昇順。
 * 辺はノード番号順に並べ、{@link MoveTable}の番号だけを持つ。
 *
 * <p>1辺2バイト＋1ノード4バイト。同じ出発点・行き先の辺は種類違いで何本も生成されるので、いちばん安いものだけ残す。
 */
final class SectionEdges {

    /** セクション内の位置 {@code lx | lz << 4 | ly << 8} のビット（4096）を収める語数。 */
    private static final int WORDS = SectionMoves.SIZE * SectionMoves.SIZE * SectionMoves.SIZE / 64;

    static final SectionEdges EMPTY = new SectionEdges(new long[WORDS], new int[1], new char[0]);

    private final long[] nodeBits;
    /** 語ごとの、それより前の語にあるノードの数。 */
    private final char[] rank;
    /** ノード{@code i}の辺は {@code move[edgeStart[i]..edgeStart[i+1])}。 */
    final int[] edgeStart;
    final char[] move;
    final int nodes;

    private SectionEdges(long[] nodeBits, int[] edgeStart, char[] move) {
        this.nodeBits = nodeBits;
        this.edgeStart = edgeStart;
        this.move = move;
        this.rank = new char[WORDS];
        int count = 0;
        for (int w = 0; w < WORDS; w++) {
            rank[w] = (char) count;
            count += Long.bitCount(nodeBits[w]);
        }
        this.nodes = count;
    }

    int size() {
        return move.length;
    }

    static int local(int x, int y, int z) {
        return Math.floorMod(x, SectionMoves.SIZE) | Math.floorMod(z, SectionMoves.SIZE) << 4
                | Math.floorMod(y, SectionMoves.SIZE) << 8;
    }

    /** セクション内の位置のノード番号。ノードでなければ-1。 */
    int nodeOf(int local) {
        long word = nodeBits[local >> 6];
        long bit = 1L << local;
        return (word & bit) == 0 ? -1 : rank[local >> 6] + Long.bitCount(word & (bit - 1));
    }

    /** ノードの位置を番号順に{@code positions}へ書く。 */
    void positions(int[] positions, int offset) {
        int id = offset;
        for (int w = 0; w < WORDS; w++) {
            long bits = nodeBits[w];
            while (bits != 0) {
                positions[id++] = w << 6 | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
            }
        }
    }

    /** 覚えている配列のおおよそのバイト数。 */
    long bytes() {
        return 8L * WORDS + 2L * WORDS + 4L * edgeStart.length + 2L * move.length + 64;
    }

    /** @return 打ち切られたら{@code null} */
    static @Nullable SectionEdges build(CellSource cells, SectionShell shell, MoveTable moves, int sectionX,
                                        int sectionY, int sectionZ, int goalX, int goalZ, BooleanSupplier cancelled) {
        // 鍵は 出発点(12) | 相対座標(MoveTable#offsetKey)
        Long2FloatOpenHashMap cheapest = new Long2FloatOpenHashMap();
        cheapest.defaultReturnValue(Float.NaN);
        boolean finished = SectionMoves.build(cells, sectionX, sectionY, sectionZ, shell, goalX, goalZ,
                (fromPos, toPos, edgeCost) -> {
                    int fx = BlockPos.getX(fromPos);
                    int fy = BlockPos.getY(fromPos);
                    int fz = BlockPos.getZ(fromPos);
                    int ddx = BlockPos.getX(toPos) - fx;
                    int ddy = BlockPos.getY(toPos) - fy;
                    int ddz = BlockPos.getZ(toPos) - fz;
                    if (ddx < Byte.MIN_VALUE || ddx > Byte.MAX_VALUE || ddz < Byte.MIN_VALUE || ddz > Byte.MAX_VALUE
                            || ddy < Short.MIN_VALUE || ddy > Short.MAX_VALUE) {
                        throw new IllegalStateException("移動が長すぎて辺に収まらない: " + BlockPos.of(fromPos)
                                + " → " + BlockPos.of(toPos));
                    }
                    long key = (long) local(fx, fy, fz) << 32 | MoveTable.offsetKey(ddx, ddy, ddz) & 0xFFFFFFFFL;
                    float known = cheapest.get(key);
                    if (Float.isNaN(known) || edgeCost < known) {
                        cheapest.put(key, edgeCost);
                    }
                }, cancelled);
        if (!finished) {
            return null;
        }
        if (cheapest.isEmpty()) {
            return EMPTY;
        }
        long[] keys = cheapest.keySet().toLongArray();
        Arrays.sort(keys);
        int n = keys.length;

        // 移動の種類はセクションあたり数百なので、表へは種類ごとに1回だけ問い合わせる
        Long2IntOpenHashMap distinct = new Long2IntOpenHashMap();
        distinct.defaultReturnValue(-1);
        int[] edgeDistinct = new int[n];
        int[] offsets = new int[n];
        float[] costs = new float[n];
        for (int i = 0; i < n; i++) {
            int offset = (int) keys[i];
            float cost = cheapest.get(keys[i]);
            long moveKey = (long) offset << 32 | Float.floatToIntBits(cost) & 0xFFFFFFFFL;
            int d = distinct.get(moveKey);
            if (d < 0) {
                d = distinct.size();
                distinct.put(moveKey, d);
                offsets[d] = offset;
                costs[d] = cost;
            }
            edgeDistinct[i] = d;
        }
        char[] ids = new char[distinct.size()];
        moves.intern(offsets, costs, distinct.size(), ids);

        long[] nodeBits = new long[WORDS];
        int nodeCount = 0;
        for (int i = 0; i < n; i++) {
            int local = (int) (keys[i] >>> 32);
            if ((nodeBits[local >> 6] & 1L << local) == 0) {
                nodeBits[local >> 6] |= 1L << local;
                nodeCount++;
            }
        }
        int[] edgeStart = new int[nodeCount + 1];
        char[] move = new char[n];
        int node = -1;
        int previous = -1;
        for (int i = 0; i < n; i++) {
            int local = (int) (keys[i] >>> 32);
            if (local != previous) {
                node++;
                edgeStart[node] = i;
                previous = local;
            }
            move[i] = ids[edgeDistinct[i]];
        }
        edgeStart[nodeCount] = n;
        return new SectionEdges(nodeBits, edgeStart, move);
    }
}
