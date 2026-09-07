package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>ネザーの長距離を、ユーザーが「ルートがおかしい」と報告した実地形そのもので測る。</b>
 * 地形は実機の保存データ（{@code x -540..-29 / z 370..881}、512ブロック四方）で、ユーザーが
 * 立っていた{@code (-447, 74, 525)}と目的地{@code (-259, 64, 379)}を含む。
 *
 * <p>ネザーの長距離はどの番人も見ていなかった——{@code PathOptimalityTest}は40〜90ブロックの
 * <b>1回のA*</b>、{@code NetherLavaSeaTest}は単発の深い探索、{@code LongRouteOptimalityTest}は
 * {@code overworld_wide}だけ。遠回りの大半は<b>組み立て方</b>から出るので、組み立てを
 * 再現しないと発生源が測れない。
 *
 * <p><b>{@code ProgressiveWalk}は使わない。</b>あちらの区間は「目的地への直線上の点」を狙う
 * （{@code PathfindingState#goalOrPointToward}に当たる道）。実機が長距離で通るのは
 * <b>層1の中間目標を狙う</b>道（{@code selectDetailTarget}→{@code reachableWaypointTarget}）で、
 * 溶岩の海のあるネザーでは両者の結果がまるで違う——直線上の点は岩の中や溶岩の上に落ちるので、
 * あちらで測ると「経路が返らない」が量産され、実機の症状と区別できない。
 */
@Tag("slow")
class NetherWideRouteTest {

    private static final String TERRAIN = "/nether_wide.txt.gz";

    /** 種を固定する理由は{@link TerrainFixture#randomRoutes}に書いてある。 */
    private static final long SEED = 20260907L;

    private static final int ROUTES = 8;
    private static final int MIN_ROUTE_BLOCKS = 150;
    private static final int MAX_ROUTE_BLOCKS = 400;

    /**
     * 経路の両端を解決する高さの基準。
     *
     * <p><b>{@code TerrainFixture#standableY}をそのまま使ってはいけない。</b>あれは列のいちばん上を
     * 返すので、岩盤天井(y123〜127)を含むフィクスチャでは<b>天井の上</b>が返る。かといって
     * 「天井の下でいちばん高い床」も実態と合わない——天井際の孤立した棚に乗ってしまい、
     * どこにも繋がっていない2点ばかりになる。ユーザーが実際に立っていた高さに近い床を選ぶ。
     */
    private static final int TYPICAL_WALKING_Y = 74;

    /** 床を探す上限。岩盤天井(123〜127)の下。 */
    private static final int UNDER_THE_CEILING = 118;

    /** ユーザーが症状を報告したときに立っていた場所と、指定した目的地。 */
    private static final BlockPos REPORTED_FROM = new BlockPos(-447, 74, 525);
    private static final BlockPos REPORTED_TO = new BlockPos(-259, 64, 379);

    /** 区間ごとの探索の予算。実機の既定（10万ノード・2秒）。 */
    private static final SearchLimits LEG_LIMITS =
            new SearchLimits(100_000, 2_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * 届かなかった区間を投げ直すときの予算。実機の{@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}
     * （8倍）・{@code DEEP_SEARCH_MAX_MILLIS}（30秒）と同じ。
     */
    private static final SearchLimits DEEP_LEG_LIMITS =
            new SearchLimits(800_000, 16_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * 1つの中間目標へ投げ直す回数の上限。
     *
     * <p><b>区間が届かなくても、そこまで引けたぶんは案内になる。</b>実機は末端から継ぎ足す
     * （{@code PathfindingState#extendPath}）ので、打ち切られた経路の先から次を投げる。
     * ここで「未到達＝行き詰まり」にすると実機より悲観的な測定になる。
     */
    private static final int LEG_ATTEMPTS = 4;

    /** 中間目標はチャンク解像度なので、実機と同じく領域ゴールとして狙う。 */
    private static final int LEG_GOAL_RADIUS = 16;

    /** 中間目標のYと実地形の床がこれ以上離れていたら、そこへは降りられない。 */
    private static final int STANDABLE_TOLERANCE_BLOCKS = 8;

    /**
     * <b>これは目標値ではなく、いまの実測値に置いた歯止め（ラチェット）。</b>実測は
     * 平均2.964倍・最悪3.345倍（8本中5本が到達、ユーザー報告の座標は2.941倍）。
     * 同じ測り方で現世（{@code LongRouteOptimalityTest}）は1.046倍なので、
     * <b>ネザーの長距離は現世の3倍近く遠回りしている</b>——これがユーザーの言う
     * 「ルートがおかしい」の正体で、直す対象そのもの。悪化だけは即座に捕まえたいので線を置く。
     */
    private static final double MEAN_LIMIT = 3.10;

    /** 1本でも破滅的なら落とす線。実測の最悪は3.345倍。 */
    private static final double WORST_LIMIT = 3.50;

    /**
     * 「基準は繋がっているのに層1の中間目標を辿ると行き詰まる」経路の許容数。
     * 実測1本（{@code -474,69,629→-271,73,482}、基準1836tick、7区間中5区間で止まる）。
     * <b>0にするのが正しい</b>——ここが1なのは、いまそういう経路が実在するという記録。
     */
    private static final int ALLOWED_DEAD_ENDS = 1;

    private static FakeCells terrain() throws IOException {
        // 実機の既定に合わせる（maxBridgeRunBlocks/maxVoidBridgeRunBlocks=96、落下許容6）
        return TerrainFixture.load(TERRAIN, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static boolean standableAt(CellSource cells, int x, int y, int z) {
        return CellData.standable(cells.cell(x, y - 1, z))
                && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z));
    }

    /** {@link #TYPICAL_WALKING_Y}にいちばん近い「立てるY」。無ければ{@link Integer#MIN_VALUE}。 */
    private static int walkableY(CellSource cells, SearchBounds bounds, int x, int z) {
        int floor = bounds.minY() + 1;
        for (int offset = 0; offset <= UNDER_THE_CEILING; offset++) {
            int up = TYPICAL_WALKING_Y + offset;
            if (up <= UNDER_THE_CEILING && standableAt(cells, x, up, z)) {
                return up;
            }
            int down = TYPICAL_WALKING_Y - offset;
            if (down > floor && standableAt(cells, x, down, z)) {
                return down;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static BlockPos onGround(FakeCells cells, int x, int z) {
        int y = walkableY(cells, cells.bounds(), x, z);
        return y == Integer.MIN_VALUE ? null : new BlockPos(x, y, z);
    }

    private static List<BlockPos[]> routes(FakeCells cells) {
        SearchBounds bounds = cells.bounds();
        Random random = new Random(SEED);
        List<BlockPos[]> routes = new ArrayList<>();
        routes.add(new BlockPos[] {onGround(cells, REPORTED_FROM.getX(), REPORTED_FROM.getZ()),
                onGround(cells, REPORTED_TO.getX(), REPORTED_TO.getZ())});
        int attempts = 0;
        while (routes.size() < ROUTES && attempts++ < 4000) {
            int x = bounds.minX() + 24 + random.nextInt(bounds.maxX() - bounds.minX() - 48);
            int z = bounds.minZ() + 24 + random.nextInt(bounds.maxZ() - bounds.minZ() - 48);
            BlockPos start = onGround(cells, x, z);
            if (start == null) {
                continue;
            }
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int distance = MIN_ROUTE_BLOCKS + random.nextInt(MAX_ROUTE_BLOCKS - MIN_ROUTE_BLOCKS + 1);
            int goalX = start.getX() + (int) Math.round(distance * Math.cos(angle));
            int goalZ = start.getZ() + (int) Math.round(distance * Math.sin(angle));
            if (!cells.isInBounds(goalX, TYPICAL_WALKING_Y, goalZ)) {
                continue;
            }
            BlockPos goal = onGround(cells, goalX, goalZ);
            if (goal != null) {
                routes.add(new BlockPos[] {start, goal});
            }
        }
        return routes;
    }

    /** 実機（{@code PathfindingState#computeCoarseRoute}）と同じ梯子。 */
    private record Attempt(CoarseRouter.BridgePolicy reachedWith, CoarseRouter.Route route) {

        String describe() {
            return (reachedWith == null ? "未到達" : reachedWith.toString())
                    + "/中間目標" + route.waypoints().size() + "個";
        }
    }

    private static Attempt ladder(CoarseMap map, BlockPos start, BlockPos goal) {
        CoarseRouter.Route furthest = null;
        for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
            CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false, policy);
            if (route.reachedGoal()) {
                return new Attempt(policy, route);
            }
            if (furthest == null || route.waypoints().size() > furthest.waypoints().size()) {
                furthest = route;
            }
        }
        return new Attempt(null, furthest);
    }

    /** その中間目標のYの近くに、実地形で立てる場所があるか（セル全体で見る）。 */
    private static boolean standableThere(FakeCells terrain, BlockPos waypoint) {
        int baseX = (waypoint.getX() >> 4) << 4;
        int baseZ = (waypoint.getZ() >> 4) << 4;
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                for (int y = waypoint.getY() - STANDABLE_TOLERANCE_BLOCKS;
                        y <= waypoint.getY() + STANDABLE_TOLERANCE_BLOCKS; y++) {
                    if (terrain.isInBounds(x, y, z) && standableAt(terrain, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int unstandableWaypoints(FakeCells terrain, CoarseRouter.Route route) {
        int bad = 0;
        for (BlockPos waypoint : route.waypoints()) {
            if (!standableThere(terrain, waypoint)) {
                bad++;
            }
        }
        return bad;
    }

    /** 中間目標を順に辿って組み立てた経路。実機の区間分割・継ぎ足しと同じ形。 */
    private record Walk(double cost, int steps, int reachedLegs, boolean arrived) {
    }

    private static Walk follow(FakeCells terrain, BlockPos start, CoarseRouter.Route route)
            throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double cost = 0;
        int steps = 0;
        int reached = 0;
        for (BlockPos waypoint : route.waypoints()) {
            boolean arrived = false;
            for (int attempt = 0; attempt < LEG_ATTEMPTS && !arrived; attempt++) {
                SearchLimits limits = attempt == 0 ? LEG_LIMITS : DEEP_LEG_LIMITS;
                PathResult result =
                        executor.submit(terrain, from, waypoint, limits, true, LEG_GOAL_RADIUS).get();
                if (result.steps().isEmpty()) {
                    break;
                }
                for (PathStep step : result.steps()) {
                    cost += step.cost();
                }
                steps += result.steps().size();
                from = result.steps().get(result.steps().size() - 1).pos();
                arrived = result.complete();
            }
            if (!arrived) {
                return new Walk(cost, steps, reached, false);
            }
            reached++;
        }
        return new Walk(cost, steps, reached, route.reachedGoal());
    }

    @Test
    void longNetherRoutesFollowTheirWaypointsAndAreNotWildlyLonger() throws Exception {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Double> ratios = new ArrayList<>();
        List<String> deadEnds = new ArrayList<>();

        for (BlockPos[] route : routes(cells)) {
            BlockPos start = route[0];
            BlockPos goal = route[1];
            String name = start.toShortString() + "→" + goal.toShortString();
            double best = ProgressiveWalk.fullVisibilityBest(cells, start, goal);
            CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), () -> false);
            Attempt attempt = ladder(map, start, goal);
            int unstandable = unstandableWaypoints(cells, attempt.route());
            Walk walk = follow(cells, start, attempt.route());
            report.add(String.format(Locale.ROOT,
                    "%3.0fブロック 基準%s 層1=%s 立てない中間目標%d個 到達区間%d/%d 経路%s %s",
                    ProgressiveWalk.horizontal(start, goal),
                    Double.isFinite(best) ? String.format(Locale.ROOT, "%6.0f", best) : "解けず",
                    attempt.describe(), unstandable, walk.reachedLegs(),
                    attempt.route().waypoints().size(),
                    !walk.arrived() ? "  未到達"
                            : Double.isFinite(best)
                                    ? String.format(Locale.ROOT, "%6.0f(%.3f倍)", walk.cost(),
                                            walk.cost() / best)
                                    : String.format(Locale.ROOT, "%6.0f(基準無し)", walk.cost()),
                    name));

            if (!Double.isFinite(best)) {
                // 基準（全視界・重み1.0・ガイド無しの1回の探索）が予算内で解けなかった2点。
                // <b>「繋がっていない」の証明ではない</b>——実際、ここで基準が解けなかった経路の
                // 1本は層1の中間目標を辿ると到達できている。比率が出せないので測定から外すだけ
                continue;
            }
            if (unstandable > 0) {
                failures.add(name + ": 層1が実地形で立てない中間目標を" + unstandable + "個並べた");
            }
            if (!walk.arrived()) {
                deadEnds.add(name + ": 基準は" + Math.round(best)
                        + "tickで繋がっているのに、層1の中間目標を辿ると"
                        + walk.reachedLegs() + "/" + attempt.route().waypoints().size()
                        + "区間で行き詰まる (" + attempt.describe() + ")");
                continue;
            }
            ratios.add(walk.cost() / best);
        }

        report.addAll(deadEnds);
        if (deadEnds.size() > ALLOWED_DEAD_ENDS) {
            failures.add("行き詰まる経路が" + deadEnds.size() + "本ある（許容" + ALLOWED_DEAD_ENDS + "本）");
        }
        report.add(check(ratios, failures));
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(), String.join("\n", failures) + "\n" + String.join("\n", report));
    }

    private static String check(List<Double> ratios, List<String> failures) {
        if (ratios.isEmpty()) {
            failures.add("測れた経路が1本も無い");
            return "測れた経路が無い";
        }
        double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double worst = ratios.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (mean > MEAN_LIMIT) {
            failures.add("ネザーの長距離が全体に遠回りになっている "
                    + String.format(Locale.ROOT, "%.3f倍", mean));
        }
        if (worst > WORST_LIMIT) {
            failures.add("破滅的に遠回りな長距離経路がある " + String.format(Locale.ROOT, "%.3f倍", worst));
        }
        return String.format(Locale.ROOT, "%d本 平均%.3f倍 最悪%.3f倍", ratios.size(), mean, worst);
    }
}
