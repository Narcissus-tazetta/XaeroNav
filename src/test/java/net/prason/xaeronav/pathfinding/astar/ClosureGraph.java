package net.prason.xaeronav.pathfinding.astar;

import java.util.Arrays;
import java.util.BitSet;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * <b>実験用。</b>層3の移動生成が箱の中で張る辺を全部持つグラフ。
 *
 * <p>始点から重み0（素のDijkstra）で箱を回し切り、{@link EdgeSink}で辺を拾う。
 * 2026-09-16の上界の実測で踏んだ罠の対策を最初から入れてある:
 * <ul>
 * <li><b>箱のYは絞らない</b>（呼び出し側が世界の高さを渡す）。絞るとエンドで奈落の下を回る道を
 *     知らず、残コストを過大に見積もる</li>
 * <li><b>閉包のゴールは本当のゴールと同じ列に置く</b>。{@code BuildMoves#addBridge}は奈落の上で
 *     ゴールへのL1距離が減る向きにしか橋を張らないので、別の点を置くと本当のゴールへ向かう橋が
 *     グラフから落ちる。Yだけを世界の外へずらして、到達で打ち切られないようにする</li>
 * <li><b>水平マージンは探索窓より広く取る</b>。はみ出した所でガイドが0を返し、幾何下限へ落ちる</li>
 * </ul>
 */
final class ClosureGraph {

    final Long2IntOpenHashMap walking = new Long2IntOpenHashMap();
    final Long2IntOpenHashMap boating = new Long2IntOpenHashMap();
    final IntArrayList xs = new IntArrayList();
    final IntArrayList ys = new IntArrayList();
    final IntArrayList zs = new IntArrayList();
    final IntArrayList from = new IntArrayList();
    final IntArrayList to = new IntArrayList();
    final FloatArrayList cost = new FloatArrayList();
    /** ノードの種類（{@link #NATURAL}・{@link #DIG}・{@link #AIR}）。 */
    final it.unimi.dsi.fastutil.bytes.ByteArrayList category = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
    long buildMillis;

    /** 掘らずに立てる（足元が床・水・掴まれるもの）。 */
    static final byte NATURAL = 0;
    /** 体の2セルのどちらかを掘らないと入れない。 */
    static final byte DIG = 1;
    /** 掘らずに入れるが、足場が無い（置いた足場の上・橋の途中・落下中）。 */
    static final byte AIR = 2;

    private CellSource source;

    private ClosureGraph() {
        walking.defaultReturnValue(-1);
        boating.defaultReturnValue(-1);
    }

    int nodes() {
        return xs.size();
    }

    long edges() {
        return from.size();
    }

    /** 始点・目的地の外接矩形を水平に{@code margin}だけ広げ、Yは{@code minY..maxY}の箱。 */
    static SearchBounds box(CellSource all, BlockPos start, BlockPos goal, int margin, int minY, int maxY) {
        SearchBounds world = all.bounds();
        return new SearchBounds(
                Math.max(world.minX(), Math.min(start.getX(), goal.getX()) - margin), minY,
                Math.max(world.minZ(), Math.min(start.getZ(), goal.getZ()) - margin),
                Math.min(world.maxX(), Math.max(start.getX(), goal.getX()) + margin), maxY,
                Math.min(world.maxZ(), Math.max(start.getZ(), goal.getZ()) + margin));
    }

    /**
     * @param start 立てる座標へ寄せた始点
     * @param goal  立てる座標へ寄せた目的地（橋の向きを本番と揃えるためだけに使う）
     */
    static ClosureGraph build(CellSource all, BlockPos start, BlockPos goal, SearchBounds box) {
        return build(all, start, goal, box, false);
    }

    /** @param naturalOnly trueなら自然に立てる点だけを展開する（体積を回らない安い閉包） */
    static ClosureGraph build(CellSource all, BlockPos start, BlockPos goal, SearchBounds box, boolean naturalOnly) {
        long began = System.currentTimeMillis();
        CellSource view = new WindowedCells(all, start, 1 << 28, box);
        ClosureGraph graph = new ClosureGraph();
        graph.source = view;
        AStarPathfinder closure = new AStarPathfinder(view, new SearchLimits(Integer.MAX_VALUE, 3_600_000L, 0.0));
        if (naturalOnly) {
            closure.expandFilter((x, y, z) -> classify(view, x, y, z) == NATURAL);
        }
        closure.edgeSink((fx, fy, fz, fb, tx, ty, tz, tb, edgeCost, kind) -> {
            int a = graph.id(fb, fx, fy, fz);
            int b = graph.id(tb, tx, ty, tz);
            boolean surfacing = ty > fy && Math.abs(tx - fx) + Math.abs(tz - fz) <= 1;
            boolean submerged = CellData.water(view.cell(tx, ty + 1, tz)) && !surfacing;
            graph.from.add(a);
            graph.to.add(b);
            graph.cost.add((float) (submerged ? edgeCost * ActionCosts.SUBMERGED_TRAVEL_PENALTY : edgeCost));
        });
        PathResult result = closure.search(start,
                new BlockPos(goal.getX(), box.maxY() + 10_000, goal.getZ()), () -> false);
        if (result.termination() != PathResult.Termination.EXHAUSTED) {
            throw new IllegalStateException("閉包が回り切っていない: " + result.termination());
        }
        graph.buildMillis = System.currentTimeMillis() - began;
        return graph;
    }

    private int id(boolean boat, int x, int y, int z) {
        Long2IntOpenHashMap ids = boat ? boating : walking;
        long key = BlockPos.asLong(x, y, z);
        int id = ids.get(key);
        if (id < 0) {
            id = xs.size();
            ids.put(key, id);
            xs.add(x);
            ys.add(y);
            zs.add(z);
            category.add(classify(source, x, y, z));
        }
        return id;
    }

    static byte classify(CellSource cells, int x, int y, int z) {
        long feet = cells.cell(x, y, z);
        if (!CellData.occupiableWithoutDigging(feet) || !CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z))) {
            return DIG;
        }
        boolean surfaceWater = CellData.water(feet) && !CellData.water(cells.cell(x, y + 1, z));
        if (CellData.standable(cells.cell(x, y - 1, z)) && !CellData.water(feet) || surfaceWater
                || CellData.climbable(feet)) {
            return NATURAL;
        }
        return AIR;
    }

    /** 種類ごとのノード数。 */
    int[] categoryCounts() {
        int[] counts = new int[3];
        for (int i = 0; i < category.size(); i++) {
            counts[category.getByte(i)]++;
        }
        return counts;
    }

    /** 両端が{@code allowed}に含まれる種類の辺だけを残す。{@code allowed}は種類ごとのビット。 */
    BitSet edgesBetween(int allowed) {
        BitSet kept = new BitSet(from.size());
        for (int e = 0; e < from.size(); e++) {
            if ((allowed >> category.getByte(from.getInt(e)) & 1) != 0
                    && (allowed >> category.getByte(to.getInt(e)) & 1) != 0) {
                kept.set(e);
            }
        }
        return kept;
    }

    /**
     * 自然に立てる点から水平{@code r}・垂直{@code k}以内にあるノードだけを残した辺。辺を間引くだけなので
     * 残りコストは本物以上（過大評価側）に留まる。
     */
    BitSet shellEdges(int r, int k) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        for (int i = 0; i < nodes(); i++) {
            minX = Math.min(minX, xs.getInt(i));
            maxX = Math.max(maxX, xs.getInt(i));
            minZ = Math.min(minZ, zs.getInt(i));
            maxZ = Math.max(maxZ, zs.getInt(i));
            minY = Math.min(minY, ys.getInt(i));
        }
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        // 列ごとに、残してよい高さのビット（Yは最小から数えて最大512まで）
        long[] allowed = new long[sizeX * sizeZ * 8];
        for (int i = 0; i < nodes(); i++) {
            if (category.getByte(i) != NATURAL) {
                continue;
            }
            int x = xs.getInt(i);
            int z = zs.getInt(i);
            int y = ys.getInt(i) - minY;
            for (int cx = Math.max(minX, x - r); cx <= Math.min(maxX, x + r); cx++) {
                for (int cz = Math.max(minZ, z - r); cz <= Math.min(maxZ, z + r); cz++) {
                    int base = ((cx - minX) + (cz - minZ) * sizeX) * 8;
                    for (int dy = Math.max(0, y - k); dy <= Math.min(511, y + k); dy++) {
                        allowed[base + (dy >> 6)] |= 1L << dy;
                    }
                }
            }
        }
        BitSet keptNodes = new BitSet(nodes());
        for (int i = 0; i < nodes(); i++) {
            int y = ys.getInt(i) - minY;
            int base = ((xs.getInt(i) - minX) + (zs.getInt(i) - minZ) * sizeX) * 8;
            if (category.getByte(i) == NATURAL || y < 512 && (allowed[base + (y >> 6)] >> y & 1) != 0) {
                keptNodes.set(i);
            }
        }
        BitSet kept = new BitSet(from.size());
        for (int e = 0; e < from.size(); e++) {
            if (keptNodes.get(from.getInt(e)) && keptNodes.get(to.getInt(e))) {
                kept.set(e);
            }
        }
        return kept;
    }

    private int[] reverseStart;
    private int[] reversePredecessor;
    private float[] reverseWeight;

    private void ensureReverse() {
        if (reverseStart != null) {
            return;
        }
        int n = nodes();
        int m = from.size();
        int[] start = new int[n + 1];
        for (int e = 0; e < m; e++) {
            start[to.getInt(e) + 1]++;
        }
        for (int i = 0; i < n; i++) {
            start[i + 1] += start[i];
        }
        int[] fill = Arrays.copyOf(start, n);
        int[] predecessor = new int[m];
        float[] weight = new float[m];
        for (int e = 0; e < m; e++) {
            int slot = fill[to.getInt(e)]++;
            predecessor[slot] = from.getInt(e);
            weight[slot] = cost.getFloat(e);
        }
        reverseStart = start;
        reversePredecessor = predecessor;
        reverseWeight = weight;
    }

    /** 窓の外の点の値。 */
    @FunctionalInterface
    interface OutsideValue {
        double at(int x, int y, int z);
    }

    /**
     * 窓（プレイヤーから水平{@code radius}の正方形）の中だけ本物の辺で逆Dijkstraし、窓から出る辺の先には
     * {@code outside}の値を置く。窓の外の点はそのまま{@code outside}を返すガイド。
     */
    CostToGo windowGuide(BlockPos goal, BlockPos player, int radius, OutsideValue outside) {
        ensureReverse();
        int n = nodes();
        double[] distance = new double[n];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        MinHeap heap = new MinHeap();
        int goalId = idAt(goal.getX(), goal.getY(), goal.getZ());
        if (goalId >= 0 && inWindow(goalId, player, radius)) {
            distance[goalId] = 0.0;
            heap.push(0.0, goalId);
        }
        // 窓の外にある点のうち、窓の中の点から辺が入ってくるものを種にする
        for (int v = 0; v < n; v++) {
            if (inWindow(v, player, radius)) {
                continue;
            }
            boolean entered = false;
            for (int slot = reverseStart[v]; slot < reverseStart[v + 1]; slot++) {
                if (inWindow(reversePredecessor[slot], player, radius)) {
                    entered = true;
                    break;
                }
            }
            if (!entered) {
                continue;
            }
            double value = outside.at(xs.getInt(v), ys.getInt(v), zs.getInt(v));
            if (Double.isFinite(value) && value < distance[v]) {
                distance[v] = value;
                heap.push(value, v);
            }
        }
        while (!heap.isEmpty()) {
            double d = heap.topKey();
            int node = heap.pop();
            if (d > distance[node]) {
                continue;
            }
            for (int slot = reverseStart[node]; slot < reverseStart[node + 1]; slot++) {
                int p = reversePredecessor[slot];
                if (!inWindow(p, player, radius)) {
                    continue;
                }
                double candidate = d + reverseWeight[slot];
                if (candidate < distance[p]) {
                    distance[p] = candidate;
                    heap.push(candidate, p);
                }
            }
        }
        OutsideValue finiteOutside = (x, y, z) -> {
            double value = outside.at(x, y, z);
            return Double.isFinite(value) ? value
                    : Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
        };
        return (x, y, z) -> {
            if (Math.abs(x - player.getX()) > radius || Math.abs(z - player.getZ()) > radius) {
                return finiteOutside.at(x, y, z);
            }
            int id = idAt(x, y, z);
            if (id >= 0 && Double.isFinite(distance[id])) {
                return distance[id];
            }
            double near = nearestValue(distance, 3, x, y, z);
            return Double.isFinite(near) ? near : finiteOutside.at(x, y, z);
        };
    }

    private boolean inWindow(int id, BlockPos player, int radius) {
        return Math.abs(xs.getInt(id) - player.getX()) <= radius && Math.abs(zs.getInt(id) - player.getZ()) <= radius;
    }

    /** 歩いている状態を優先し、無ければボートの状態のid。無ければ-1。 */
    int idAt(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        int id = walking.get(key);
        return id >= 0 ? id : boating.get(key);
    }

    /**
     * 目的地までの残りコストを全ノードについて出す。{@code kept}が{@code null}でなければ、
     * そこに立っている辺だけを使う。
     */
    double[] distancesTo(BlockPos goal, BitSet kept) {
        return distancesTo(goal, kept, new IntArrayList(), new IntArrayList(), new FloatArrayList());
    }

    /** {@code kept}の辺に加えて、{@code extraFrom→extraTo}の辺（値段{@code extraCost}）も使う。 */
    double[] distancesTo(BlockPos goal, BitSet kept, IntArrayList extraFrom, IntArrayList extraTo,
                         FloatArrayList extraCost) {
        int n = nodes();
        int m = from.size();
        int[] start = new int[n + 1];
        for (int e = 0; e < m; e++) {
            if (kept == null || kept.get(e)) {
                start[to.getInt(e) + 1]++;
            }
        }
        for (int e = 0; e < extraTo.size(); e++) {
            start[extraTo.getInt(e) + 1]++;
        }
        for (int i = 0; i < n; i++) {
            start[i + 1] += start[i];
        }
        int[] fill = Arrays.copyOf(start, n);
        int[] predecessor = new int[start[n]];
        float[] weight = new float[start[n]];
        for (int e = 0; e < m; e++) {
            if (kept == null || kept.get(e)) {
                int slot = fill[to.getInt(e)]++;
                predecessor[slot] = from.getInt(e);
                weight[slot] = cost.getFloat(e);
            }
        }
        for (int e = 0; e < extraTo.size(); e++) {
            int slot = fill[extraTo.getInt(e)]++;
            predecessor[slot] = extraFrom.getInt(e);
            weight[slot] = extraCost.getFloat(e);
        }
        double[] distance = new double[n];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        MinHeap heap = new MinHeap();
        long key = BlockPos.asLong(goal.getX(), goal.getY(), goal.getZ());
        for (int seed : new int[] {walking.get(key), boating.get(key)}) {
            if (seed >= 0) {
                distance[seed] = 0.0;
                heap.push(0.0, seed);
            }
        }
        if (heap.isEmpty()) {
            throw new IllegalStateException("閉包が目的地に届いていない: " + goal.toShortString());
        }
        while (!heap.isEmpty()) {
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
        return distance;
    }

    /** 残りコストの表をガイドとして引く。 */
    CostToGo guide(double[] distance) {
        return new Table(this, distance);
    }

    /**
     * 別の世界（Xaeroの地図から組み直した世界など）で作った表を、本物の座標から引くガイド。
     * 引いた座標がグラフに無ければ、{@code reach}ブロック以内のノードから幾何下限で延ばした値の最小。
     */
    CostToGo nearestGuide(double[] distance, int reach) {
        return (x, y, z) -> {
            if (idAt(x, y, z) < 0 && !Double.isFinite(nearestValue(distance, reach, x, y, z))) {
                // グラフの外（箱の外・閉包が届かない所）は幾何下限に任せる
                return 0.0;
            }
            double value = nearestValue(distance, reach, x, y, z);
            // グラフの中にあるのに値が無い（間引いた種類・行き止まり）点は避ける。0にすると吸い寄せる
            return Double.isFinite(value) ? value : 1.0e7;
        };
    }

    /** {@link #nearestGuide}と同じ引き方で、見つからなければ{@link Double#POSITIVE_INFINITY}。窓の境界の種に使う。 */
    double nearestValue(double[] distance, int reach, int x, int y, int z) {
        double value = exact(this, distance, x, y, z);
        if (Double.isFinite(value)) {
            return value;
        }
        double bestValue = Double.POSITIVE_INFINITY;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    int id = idAt(x + dx, y + dy, z + dz);
                    if (id >= 0 && Double.isFinite(distance[id])) {
                        bestValue = Math.min(bestValue, distance[id] + Heuristic.estimate(x, y, z,
                                x + dx, y + dy, z + dz));
                    }
                }
            }
        }
        return bestValue;
    }

    /** 閉包の中でのこのセルの値。{@code NaN}なら閉包の外。 */
    static double exact(ClosureGraph graph, double[] distance, int x, int y, int z) {
        int id = graph.idAt(x, y, z);
        return id < 0 ? Double.NaN : distance[id];
    }

    private record Table(ClosureGraph graph, double[] distance) implements CostToGo {

        /** 閉包に含まれない座標。幾何下限に任せる。 */
        private static final double UNKNOWN = 0.0;

        /**
         * 閉包に含まれるのに目的地へ着けない座標。無限大にすると{@code selectFallback}の採点が
         * NaNになるので、どの経路よりも高い有限値にする。
         */
        private static final double DEAD_END = 1.0e7;

        @Override
        public double estimate(int x, int y, int z) {
            double value = exact(graph, distance, x, y, z);
            if (Double.isNaN(value)) {
                // 掘った・置いたブロックで生まれた、閉包に無い立ち位置。0を返すとそこへ探索と終点選びが
                // 吸い寄せられるので、近くの点の値から延ばす
                double near = graph.nearestValue(distance, 3, x, y, z);
                return Double.isFinite(near) ? near : UNKNOWN;
            }
            return Double.isInfinite(value) ? DEAD_END : value;
        }
    }

    /** 重複を許す二分ヒープ（遅延削除）。 */
    static final class MinHeap {
        private double[] keys = new double[1024];
        private int[] values = new int[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        double topKey() {
            return keys[0];
        }

        void push(double key, int value) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                values = Arrays.copyOf(values, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (keys[parent] <= key) {
                    break;
                }
                keys[i] = keys[parent];
                values[i] = values[parent];
                i = parent;
            }
            keys[i] = key;
            values[i] = value;
        }

        int pop() {
            int top = values[0];
            size--;
            if (size > 0) {
                double key = keys[size];
                int value = values[size];
                int i = 0;
                while (true) {
                    int child = 2 * i + 1;
                    if (child >= size) {
                        break;
                    }
                    if (child + 1 < size && keys[child + 1] < keys[child]) {
                        child++;
                    }
                    if (keys[child] >= key) {
                        break;
                    }
                    keys[i] = keys[child];
                    values[i] = values[child];
                    i = child;
                }
                keys[i] = key;
                values[i] = value;
            }
            return top;
        }
    }
}
