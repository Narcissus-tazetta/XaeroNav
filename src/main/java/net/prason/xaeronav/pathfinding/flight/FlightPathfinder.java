package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/**
 * {@link AirGrid}の上を26近傍で解くA*。空いている空間だけを通る折れ線を返す。
 *
 * <p>ノードは座標を持つオブジェクトではなく<b>平行配列に載せた整数ID</b>にしてある。歩行の
 * {@code PathNode}が持っている情報（移動の種類・橋の連続長・掘削）は空中には1つも無く、残るのは
 * コストと親だけ。3D格子は同じ距離でも歩行の何倍もノードを触るので、1ノードあたりのオブジェクト確保を
 * 無くしておく意味が大きい。
 *
 * <p>{@code PathNode.closed}と同じ規律を守る——重み付きA*は一貫性が崩れるので、確定済みノードを
 * openへ戻すと同じセルを何度も展開し直す（歩行側の実測で1セルあたり6.3回）。戻さない代わりに
 * 経路のコストは最適の{@code heuristicWeight}倍以内に収まる。
 */
public final class FlightPathfinder {

    /**
     * 未到達で終わったときに「どこまで進めたか」を選ぶための係数。小さいほど実際に進んだ距離を
     * 重く見る。歩行側（{@code AStarPathfinder.COEFFICIENTS}）と同じ考え方で、Baritoneに倣っている。
     */
    private static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    /** 打ち切り・時間切れの確認を入れる間隔（展開数のマスク）。 */
    private static final int CHECK_INTERVAL_MASK = 0x3F;

    /** 部分経路として提示する価値がある最短の長さ（ブロック）。これ未満なら経路なしとして扱う。 */
    private static final double MIN_USEFUL_PATH_BLOCKS = 8.0;

    /** 始点・目的地が格子の目に乗っていないときに、飛行可なセルを探す半径（セル数）。 */
    private static final int SNAP_CELL_RADIUS = 3;

    private static final double MIN_IMPROVEMENT = 0.01;

    private final AirGrid grid;
    private final boolean rockets;
    private final SearchLimits limits;
    private final double clearancePenaltyTicks;

    // ノード表（IDは0始まりの連番）。cellKey -> ID の引きは1本だけ持つ
    private final Long2IntOpenHashMap ids = new Long2IntOpenHashMap();
    private long[] cellKey = new long[1024];
    private double[] cost = new double[1024];
    private double[] combined = new double[1024];
    private double[] estimate = new double[1024];
    private int[] previous = new int[1024];
    private int[] heapPosition = new int[1024];
    private boolean[] closed = new boolean[1024];
    private int nodeCount;

    private int[] heap = new int[1024];
    private int heapSize;

    private final int[] bestSoFar = new int[COEFFICIENTS.length];
    private final double[] bestHeuristic = new double[COEFFICIENTS.length];

    private Vec3 goal;
    private double goalRadius;

    /**
     * @param clearancePenaltyTicks 26近傍が完全に塞がったセルへ入るときの割増（tick）。0で無効。
     *                              最短でも狭い所は通したくない、という要求をここで表す
     */
    public FlightPathfinder(AirGrid grid, boolean rockets, SearchLimits limits, double clearancePenaltyTicks) {
        this.grid = grid;
        this.rockets = rockets;
        this.limits = limits;
        this.clearancePenaltyTicks = clearancePenaltyTicks;
        this.ids.defaultReturnValue(-1);
    }

    public FlightRoute search(Vec3 start, Vec3 target, double goalRadiusBlocks) {
        return search(start, target, goalRadiusBlocks, () -> false);
    }

    /**
     * {@code start}から{@code target}の半径{@code goalRadiusBlocks}以内へ届く折れ線を探す。
     *
     * <p>ゴールを点ではなく<b>領域</b>にするのは歩行側と同じ理由——目的地はたいてい着地する地面
     * そのもの（＝飛行不可のセル）で、点で要求すると原理的に到達しない。空中でどこまで寄れば
     * あとは自力で降りられるか、が実際に知りたいこと。
     */
    public FlightRoute search(Vec3 start, Vec3 target, double goalRadiusBlocks, BooleanSupplier cancelled) {
        // ノードの見積もりはゴールが決まって初めて計算できる。2回目の探索でゴールが変わっても
        // 前回のノードは古い見積もりを持ったままなので、表ごと捨てる
        ids.clear();
        nodeCount = 0;
        heapSize = 0;
        this.goal = target;
        this.goalRadius = goalRadiusBlocks;

        long startCell = grid.nearestFlyable(start, SNAP_CELL_RADIUS);
        if (startCell == AirGrid.NONE) {
            // 周りが塞がっている（岩の中・未ロード）。ここから引ける経路は無い
            return FlightRoute.NONE;
        }

        int startNode = node(startCell);
        cost[startNode] = 0.0;
        combined[startNode] = limits.heuristicWeight() * estimate[startNode];
        insert(startNode);
        Arrays.fill(bestSoFar, startNode);
        Arrays.fill(bestHeuristic, estimate[startNode]);

        long deadline = System.currentTimeMillis() + limits.timeLimitMillis();
        int expanded = 0;
        PathResult.Termination termination = PathResult.Termination.EXHAUSTED;

        while (heapSize > 0) {
            if (expanded >= limits.maxExpandedNodes()) {
                termination = PathResult.Termination.NODE_BUDGET;
                break;
            }
            if ((expanded & CHECK_INTERVAL_MASK) == 0) {
                if (cancelled.getAsBoolean()) {
                    termination = PathResult.Termination.CANCELLED;
                    break;
                }
                if (System.currentTimeMillis() >= deadline) {
                    termination = PathResult.Termination.TIME_LIMIT;
                    break;
                }
            }

            int current = removeLowest();
            closed[current] = true;
            expanded++;
            if (reachedGoal(current)) {
                return build(startNode, current, start, PathResult.Termination.REACHED_GOAL, expanded);
            }
            expand(current);
        }

        return build(startNode, fallback(startNode), start, termination, expanded);
    }

    private boolean reachedGoal(int node) {
        return centerOf(node).distanceToSqr(goal) <= goalRadius * goalRadius;
    }

    /**
     * ゴールへ届かなかったときの到達点。係数の小さい（＝進んだ距離を重く見る）ものから順に、
     * 始点から{@link #MIN_USEFUL_PATH_BLOCKS}以上離れている候補を採る。
     */
    private int fallback(int startNode) {
        double threshold = MIN_USEFUL_PATH_BLOCKS * MIN_USEFUL_PATH_BLOCKS;
        Vec3 origin = centerOf(startNode);
        for (int candidate : bestSoFar) {
            if (centerOf(candidate).distanceToSqr(origin) > threshold) {
                return candidate;
            }
        }
        return startNode;
    }

    /**
     * 26近傍へ伸ばす。斜めの移動は<b>跨ぐ2×2×2の箱が全て飛行可のときだけ</b>許す。
     * 端の2セルだけを見ると、岩の角を斜めに擦り抜ける経路が出る。
     */
    private void expand(int current) {
        long key = cellKey[current];
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (!boxClear(x, y, z, dx, dy, dz)) {
                        continue;
                    }
                    relax(current, x + dx, y + dy, z + dz, dx, dy, dz);
                }
            }
        }
    }

    /** 移動が跨ぐ全セル（軸移動なら2、面斜めなら4、立体斜めなら8）が飛行可か。 */
    private boolean boxClear(int x, int y, int z, int dx, int dy, int dz) {
        for (int stepX = 0; stepX <= Math.abs(dx); stepX++) {
            for (int stepY = 0; stepY <= Math.abs(dy); stepY++) {
                for (int stepZ = 0; stepZ <= Math.abs(dz); stepZ++) {
                    if (!grid.flyable(x + stepX * dx, y + stepY * dy, z + stepZ * dz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void relax(int current, int x, int y, int z, int dx, int dy, int dz) {
        int neighbor = node(BlockPos.asLong(x, y, z));
        if (closed[neighbor]) {
            return;
        }
        int cells = grid.cellBlocks();
        double horizontal = Math.sqrt(dx * dx + dz * dz) * cells;
        double vertical = dy * (double) cells;
        double tentative = cost[current] + FlightCosts.segmentTicks(horizontal, vertical, rockets)
                + Clearance.cell(grid, x, y, z, clearancePenaltyTicks);
        if (tentative >= cost[neighbor]) {
            return;
        }

        previous[neighbor] = current;
        cost[neighbor] = tentative;
        combined[neighbor] = tentative + limits.heuristicWeight() * estimate[neighbor];
        if (heapPosition[neighbor] >= 0) {
            siftUp(neighbor);
        } else {
            insert(neighbor);
        }

        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double heuristic = estimate[neighbor] + cost[neighbor] / COEFFICIENTS[i];
            if (bestHeuristic[i] - heuristic > MIN_IMPROVEMENT) {
                bestHeuristic[i] = heuristic;
                bestSoFar[i] = neighbor;
            }
        }
    }

    /**
     * ゴールまでの見積もり。<b>ゴール半径ぶんを差し引く</b>——領域ゴールでは中心までの見積もりが
     * 半径ぶん過大＝非許容になる。差し引く単価は最も安い移動（降下）に合わせて、割り引きすぎない
     * ようにする。
     *
     * <p>狭さの割増（{@link Clearance}）はここに入れない。割増は常に0以上なので、入れない限り
     * 見積もりは下限のままで、A*の性質は変わらない。
     */
    private double estimateToGoal(int x, int y, int z) {
        Vec3 center = grid.center(x, y, z);
        double dx = goal.x - center.x;
        double dz = goal.z - center.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vertical = goal.y - center.y;
        double raw = FlightCosts.heuristicTicks(horizontal, vertical, rockets);
        return Math.max(0.0, raw - goalRadius * FlightCosts.DESCENT_TICKS_PER_BLOCK);
    }

    private FlightRoute build(int startNode, int endNode, Vec3 start, PathResult.Termination termination,
                              int expanded) {
        if (endNode == startNode) {
            return new FlightRoute(List.of(), termination, expanded, grid.cellBlocks());
        }
        List<Vec3> reversed = new ArrayList<>();
        for (int node = endNode; node != -1; node = previous[node]) {
            reversed.add(centerOf(node));
            if (node == startNode) {
                break;
            }
        }
        Collections.reverse(reversed);
        // 先頭はプレイヤーがいるセルの中心なので、実際の位置へ差し替える。ここを中心のままにすると
        // 線が自分の横から生えて見える
        reversed.set(0, start);
        List<Vec3> smoothed = FlightSmoother.smooth(reversed, grid, rockets, clearancePenaltyTicks);
        return new FlightRoute(List.copyOf(smoothed), termination, expanded, grid.cellBlocks());
    }

    private Vec3 centerOf(int node) {
        long key = cellKey[node];
        return grid.center(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
    }

    /** そのセルのノードID。無ければ作る。 */
    private int node(long key) {
        int existing = ids.get(key);
        if (existing >= 0) {
            return existing;
        }
        if (nodeCount == cellKey.length) {
            growNodes();
        }
        int id = nodeCount++;
        ids.put(key, id);
        cellKey[id] = key;
        cost[id] = Double.POSITIVE_INFINITY;
        combined[id] = Double.POSITIVE_INFINITY;
        estimate[id] = estimateToGoal(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
        previous[id] = -1;
        heapPosition[id] = -1;
        closed[id] = false;
        return id;
    }

    private void growNodes() {
        int size = cellKey.length << 1;
        cellKey = Arrays.copyOf(cellKey, size);
        cost = Arrays.copyOf(cost, size);
        combined = Arrays.copyOf(combined, size);
        estimate = Arrays.copyOf(estimate, size);
        previous = Arrays.copyOf(previous, size);
        heapPosition = Arrays.copyOf(heapPosition, size);
        closed = Arrays.copyOf(closed, size);
    }

    // --- オープンセット（IDを並べた二分ヒープ。decrease-keyのためにノード側が位置を持つ） ---

    private void insert(int node) {
        if (heapSize + 1 == heap.length) {
            heap = Arrays.copyOf(heap, heap.length << 1);
        }
        heapSize++;
        heap[heapSize] = node;
        heapPosition[node] = heapSize;
        siftUp(node);
    }

    private int removeLowest() {
        int result = heap[1];
        heapPosition[result] = -1;
        int last = heap[heapSize];
        heap[heapSize] = 0;
        heapSize--;
        if (heapSize > 0) {
            heap[1] = last;
            heapPosition[last] = 1;
            siftDown(last);
        }
        return result;
    }

    private void siftUp(int node) {
        int index = heapPosition[node];
        double key = combined[node];
        while (index > 1) {
            int parentIndex = index >>> 1;
            int parent = heap[parentIndex];
            if (combined[parent] <= key) {
                break;
            }
            heap[parentIndex] = node;
            heap[index] = parent;
            heapPosition[parent] = index;
            index = parentIndex;
        }
        heap[index] = node;
        heapPosition[node] = index;
    }

    private void siftDown(int node) {
        int index = heapPosition[node];
        double key = combined[node];
        while (true) {
            int childIndex = index << 1;
            if (childIndex > heapSize) {
                break;
            }
            int child = heap[childIndex];
            if (childIndex < heapSize && combined[heap[childIndex + 1]] < combined[child]) {
                childIndex++;
                child = heap[childIndex];
            }
            if (key <= combined[child]) {
                break;
            }
            heap[index] = child;
            heap[childIndex] = node;
            heapPosition[child] = index;
            index = childIndex;
        }
        heap[index] = node;
        heapPosition[node] = index;
    }
}
