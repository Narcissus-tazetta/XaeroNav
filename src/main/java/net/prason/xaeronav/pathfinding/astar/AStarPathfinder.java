package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.BlockSnapshotData;
import net.prason.xaeronav.pathfinding.world.WorldSnapshot;

/**
 * design doc §4。Phase 1ではTraverse/Ascend/Descendのみ（Diagonal/Fall/Pillarは後回し）。
 * ワーカースレッドから呼ぶ想定 — {@link WorldSnapshot}以外のMinecraft状態には一切触れない。
 */
public final class AStarPathfinder {

    public static final int DEFAULT_MAX_EXPANDED_NODES = 30_000;
    public static final long DEFAULT_TIME_LIMIT_MILLIS = 300;

    /** 落下ブロックが延々と積まれている異常な塔でも1エッジの評価が固まらないようにする安全弁。 */
    private static final int MAX_FALLING_CHAIN_SCAN = 16;

    private final WorldSnapshot snapshot;
    private final int maxExpandedNodes;
    private final long timeLimitMillis;

    public AStarPathfinder(WorldSnapshot snapshot) {
        this(snapshot, DEFAULT_MAX_EXPANDED_NODES, DEFAULT_TIME_LIMIT_MILLIS);
    }

    public AStarPathfinder(WorldSnapshot snapshot, int maxExpandedNodes, long timeLimitMillis) {
        this.snapshot = snapshot;
        this.maxExpandedNodes = maxExpandedNodes;
        this.timeLimitMillis = timeLimitMillis;
    }

    /** 打ち切り条件（展開数上限・時間上限・cancelled）のいずれかに達したら、その時点で最もゴールに近い暫定経路を返す（design doc §4-4）。 */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled) {
        record OpenEntry(BlockPos pos, double f) {
        }

        PriorityQueue<OpenEntry> open = new PriorityQueue<>(Comparator.comparingDouble(OpenEntry::f));
        Map<BlockPos, Double> gScore = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Edge> cameFromEdge = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        gScore.put(start, 0.0);
        open.add(new OpenEntry(start, Heuristic.estimate(start, goal)));

        BlockPos bestSoFar = start;
        double bestSoFarH = Heuristic.estimate(start, goal);

        long deadline = System.currentTimeMillis() + timeLimitMillis;
        int expanded = 0;

        while (!open.isEmpty()) {
            if (cancelled.getAsBoolean()
                    || expanded >= maxExpandedNodes
                    || System.currentTimeMillis() >= deadline) {
                return buildResult(cameFrom, cameFromEdge, gScore, start, bestSoFar, false, expanded);
            }

            OpenEntry current = open.poll();
            if (closed.contains(current.pos())) {
                continue;
            }
            if (current.pos().equals(goal)) {
                return buildResult(cameFrom, cameFromEdge, gScore, start, goal, true, expanded);
            }
            closed.add(current.pos());
            expanded++;

            double h = Heuristic.estimate(current.pos(), goal);
            if (h < bestSoFarH) {
                bestSoFarH = h;
                bestSoFar = current.pos();
            }

            double currentG = gScore.get(current.pos());
            for (Edge edge : neighbors(current.pos())) {
                if (closed.contains(edge.to())) {
                    continue;
                }
                double tentativeG = currentG + edge.cost();
                Double existing = gScore.get(edge.to());
                if (existing == null || tentativeG < existing) {
                    gScore.put(edge.to(), tentativeG);
                    cameFrom.put(edge.to(), current.pos());
                    cameFromEdge.put(edge.to(), edge);
                    open.add(new OpenEntry(edge.to(), tentativeG + Heuristic.estimate(edge.to(), goal)));
                }
            }
        }

        // 到達不能：これまでで一番ゴールに近づけた地点までの経路を返す(design doc §4-4)
        return buildResult(cameFrom, cameFromEdge, gScore, start, bestSoFar, false, expanded);
    }

    private PathResult buildResult(Map<BlockPos, BlockPos> cameFrom, Map<BlockPos, Edge> cameFromEdge,
                                    Map<BlockPos, Double> gScore, BlockPos start, BlockPos end,
                                    boolean complete, int expanded) {
        List<PathStep> steps = new ArrayList<>();
        BlockPos cursor = end;
        while (!cursor.equals(start)) {
            BlockPos prev = cameFrom.get(cursor);
            if (prev == null) {
                break;
            }
            Edge edge = cameFromEdge.get(cursor);
            double stepCost = gScore.get(cursor) - gScore.getOrDefault(prev, 0.0);
            steps.add(new PathStep(cursor, edge.movement(), stepCost, edge.digging(), edge.bodyCells(), PathRisk.NONE));
            cursor = prev;
        }
        Collections.reverse(steps);
        return new PathResult(steps, complete, expanded);
    }

    private List<Edge> neighbors(BlockPos pos) {
        List<Edge> edges = new ArrayList<>(12);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            addTraverse(edges, pos, dir);
            addAscend(edges, pos, dir);
            addDescend(edges, pos, dir);
        }
        return edges;
    }

    private void addTraverse(List<Edge> edges, BlockPos pos, Direction dir) {
        BlockPos bodyLow = pos.relative(dir);
        BlockPos bodyHigh = bodyLow.above();
        BlockPos floor = bodyLow.below();

        BlockSnapshotData floorCell = snapshot.get(floor);
        if (floorCell == null || !floorCell.standable()) {
            return;
        }
        List<BlockPos> bodyCells = List.of(bodyLow, bodyHigh);
        double bodyCost = bodyClearanceCost(bodyCells);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        BlockSnapshotData lowCell = snapshot.get(bodyLow);
        double baseCost = (lowCell != null && lowCell.water())
                ? ActionCosts.WALK_ONE_IN_WATER
                : ActionCosts.SPRINT_ONE_BLOCK;
        edges.add(new Edge(bodyLow, baseCost + bodyCost, MovementType.TRAVERSE, bodyCost > 0, bodyCells));
    }

    private void addAscend(List<Edge> edges, BlockPos pos, Direction dir) {
        BlockPos step = pos.relative(dir);
        BlockPos target = step.above();
        BlockPos targetHigh = target.above();
        BlockPos headroom = pos.above(2);

        BlockSnapshotData stepCell = snapshot.get(step);
        if (stepCell == null || !stepCell.standable()) {
            return;
        }
        // 頭上に十分な空間がないとジャンプできない。ここを掘る選択肢はPhase 1では扱わない
        BlockSnapshotData headroomCell = snapshot.get(headroom);
        if (headroomCell == null || !headroomCell.isOccupiableWithoutDigging()) {
            return;
        }
        List<BlockPos> bodyCells = List.of(target, targetHigh);
        double bodyCost = bodyClearanceCost(bodyCells);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        edges.add(new Edge(target, ActionCosts.ASCEND_ONE_BLOCK + bodyCost, MovementType.ASCEND, bodyCost > 0, bodyCells));
    }

    private void addDescend(List<Edge> edges, BlockPos pos, Direction dir) {
        BlockPos sameLevel = pos.relative(dir);
        BlockPos target = sameLevel.below();
        BlockPos floor = target.below();

        BlockSnapshotData floorCell = snapshot.get(floor);
        if (floorCell == null || !floorCell.standable()) {
            return;
        }
        List<BlockPos> bodyCells = List.of(sameLevel, sameLevel.above(), target);
        double bodyCost = bodyClearanceCost(bodyCells);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        edges.add(new Edge(target, ActionCosts.DESCEND_ONE_BLOCK + bodyCost, MovementType.DESCEND, bodyCost > 0, bodyCells));
    }

    /**
     * 必須セル群それぞれの単体破壊コストを合算し、さらにその中で最もYが高いセルの真上から
     * 落下ブロック（砂・砂利等）が連なっている分だけ一度追加する（design doc §3-3）。
     * 必須セル自体は個別に数えるだけなので、隣接する必須セル同士で連鎖コストが重複しない。
     */
    private double bodyClearanceCost(List<BlockPos> requiredCells) {
        double total = 0.0;
        BlockPos topmost = requiredCells.get(0);
        for (BlockPos cell : requiredCells) {
            double cost = occupyCost(cell);
            if (Double.isInfinite(cost)) {
                return ActionCosts.INFEASIBLE;
            }
            total += cost;
            if (cell.getY() > topmost.getY()) {
                topmost = cell;
            }
        }

        BlockPos cursor = topmost.above();
        for (int i = 0; i < MAX_FALLING_CHAIN_SCAN; i++) {
            BlockSnapshotData cell = snapshot.get(cursor);
            if (cell == null || !cell.fallingBlock()) {
                break;
            }
            double cost = occupyCost(cursor);
            if (Double.isInfinite(cost)) {
                break;
            }
            total += cost;
            cursor = cursor.above();
        }
        return total;
    }

    private double occupyCost(BlockPos pos) {
        BlockSnapshotData cell = snapshot.get(pos);
        if (cell == null) {
            return ActionCosts.INFEASIBLE;
        }
        if (cell.isOccupiableWithoutDigging()) {
            return 0.0;
        }
        return cell.digTicks();
    }
}
