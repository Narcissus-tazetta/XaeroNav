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
     * ボートで進むときの水面通過倍率。{@link ActionCosts#PADDLE_ONE_BLOCK}が
     * {@link ActionCosts#WALK_ONE_BLOCK}より小さいため、{@link #WATER_MULTIPLIER}と違い
     * 1未満になる＝水を避けるコストではなく積極的に選ぶ近道になる。
     */
    private static final double BOAT_MULTIPLIER =
            ActionCosts.PADDLE_ONE_BLOCK / ActionCosts.WALK_ONE_BLOCK;

    /**
     * 地図に無いセルを通る倍率。通れないと決めつけると、未訪問の土地を挟む目的地へは
     * 一切ルートが出ない。逆に陸と同じ扱いにすると、既知の迂回路を捨てて未知の直線へ突っ込む。
     * 「分かっている道が多少遠回りでも、そちらを選ぶ」程度に重くしておく。
     */
    private static final double UNKNOWN_MULTIPLIER = 1.6;

    /**
     * 溶岩が混じるセルを通る倍率。他の倍率と違い実測の速度比ではない——溶岩は歩みを遅くするのではなく
     * 迂回を強いるものなので、「チャンク内で溶岩を避けて回り込むぶん実距離が2倍前後になる」という
     * 見積もりに、層1からは安全に抜けられるか分からないぶんの余裕を足した値。
     *
     * <p>ネザーはこの種のセルが常時混じるので、これを通行不能にすると経路が繋がらない。
     * かといって陸と同じにすると溶岩地帯を突っ切る案内になる。「他に道があるならそちら」を選ばせる重み。
     */
    private static final double LAVA_MIXED_MULTIPLIER = 2.5;

    /**
     * {@link LavaPolicy#BRIDGE}で溶岩セルを渡る倍率。層1と層3はコストの単位をtickで揃えてあるので、
     * ここは勘ではなく層3の実コストから導く——1ブロックあたり
     * {@code SPRINT_ONE_BLOCK + PLACE_BLOCK_OVERHEAD_TICKS + LAVA_BRIDGE_PENALTY_TICKS ≒ 35.6}tick、
     * 通常の陸が3.564なので比は約10倍になる。
     */
    private static final double LAVA_BRIDGE_MULTIPLIER =
            (ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS
                    + ActionCosts.LAVA_BRIDGE_PENALTY_TICKS) / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * 高低差1ブロックあたりの追加コスト。登りも下りも同じだけ掛ける。
     * 粗いセルでは崖と緩斜面を区別できないので、どちらつかずの中間の重みにしておき、
     * 「同じくらいの距離なら平坦な方」を選ばせるためだけに使う。
     */
    private static final double HEIGHT_COST_PER_BLOCK = ActionCosts.JUMP_ONE_BLOCK;

    /**
     * セル内の起伏（{@code maxHeight - minHeight}）がこれを超えたら崖とみなす。
     * バニラの{@code SAFE_FALL_DISTANCE}既定値（{@link ActionCosts#SAFE_FALL_BLOCKS}）をそのまま使う。
     * これより緩やかな起伏は、平均高さの差分で表現される通常の坂として扱えば十分。
     */
    private static final int CLIFF_THRESHOLD_BLOCKS = ActionCosts.SAFE_FALL_BLOCKS;

    /**
     * 崖セルへ踏み込む追加コスト。平均高さは周囲と同じでも、セル内部の起伏が大きいチャンクは
     * 「崖の途中に平地が乗っている」可能性が高く、詳細探索が大きく迂回・掘削する羽目になりやすい。
     * 平均だけを見る{@link #HEIGHT_COST_PER_BLOCK}では、山腹の急斜面チャンクと緩斜面チャンクが
     * 同じ扱いになってしまうのを補う。
     */
    private static final double CLIFF_COST_PER_BLOCK = ActionCosts.JUMP_ONE_BLOCK;

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

    /**
     * 溶岩セルの扱い。呼び出し側は{@link #AVOID}から順に試し、届かなかったときだけ緩める
     * （{@code PathfindingState#computeCoarseRoute}のエスカレーション梯子）。
     *
     * <p>層1が溶岩地帯を突っ切ると決めると、そのwaypointへは詳細探索が原理的に到達できない
     * （溶岩の上は歩けない）。段階を分けるのは「大きく迂回してでも溶岩を避ける道」を必ず先に
     * 探させるため——迂回や後戻りはA*が勝手に見つけるので、ここで表現するのは可否だけでよい。
     */
    public enum LavaPolicy {
        /** 溶岩の混じるセルを一切通らない。大回りでも溶岩を避けた道があるならそれを見つける。 */
        AVOID,
        /** 溶岩が混じるセルは通れるが高い。過半数が溶岩のセルは通行不能。 */
        ALLOW,
        /** 過半数が溶岩のセルも橋を架けて渡る前提で通す。最後の手段。 */
        BRIDGE
    }

    public static Route findRoute(CoarseMap map, BlockPos start, BlockPos goal, boolean boatAvailable,
                                   LavaPolicy lavaPolicy) {
        int startX = start.getX() >> 4;
        int startZ = start.getZ() >> 4;
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        if (!map.containsChunk(startX, startZ) || !map.containsChunk(goalX, goalZ)) {
            return new Route(List.of(), false);
        }
        double waterMultiplier = boatAvailable ? BOAT_MULTIPLIER : WATER_MULTIPLIER;

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
        open.add(new Candidate(startIndex, heuristic(map, startX, startZ, goalX, goalZ, waterMultiplier)));

        int[] bestSoFar = new int[COEFFICIENTS.length];
        double[] bestHeuristic = new double[COEFFICIENTS.length];
        Arrays.fill(bestSoFar, startIndex);
        Arrays.fill(bestHeuristic, heuristic(map, startX, startZ, goalX, goalZ, waterMultiplier));

        while (!open.isEmpty()) {
            Candidate current = open.poll();
            // decrease-keyの代わりに同じセルを複数回積むので、古い方はここで捨てる
            if (closed[current.index()]) {
                continue;
            }
            closed[current.index()] = true;
            if (current.index() == goalIndex) {
                return buildRoute(map, previous, goalIndex, startIndex, true, start.getY());
            }

            int x = cellX(map, current.index());
            int z = cellZ(map, current.index());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    relax(map, cost, previous, closed, open, x, z, dx, dz, goalX, goalZ,
                            bestSoFar, bestHeuristic, waterMultiplier, lavaPolicy);
                }
            }
        }

        return buildRoute(map, previous, selectFallback(map, bestSoFar, startIndex), startIndex, false,
                start.getY());
    }

    private static void relax(CoarseMap map, double[] cost, int[] previous, boolean[] closed,
                              PriorityQueue<Candidate> open, int x, int z, int dx, int dz,
                              int goalX, int goalZ, int[] bestSoFar, double[] bestHeuristic,
                              double waterMultiplier, LavaPolicy lavaPolicy) {
        int nextX = x + dx;
        int nextZ = z + dz;
        if (!map.containsChunk(nextX, nextZ)) {
            return;
        }
        int nextIndex = index(map, nextX, nextZ);
        if (closed[nextIndex]) {
            return;
        }
        double step = stepCost(map, x, z, nextX, nextZ, dx != 0 && dz != 0, waterMultiplier, lavaPolicy);
        if (Double.isInfinite(step)) {
            return;
        }
        double tentative = cost[index(map, x, z)] + step;
        if (tentative >= cost[nextIndex]) {
            return;
        }
        cost[nextIndex] = tentative;
        previous[nextIndex] = index(map, x, z);
        double remaining = heuristic(map, nextX, nextZ, goalX, goalZ, waterMultiplier);
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

    private static double stepCost(CoarseMap map, int fromX, int fromZ, int toX, int toZ, boolean diagonal,
                                   double waterMultiplier, LavaPolicy lavaPolicy) {
        byte kind = map.kindAtChunk(toX, toZ);
        double lavaMultiplier = lavaMultiplier(kind, lavaPolicy);
        if (Double.isInfinite(lavaMultiplier)) {
            return ActionCosts.INFEASIBLE;
        }
        double base = diagonal ? DIAGONAL_COST : STRAIGHT_COST;
        double multiplier = switch (kind) {
            case CoarseMap.WATER -> waterMultiplier;
            case CoarseMap.NO_DATA -> UNKNOWN_MULTIPLIER;
            case CoarseMap.LAVA, CoarseMap.LAVA_MIXED -> lavaMultiplier;
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
        return base * multiplier + heightPenalty + cliffPenalty(map, toX, toZ);
    }

    /**
     * 溶岩を含むセルの倍率。溶岩を含まないセルには1.0を返す（呼び出し側が他の倍率を使う）。
     * {@link ActionCosts#INFEASIBLE}なら通行不能。
     */
    private static double lavaMultiplier(byte kind, LavaPolicy lavaPolicy) {
        if (kind == CoarseMap.LAVA) {
            return lavaPolicy == LavaPolicy.BRIDGE ? LAVA_BRIDGE_MULTIPLIER : ActionCosts.INFEASIBLE;
        }
        if (kind == CoarseMap.LAVA_MIXED) {
            return lavaPolicy == LavaPolicy.AVOID ? ActionCosts.INFEASIBLE : LAVA_MIXED_MULTIPLIER;
        }
        return 1.0;
    }

    /** 踏み込み先セルの起伏が大きいときの追加コスト。起伏が分からなければ平坦扱い（0）。 */
    private static double cliffPenalty(CoarseMap map, int chunkX, int chunkZ) {
        short min = map.minHeightAtChunk(chunkX, chunkZ);
        short max = map.maxHeightAtChunk(chunkX, chunkZ);
        if (min == CoarseMap.UNKNOWN_HEIGHT || max == CoarseMap.UNKNOWN_HEIGHT) {
            return 0.0;
        }
        int relief = max - min;
        if (relief <= CLIFF_THRESHOLD_BLOCKS) {
            return 0.0;
        }
        return (relief - CLIFF_THRESHOLD_BLOCKS) * CLIFF_COST_PER_BLOCK;
    }

    /**
     * 残りコストの下限。{@code waterMultiplier}(ボート所持時は{@link #BOAT_MULTIPLIER}<1.0)を
     * 掛けておかないと、経路が丸ごとボート水域だった場合の実コストがこの下限を下回り非許容になる。
     * 陸・未知・段差はどれも倍率が1.0以上なのでこれより安くはならない。
     */
    private static double heuristic(CoarseMap map, int x, int z, int goalX, int goalZ, double waterMultiplier) {
        int dx = Math.abs(goalX - x);
        int dz = Math.abs(goalZ - z);
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        double multiplier = Math.min(1.0, waterMultiplier);
        return (diagonal * DIAGONAL_COST + straight * STRAIGHT_COST) * multiplier;
    }

    /**
     * 経路を中間目標へ間引く。全セルを返すと詳細探索が数チャンクごとに呼ばれることになり、
     * 粗い線をなぞるだけの案内になってしまう。
     */
    private static Route buildRoute(CoarseMap map, int[] previous, int endIndex, int startIndex,
                                    boolean reachedGoal, int startY) {
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
        // 高さが分からないセルのフォールバックは、直前に分かった高さを引き継ぐ（無ければ出発点）。
        // 固定の0だと、ネザーのように地形の主要な高さ帯が0から遠い次元で、詳細探索が
        // 奈落の底へ経路を引こうとしてノード上限を焼き切る
        int fallbackHeight = startY;
        for (int i = 1; i < cells.size(); i++) {
            int cell = cells.get(i);
            int x = cellX(map, cell);
            int z = cellZ(map, cell);
            boolean last = i == cells.size() - 1;
            int spanX = Math.abs(x - lastX);
            int spanZ = Math.abs(z - lastZ);
            if (last || Math.max(spanX, spanZ) >= WAYPOINT_SPACING_CELLS) {
                BlockPos waypoint = toBlockPos(map, x, z, fallbackHeight);
                waypoints.add(waypoint);
                fallbackHeight = waypoint.getY();
                lastX = x;
                lastZ = z;
            }
        }
        return new Route(List.copyOf(waypoints), reachedGoal);
    }

    /** セルの中心。高さが分からないセルは{@code fallbackHeight}を使う。 */
    private static BlockPos toBlockPos(CoarseMap map, int chunkX, int chunkZ, int fallbackHeight) {
        short height = map.heightAtChunk(chunkX, chunkZ);
        return new BlockPos(chunkX * CELL_BLOCKS + CELL_BLOCKS / 2,
                height == CoarseMap.UNKNOWN_HEIGHT ? fallbackHeight : height,
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
