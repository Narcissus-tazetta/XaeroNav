package net.prason.xaeronav.pathfinding.navgraph;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntArrayList;
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

    /**
     * 窓の縁からこの幅（ブロック）の中は、値が{@link FarField}の推定から来ているとみなす
     * （{@link #measuredInWindow}）。縁のセクションは外へ出る辺の先に外の値を置いて種にしている
     * （{@link #EDGE_SEED_BAND}）ので、その周りの値には推定がそのまま残る。
     */
    private static final int EDGE_MARGIN_BLOCKS = 32;

    /** 並べて数える小分けの大きさ（セクション数）。 */
    private static final int SLOTS_PER_TASK = 32;

    private static final VarHandle INTS = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle DOUBLES = MethodHandles.arrayElementVarHandle(double[].class);

    /** Dial法のバケットを並べて回す下限（入る辺の本数）。これより小さいと、並べる手間の方が高くつく。 */
    private static final long PARALLEL_BUCKET_EDGES = 1024;

    /** 並べたバケットで、1つの小分けが受け持つノードの数。 */
    private static final int NODES_PER_TASK = 128;

    /** 窓の外・まだ組んでいないセクション。 */
    private static final int OUTSIDE = -1;
    /** 組んだセクションの中だが、ノードではない（殻の外）。 */
    private static final int NOT_A_NODE = -2;

    private final BlockPos goal;
    private final FarField far;
    private final Index index;
    private final MoveTable.View moves;
    private final double[] distance;
    private final int edges;
    private final long buildMillis;
    private final boolean goalCut;
    private final int centerX;
    private final int centerZ;
    private final int radius;

    private WindowField(BlockPos goal, FarField far, Index index, MoveTable.View moves, double[] distance, int edges,
                        long buildMillis, boolean goalCut, int centerX, int centerZ, int radius) {
        this.moves = moves;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.goal = goal;
        this.far = far;
        this.index = index;
        this.distance = distance;
        this.edges = edges;
        this.buildMillis = buildMillis;
        this.goalCut = goalCut;
    }

    public BlockPos goal() {
        return goal;
    }

    /** 窓の中心と半径（ブロック）。 */
    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
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
        private final BucketQueue queue = new BucketQueue();

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

        BucketQueue queue(int buckets) {
            queue.clear(buckets);
            return queue;
        }

        long bytes() {
            return 4L * start.length + 4L * position.length + 2L * inMove.length + queue.bytes();
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
        // 縁の近くの目的地は窓の外として扱う。縁のセクションは周りが読めないまま仮に組むので、目的地へ入る辺が生成されず、
        // 窓の中なのに繋がらないと判定して従来の探索へ落ちる（実測: エンドで目的地が縁から5ブロックの区間が2回、1.022→1.050倍）
        boolean goalInWindow = Math.abs(goal.getX() - centerX) <= radius - NavGraph.READ_MARGIN
                && Math.abs(goal.getZ() - centerZ) <= radius - NavGraph.READ_MARGIN;
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

        double base = Double.POSITIVE_INFINITY;
        double top = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(distance[i])) {
                base = Math.min(base, distance[i]);
                top = Math.max(top, distance[i]);
            }
        }
        BitSet settled = new BitSet(n);
        if (Double.isFinite(base)) {
            // 振った移動の値段はどれもこの幅以上なので、バケットを前から空にするだけで確定順になる。
            // 窓の外のセクションの移動も含む最小値だが、幅が狭いぶんには正しさは変わらない
            double width = moves.minCost;
            BucketQueue queue = buffers.queue((int) ((top - base) / width) + 1);
            for (int i = 0; i < n; i++) {
                if (Double.isFinite(distance[i])) {
                    queue.push((int) ((distance[i] - base) / width), i);
                }
            }
            if (!settle(queue, settled, distance, position, start, inMove, index, moves, base, width, parallel,
                    cancelled)) {
                return null;
            }
        }
        return new WindowField(goal, far, index, moves, distance, m, MonotonicTime.millis() - began,
                goalInWindow && !goalEntered.get(), centerX, centerZ, radius);
    }

    /**
     * バケットを前から空にして距離を確定させる（Dial法）。
     *
     * <p>大きいバケットは中のノードの緩和を並べる。辺の値段はどれもバケット幅以上なので、あるバケットのノードから
     * 緩和した先は後ろのバケットへ行き、同じバケットの中どうしは互いの値を使わない（丸めで同じバケットへ戻った
     * 改善は、そのバケットをもう一度回して拾う）。距離は小さくなるときだけ書くので、並べても1本で回したときと
     * 同じ最短距離に落ち着く——各経路の値は目的地側から同じ順に足して作られるので、ビットまで一致する。
     *
     * @return 打ち切られたら{@code false}
     */
    private static boolean settle(BucketQueue queue, BitSet settled, double[] distance, int[] position, int[] start,
                                  char[] inMove, Index index, MoveTable.View moves, double base, double width,
                                  Parallel parallel, BooleanSupplier cancelled) {
        boolean concurrent = parallel.workers() > 1;
        int[] frontier = new int[1024];
        int cursor = 0;
        while (cursor >= 0) {
            int size = 0;
            long edges = 0;
            for (int node = queue.pop(cursor); node >= 0; node = queue.pop(cursor)) {
                if (settled.get(node)) {
                    continue;
                }
                settled.set(node);
                if (size == frontier.length) {
                    frontier = Arrays.copyOf(frontier, size * 2);
                }
                frontier[size++] = node;
                edges += start[node + 1] - start[node];
            }
            if (size == 0) {
                cursor = queue.nextNonEmpty(cursor + 1);
                continue;
            }
            if (cancelled.getAsBoolean()) {
                return false;
            }
            int bucket = cursor;
            if (!concurrent || edges < PARALLEL_BUCKET_EDGES) {
                for (int i = 0; i < size; i++) {
                    int node = frontier[i];
                    double d = distance[node];
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
                            // 丸めで同じバケットへ戻ってきた改善は、確定を取り消して解き直す
                            settled.clear(p);
                            queue.push(Math.max(bucket, (int) ((candidate - base) / width)), p);
                        }
                    }
                }
                continue;
            }
            int[] nodes = frontier;
            ConcurrentLinkedQueue<int[]> improved = new ConcurrentLinkedQueue<>();
            boolean relaxed = parallel.forEach(size, NODES_PER_TASK, cancelled, (from, to) -> {
                IntArrayList lowered = new IntArrayList();
                for (int i = from; i < to; i++) {
                    int node = nodes[i];
                    double d = (double) DOUBLES.getOpaque(distance, node);
                    int packed = position[node];
                    int slot = packed >>> 12;
                    int lx = packed & 15;
                    int ly = packed >> 8 & 15;
                    int lz = packed >> 4 & 15;
                    for (int k = start[node]; k < start[node + 1]; k++) {
                        int move = inMove[k];
                        int p = index.resolve(slot, lx - moves.dx[move], ly - moves.dy[move], lz - moves.dz[move]);
                        if (lower(distance, p, d + moves.cost[move])) {
                            lowered.add(p);
                        }
                    }
                }
                improved.add(lowered.toIntArray());
                return true;
            });
            if (!relaxed) {
                return false;
            }
            for (int[] lowered : improved) {
                for (int p : lowered) {
                    settled.clear(p);
                    queue.push(Math.max(bucket, (int) ((distance[p] - base) / width)), p);
                }
            }
        }
        return true;
    }

    /** {@code distance[p]}を{@code candidate}へ下げる。下げられたら{@code true}。 */
    private static boolean lower(double[] distance, int p, double candidate) {
        double current = (double) DOUBLES.getOpaque(distance, p);
        while (candidate < current) {
            if (DOUBLES.compareAndSet(distance, p, current, candidate)) {
                return true;
            }
            current = (double) DOUBLES.getOpaque(distance, p);
        }
        return false;
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

    /**
     * ガイドの値がどこから来たか。{@code exit}は値の出どころ——目的地そのもの、または窓の外の推定を読んだ点。
     *
     * @param inside {@code from}から{@code exit}の手前まで、窓の中を辿った値段
     * @param outside {@code exit}で読んだ窓の外の推定（目的地なら0）
     */
    public record Descent(BlockPos exit, double inside, double outside, boolean reachedGoal) {
    }

    /**
     * {@code (x, y, z)}から、ガイドの値を作った辺を下って値の出どころを探す。ノードでない・値が無いなら{@code null}。
     *
     * <p>経路の向きがガイドのどの推定に引かれて決まったかを実機のログで見るためのもの。値は辺の値段の和で確定しているので、
     * 各ノードで「値段＋行き先の値」が自分の値に一致する辺を辿れば出どころに着く。
     */
    public @Nullable Descent descend(int x, int y, int z) {
        return descend(x, y, z, null);
    }

    /** 下る途中で踏む点。 */
    @FunctionalInterface
    public interface Trail {
        void visit(int x, int y, int z);
    }

    /** {@link #descend(int, int, int)}と同じ。踏んだ点を始点から順に{@code trail}へ渡す（窓の外の出口は渡さない）。 */
    public @Nullable Descent descend(int x, int y, int z, @Nullable Trail trail) {
        int id = index.resolveAbsolute(x, y, z);
        if (id < 0 || !Double.isFinite(distance[id])) {
            return null;
        }
        double inside = 0;
        for (int guard = 0; guard < distance.length; guard++) {
            if (trail != null) {
                trail.visit(x, y, z);
            }
            if (x == goal.getX() && y == goal.getY() && z == goal.getZ()) {
                return new Descent(goal, inside, 0, true);
            }
            int slot = index.slotOf.get(NavGraph.key(x >> 4, y >> 4, z >> 4));
            int lx = x & 15;
            int ly = y & 15;
            int lz = z & 15;
            SectionEdges section = index.sections[slot];
            int node = section.nodeOf(lx | lz << 4 | ly << 8);
            double best = Double.POSITIVE_INFINITY;
            int bestMove = -1;
            int bestTarget = OUTSIDE;
            if (Math.abs(x - centerX) > radius - EDGE_SEED_BAND || Math.abs(z - centerZ) > radius - EDGE_SEED_BAND) {
                best = far.at(x, y, z);
            }
            for (int e = section.edgeStart[node]; e < section.edgeStart[node + 1]; e++) {
                int m = section.move[e];
                int tx = x + moves.dx[m];
                int ty = y + moves.dy[m];
                int tz = z + moves.dz[m];
                double candidate;
                int target = index.resolve(slot, lx + moves.dx[m], ly + moves.dy[m], lz + moves.dz[m]);
                if (tx == goal.getX() && ty == goal.getY() && tz == goal.getZ()) {
                    candidate = moves.cost[m];
                } else if (target >= 0) {
                    candidate = moves.cost[m] + distance[target];
                } else if (target == OUTSIDE) {
                    candidate = moves.cost[m] + far.at(tx, ty, tz);
                } else {
                    continue;
                }
                if (candidate < best) {
                    best = candidate;
                    bestMove = m;
                    bestTarget = target;
                }
            }
            if (bestMove < 0) {
                return new Descent(new BlockPos(x, y, z), inside, best, false);
            }
            int tx = x + moves.dx[bestMove];
            int ty = y + moves.dy[bestMove];
            int tz = z + moves.dz[bestMove];
            if (tx == goal.getX() && ty == goal.getY() && tz == goal.getZ()) {
                return new Descent(goal, inside + moves.cost[bestMove], 0, true);
            }
            inside += moves.cost[bestMove];
            if (bestTarget < 0) {
                return new Descent(new BlockPos(tx, ty, tz), inside, best - moves.cost[bestMove], false);
            }
            x = tx;
            y = ty;
            z = tz;
        }
        throw new IllegalStateException("ガイドを下りきれない: " + x + ", " + y + ", " + z);
    }

    /**
     * この点の値が、窓の中を実際に辿った結果から来ているか。<b>2点の値を引き算するなら、どちらもこれを満たすこと</b>
     * ——縁の近くと窓の外の値は{@link FarField}の推定で、尺度が窓の中と揃っていない（ネザーの3D粗層は
     * {@code NavGraphGuide.VOXEL_FAR_SCALE}倍して置いてある）。差を取ると推定のずれがそのまま結論になる。
     */
    public boolean measuredInWindow(int x, int z) {
        int limit = radius - EDGE_MARGIN_BLOCKS;
        return Math.abs(x - centerX) <= limit && Math.abs(z - centerZ) <= limit;
    }

    /** グラフのノードから直接引ける値。ノードでないか、目的地へ繋がらなければ{@link Double#NaN}。 */
    public double exact(int x, int y, int z) {
        int id = index.resolveAbsolute(x, y, z);
        return id >= 0 && Double.isFinite(distance[id]) ? distance[id] : Double.NaN;
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
        double nearest = nearest(x, y, z);
        return Double.isFinite(nearest) ? nearest : outside(x, y, z);
    }

    /**
     * 組んだセクションの中で値を持たない点（殻の外、または目的地へ繋がらないノード）で、近くにも値が無ければ{@link Double#NaN}。
     *
     * <p>そこで{@link #outside}（窓の外の推定や幾何下限）を探索の値にすると、隣の島の上のノードより数千tick安く見える穴になる。
     * 外してしまうとグラフが持たない橋（目的地へ向かないL字の橋など）を探索が架けられなくなるので、値は探索側で親から引き継ぐ。
     */
    @Override
    public double searchEstimate(int x, int y, int z) {
        int id = index.resolveAbsolute(x, y, z);
        if (id >= 0 && Double.isFinite(distance[id])) {
            return distance[id];
        }
        if (id == OUTSIDE) {
            return outside(x, y, z);
        }
        double nearest = nearest(x, y, z);
        return Double.isFinite(nearest) ? nearest : Double.NaN;
    }

    /** {@link #NEAREST_REACH}以内のノードの値から延ばした値。無ければ{@link Double#POSITIVE_INFINITY}。 */
    private double nearest(int x, int y, int z) {
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
        return nearest;
    }

    /** グラフから値を引けない点。外の値があればそれ、無ければ目的地までの幾何下限。 */
    private double outside(int x, int y, int z) {
        double value = far.at(x, y, z);
        return Double.isFinite(value) ? value : Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
    }
}
