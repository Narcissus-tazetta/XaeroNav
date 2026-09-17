package net.prason.xaeronav.pathfinding.navgraph;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * 窓の中の、目的地までの残りコスト。{@link NavGraph}の辺を逆向きにDijkstraして作る。
 *
 * <p>窓の外、またはまだ組んでいないセクションへ出る辺は、その先に{@link FarField}の値を置いて種にする。
 * 外の値が分からない点は種にしない（{@link FarField}の約束）。
 *
 * <p>作った後は読むだけなので、複数のスレッドから引いてよい。
 */
public final class WindowField implements CostToGo {

    /**
     * グラフに無い点を、近くのノードの値から延ばすときに見る範囲（ブロック）。
     *
     * <p><b>ガイドを0にしてはいけない。</b>掘った・置いたブロックで生まれた立ち位置はグラフに無く、
     * そこで0を返すと探索と部分経路の終点選びがそこへ吸い寄せられる（実測: 完璧なガイドでも1.30倍に落ちた）。
     */
    private static final int NEAREST_REACH = 3;

    /**
     * 窓の縁からこの幅（ブロック）の中にある点には、外の値を直接置く。
     *
     * <p>探索は読み込まれていないセルへの移動を作らないので、縁のセクションからは窓の外へ出る辺が1本も無い。
     * 辺の先に外の値を置くだけだと種が1つも無く、窓の中のガイドが丸ごと外の値に落ちる
     * （実測: 広域長距離で2.651倍）。
     */
    private static final int EDGE_SEED_BAND = 2;

    /** 並べて数える小分けの大きさ（セクション数）。 */
    private static final int SLOTS_PER_TASK = 32;

    private static final VarHandle INTS = MethodHandles.arrayElementVarHandle(int[].class);

    /** 窓の外・まだ組んでいないセクション。 */
    private static final int OUTSIDE = -1;
    /** 組んだセクションの中だが、ノードではない（殻の外）。 */
    private static final int NOT_A_NODE = -2;

    private final BlockPos goal;
    private final FarField far;
    private final Index index;
    private final double[] distance;
    private final int edges;
    private final long buildMillis;
    private final boolean goalCut;

    private WindowField(BlockPos goal, FarField far, Index index, double[] distance, int edges, long buildMillis,
                        boolean goalCut) {
        this.goal = goal;
        this.far = far;
        this.index = index;
        this.distance = distance;
        this.edges = edges;
        this.buildMillis = buildMillis;
        this.goalCut = goalCut;
    }

    public int nodes() {
        return distance.length;
    }

    public int edges() {
        return edges;
    }

    public long buildMillis() {
        return buildMillis;
    }

    /** 組み立て後も覚えている配列のおおよそのバイト数。 */
    public long bytes() {
        return 8L * distance.length + index.bytes();
    }

    /**
     * 組み立てにだけ使う大きな配列。区間ごとに数千万要素を作り直すと、そのたびに数十MBのごみになる。
     * 組み立て後のガイドはこれを参照しないので、次の組み立てで上書きしてよい。
     */
    static final class Buffers {
        private int[] start = new int[0];
        private int[] position = new int[0];
        private char[] inMove = new char[0];
        private final DistanceHeap heap = new DistanceHeap();

        int[] start(int size) {
            if (start.length < size) {
                start = new int[size + size / 4];
            }
            Arrays.fill(start, 0, size, 0);
            return start;
        }

        int[] position(int size) {
            if (position.length < size) {
                position = new int[size + size / 4];
            }
            return position;
        }

        char[] inMove(int size) {
            if (inMove.length < size) {
                inMove = new char[size + size / 4];
            }
            return inMove;
        }

        DistanceHeap heap() {
            heap.clear();
            return heap;
        }

        long bytes() {
            return 4L * start.length + 4L * position.length + 2L * inMove.length + heap.bytes();
        }
    }

    /**
     * 窓のセクションと、その中のノードの通し番号。セクション{@code s}のノードは
     * {@code offsets[s]..offsets[s+1]}。
     */
    private static final class Index {
        final long[] keys;
        final SectionEdges[] sections;
        final Long2IntOpenHashMap slotOf;
        final int[] offsets;
        /** セクション{@code s}の隣（各軸-1〜1）のセクションの番号。無ければ-1。 */
        final int[] neighbor;

        Index(long[] keys, SectionEdges[] sections, Long2IntOpenHashMap slotOf, int[] offsets, int[] neighbor) {
            this.keys = keys;
            this.sections = sections;
            this.slotOf = slotOf;
            this.offsets = offsets;
            this.neighbor = neighbor;
        }

        /** セクション{@code slot}の原点から({@code x},{@code y},{@code z})の点のノード番号。 */
        int resolve(int slot, int x, int y, int z) {
            if (((x | y | z) & ~15) == 0) {
                // 辺の大半は同じセクションの中で閉じる
                int node = sections[slot].nodeOf(x | z << 4 | y << 8);
                return node < 0 ? NOT_A_NODE : offsets[slot] + node;
            }
            int sdx = x >> 4;
            int sdy = y >> 4;
            int sdz = z >> 4;
            int target;
            if (isNear(sdx) && isNear(sdy) && isNear(sdz)) {
                target = neighbor[slot * 27 + (sdx + 1) * 9 + (sdy + 1) * 3 + sdz + 1];
            } else {
                long key = keys[slot];
                target = slotOf.get(NavGraph.key(BlockPos.getX(key) + sdx, BlockPos.getY(key) + sdy,
                        BlockPos.getZ(key) + sdz));
            }
            if (target < 0) {
                return OUTSIDE;
            }
            int node = sections[target].nodeOf(x & 15 | (z & 15) << 4 | (y & 15) << 8);
            return node < 0 ? NOT_A_NODE : offsets[target] + node;
        }

        private static boolean isNear(int d) {
            return d >= -1 && d <= 1;
        }

        int resolveAbsolute(int x, int y, int z) {
            int slot = slotOf.get(NavGraph.key(Math.floorDiv(x, SectionMoves.SIZE), Math.floorDiv(y, SectionMoves.SIZE),
                    Math.floorDiv(z, SectionMoves.SIZE)));
            if (slot < 0) {
                return OUTSIDE;
            }
            int node = sections[slot].nodeOf(SectionEdges.local(x, y, z));
            return node < 0 ? NOT_A_NODE : offsets[slot] + node;
        }

        long bytes() {
            return 8L * keys.length + 12L * sections.length + 4L * offsets.length + 4L * neighbor.length;
        }
    }

    static @Nullable WindowField build(NavGraph graph, Buffers buffers, int centerX, int centerZ, int radius,
                                       FarField givenFar, Parallel parallel, BooleanSupplier cancelled) {
        long began = MonotonicTime.millis();
        BlockPos goal = graph.goal();
        boolean goalInWindow = Math.abs(goal.getX() - centerX) <= radius - EDGE_SEED_BAND
                && Math.abs(goal.getZ() - centerZ) <= radius - EDGE_SEED_BAND;
        FarField far = goalInWindow && givenFar.onlyWhenGoalOutside() ? FarField.UNKNOWN : givenFar;
        LongArrayList keyList = new LongArrayList();
        graph.forEachWindowSection(centerX, centerZ, radius, (sx, sy, sz) -> {
            long key = NavGraph.key(sx, sy, sz);
            if (graph.section(key) != null) {
                keyList.add(key);
            }
        });
        long[] keys = keyList.toLongArray();
        int slots = keys.length;
        SectionEdges[] sections = new SectionEdges[slots];
        Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap(slots);
        slotOf.defaultReturnValue(-1);
        int[] offsets = new int[slots + 1];
        for (int s = 0; s < slots; s++) {
            SectionEdges edges = graph.section(keys[s]);
            // 組み立ての途中で捨てられた（チャンクの更新）なら、まだ組んでいないのと同じに扱う
            sections[s] = edges == null ? SectionEdges.EMPTY : edges;
            slotOf.put(keys[s], s);
            offsets[s + 1] = offsets[s] + sections[s].nodes;
        }
        // 辺の移動の番号は、セクションを覚える前に表へ載っている。セクションを集め終えてから表を取れば全部引ける
        MoveTable.View moves = graph.moves().view();
        int[] neighbor = new int[slots * 27];
        for (int s = 0; s < slots; s++) {
            int sx = BlockPos.getX(keys[s]);
            int sy = BlockPos.getY(keys[s]);
            int sz = BlockPos.getZ(keys[s]);
            for (int k = 0; k < 27; k++) {
                neighbor[s * 27 + k] = slotOf.get(NavGraph.key(sx + k / 9 - 1, sy + k / 3 % 3 - 1, sz + k % 3 - 1));
            }
        }
        Index index = new Index(keys, sections, slotOf, offsets, neighbor);

        int n = offsets[slots];
        int[] position = buffers.position(n);
        for (int s = 0; s < slots; s++) {
            sections[s].positions(position, offsets[s]);
        }
        double[] distance = new double[n];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        int goalId = index.resolveAbsolute(goal.getX(), goal.getY(), goal.getZ());
        if (goalId >= 0) {
            distance[goalId] = 0.0;
        }
        AtomicBoolean goalEntered = new AtomicBoolean();

        // 1周目: 窓の中へ入る辺を行き先ごとに数え（start[行き先+2]）、窓から出る辺は種にする。
        // 種はセクションの自分のノードにしか書かないので、数える所だけ並べたときに原子的に足す
        int[] start = buffers.start(n + 2);
        boolean concurrent = parallel.workers() > 1;
        boolean counted = parallel.forEach(slots, SLOTS_PER_TASK, cancelled, (fromSlot, toSlot) -> {
            for (int s = fromSlot; s < toSlot; s++) {
                SectionEdges section = sections[s];
                long key = keys[s];
                int baseX = BlockPos.getX(key) * SectionMoves.SIZE;
                int baseY = BlockPos.getY(key) * SectionMoves.SIZE;
                int baseZ = BlockPos.getZ(key) * SectionMoves.SIZE;
                for (int i = 0; i < section.nodes; i++) {
                    int from = offsets[s] + i;
                    int local = position[from];
                    int lx = local & 15;
                    int ly = local >> 8 & 15;
                    int lz = local >> 4 & 15;
                    if (Math.abs(baseX + lx - centerX) > radius - EDGE_SEED_BAND
                            || Math.abs(baseZ + lz - centerZ) > radius - EDGE_SEED_BAND) {
                        double value = far.at(baseX + lx, baseY + ly, baseZ + lz);
                        if (Double.isFinite(value)) {
                            distance[from] = Math.min(distance[from], value);
                        }
                    }
                    for (int e = section.edgeStart[i]; e < section.edgeStart[i + 1]; e++) {
                        int m = section.move[e];
                        int tx = lx + moves.dx[m];
                        int ty = ly + moves.dy[m];
                        int tz = lz + moves.dz[m];
                        if (baseX + tx == goal.getX() && baseY + ty == goal.getY() && baseZ + tz == goal.getZ()) {
                            // 目的地そのものは殻の外（展開しないセル）にあってもよい。そこへ入る辺は目的地までの値段そのもの
                            distance[from] = Math.min(distance[from], moves.cost[m]);
                            goalEntered.set(true);
                        }
                        int target = index.resolve(s, tx, ty, tz);
                        if (target >= 0) {
                            if (concurrent) {
                                INTS.getAndAdd(start, target + 2, 1);
                            } else {
                                start[target + 2]++;
                            }
                        } else if (target == OUTSIDE) {
                            double value = far.at(baseX + tx, baseY + ty, baseZ + tz);
                            if (Double.isFinite(value)) {
                                distance[from] = Math.min(distance[from], moves.cost[m] + value);
                            }
                        }
                    }
                }
            }
            return true;
        });
        if (!counted) {
            return null;
        }
        for (int i = 2; i <= n + 1; i++) {
            start[i] += start[i - 1];
        }
        int m = start[n + 1];
        char[] inMove = buffers.inMove(m);
        // 2周目: 行き先ごとに、入ってくる辺の移動を埋める。出発点は行き先から移動を引き戻せば分かる。
        // 並べると入る辺の並び順は変わるが、距離は変わらない
        boolean filled = parallel.forEach(slots, SLOTS_PER_TASK, cancelled, (fromSlot, toSlot) -> {
            for (int s = fromSlot; s < toSlot; s++) {
                SectionEdges section = sections[s];
                for (int i = 0; i < section.nodes; i++) {
                    int local = position[offsets[s] + i];
                    for (int e = section.edgeStart[i]; e < section.edgeStart[i + 1]; e++) {
                        int move = section.move[e];
                        int target = index.resolve(s, (local & 15) + moves.dx[move],
                                (local >> 8 & 15) + moves.dy[move], (local >> 4 & 15) + moves.dz[move]);
                        if (target >= 0) {
                            int slot = concurrent ? (int) INTS.getAndAdd(start, target + 1, 1) : start[target + 1]++;
                            inMove[slot] = (char) move;
                        }
                    }
                }
            }
            return true;
        });
        if (!filled) {
            return null;
        }
        // 埋め終えると start[t]..start[t+1] が行き先tへ入る辺になる
        for (int s = 0; s < slots; s++) {
            for (int i = offsets[s]; i < offsets[s + 1]; i++) {
                position[i] |= s << 12;
            }
        }

        DistanceHeap heap = buffers.heap();
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(distance[i])) {
                heap.push(distance[i], i);
            }
        }
        int popped = 0;
        while (!heap.isEmpty()) {
            if ((++popped & 0xFFFF) == 0 && cancelled.getAsBoolean()) {
                return null;
            }
            double d = heap.topKey();
            int node = heap.pop();
            if (d > distance[node]) {
                continue;
            }
            int packed = position[node];
            int slot = packed >>> 12;
            int lx = packed & 15;
            int ly = packed >> 8 & 15;
            int lz = packed >> 4 & 15;
            for (int k = start[node]; k < start[node + 1]; k++) {
                int move = inMove[k];
                int p = index.resolve(slot, lx - moves.dx[move], ly - moves.dy[move], lz - moves.dz[move]);
                double candidate = d + moves.cost[move];
                if (candidate < distance[p]) {
                    distance[p] = candidate;
                    heap.push(candidate, p);
                }
            }
        }
        return new WindowField(goal, far, index, distance, m, MonotonicTime.millis() - began,
                goalInWindow && !goalEntered.get());
    }

    /**
     * 窓の中にある目的地へ、殻のどこかから入れるか。入れなければ窓全体の値が縁の外の推定だけから来るので、
     * このガイドで探してはいけない。目的地が窓の外なら常に{@code true}。
     */
    public boolean reachesGoal() {
        return !goalCut;
    }

    /**
     * ({@code x},{@code y},{@code z})が、目的地へ繋がる殻の中にあるか。
     *
     * <p>殻（{@link SectionShell}）は自然に立てる点の周りの体積しか持たないので、閉じた洞窟の中や、16ブロックを超える奈落で
     * 隔てられた島からは繋がらない。そこでは近くのノードがどれも値を持たず、値は外の推定か幾何下限へ落ちる。
     *
     * <p><b>近くにノードが1つも無い点は繋がっているものとして扱う。</b>奈落の上に架けた橋の先など、置いたブロックの上は
     * 殻の外にあるのが普通で、そこでは近くの値を延ばす（{@link #estimate}）。
     */
    public boolean connects(int x, int y, int z) {
        boolean nodeNearby = false;
        for (int dx = -NEAREST_REACH; dx <= NEAREST_REACH; dx++) {
            for (int dy = -NEAREST_REACH; dy <= NEAREST_REACH; dy++) {
                for (int dz = -NEAREST_REACH; dz <= NEAREST_REACH; dz++) {
                    int near = index.resolveAbsolute(x + dx, y + dy, z + dz);
                    if (near >= 0) {
                        if (Double.isFinite(distance[near])) {
                            return true;
                        }
                        nodeNearby = true;
                    }
                }
            }
        }
        return !nodeNearby;
    }

    @Override
    public double estimate(int x, int y, int z) {
        int id = index.resolveAbsolute(x, y, z);
        if (id >= 0 && Double.isFinite(distance[id])) {
            return distance[id];
        }
        if (id == OUTSIDE) {
            return outside(x, y, z);
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (int dx = -NEAREST_REACH; dx <= NEAREST_REACH; dx++) {
            for (int dy = -NEAREST_REACH; dy <= NEAREST_REACH; dy++) {
                for (int dz = -NEAREST_REACH; dz <= NEAREST_REACH; dz++) {
                    int near = index.resolveAbsolute(x + dx, y + dy, z + dz);
                    if (near >= 0 && Double.isFinite(distance[near])) {
                        nearest = Math.min(nearest,
                                distance[near] + Heuristic.estimate(x, y, z, x + dx, y + dy, z + dz));
                    }
                }
            }
        }
        return Double.isFinite(nearest) ? nearest : outside(x, y, z);
    }

    /** グラフから値を引けない点。外の値があればそれ、無ければ目的地までの幾何下限。 */
    private double outside(int x, int y, int z) {
        double value = far.at(x, y, z);
        return Double.isFinite(value) ? value : Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
    }
}
