package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 1セクションから出る辺。出発点（ノード）はセクション内の位置のビットで持ち、番号は位置の昇順。
 * 辺は{@link MoveTable}の番号だけを持つ。
 *
 * <p>ノードの辺の並び（移動の番号の列）はセクションの中で同じものが多い（実測: 異なる並びはノードの6〜30%）ので、
 * 異なる並びだけを覚え、ノードは並びの番号を持つ。同じ出発点・行き先の辺は種類違いで何本も生成されるので、
 * いちばん安いものだけ残す。
 *
 * <p>ガイドは行き先から逆にたどるので入る辺も要る。入る辺の約9割は同じセクションの中で閉じ、それはこのセクションの辺だけで
 * 決まるので、組むときに一度だけ作って覚える（ガイドのたびに組むと、ガイドの組み立て用の配列でいちばん大きくなる）。
 * セクションをまたぐ入る辺はガイドのたびに組む。
 */
final class SectionEdges {

    /** セクション内の位置 {@code lx | lz << 4 | ly << 8} のビット（4096）を収める語数。 */
    private static final int WORDS = SectionMoves.SIZE * SectionMoves.SIZE * SectionMoves.SIZE / 64;

    static final SectionEdges EMPTY = new SectionEdges(new long[WORDS], new char[0], new int[1], new char[0], 0,
            new char[0], new int[1], new char[0], 0);

    private final long[] nodeBits;
    /** 語ごとの、それより前の語にあるノードの数。 */
    private final char[] rank;
    /** ノード{@code i}の辺の並びの番号。 */
    private final char[] pattern;
    /** 並び{@code p}は {@code move[patternStart[p]..patternStart[p+1])}。 */
    private final int[] patternStart;
    final char[] move;
    final int nodes;
    /** 並びを共有する前の辺の数。 */
    private final int edges;
    /** セクションの中から入る辺。持ち方は出る辺と同じ。 */
    private final char[] inPattern;
    private final int[] inPatternStart;
    final char[] inMove;
    private final int inEdges;

    private SectionEdges(long[] nodeBits, char[] pattern, int[] patternStart, char[] move, int edges,
                         char[] inPattern, int[] inPatternStart, char[] inMove, int inEdges) {
        this.nodeBits = nodeBits;
        this.pattern = pattern;
        this.patternStart = patternStart;
        this.move = move;
        this.edges = edges;
        this.inPattern = inPattern;
        this.inPatternStart = inPatternStart;
        this.inMove = inMove;
        this.inEdges = inEdges;
        this.rank = new char[WORDS];
        int count = 0;
        for (int w = 0; w < WORDS; w++) {
            rank[w] = (char) count;
            count += Long.bitCount(nodeBits[w]);
        }
        this.nodes = count;
    }

    int size() {
        return edges;
    }

    /** ノード{@code node}の辺は {@code move[first(node)..end(node))}。 */
    int first(int node) {
        return patternStart[pattern[node]];
    }

    int end(int node) {
        return patternStart[pattern[node] + 1];
    }

    /** 並びを共有する前の、セクションの中から入る辺の数。 */
    int inSize() {
        return inEdges;
    }

    /** ノード{@code node}へセクションの中から入る辺は {@code inMove[inFirst(node)..inEnd(node))}。 */
    int inFirst(int node) {
        return inPatternStart[inPattern[node]];
    }

    int inEnd(int node) {
        return inPatternStart[inPattern[node] + 1];
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
        return 8L * WORDS + 2L * WORDS + 2L * pattern.length + 4L * patternStart.length + 2L * move.length
                + 2L * inPattern.length + 4L * inPatternStart.length + 2L * inMove.length + 64;
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
                        throw new IllegalStateException("Move too long to fit in an edge: " + BlockPos.of(fromPos)
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

        // 並びを番号順に積み、同じ並びは先に積んだものを指す
        char[] all = w.all(n);
        for (int i = 0; i < n; i++) {
            all[i] = ids[edgeDistinct[i]];
        }
        int[] nodeEnd = w.nodeEnd;
        int node = 0;
        for (int l = 0; l < LOCALS; l++) {
            if ((nodeBits[l >> 6] & 1L << l) != 0) {
                nodeEnd[node++] = groupEnd[l];
            }
        }
        char[] pattern = new char[nodeCount];
        int[] patternStart = w.patternStart(nodeCount + 1);
        int patterns = share(all, nodeEnd, nodeCount, pattern, patternStart, w.table);
        int[] sharedStart = Arrays.copyOf(patternStart, patterns + 1);
        char[] sharedMove = Arrays.copyOf(all, patternStart[patterns]);

        // セクションの中で閉じる辺を行き先ごとに並べる。出発点の昇順に積むと、行き先の中では相対座標の降順になる
        // （両端ともセクション内なら、位置の差が相対座標そのもの）ので、同じ移動の組はいつも同じ並びになる
        int[] inCount = count;
        Arrays.fill(inCount, 0);
        int previous = 0;
        for (int l = 0; l < LOCALS; l++) {
            if ((nodeBits[l >> 6] & 1L << l) == 0) {
                continue;
            }
            for (int i = previous; i < groupEnd[l]; i++) {
                int target = insideTarget(nodeBits, l, offset[i]);
                if (target >= 0) {
                    inCount[target + 1]++;
                }
            }
            previous = groupEnd[l];
        }
        for (int l = 1; l <= LOCALS; l++) {
            inCount[l] += inCount[l - 1];
        }
        int inside = inCount[LOCALS];
        char[] inAll = w.all(inside);
        System.arraycopy(inCount, 0, cursor, 0, LOCALS + 1);
        previous = 0;
        for (int l = 0; l < LOCALS; l++) {
            if ((nodeBits[l >> 6] & 1L << l) == 0) {
                continue;
            }
            for (int i = previous; i < groupEnd[l]; i++) {
                int target = insideTarget(nodeBits, l, offset[i]);
                if (target >= 0) {
                    inAll[cursor[target]++] = ids[edgeDistinct[i]];
                }
            }
            previous = groupEnd[l];
        }
        node = 0;
        for (int l = 0; l < LOCALS; l++) {
            if ((nodeBits[l >> 6] & 1L << l) != 0) {
                nodeEnd[node++] = inCount[l + 1];
            }
        }
        char[] inPattern = new char[nodeCount];
        int inPatterns = share(inAll, nodeEnd, nodeCount, inPattern, patternStart, w.table);
        return new SectionEdges(nodeBits, pattern, sharedStart, sharedMove, n, inPattern,
                Arrays.copyOf(patternStart, inPatterns + 1), Arrays.copyOf(inAll, patternStart[inPatterns]), inside);
    }

    /** 位置{@code from}から相対座標{@code offsetKey}へ動いた先が同じセクションのノードなら、その位置。でなければ-1。 */
    private static int insideTarget(long[] nodeBits, int from, int offsetKey) {
        int x = (from & 15) + (byte) (offsetKey >> 24);
        int z = (from >> 4 & 15) + (byte) (offsetKey >> 16);
        int y = (from >> 8) + (short) offsetKey;
        if (((x | y | z) & ~15) != 0) {
            return -1;
        }
        int target = x | z << 4 | y << 8;
        return (nodeBits[target >> 6] & 1L << target) == 0 ? -1 : target;
    }

    /**
     * ノード{@code k}の並び {@code all[nodeEnd[k-1]..nodeEnd[k])}（{@code k=0}は0から）のうち同じものを共有する。
     * {@code pattern}へ並びの番号を書き、異なる並びは{@code all}の前へ詰めて{@code patternStart}に区切りを書く。
     *
     * @return 異なる並びの数
     */
    private static int share(char[] all, int[] nodeEnd, int nodeCount, char[] pattern, int[] patternStart,
                             int[] table) {
        // 表は半分以上空くように取る。セクションごとに埋め直すので、ノードが少ないセクションで表全体を埋めない
        int mask = (Integer.highestOneBit(nodeCount) << 2) - 1;
        Arrays.fill(table, 0, mask + 1, -1);
        int patterns = 0;
        int stored = 0;
        int from = 0;
        patternStart[0] = 0;
        for (int node = 0; node < nodeCount; node++) {
            int to = nodeEnd[node];
            int hash = 1;
            for (int i = from; i < to; i++) {
                hash = 31 * hash + all[i];
            }
            int at = HashCommon.mix(hash) & mask;
            int found = -1;
            for (int p = table[at]; p >= 0; p = table[at = at + 1 & mask]) {
                if (Arrays.equals(all, patternStart[p], patternStart[p + 1], all, from, to)) {
                    found = p;
                    break;
                }
            }
            if (found < 0) {
                // 積んだ並びは all の前へ詰め直す。詰め先は読み終えた位置より前なので、まだ読んでいない並びは壊さない
                System.arraycopy(all, from, all, stored, to - from);
                patternStart[patterns] = stored;
                stored += to - from;
                patternStart[patterns + 1] = stored;
                table[at] = patterns;
                found = patterns++;
            }
            pattern[node] = (char) found;
            from = to;
        }
        return patterns;
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
        final int[] nodeEnd = new int[LOCALS];
        int[] sortedOffset = new int[0];
        float[] sortedCost = new float[0];
        private int[] edgeDistinct = new int[0];
        private char[] all = new char[0];
        private int[] patternStart = new int[0];
        final int[] table = new int[4 * LOCALS];
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

        char[] all(int size) {
            if (all.length < size) {
                all = new char[size + size / 2];
            }
            return all;
        }

        int[] patternStart(int size) {
            if (patternStart.length < size) {
                patternStart = new int[size];
            }
            return patternStart;
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
