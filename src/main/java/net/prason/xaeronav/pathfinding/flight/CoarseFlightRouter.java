package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/**
 * {@link CoarseAirMap}の上を解く、空中の長距離ルート（層1相当）。描画距離の外まで届く。
 *
 * <p><b>歩行の{@code CoarseRouter}は流用しない。</b>あれは溶岩を通行不能〜高コストに、水をボート
 * 倍率で扱う歩行専用のコストモデルで、飛行では<b>溶岩の海の上を飛ぶのが完全に正解</b>——
 * 符号ごと間違っている。地形の読み方（{@code CoarseMap}）だけを共有して、コストは別に持つ。
 *
 * <p>状態は{@code (チャンクX, チャンクZ, 高度帯)}。<b>同じセルの中で帯をまたぐ移動は作らない</b>——
 * 帯と帯のあいだにあるのは床（岩）なので、そこを縦に抜ける道があるかどうかはチャンク解像度では
 * 分からない。層をまたぐのは、隣のセルで帯どうしが重なっている所を通ることで自然に起きる。
 * 分からないものを繋がっていることにするより、層3（{@link AirGrid}）へ委ねる方が安全側。
 * これは歩行の層1/層2がレイヤーまたぎを表現しないのと同じ割り切り。
 */
public final class CoarseFlightRouter {

    private static final int CELL_BLOCKS = 16;

    /** 中間目標を落とす間隔（セル）。歩行の層1に揃える。 */
    private static final int WAYPOINT_SPACING_CELLS = 4;

    /** 高さがこれだけ変わったら、間隔を待たずに中間目標を落とす（ブロック）。 */
    private static final int WAYPOINT_VERTICAL_SPACING_BLOCKS = 24;

    /**
     * 隣のセルの帯へ移るときに許す高さの隙間（ブロック）。重なっていなくても、これ以内なら
     * 地形なりの緩い昇降とみなす。大きくすると、実際には岩で隔てられた別の階層どうしが
     * 繋がっているように見えはじめる。
     */
    private static final int BAND_LINK_GAP_BLOCKS = 8;

    /**
     * データが無いセルを通る倍率。歩行の{@code UNKNOWN_MULTIPLIER}と同じ役割だが、飛行では
     * 未訪問であることの不利が小さい（溶岩も水も関係なく、要るのは開けた空間だけ）ので控えめ。
     */
    private static final double UNKNOWN_MULTIPLIER = 1.3;

    private CoarseFlightRouter() {
    }

    /**
     * {@code start}から{@code goal}への中間目標列。届かなければ、その時点で最もゴールに
     * 近づけた地点までを返す（{@link CoarseRouter.Route#reachedGoal()}がfalse）。
     */
    public static CoarseRouter.Route findRoute(CoarseAirMap map, BlockPos start, BlockPos goal,
                                                boolean rockets) {
        int startX = start.getX() >> 4;
        int startZ = start.getZ() >> 4;
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        if (!map.containsChunk(startX, startZ) || !map.containsChunk(goalX, goalZ)) {
            return new CoarseRouter.Route(List.of(), false);
        }

        int states = map.chunksX() * map.chunksZ() * CoarseAirMap.MAX_BANDS;
        double[] cost = new double[states];
        int[] previous = new int[states];
        boolean[] closed = new boolean[states];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);

        if (map.blocked(startX, startZ) || map.blocked(goalX, goalZ)) {
            // 出発点か目的地の列が粗い地図では壁。ここで無理に経路を作っても意味が無いので、
            // 粗い層は諦めて層3（読み込み済みチャンクを見る側）へ委ねる
            return new CoarseRouter.Route(List.of(), false);
        }
        int startState = stateIndex(map, startX, startZ, map.bandAt(startX, startZ, start.getY()));
        int goalState = stateIndex(map, goalX, goalZ, map.bandAt(goalX, goalZ, goal.getY()));
        cost[startState] = 0.0;

        PriorityQueue<Candidate> open = new PriorityQueue<>();
        open.add(new Candidate(startState, heuristic(map, startState, goal, rockets)));
        int bestState = startState;
        double bestHeuristic = heuristic(map, startState, goal, rockets);
        boolean reachedGoal = false;

        while (!open.isEmpty()) {
            Candidate candidate = open.poll();
            int state = candidate.state();
            if (closed[state]) {
                continue;
            }
            closed[state] = true;
            if (state == goalState) {
                reachedGoal = true;
                break;
            }
            double estimate = heuristic(map, state, goal, rockets);
            if (estimate < bestHeuristic) {
                bestHeuristic = estimate;
                bestState = state;
            }
            expand(map, state, goal, rockets, cost, previous, closed, open);
        }

        return buildRoute(map, reachedGoal ? goalState : bestState, startState, previous, start.getY(),
                reachedGoal);
    }

    private static void expand(CoarseAirMap map, int state, BlockPos goal, boolean rockets,
                                double[] cost, int[] previous, boolean[] closed,
                                PriorityQueue<Candidate> open) {
        int chunkX = stateChunkX(map, state);
        int chunkZ = stateChunkZ(map, state);
        int band = stateBand(state);
        int bottom = map.bandBottom(chunkX, chunkZ, band);
        int top = map.bandTop(chunkX, chunkZ, band);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nextX = chunkX + dx;
                int nextZ = chunkZ + dz;
                if (!map.containsChunk(nextX, nextZ) || map.blocked(nextX, nextZ)) {
                    continue;
                }
                double horizontal = Math.sqrt(dx * dx + dz * dz) * CELL_BLOCKS;
                for (int nextBand = 0; nextBand < map.stateBands(nextX, nextZ); nextBand++) {
                    int nextBottom = map.bandBottom(nextX, nextZ, nextBand);
                    int nextTop = map.bandTop(nextX, nextZ, nextBand);
                    // 帯どうしが重なっていれば高さを変えずに移れる。離れていれば、その隙間ぶんの
                    // 昇降が要る——離れすぎているものは繋がっている根拠が無いので辺を作らない
                    int vertical = verticalGap(bottom, top, nextBottom, nextTop);
                    if (Math.abs(vertical) > BAND_LINK_GAP_BLOCKS) {
                        continue;
                    }
                    double step = FlightCosts.segmentTicks(horizontal, vertical, rockets);
                    if (map.unknown(nextX, nextZ)) {
                        step *= UNKNOWN_MULTIPLIER;
                    }
                    int nextState = stateIndex(map, nextX, nextZ, nextBand);
                    if (closed[nextState]) {
                        continue;
                    }
                    double tentative = cost[state] + step;
                    if (tentative >= cost[nextState]) {
                        continue;
                    }
                    cost[nextState] = tentative;
                    previous[nextState] = state;
                    open.add(new Candidate(nextState,
                            tentative + heuristic(map, nextState, goal, rockets)));
                }
            }
        }
    }

    /**
     * 2つの高度帯のあいだの符号付き最小移動量。重なっていれば0（高さを変えずに移れる）。
     * 正なら登り、負なら下り。
     */
    private static int verticalGap(int bottom, int top, int nextBottom, int nextTop) {
        if (nextBottom > top) {
            return nextBottom - top;
        }
        if (nextTop < bottom) {
            return nextTop - bottom;
        }
        return 0;
    }

    private static double heuristic(CoarseAirMap map, int state, BlockPos goal, boolean rockets) {
        int chunkX = stateChunkX(map, state);
        int chunkZ = stateChunkZ(map, state);
        double dx = goal.getX() - (chunkX * CELL_BLOCKS + CELL_BLOCKS / 2.0);
        double dz = goal.getZ() - (chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2.0);
        // 帯の中でゴールのYに最も近い高さから測る。帯は幅を持つので、中心から測ると過大になる
        int from = map.clampToBand(chunkX, chunkZ, stateBand(state), goal.getY());
        return FlightCosts.heuristicTicks(Math.sqrt(dx * dx + dz * dz), goal.getY() - from, rockets);
    }

    private static CoarseRouter.Route buildRoute(CoarseAirMap map, int endState, int startState,
                                                  int[] previous, int startY, boolean reachedGoal) {
        List<Integer> states = new ArrayList<>();
        for (int state = endState; state != -1; state = previous[state]) {
            states.add(state);
            if (state == startState) {
                break;
            }
        }
        Collections.reverse(states);
        if (states.size() <= 1) {
            return new CoarseRouter.Route(List.of(), reachedGoal);
        }

        List<BlockPos> waypoints = new ArrayList<>();
        int lastX = stateChunkX(map, states.get(0));
        int lastZ = stateChunkZ(map, states.get(0));
        // 高さは「1つ前の中間目標の高さを帯へ寄せた値」で決める。帯の中心を使うと、厚い帯で
        // 高度が理由もなく跳ね、案内が上下に振れて見える
        int lastY = startY;
        for (int i = 1; i < states.size(); i++) {
            int state = states.get(i);
            int chunkX = stateChunkX(map, state);
            int chunkZ = stateChunkZ(map, state);
            int y = map.clampToBand(chunkX, chunkZ, stateBand(state), lastY);
            boolean last = i == states.size() - 1;
            int spanX = Math.abs(chunkX - lastX);
            int spanZ = Math.abs(chunkZ - lastZ);
            if (last || Math.max(spanX, spanZ) >= WAYPOINT_SPACING_CELLS
                    || Math.abs(y - lastY) >= WAYPOINT_VERTICAL_SPACING_BLOCKS) {
                waypoints.add(new BlockPos(chunkX * CELL_BLOCKS + CELL_BLOCKS / 2, y,
                        chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2));
                lastX = chunkX;
                lastZ = chunkZ;
            }
            lastY = y;
        }
        return new CoarseRouter.Route(List.copyOf(waypoints), reachedGoal);
    }

    private static int stateIndex(CoarseAirMap map, int chunkX, int chunkZ, int band) {
        int localX = chunkX - map.minChunkX();
        int localZ = chunkZ - map.minChunkZ();
        return (localZ * map.chunksX() + localX) * CoarseAirMap.MAX_BANDS + band;
    }

    private static int stateChunkX(CoarseAirMap map, int state) {
        return map.minChunkX() + (state / CoarseAirMap.MAX_BANDS) % map.chunksX();
    }

    private static int stateChunkZ(CoarseAirMap map, int state) {
        return map.minChunkZ() + (state / CoarseAirMap.MAX_BANDS) / map.chunksX();
    }

    private static int stateBand(int state) {
        return state % CoarseAirMap.MAX_BANDS;
    }

    private record Candidate(int state, double estimatedTotal) implements Comparable<Candidate> {

        @Override
        public int compareTo(Candidate other) {
            return Double.compare(estimatedTotal, other.estimatedTotal);
        }
    }
}
