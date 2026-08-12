package net.prason.xaeronav.pathfinding.coarse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * {@link CoarseMap}の上で、目的地までのおおまかな道筋を1チャンク単位で引く。
 *
 * <p>結果は経路そのものではなく<b>中間目標の列</b>として使う。ここで決めるのは「海をどちら回りで
 * 避けるか」「どの谷を通るか」という大局だけで、実際に辿る経路は読み込み済みチャンクを見る詳細探索が
 * 中間目標ごとに引き直す。粗い側が1マス単位の通行可否を持たない以上、ここで出した線をそのまま
 * 歩けるとは限らない。
 *
 * <p>コストは詳細探索と同じtick単位で見積もる。単位を揃えておかないと、「粗い側では最短なのに
 * 詳細側では明らかな遠回り」という食い違いが起きたときに、どちらの見積もりが外れているのか
 * 突き合わせられない。
 */
public final class CoarseRouter {

    /** 1セルの一辺（ブロック）。{@link CoarseMap}が1チャンク単位なので16。 */
    private static final int CELL_BLOCKS = 16;

    private static final double STRAIGHT_COST = CELL_BLOCKS * ActionCosts.SPRINT_ONE_BLOCK;
    private static final double DIAGONAL_COST = STRAIGHT_COST * ActionCosts.DIAGONAL_DISTANCE;

    /** 水面を渡る倍率。実測の徒歩と遊泳の速度比（4.317 / 2.2）そのもの。 */
    private static final double WATER_MULTIPLIER =
            ActionCosts.WALK_ONE_IN_WATER / ActionCosts.WALK_ONE_BLOCK;

    /**
     * 地図に無いセルを通る倍率。通れないと決めつけると、未訪問の土地を挟む目的地へは
     * 一切ルートが出ない。逆に陸と同じ扱いにすると、既知の迂回路を捨てて未知の直線へ突っ込む。
     * 「分かっている道が多少遠回りでも、そちらを選ぶ」程度に重くしておく。
     */
    private static final double UNKNOWN_MULTIPLIER = 1.6;

    /**
     * 高低差1ブロックあたりの追加コスト。登りも下りも同じだけ掛ける。
     * 粗いセルでは崖と緩斜面を区別できないので、どちらつかずの中間の重みにしておき、
     * 「同じくらいの距離なら平坦な方」を選ばせるためだけに使う。
     */
    private static final double HEIGHT_COST_PER_BLOCK = ActionCosts.JUMP_ONE_BLOCK;

    /**
     * 中間目標を置く間隔（セル＝チャンク）。詳細探索が一度に解ける距離より短くしないと、
     * 次の目標が読み込み済みチャンクの外に出てしまう。
     */
    private static final int WAYPOINT_SPACING_CELLS = 6;

    /**
     * ゴールに届かなかったときの到達点候補を、{@code h + g / 係数}という複数の指標で同時に追う。
     * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder}と同じ考え方・同じ係数列。
     * ヒューリスティック単独（＝ゴールに一番近いセル）で選ぶと、海に突き出した半島の先端のような
     * 「辿り着くのに莫大なコストを払った行き止まり」を掴んでしまう。係数が小さいほど
     * 実際に進んだ距離を重く見る。
     */
    private static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    /**
     * これ未満しか進めない暫定ルートは提示する価値がない（セル＝チャンク）。
     * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder#MIN_DIST_PATH}と同じ役割だが、
     * 単位がブロックではなくチャンクなのでこちらは1セルにしておく。
     */
    private static final double MIN_DIST_CELLS = 1.0;

    private CoarseRouter() {
    }

    /**
     * 中間目標の列。{@code reachedGoal}がfalseなら、目的地まで届かないまま
     * 「その時点で最もゴールに近づけた地点」で終わっている。
     */
    public record Route(List<BlockPos> waypoints, boolean reachedGoal) {

        public boolean isEmpty() {
            return waypoints.isEmpty();
        }
    }

    public static Route findRoute(CoarseMap map, BlockPos start, BlockPos goal) {
        int startX = start.getX() >> 4;
        int startZ = start.getZ() >> 4;
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        if (!map.containsChunk(startX, startZ) || !map.containsChunk(goalX, goalZ)) {
            return new Route(List.of(), false);
        }

        int cells = map.chunksX() * map.chunksZ();
        double[] cost = new double[cells];
        int[] previous = new int[cells];
        boolean[] closed = new boolean[cells];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);

        int startIndex = index(map, startX, startZ);
        int goalIndex = index(map, goalX, goalZ);
        cost[startIndex] = 0.0;

        PriorityQueue<Candidate> open =
                new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimatedTotal));
        open.add(new Candidate(startIndex, heuristic(map, startX, startZ, goalX, goalZ)));

        int[] bestSoFar = new int[COEFFICIENTS.length];
        double[] bestHeuristic = new double[COEFFICIENTS.length];
        Arrays.fill(bestSoFar, startIndex);
        Arrays.fill(bestHeuristic, heuristic(map, startX, startZ, goalX, goalZ));

        while (!open.isEmpty()) {
            Candidate current = open.poll();
            // decrease-keyの代わりに同じセルを複数回積むので、古い方はここで捨てる
            if (closed[current.index()]) {
                continue;
            }
            closed[current.index()] = true;
            if (current.index() == goalIndex) {
                return buildRoute(map, previous, goalIndex, startIndex, true);
            }

            int x = cellX(map, current.index());
            int z = cellZ(map, current.index());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    relax(map, cost, previous, closed, open, x, z, dx, dz, goalX, goalZ,
                            bestSoFar, bestHeuristic);
                }
            }
        }

        return buildRoute(map, previous, selectFallback(map, bestSoFar, startIndex), startIndex, false);
    }

    private static void relax(CoarseMap map, double[] cost, int[] previous, boolean[] closed,
                              PriorityQueue<Candidate> open, int x, int z, int dx, int dz,
                              int goalX, int goalZ, int[] bestSoFar, double[] bestHeuristic) {
        int nextX = x + dx;
        int nextZ = z + dz;
        if (!map.containsChunk(nextX, nextZ)) {
            return;
        }
        int nextIndex = index(map, nextX, nextZ);
        if (closed[nextIndex]) {
            return;
        }
        double step = stepCost(map, x, z, nextX, nextZ, dx != 0 && dz != 0);
        if (Double.isInfinite(step)) {
            return;
        }
        double tentative = cost[index(map, x, z)] + step;
        if (tentative >= cost[nextIndex]) {
            return;
        }
        cost[nextIndex] = tentative;
        previous[nextIndex] = index(map, x, z);
        double remaining = heuristic(map, nextX, nextZ, goalX, goalZ);
        open.add(new Candidate(nextIndex, tentative + remaining));

        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double candidateHeuristic = remaining + tentative / COEFFICIENTS[i];
            if (candidateHeuristic < bestHeuristic[i]) {
                bestHeuristic[i] = candidateHeuristic;
                bestSoFar[i] = nextIndex;
            }
        }
    }

    /**
     * ゴールに届かなかったときの到達点を選ぶ。係数の小さい（＝実際に進んだ距離を重く見る）ものから順に、
     * 始点から{@link #MIN_DIST_CELLS}以上離れている候補を採用する。どれも届かない場合は始点自身を返し、
     * 空のルート＝「提示できるルートなし」として扱う。
     */
    private static int selectFallback(CoarseMap map, int[] bestSoFar, int startIndex) {
        int startX = cellX(map, startIndex);
        int startZ = cellZ(map, startIndex);
        double thresholdSquared = MIN_DIST_CELLS * MIN_DIST_CELLS;
        for (int candidate : bestSoFar) {
            double dx = cellX(map, candidate) - startX;
            double dz = cellZ(map, candidate) - startZ;
            if (dx * dx + dz * dz > thresholdSquared) {
                return candidate;
            }
        }
        return startIndex;
    }

    private static double stepCost(CoarseMap map, int fromX, int fromZ, int toX, int toZ, boolean diagonal) {
        byte kind = map.kindAtChunk(toX, toZ);
        if (kind == CoarseMap.LAVA) {
            return ActionCosts.INFEASIBLE;
        }
        double base = diagonal ? DIAGONAL_COST : STRAIGHT_COST;
        double multiplier = switch (kind) {
            case CoarseMap.WATER -> WATER_MULTIPLIER;
            case CoarseMap.NO_DATA -> UNKNOWN_MULTIPLIER;
            default -> 1.0;
        };

        double heightPenalty = 0.0;
        short fromHeight = map.heightAtChunk(fromX, fromZ);
        short toHeight = map.heightAtChunk(toX, toZ);
        // 片方でも高さが分からなければ段差は測れない。分からないことを段差0として扱うと、
        // 未知の領域が「平坦な近道」に見えてしまう
        if (fromHeight != CoarseMap.UNKNOWN_HEIGHT && toHeight != CoarseMap.UNKNOWN_HEIGHT) {
            heightPenalty = Math.abs(toHeight - fromHeight) * HEIGHT_COST_PER_BLOCK;
        }
        return base * multiplier + heightPenalty;
    }

    /**
     * 残りコストの下限。平坦な陸を最短で進んだ場合の値で、水も未知も段差もこれを下回らない。
     */
    private static double heuristic(CoarseMap map, int x, int z, int goalX, int goalZ) {
        int dx = Math.abs(goalX - x);
        int dz = Math.abs(goalZ - z);
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return diagonal * DIAGONAL_COST + straight * STRAIGHT_COST;
    }

    /**
     * 経路を中間目標へ間引く。全セルを返すと詳細探索が数チャンクごとに呼ばれることになり、
     * 粗い線をなぞるだけの案内になってしまう。
     */
    private static Route buildRoute(CoarseMap map, int[] previous, int endIndex, int startIndex,
                                    boolean reachedGoal) {
        List<Integer> cells = new ArrayList<>();
        for (int cursor = endIndex; cursor != -1; cursor = previous[cursor]) {
            cells.add(cursor);
            if (cursor == startIndex) {
                break;
            }
        }
        Collections.reverse(cells);
        if (cells.size() <= 1) {
            return new Route(List.of(), reachedGoal);
        }

        List<BlockPos> waypoints = new ArrayList<>();
        int lastX = cellX(map, cells.get(0));
        int lastZ = cellZ(map, cells.get(0));
        for (int i = 1; i < cells.size(); i++) {
            int cell = cells.get(i);
            int x = cellX(map, cell);
            int z = cellZ(map, cell);
            boolean last = i == cells.size() - 1;
            int spanX = Math.abs(x - lastX);
            int spanZ = Math.abs(z - lastZ);
            if (last || Math.max(spanX, spanZ) >= WAYPOINT_SPACING_CELLS) {
                waypoints.add(toBlockPos(map, x, z));
                lastX = x;
                lastZ = z;
            }
        }
        return new Route(List.copyOf(waypoints), reachedGoal);
    }

    /** セルの中心。高さが分からないセルは、詳細探索が始点の高さから解き直せるよう0にしておく。 */
    private static BlockPos toBlockPos(CoarseMap map, int chunkX, int chunkZ) {
        short height = map.heightAtChunk(chunkX, chunkZ);
        return new BlockPos(chunkX * CELL_BLOCKS + CELL_BLOCKS / 2,
                height == CoarseMap.UNKNOWN_HEIGHT ? 0 : height,
                chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2);
    }

    private static int index(CoarseMap map, int chunkX, int chunkZ) {
        return (chunkZ - map.minChunkZ()) * map.chunksX() + (chunkX - map.minChunkX());
    }

    private static int cellX(CoarseMap map, int index) {
        return map.minChunkX() + index % map.chunksX();
    }

    private static int cellZ(CoarseMap map, int index) {
        return map.minChunkZ() + index / map.chunksX();
    }

    private record Candidate(int index, double estimatedTotal) {
    }
}
