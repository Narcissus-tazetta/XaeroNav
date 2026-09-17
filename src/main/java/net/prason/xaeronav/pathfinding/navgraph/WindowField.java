package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;
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

    private final BlockPos goal;
    private final FarField far;
    private final Long2IntOpenHashMap slotOf;
    private final short[][] localOf;
    private final int[] offsets;
    private final double[] distance;
    private final int edges;
    private final long buildMillis;

    private WindowField(BlockPos goal, FarField far, Long2IntOpenHashMap slotOf, short[][] localOf, int[] offsets,
                        double[] distance, int edges, long buildMillis) {
        this.goal = goal;
        this.far = far;
        this.slotOf = slotOf;
        this.localOf = localOf;
        this.offsets = offsets;
        this.distance = distance;
        this.edges = edges;
        this.buildMillis = buildMillis;
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

    /**
     * 組み立てにだけ使う大きな配列。区間ごとに数千万要素を作り直すと、そのたびに数百MBのごみになる。
     * 組み立て後のガイドはこれを参照しないので、次の組み立てで上書きしてよい。
     */
    static final class Buffers {
        private int[] start = new int[0];
        private int[] fill = new int[0];
        private int[] predecessor = new int[0];
        private float[] weight = new float[0];

        int[] start(int size) {
            if (start.length < size) {
                start = new int[size + size / 4];
            }
            Arrays.fill(start, 0, size, 0);
            return start;
        }

        int[] fill(int[] source, int size) {
            if (fill.length < size) {
                fill = new int[size + size / 4];
            }
            System.arraycopy(source, 0, fill, 0, size);
            return fill;
        }

        int[] predecessor(int size) {
            if (predecessor.length < size) {
                predecessor = new int[size + size / 4];
            }
            return predecessor;
        }

        float[] weight(int size) {
            if (weight.length < size) {
                weight = new float[size + size / 4];
            }
            return weight;
        }
    }

    static @Nullable WindowField build(NavGraph graph, Buffers buffers, int centerX, int centerZ, int radius,
                                       FarField far, BooleanSupplier cancelled) {
        long began = MonotonicTime.millis();
        BlockPos goal = graph.goal();
        LongArrayList keys = new LongArrayList();
        graph.forEachWindowSection(centerX, centerZ, radius, (sx, sy, sz) -> {
            long key = NavGraph.key(sx, sy, sz);
            if (graph.section(key) != null) {
                keys.add(key);
            }
        });
        int slots = keys.size();
        SectionEdges[] sections = new SectionEdges[slots];
        Long2IntOpenHashMap slotOf = new Long2IntOpenHashMap(slots);
        slotOf.defaultReturnValue(-1);
        short[][] localOf = new short[slots][];
        int[] offsets = new int[slots + 1];
        for (int s = 0; s < slots; s++) {
            long key = keys.getLong(s);
            SectionEdges edges = graph.section(key);
            // 組み立ての途中で捨てられた（チャンクの更新）なら、まだ組んでいないのと同じに扱う
            sections[s] = edges == null ? SectionEdges.EMPTY : edges;
            slotOf.put(key, s);
            localOf[s] = sections[s].localOf;
            offsets[s + 1] = offsets[s] + sections[s].nodes;
        }
        int n = offsets[slots];
        double[] seed = new double[n];
        Arrays.fill(seed, Double.POSITIVE_INFINITY);
        int[] start = buffers.start(n + 1);
        int goalId = idOf(slotOf, localOf, offsets, goal.getX(), goal.getY(), goal.getZ());
        if (goalId >= 0) {
            seed[goalId] = 0.0;
        }

        // 1周目: 窓の中へ入る辺を数え、窓から出る辺は種にする
        for (int s = 0; s < slots; s++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            SectionEdges section = sections[s];
            long key = keys.getLong(s);
            int baseX = BlockPos.getX(key) * SectionMoves.SIZE;
            int baseY = BlockPos.getY(key) * SectionMoves.SIZE;
            int baseZ = BlockPos.getZ(key) * SectionMoves.SIZE;
            for (int i = 0; i < section.size(); i++) {
                int local = section.from[i];
                int fromId = offsets[s] + localOf[s][local];
                int fx = baseX + (local & 15);
                int fz = baseZ + (local >> 4 & 15);
                if (Math.abs(fx - centerX) > radius - EDGE_SEED_BAND || Math.abs(fz - centerZ) > radius - EDGE_SEED_BAND) {
                    double value = far.at(fx, baseY + (local >> 8 & 15), fz);
                    if (Double.isFinite(value)) {
                        seed[fromId] = Math.min(seed[fromId], value);
                    }
                }
                int tx = baseX + (local & 15) + section.dx[i];
                int ty = baseY + (local >> 8 & 15) + section.dy[i];
                int tz = baseZ + (local >> 4 & 15) + section.dz[i];
                if (tx == goal.getX() && ty == goal.getY() && tz == goal.getZ()) {
                    // 目的地そのものは殻の外（展開しないセル）にあってもよい。そこへ入る辺は目的地までの値段そのもの
                    seed[fromId] = Math.min(seed[fromId], section.cost[i]);
                }
                int target = targetId(slotOf, localOf, offsets, tx, ty, tz);
                if (target >= 0) {
                    start[target + 1]++;
                } else if (target == OUTSIDE) {
                    double value = far.at(tx, ty, tz);
                    if (Double.isFinite(value)) {
                        seed[fromId] = Math.min(seed[fromId], section.cost[i] + value);
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            start[i + 1] += start[i];
        }
        int m = start[n];
        int[] fill = buffers.fill(start, n);
        int[] predecessor = buffers.predecessor(m);
        float[] weight = buffers.weight(m);
        // 2周目: 逆向きの隣接表を埋める
        for (int s = 0; s < slots; s++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            SectionEdges section = sections[s];
            long key = keys.getLong(s);
            int baseX = BlockPos.getX(key) * SectionMoves.SIZE;
            int baseY = BlockPos.getY(key) * SectionMoves.SIZE;
            int baseZ = BlockPos.getZ(key) * SectionMoves.SIZE;
            for (int i = 0; i < section.size(); i++) {
                int local = section.from[i];
                int target = targetId(slotOf, localOf, offsets, baseX + (local & 15) + section.dx[i],
                        baseY + (local >> 8 & 15) + section.dy[i], baseZ + (local >> 4 & 15) + section.dz[i]);
                if (target >= 0) {
                    int slot = fill[target]++;
                    predecessor[slot] = offsets[s] + localOf[s][local];
                    weight[slot] = section.cost[i];
                }
            }
        }

        double[] distance = seed;
        DistanceHeap heap = new DistanceHeap();
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
            for (int slot = start[node]; slot < start[node + 1]; slot++) {
                int p = predecessor[slot];
                double candidate = d + weight[slot];
                if (candidate < distance[p]) {
                    distance[p] = candidate;
                    heap.push(candidate, p);
                }
            }
        }
        return new WindowField(goal, far, slotOf, localOf, offsets, distance, m,
                MonotonicTime.millis() - began);
    }

    /** 窓の外・まだ組んでいないセクション。 */
    private static final int OUTSIDE = -1;
    /** 組んだセクションの中だが、ノードではない（殻の外）。 */
    private static final int NOT_A_NODE = -2;

    private static int targetId(Long2IntOpenHashMap slotOf, short[][] localOf, int[] offsets, int x, int y, int z) {
        int slot = slotOf.get(NavGraph.key(Math.floorDiv(x, SectionMoves.SIZE), Math.floorDiv(y, SectionMoves.SIZE),
                Math.floorDiv(z, SectionMoves.SIZE)));
        if (slot < 0) {
            return OUTSIDE;
        }
        short local = localOf[slot][SectionEdges.local(x, y, z)];
        return local < 0 ? NOT_A_NODE : offsets[slot] + local;
    }

    private static int idOf(Long2IntOpenHashMap slotOf, short[][] localOf, int[] offsets, int x, int y, int z) {
        int id = targetId(slotOf, localOf, offsets, x, y, z);
        return Math.max(id, -1);
    }

    @Override
    public double estimate(int x, int y, int z) {
        int id = targetId(slotOf, localOf, offsets, x, y, z);
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
                    int near = targetId(slotOf, localOf, offsets, x + dx, y + dy, z + dz);
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
