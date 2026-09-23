package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

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
        Scratch w = SCRATCH.get();
        w.size = 0;
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
                    w.add(local(fx, fy, fz), MoveTable.offsetKey(ddx, ddy, ddz), edgeCost);
                }, cancelled);
        if (!finished) {
            return null;
        }
        int raw = w.size;
        if (raw == 0) {
            return EMPTY;
        }
        // 出発点（4096通り）で数え上げて並べる。辺は1セクションで数万本あり、ハッシュ表に積むより速い
        int[] count = w.count;
        Arrays.fill(count, 0);
        for (int i = 0; i < raw; i++) {
            count[w.from[i] + 1]++;
        }
        for (int l = 1; l <= LOCALS; l++) {
            count[l] += count[l - 1];
        }
        w.ensureSorted(raw);
        int[] offset = w.sortedOffset;
        float[] cost = w.sortedCost;
        int[] cursor = w.cursor;
        System.arraycopy(count, 0, cursor, 0, LOCALS + 1);
        for (int i = 0; i < raw; i++) {
            int at = cursor[w.from[i]]++;
            offset[at] = w.offset[i];
            cost[at] = w.cost[i];
        }
        // 出発点の中を相対座標の昇順（符号なし）に並べ、同じ行き先はいちばん安いものだけ残す。出発点あたりの辺は数十本なので挿入ソートでよい
        long[] nodeBits = new long[WORDS];
        int nodeCount = 0;
        int n = 0;
        int[] groupEnd = w.groupEnd;
        for (int l = 0; l < LOCALS; l++) {
            int from = count[l];
            int to = count[l + 1];
            if (from == to) {
                continue;
            }
            for (int i = from + 1; i < to; i++) {
                int o = offset[i];
                float c = cost[i];
                int j = i - 1;
                while (j >= from && Integer.compareUnsigned(offset[j], o) > 0) {
                    offset[j + 1] = offset[j];
                    cost[j + 1] = cost[j];
                    j--;
                }
                offset[j + 1] = o;
                cost[j + 1] = c;
            }
            nodeBits[l >> 6] |= 1L << l;
            nodeCount++;
            int groupStart = n;
            for (int i = from; i < to; i++) {
                if (n > groupStart && offset[n - 1] == offset[i]) {
                    cost[n - 1] = Math.min(cost[n - 1], cost[i]);
                } else {
                    offset[n] = offset[i];
                    cost[n] = cost[i];
                    n++;
                }
            }
            groupEnd[l] = n;
        }

        // 移動の種類はセクションあたり数百なので、表へは種類ごとに1回だけ問い合わせる
        Long2IntOpenHashMap distinct = w.distinct;
        distinct.clear();
        int[] edgeDistinct = w.edgeDistinct(n);
        int kinds = 0;
        for (int i = 0; i < n; i++) {
            long moveKey = (long) offset[i] << 32 | Float.floatToIntBits(cost[i]) & 0xFFFFFFFFL;
            int d = distinct.putIfAbsent(moveKey, kinds);
            if (d < 0) {
                d = kinds;
                w.kind(kinds++, offset[i], cost[i]);
            }
            edgeDistinct[i] = d;
        }
        char[] ids = new char[kinds];
        moves.intern(w.kindOffset, w.kindCost, kinds, ids);

        int[] edgeStart = new int[nodeCount + 1];
        char[] move = new char[n];
        int node = 0;
        int previous = 0;
        for (int l = 0; l < LOCALS; l++) {
            if ((nodeBits[l >> 6] & 1L << l) != 0) {
                edgeStart[node++] = previous;
                previous = groupEnd[l];
            }
        }
        edgeStart[nodeCount] = n;
        for (int i = 0; i < n; i++) {
            move[i] = ids[edgeDistinct[i]];
        }
        return new SectionEdges(nodeBits, edgeStart, move);
    }

    /** セクション内の位置の数。 */
    private static final int LOCALS = SectionMoves.SIZE * SectionMoves.SIZE * SectionMoves.SIZE;

    /** セクションを組むたびに作り直すと、辺の数ぶんのごみになる。組み終えたセクションはこれを参照しない。 */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        int[] from = new int[1 << 12];
        int[] offset = new int[1 << 12];
        float[] cost = new float[1 << 12];
        int size;
        final int[] count = new int[LOCALS + 1];
        final int[] cursor = new int[LOCALS + 1];
        final int[] groupEnd = new int[LOCALS];
        int[] sortedOffset = new int[0];
        float[] sortedCost = new float[0];
        private int[] edgeDistinct = new int[0];
        int[] kindOffset = new int[1 << 9];
        float[] kindCost = new float[1 << 9];
        final Long2IntOpenHashMap distinct = new Long2IntOpenHashMap();

        Scratch() {
            distinct.defaultReturnValue(-1);
        }

        void add(int local, int moveOffset, float moveCost) {
            if (size == from.length) {
                from = Arrays.copyOf(from, size * 2);
                offset = Arrays.copyOf(offset, size * 2);
                cost = Arrays.copyOf(cost, size * 2);
            }
            from[size] = local;
            offset[size] = moveOffset;
            cost[size] = moveCost;
            size++;
        }

        void ensureSorted(int size) {
            if (sortedOffset.length < size) {
                sortedOffset = new int[size + size / 2];
                sortedCost = new float[size + size / 2];
            }
        }

        int[] edgeDistinct(int size) {
            if (edgeDistinct.length < size) {
                edgeDistinct = new int[size + size / 2];
            }
            return edgeDistinct;
        }

        void kind(int id, int moveOffset, float moveCost) {
            if (id == kindOffset.length) {
                kindOffset = Arrays.copyOf(kindOffset, id * 2);
                kindCost = Arrays.copyOf(kindCost, id * 2);
            }
            kindOffset[id] = moveOffset;
            kindCost[id] = moveCost;
        }
    }
}
