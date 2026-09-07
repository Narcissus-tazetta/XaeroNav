package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.WindowedCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>ネザーの長距離が基準の約3倍になる（{@link NetherWideRouteTest}）のは、どの層のせいか。</b>
 * 1本の経路について、実機の探索を要素ごとに剥がして測る:
 *
 * <ol>
 * <li><b>基準</b> — 全視界・重み1.0・ガイド無しの1回の探索（これが1.000倍）</li>
 * <li><b>重みだけ</b> — ガイド無し・重み{@value AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}。
 *     重み付きA\*の貪欲さの取り分</li>
 * <li><b>ガイドだけ</b> — 層1のcost-to-goガイドあり・重み1.0。ガイドが下限を破っていれば、
 *     重み1.0でも最適から外れる</li>
 * <li><b>重み＋ガイド</b> — 実機の1区間と同じ設定を、区間に切らずに通しで</li>
 * <li><b>実機の組み立て</b> — 中間目標ごとに区間へ切る（{@link NetherWideRouteTest}の測定）</li>
 * </ol>
 *
 * <p>あわせて<b>ガイドが最適経路の各点で残りコストを超えていないか</b>（下限違反）も測る。
 * {@code GuideAdmissibilityTest}はネザーを荒地224ブロック四方・60〜160ブロックでしか見ておらず、
 * 溶岩の海と長距離が入っていない。
 */
@Tag("slow")
class NetherDetourBreakdownTest {

    private static final String TERRAIN = "/nether_wide.txt.gz";

    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;
    private static final long TIME_LIMIT_MILLIS = 120_000;

    /**
     * {@link NetherWideRouteTest}で基準が解けた4本。座標をそのまま持つのは、乱数の種や
     * 両端の解決規則を変えても<b>同じ経路を測り続けられる</b>ようにするため。
     */
    private static List<BlockPos[]> routes() {
        return List.of(
                new BlockPos[] {new BlockPos(-447, 74, 525), new BlockPos(-259, 65, 379)},
                new BlockPos[] {new BlockPos(-505, 71, 836), new BlockPos(-538, 67, 496)},
                new BlockPos[] {new BlockPos(-317, 44, 567), new BlockPos(-523, 66, 465)},
                new BlockPos[] {new BlockPos(-474, 69, 629), new BlockPos(-271, 73, 482)});
    }

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load(TERRAIN, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static PathResult solve(FakeCells cells, BlockPos start, BlockPos goal,
                                     CostToGo guide, double weight) {
        return new AStarPathfinder(cells,
                new SearchLimits(UNLIMITED_NODE_BUDGET, TIME_LIMIT_MILLIS, weight), guide)
                .search(start, goal, () -> false);
    }

    private static double cost(PathResult result) {
        return result.complete()
                ? result.steps().stream().mapToDouble(PathStep::cost).sum()
                : Double.POSITIVE_INFINITY;
    }

    private static String ratio(double value, double best) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%6.0f(%.3f倍)", value, value / best)
                : "     未到達";
    }

    /** ガイドが最適経路の各点で残りコストを超えていないか。1.0を超えたら下限違反。 */
    private static double worstOverestimate(PathResult best, CostToGo guide, BlockPos start) {
        double remaining = best.steps().stream().mapToDouble(PathStep::cost).sum();
        double worst = 0.0;
        BlockPos at = start;
        for (PathStep step : best.steps()) {
            if (remaining >= 50.0) {
                worst = Math.max(worst, guide.estimate(at.getX(), at.getY(), at.getZ()) / remaining);
            }
            remaining -= step.cost();
            at = step.pos();
        }
        return worst;
    }

    /** 折れ線の長さ（ブロック）。 */
    private static double polylineLength(List<BlockPos> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) {
            length += Math.sqrt(points.get(i).distSqr(points.get(i - 1)));
        }
        return length;
    }

    private static double pathLength(PathResult result) {
        List<BlockPos> points = new ArrayList<>();
        for (PathStep step : result.steps()) {
            points.add(step.pos());
        }
        return polylineLength(points);
    }

    /**
     * <b>区間ごとに「その2点の間としては最適か」を測る。</b>各区間が最適に解けているのに全体が
     * 3倍なら、悪いのは区間の解き方ではなく<b>中間目標の並びそのもの</b>——つまり層1の大局。
     */
    private static String legByLeg(FakeCells cells, BlockPos start, CoarseRouter.Route route)
            throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double walked = 0;
        double optimal = 0;
        double walkedDistance = 0;
        double straightDistance = 0;
        int solved = 0;
        List<String> worst = new ArrayList<>();
        for (BlockPos waypoint : route.waypoints()) {
            PathResult leg = executor
                    .submit(cells, from, waypoint, new SearchLimits(800_000, 16_000,
                            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT), true, LEG_GOAL_RADIUS)
                    .get();
            if (leg.steps().isEmpty() || !leg.complete()) {
                break;
            }
            BlockPos to = leg.steps().get(leg.steps().size() - 1).pos();
            PathResult ideal = solve(cells, from, to, null, 1.0);
            double legCost = leg.steps().stream().mapToDouble(PathStep::cost).sum();
            walkedDistance += pathLength(leg);
            straightDistance += Math.sqrt(from.distSqr(to));
            double idealCost = cost(ideal);
            walked += legCost;
            if (Double.isFinite(idealCost)) {
                optimal += idealCost;
                solved++;
                if (legCost / idealCost > 1.15) {
                    worst.add(String.format(Locale.ROOT, "%s→%s %.2f倍",
                            from.toShortString(), to.toShortString(), legCost / idealCost));
                }
            }
            from = to;
        }
        return String.format(Locale.ROOT,
                "区間ごと%d本 walked%.0f/最適%.0f=%.3f倍 / 実際に歩いた道のり%.0fブロック"
                        + "（中間目標間の直線の合計%.0f＝%.1f倍の大迂回） %s",
                solved, walked, optimal, optimal > 0 ? walked / optimal : 0,
                walkedDistance, straightDistance,
                straightDistance > 0 ? walkedDistance / straightDistance : 0,
                worst.isEmpty() ? "" : "悪い区間: " + String.join(", ", worst));
    }

    /** 中間目標はチャンク解像度なので、実機と同じく領域ゴールとして狙う。 */
    private static final int LEG_GOAL_RADIUS = 16;

    /** 継ぎ足しの回数の上限。実機は歩きながら何度でも継ぎ足すので、行き詰まりの検出用。 */
    private static final int MAX_SEGMENTS = 24;

    /**
     * <b>設計変更案の測定。</b>中間目標を「必ず立ち寄る点」にせず、<b>目的地をそのまま狙って
     * 層1ガイドで方向づけ、予算で打ち切られた部分経路を採って末端から継ぎ足す</b>。
     *
     * <p>いまの実装が中間目標を経由地として扱うのは「一度に解けない距離を区間へ割る」ため。
     * だが測定では、区間ごとに最適でも<b>経由すること自体</b>がコストを3倍にしていた。
     * ガイドは下限を破っていないので、遠い目的地を狙っても方向は正しいはず——という仮説。
     */
    private record GuidedWalk(double cost, boolean arrived, String trace, long nodes) {
    }

    private static GuidedWalk followGuidedToGoal(FakeCells cells, BlockPos start, BlockPos goal,
                                                  CostToGo guide, SearchLimits limits) {
        BlockPos from = start;
        double cost = 0;
        long nodes = 0;
        List<String> trace = new ArrayList<>();
        for (int segment = 0; segment < MAX_SEGMENTS; segment++) {
            PathResult result = new AStarPathfinder(cells, limits, guide).search(from, goal, () -> false);
            nodes += result.expandedNodes();
            if (result.steps().isEmpty()) {
                // 実機と同じエスカレーション（PathfindingState#DEEP_SEARCH_BUDGET_FACTOR）。
                // 中間目標を狙う側にはこれを与えていたので、揃えないと比較にならない
                result = new AStarPathfinder(cells, new SearchLimits(800_000, 16_000,
                        limits.heuristicWeight()), guide).search(from, goal, () -> false);
                nodes += result.expandedNodes();
                trace.add("深い予算へ");
            }
            if (result.steps().isEmpty()) {
                trace.add("0ステップ(" + result.termination() + ")");
                return new GuidedWalk(cost, false, String.join(" ", trace), nodes);
            }
            cost += result.steps().stream().mapToDouble(PathStep::cost).sum();
            BlockPos end = result.steps().get(result.steps().size() - 1).pos();
            trace.add(String.format(Locale.ROOT, "%dステップ→%s(目的地まで%.0f, %s)",
                    result.steps().size(), end.toShortString(), Math.sqrt(end.distSqr(goal)),
                    result.termination()));
            if (result.complete()) {
                return new GuidedWalk(cost, true, String.join(" ", trace), nodes);
            }
            if (end.equals(from)) {
                trace.add("末端が進まない");
                return new GuidedWalk(cost, false, String.join(" ", trace), nodes);
            }
            from = end;
        }
        trace.add("継ぎ足し" + MAX_SEGMENTS + "回で届かず");
        return new GuidedWalk(cost, false, String.join(" ", trace), nodes);
    }

    /**
     * <b>「中間目標の列さえ正しければ組み立ては機能するのか」の確認。</b>最適経路そのものを
     * 中間目標の列に間引いて、実機と同じ区間分割で辿り直す。ここが1.0倍近くになるなら、
     * 直すべきは<b>層1のルート選択だけ</b>で、区間分割・継ぎ足しの仕組みは無罪。
     */
    private static double followOptimalAsWaypoints(FakeCells cells, PathResult best, BlockPos start,
                                                    BlockPos goal) throws Exception {
        List<BlockPos> waypoints = new ArrayList<>();
        BlockPos last = start;
        for (PathStep step : best.steps()) {
            if (Math.sqrt(step.pos().distSqr(last)) >= 64) {
                waypoints.add(step.pos());
                last = step.pos();
            }
        }
        waypoints.add(goal);
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double cost = 0;
        for (BlockPos waypoint : waypoints) {
            PathResult leg = executor.submit(cells, from, waypoint,
                    new SearchLimits(800_000, 16_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT),
                    true, LEG_GOAL_RADIUS).get();
            if (leg.steps().isEmpty() || !leg.complete()) {
                return Double.POSITIVE_INFINITY;
            }
            cost += leg.steps().stream().mapToDouble(PathStep::cost).sum();
            from = leg.steps().get(leg.steps().size() - 1).pos();
        }
        return cost;
    }

    /** 中間目標のセルが層1にどう見えていたか。 */
    private static String waypointKinds(CoarseMap map, CoarseRouter.Route route) {
        List<String> kinds = new ArrayList<>();
        for (BlockPos waypoint : route.waypoints()) {
            int cx = waypoint.getX() >> 4;
            int cz = waypoint.getZ() >> 4;
            int floor = map.nearestFloor(cx, cz, waypoint.getY());
            byte kind = floor < 0 ? CoarseMap.NO_DATA : map.kindAtFloor(cx, cz, floor);
            int span = floor < 0 ? 0
                    : map.maxHeightAtFloor(cx, cz, floor) - map.minHeightAtFloor(cx, cz, floor);
            kinds.add(kindName(kind) + "(起伏" + span + ")");
        }
        return String.join(" ", kinds);
    }

    private static String kindName(byte kind) {
        return switch (kind) {
            case CoarseMap.LAND -> "陸";
            case CoarseMap.WATER -> "水";
            case CoarseMap.LAVA -> "溶岩";
            case CoarseMap.LAVA_MIXED -> "溶岩混";
            case CoarseMap.VOID -> "奈落";
            default -> "不明";
        };
    }

    /**
     * <b>ハイブリッド案。</b>探索のゴールは常に最終目的地（層1ガイドで方向づけ、予算で
     * 打ち切られた部分経路を採って末端から継ぎ足す）。<b>前進しなくなったときだけ</b>、
     * 従来どおり層1の中間目標をゴールにして脱出する。
     *
     * <p>中間目標を「必ず立ち寄る点」にすると3倍になり（本テストの測定）、目的地だけを
     * 狙うと安いが袋小路で止まる（同）。両者の良いところを取る。
     */
    private static GuidedWalk followHybrid(FakeCells cells, BlockPos start, BlockPos goal,
                                            CostToGo guide, SearchLimits limits,
                                            CoarseRouter.Route coarse) throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        List<BlockPos> waypoints = new ArrayList<>(coarse.waypoints());
        BlockPos from = start;
        double cost = 0;
        int rescues = 0;
        for (int segment = 0; segment < MAX_SEGMENTS; segment++) {
            PathResult result = new AStarPathfinder(cells, limits, guide).search(from, goal, () -> false);
            boolean progressed = !result.steps().isEmpty()
                    && !result.steps().get(result.steps().size() - 1).pos().equals(from);
            if (progressed) {
                cost += result.steps().stream().mapToDouble(PathStep::cost).sum();
                from = result.steps().get(result.steps().size() - 1).pos();
                if (result.complete()) {
                    return new GuidedWalk(cost, true, "中間目標への退避" + rescues + "回", 0);
                }
                continue;
            }
            // 前進しなかった。層1の中間目標のうち、まだ先にあるものを1つ選んで脱出する
            BlockPos rescue = null;
            for (BlockPos waypoint : waypoints) {
                if (Math.sqrt(waypoint.distSqr(goal)) < Math.sqrt(from.distSqr(goal))) {
                    rescue = waypoint;
                    break;
                }
            }
            if (rescue == null) {
                return new GuidedWalk(cost, false, "退避先の中間目標が無い（退避" + rescues + "回）", 0);
            }
            waypoints.remove(rescue);
            rescues++;
            PathResult leg = executor.submit(cells, from, rescue,
                    new SearchLimits(800_000, 16_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT),
                    true, LEG_GOAL_RADIUS).get();
            if (leg.steps().isEmpty()) {
                return new GuidedWalk(cost, false, "退避もできない（退避" + rescues + "回）", 0);
            }
            cost += leg.steps().stream().mapToDouble(PathStep::cost).sum();
            from = leg.steps().get(leg.steps().size() - 1).pos();
        }
        return new GuidedWalk(cost, false, "継ぎ足し" + MAX_SEGMENTS + "回で届かず", 0);
    }

    /** 実機の描画距離10チャンク相当。探索の箱はこれで切られる。 */
    private static final int WINDOW_RADIUS = 160;

    /**
     * <b>案3。</b>案2と同じくゴールは最終目的地だが、<b>探索の箱を現在地の周りで切る</b>
     * （実機は{@code SearchBounds.around}が描画距離で切っている）。案2が1本で詰んだのは、
     * 箱を切らずに遠い目的地を狙わせたせいで<b>行き止まりの奥深くまで入り込んだ</b>ため——
     * 箱を切れば、そこから出られなくなる前に縁で最良の点が選ばれる。
     */
    private static GuidedWalk followWindowed(FakeCells cells, BlockPos start, BlockPos goal,
                                              CostToGo guide, SearchLimits limits) {
        BlockPos from = start;
        double cost = 0;
        for (int segment = 0; segment < MAX_SEGMENTS; segment++) {
            PathResult result = new AStarPathfinder(new WindowedCells(cells, from, WINDOW_RADIUS),
                    limits, guide).search(from, goal, () -> false);
            if (result.steps().isEmpty()
                    || result.steps().get(result.steps().size() - 1).pos().equals(from)) {
                return new GuidedWalk(cost, false, "セグメント" + segment + "で前進しない", 0);
            }
            cost += result.steps().stream().mapToDouble(PathStep::cost).sum();
            from = result.steps().get(result.steps().size() - 1).pos();
            if (result.complete()) {
                return new GuidedWalk(cost, true, "セグメント" + (segment + 1) + "本", 0);
            }
        }
        return new GuidedWalk(cost, false, "継ぎ足し" + MAX_SEGMENTS + "回で届かず", 0);
    }

    /**
     * <b>案4。</b>中間目標は使うが、<b>狙う半径を緩める</b>。いまは16ブロック（セルの半幅）で
     * 「そのチャンクへ立ち寄れ」に近い。半径を広げれば「その方角へ進めばよい」に緩む。
     *
     * <p>実装は{@code PathfindingState}が{@code PathfindingExecutor#submit}へ渡す
     * {@code goalRadius}を変えるだけなので、効くなら一番安い直し方。
     */
    private static GuidedWalk followWithRadius(FakeCells cells, BlockPos start, CoarseRouter.Route route,
                                                int radius) throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double cost = 0;
        long nodes = 0;
        for (BlockPos waypoint : route.waypoints()) {
            PathResult leg = executor.submit(cells, from, waypoint,
                    new SearchLimits(800_000, 16_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT),
                    true, radius).get();
            nodes += leg.expandedNodes();
            if (leg.steps().isEmpty() || !leg.complete()) {
                return new GuidedWalk(cost, false, "", nodes);
            }
            cost += leg.steps().stream().mapToDouble(PathStep::cost).sum();
            from = leg.steps().get(leg.steps().size() - 1).pos();
        }
        return new GuidedWalk(cost, true, "", nodes);
    }

    /** 廊下の水平マージン（ブロック）。層2（{@code CorridorLegSolver}）と同じ値。 */
    private static final int CORRIDOR_MARGIN_BLOCKS = 48;

    /**
     * <b>案5（本命）。</b>層1の中間目標は<b>箱の置き場所</b>としてだけ使い、探索のゴールは
     * 常に最終目的地にする。
     *
     * <ul>
     * <li>中間目標に<b>立ち寄らない</b>ので、層1が選んだ「短いが高くつく道」に縛られない</li>
     * <li>箱が中間目標の周りに限られるので、目的地だけを狙ったときのように
     *     <b>行き止まりの奥へ入り込まない</b>（案・案3が1本で詰んだ原因）</li>
     * </ul>
     */
    private static GuidedWalk followCorridor(FakeCells cells, BlockPos start, BlockPos goal,
                                              CostToGo guide, SearchLimits limits,
                                              CoarseRouter.Route coarse) {
        List<BlockPos> waypoints = new ArrayList<>(coarse.waypoints());
        waypoints.add(goal);
        BlockPos from = start;
        double cost = 0;
        int at = 0;
        for (int segment = 0; segment < MAX_SEGMENTS; segment++) {
            // もう通り過ぎた中間目標は飛ばす（目的地に近いものが先）
            while (at < waypoints.size() - 1
                    && waypoints.get(at).distSqr(goal) >= from.distSqr(goal)) {
                at++;
            }
            BlockPos ahead = waypoints.get(at);
            BlockPos center = new BlockPos((from.getX() + ahead.getX()) / 2, from.getY(),
                    (from.getZ() + ahead.getZ()) / 2);
            int radius = Math.max(Math.abs(from.getX() - ahead.getX()),
                    Math.abs(from.getZ() - ahead.getZ())) / 2 + CORRIDOR_MARGIN_BLOCKS;
            PathResult result = new AStarPathfinder(new WindowedCells(cells, center, radius),
                    limits, guide).search(from, goal, () -> false);
            boolean progressed = !result.steps().isEmpty()
                    && !result.steps().get(result.steps().size() - 1).pos().equals(from);
            if (!progressed) {
                if (at < waypoints.size() - 1) {
                    // この箱では進めない。次の中間目標まで箱を伸ばして試す
                    at++;
                    continue;
                }
                return new GuidedWalk(cost, false, "セグメント" + segment + "で前進しない", 0);
            }
            cost += result.steps().stream().mapToDouble(PathStep::cost).sum();
            from = result.steps().get(result.steps().size() - 1).pos();
            if (result.complete()) {
                return new GuidedWalk(cost, true, "セグメント" + (segment + 1) + "本", 0);
            }
        }
        return new GuidedWalk(cost, false, "継ぎ足し" + MAX_SEGMENTS + "回で届かず", 0);
    }

    /**
     * <b>層1が選んだ道のセルと、最適経路が通ったセルを、層1が持っている情報だけで比べる。</b>
     * ここで差が付くなら、その情報をコストへ足せば層1は正しい道を選べる＝根本解決になる。
     * 差が付かないなら、チャンク解像度の地図では原理的に区別できないということ。
     */
    private static String compareCells(CoarseMap map, FakeCells cells, PathResult best,
                                        BlockPos start, CoarseRouter.Route coarse) {
        return "  最適経路のセル: " + cellStats(map, cellsAlong(pathPoints(best, start)))
                + " / 層1が選んだセル: " + cellStats(map, cellsAlong(coarse.waypoints()));
    }

    private static List<BlockPos> pathPoints(PathResult result, BlockPos start) {
        List<BlockPos> points = new ArrayList<>();
        points.add(start);
        for (PathStep step : result.steps()) {
            points.add(step.pos());
        }
        return points;
    }

    /** 点列が通ったセル（重複なし、通った順）。 */
    private static List<BlockPos> cellsAlong(List<BlockPos> points) {
        List<BlockPos> cells = new ArrayList<>();
        for (BlockPos point : points) {
            BlockPos cell = new BlockPos(point.getX() >> 4, point.getY(), point.getZ() >> 4);
            if (cells.isEmpty() || cells.get(cells.size() - 1).getX() != cell.getX()
                    || cells.get(cells.size() - 1).getZ() != cell.getZ()) {
                cells.add(cell);
            }
        }
        return cells;
    }

    private static String cellStats(CoarseMap map, List<BlockPos> cells) {
        int total = 0;
        int floors = 0;
        int span = 0;
        int lava = 0;
        int gapSum = 0;
        for (BlockPos cell : cells) {
            int cx = cell.getX();
            int cz = cell.getZ();
            if (!map.containsChunk(cx, cz)) {
                continue;
            }
            total++;
            int count = map.floorCount(cx, cz);
            floors += count;
            int floor = map.nearestFloor(cx, cz, cell.getY());
            if (floor >= 0) {
                span += map.maxHeightAtFloor(cx, cz, floor) - map.minHeightAtFloor(cx, cz, floor);
                byte kind = map.kindAtFloor(cx, cz, floor);
                if (kind == CoarseMap.LAVA || kind == CoarseMap.LAVA_MIXED) {
                    lava++;
                }
            }
            // 床同士の高さの隔たり（3D迷路の深さ）
            for (int i = 1; i < count; i++) {
                gapSum += map.heightAtFloor(cx, cz, i) - map.heightAtFloor(cx, cz, i - 1);
            }
        }
        if (total == 0) {
            return "セル無し";
        }
        return String.format(Locale.ROOT,
                "%dセル 床%.2f枚/セル 起伏%.1f 床間の隔たり%.1f 溶岩%d%%",
                total, floors / (double) total, span / (double) total, gapSum / (double) total,
                lava * 100 / total);
    }

    @Test
    void showsWhichLayerTheNetherDetourComesFrom() throws Exception {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        int measured = 0;
        double worstGuideRatio = 0.0;
        String worstGuideAt = "";

        for (BlockPos[] route : routes()) {
            BlockPos start = route[0];
            BlockPos goal = route[1];
            PathResult best = solve(cells, start, goal, null, 1.0);
            if (!best.complete()) {
                report.add(start.toShortString() + "→" + goal.toShortString() + ": 基準が解けない");
                continue;
            }
            measured++;
            double bestCost = cost(best);
            CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), () -> false);
            // 実機と同じ作り方（PathfindingExecutor#buildCostToGoGuideと同じ引数）
            CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE);

            double weighted = cost(solve(cells, start, goal, null, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT));
            double guided = cost(solve(cells, start, goal, guide, 1.0));
            double both = cost(solve(cells, start, goal, guide, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT));

            double overestimate = worstOverestimate(best, guide, start);
            if (overestimate > worstGuideRatio) {
                worstGuideRatio = overestimate;
                worstGuideAt = start.toShortString() + "→" + goal.toShortString();
            }

            report.add(String.format(Locale.ROOT,
                    "%s→%s 基準%6.0f 重みだけ%s ガイドだけ%s 重み+ガイド%s ガイドの下限違反%.3f倍",
                    start.toShortString(), goal.toShortString(), bestCost,
                    ratio(weighted, bestCost), ratio(guided, bestCost), ratio(both, bestCost),
                    overestimate));

            // 層1の中間目標の並びそのものが遠回りなのかを見る。最適経路の道のりと、
            // 中間目標を順に結んだ折れ線の長さを比べる（どちらもブロック単位の距離）
            CoarseRouter.Route coarse = null;
            for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
                CoarseRouter.Route candidate = CoarseRouter.findRoute(map, start, goal, false, policy);
                if (candidate.reachedGoal()) {
                    coarse = candidate;
                    break;
                }
            }
            if (coarse == null) {
                report.add("  層1が目的地へ届かない");
                continue;
            }
            List<BlockPos> polyline = new ArrayList<>();
            polyline.add(start);
            polyline.addAll(coarse.waypoints());
            report.add(String.format(Locale.ROOT,
                    "  最適経路の道のり%.0fブロック / 中間目標の折れ線%.0fブロック(%.3f倍) / 直線%.0fブロック",
                    pathLength(best), polylineLength(polyline),
                    polylineLength(polyline) / pathLength(best),
                    Math.sqrt(start.distSqr(goal))));
            report.add("  " + legByLeg(cells, start, coarse));

            // 設計変更案: 中間目標を経由せず、目的地を狙って継ぎ足す（実機の既定予算で）
            SearchLimits liveLimits = new SearchLimits(100_000, 2_000,
                    AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
            report.add("  中間目標のセル: " + waypointKinds(map, coarse));
            report.add(compareCells(map, cells, best, start, coarse));
            double asWaypoints = followOptimalAsWaypoints(cells, best, start, goal);
            report.add("  【対照】最適経路を中間目標に間引いて辿り直す " + ratio(asWaypoints, bestCost));
            GuidedWalk toGoal = followGuidedToGoal(cells, start, goal, guide, liveLimits);
            report.add(String.format(Locale.ROOT, "  【案】目的地を狙って継ぎ足す %s 展開%,d",
                    ratio(toGoal.arrived() ? toGoal.cost() : Double.POSITIVE_INFINITY, bestCost),
                    toGoal.nodes()));
            if (!toGoal.arrived()) {
                report.add("    " + toGoal.trace());
            }
            GuidedWalk corridor = followCorridor(cells, start, goal, guide, liveLimits, coarse);
            report.add("  【案5】中間目標の周りに箱を置き、目的地を狙う "
                    + ratio(corridor.arrived() ? corridor.cost() : Double.POSITIVE_INFINITY, bestCost)
                    + " " + corridor.trace());
            GuidedWalk viaWaypoints = followWithRadius(cells, start, coarse, 16);
            report.add(String.format(Locale.ROOT, "  【現行】中間目標に立ち寄る %s 展開%,d",
                    ratio(viaWaypoints.arrived() ? viaWaypoints.cost() : Double.POSITIVE_INFINITY,
                            bestCost),
                    viaWaypoints.nodes()));
            GuidedWalk windowed = followWindowed(cells, start, goal, guide, liveLimits);
            report.add("  【案3】箱を切って目的地を狙う "
                    + ratio(windowed.arrived() ? windowed.cost() : Double.POSITIVE_INFINITY, bestCost)
                    + " " + windowed.trace());
            GuidedWalk hybrid = followHybrid(cells, start, goal, guide, liveLimits, coarse);
            report.add("  【案2】目的地を狙い、進めないときだけ中間目標へ退避 "
                    + ratio(hybrid.arrived() ? hybrid.cost() : Double.POSITIVE_INFINITY, bestCost)
                    + " " + hybrid.trace());
        }

        report.add(String.format(Locale.ROOT, "ガイドの下限違反の最悪: %.3f倍 %s",
                worstGuideRatio, worstGuideAt));
        System.out.println(String.join("\n", report));
        assertTrue(measured > 0, "1本も測れていない\n" + String.join("\n", report));
    }
}
