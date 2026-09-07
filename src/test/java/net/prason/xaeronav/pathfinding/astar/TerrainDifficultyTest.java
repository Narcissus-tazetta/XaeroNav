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
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * <b>「中間目標を経由地として使うのをやめる」判断の材料を、地形ごとに実測で並べる。</b>
 *
 * <p>{@code PathfindingState}は<b>天井のある次元でだけ</b>、探索が返るたびに測る
 * 「実コスト ÷ 直線距離を平坦に走ったコスト」が{@code COARSE_ROUTE_DISTRUST_RATIO}(2.0)を
 * 超えたら、中間目標へ立ち寄るのをやめて目的地をそのまま狙う。
 *
 * <p><b>難しさの比だけでは次元を分けられない</b>ことがここで分かる。実機と同じ形
 * （層1の中間目標へ半径16で立ち寄る区間）で測ると:
 *
 * <pre>
 * 地上/広域 1.61  地上/山岳 2.07  地上/海岸 1.62
 * エンド/島渡り 4.61  エンド/実機 1.13  ネザー/広域 2.66
 * </pre>
 *
 * <p>エンドの島渡りがネザーより高い。「目的地を狙う方が単位距離あたり安い」比でも
 * エンド1.70倍 &gt; ネザー1.37倍。<b>それでもエンドで中間目標を捨ててはいけない</b>——
 * あそこの中間目標は「どの島を経由するか」を決めていて、{@code CoarseRouter}の
 * {@code SMALL_ISLAND_PENALTY}がユーザー要望「大きい島を渡りながらのルートにしたい」を
 * 実現している。だから条件を<b>天井のある次元</b>に置いてある。
 *
 * <p>このテストが見張るのは<b>閾値が天井のある次元の中で意味を持つか</b>——ネザーが
 * {@code COARSE_ROUTE_DISTRUST_RATIO}を超えること。他の地形の値は、条件を次元から
 * 難しさへ戻したくなったときのために記録として並べる。
 */
@Tag("slow")
class TerrainDifficultyTest {

    /** {@code PathfindingState#COARSE_ROUTE_DISTRUST_RATIO}と同じ値。 */
    private static final double DISTRUST_RATIO = 2.0;

    /** {@code PathfindingState#DIFFICULTY_MIN_SPAN_BLOCKS}と同じ値。 */
    private static final double MIN_SPAN_BLOCKS = 32.0;

    /** 実機の既定（10万ノード・2秒）。 */
    private static final SearchLimits LIMITS =
            new SearchLimits(100_000, 2_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static final long SEED = 20260907L;

    /** 中間目標はチャンク解像度なので、実機と同じく領域ゴールとして狙う。 */
    private static final int LEG_GOAL_RADIUS = 16;

    private record Terrain(String name, String resource, boolean ceiling, int routes, int min, int max) {
    }

    /** 記録として並べる地形。天井が無いので、いまの条件では切り替えの対象外。 */
    private static List<Terrain> terrains() {
        return List.of(
                new Terrain("地上/広域", "/overworld_wide.txt.gz", false, 6, 150, 400),
                new Terrain("地上/山岳", "/overworld_mountains.txt.gz", false, 6, 60, 160),
                new Terrain("地上/海岸", "/overworld_coast.txt.gz", false, 6, 60, 160),
                new Terrain("エンド/島渡り", "/end_terrain_columns.txt.gz", false, 6, 60, 160),
                new Terrain("エンド/実機", "/end_player_area.txt.gz", false, 6, 40, 120));
    }

    private static FakeCells terrain(String resource, boolean ceiling) throws IOException {
        return TerrainFixture.load(resource, bounds -> {
            FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxFallDamagePoints(6)
                    .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96);
            return ceiling ? cells.openSkyYOverride(bounds.maxY()) : cells;
        });
    }

    /** 実機の描画距離10チャンク相当。{@code SearchBounds.around}はこれで箱を切る。 */
    private static final int WINDOW_RADIUS = 160;

    /**
     * 実機の{@code PathfindingState#noteTerrainDifficulty}と同じ比を集める。測るのは
     * <b>層1の中間目標へ立ち寄る区間</b>——実機が投げるのがそれだから。
     *
     * @return 移動平均の落ち着き先（重み0.3の指数移動平均を最後まで回した値）
     */
    private static double difficulty(FakeCells cells, List<BlockPos[]> routes, List<String> detail)
            throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        double smoothed = 1.0;
        int measured = 0;
        for (BlockPos[] route : routes) {
            CoarseRouter.Route coarse = coarseRoute(cells, route[0], route[1]);
            if (coarse == null) {
                continue;
            }
            BlockPos from = route[0];
            for (BlockPos waypoint : coarse.waypoints()) {
                PathResult result =
                        executor.submit(cells, from, waypoint, LIMITS, true, LEG_GOAL_RADIUS).get();
                List<PathStep> steps = result.steps();
                if (steps.isEmpty()) {
                    break;
                }
                BlockPos end = steps.get(steps.size() - 1).pos();
                double span = horizontal(from, end);
                from = end;
                if (span < MIN_SPAN_BLOCKS) {
                    continue;
                }
                double cost = 0;
                for (PathStep step : steps) {
                    cost += step.cost();
                }
                smoothed = smoothed * 0.7
                        + (cost / (span * ActionCosts.SPRINT_ONE_BLOCK)) * 0.3;
                measured++;
            }
        }
        detail.add("区間" + measured + "本");
        return smoothed;
    }

    /** 実機（{@code PathfindingState#computeCoarseRoute}）と同じ梯子で引いた層1ルート。 */
    private static CoarseRouter.Route coarseRoute(FakeCells cells, BlockPos start, BlockPos goal) {
        CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), () -> false);
        for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
            CoarseRouter.Route candidate = CoarseRouter.findRoute(map, start, goal, false, policy);
            if (candidate.reachedGoal() && !candidate.waypoints().isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /** 1本の経路について測った、2つの狙い方の「進んだ距離1ブロックあたりのコスト」。 */
    private record Pair(double viaWaypoints, double toGoal) {
    }

    /**
     * <b>同じ地形・同じ始点で、2つの狙い方を実際に走らせて比べる。</b>
     *
     * <ul>
     * <li><b>中間目標を狙う</b> — 実機の従来動作。層1の中間目標へ半径16で立ち寄る</li>
     * <li><b>目的地を狙う</b> — 層1をcost-to-goガイドとしてだけ使い、箱で切られた部分経路を採る</li>
     * </ul>
     *
     * <p>物差しは<b>進んだ直線距離1ブロックあたりのコスト</b>。両者は同じ距離進むとは限らないので、
     * 総コストでは比べられない。
     */
    private static Pair compare(FakeCells cells, List<BlockPos[]> routes, List<String> detail)
            throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        double viaCost = 0;
        double viaSpan = 0;
        double goalCost = 0;
        double goalSpan = 0;
        int measured = 0;
        for (BlockPos[] route : routes) {
            CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), route[0].getY(), () -> false);
            CoarseRouter.Route coarse = null;
            for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
                CoarseRouter.Route candidate =
                        CoarseRouter.findRoute(map, route[0], route[1], false, policy);
                if (candidate.reachedGoal()) {
                    coarse = candidate;
                    break;
                }
            }
            if (coarse == null || coarse.waypoints().isEmpty()) {
                continue;
            }
            measured++;

            // 中間目標を狙う（最初の1区間だけ。実機も1回の探索でこれを測る）
            PathResult via = executor
                    .submit(cells, route[0], coarse.waypoints().get(0), LIMITS, true, LEG_GOAL_RADIUS)
                    .get();
            if (!via.steps().isEmpty()) {
                viaCost += via.steps().stream().mapToDouble(PathStep::cost).sum();
                viaSpan += horizontal(route[0], via.steps().get(via.steps().size() - 1).pos());
            }

            // 目的地を狙う（箱は実機と同じく描画距離で切る）
            CostToGo guide = CoarseRouter.costToGo(map, route[1], false,
                    CoarseRouter.BridgePolicy.BRIDGE);
            PathResult toGoal = new AStarPathfinder(new WindowedCells(cells, route[0], WINDOW_RADIUS),
                    LIMITS, guide).search(route[0], route[1], () -> false);
            if (!toGoal.steps().isEmpty()) {
                goalCost += toGoal.steps().stream().mapToDouble(PathStep::cost).sum();
                goalSpan += horizontal(route[0], toGoal.steps().get(toGoal.steps().size() - 1).pos());
            }
        }
        double via = viaSpan > 0 ? viaCost / viaSpan : Double.NaN;
        double goal = goalSpan > 0 ? goalCost / goalSpan : Double.NaN;
        detail.add(String.format(Locale.ROOT,
                "%d本 中間目標を狙う%.2f tick/ブロック 目的地を狙う%.2f tick/ブロック → %.2f倍安い",
                measured, via, goal, via / goal));
        return new Pair(via, goal);
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Test
    void theNetherCrossesTheThresholdAndTheOthersAreRecorded() throws Exception {
        List<String> report = new ArrayList<>();
        for (Terrain terrain : terrains()) {
            FakeCells cells = terrain(terrain.resource(), terrain.ceiling());
            List<BlockPos[]> routes = TerrainFixture.randomRoutes(cells, cells.bounds(), SEED,
                    terrain.routes(), terrain.min(), terrain.max());
            List<String> detail = new ArrayList<>();
            double difficulty = difficulty(cells, routes, detail);
            List<String> pair = new ArrayList<>();
            compare(cells, routes, pair);
            report.add(String.format(Locale.ROOT, "%-12s 難しさ%.2f倍 / %s", terrain.name(),
                    difficulty, pair.get(0)));
        }

        FakeCells nether = terrain("/nether_wide.txt.gz", true);
        List<BlockPos[]> netherRoutes = netherRoutes(nether);
        List<String> netherDetail = new ArrayList<>();
        double netherDifficulty = difficulty(nether, netherRoutes, netherDetail);
        List<String> netherPair = new ArrayList<>();
        compare(nether, netherRoutes, netherPair);
        report.add(String.format(Locale.ROOT, "%-12s 難しさ%.2f倍 / %s", "ネザー/広域",
                netherDifficulty, netherPair.get(0)));

        System.out.println(String.join("\n", report));
        assertTrue(netherDifficulty > DISTRUST_RATIO,
                "ネザーが切り替えの閾値に届いていない（届かなければ切り替えは一度も起きない）: "
                        + String.format(Locale.ROOT, "%.2f倍 <= %.2f", netherDifficulty, DISTRUST_RATIO)
                        + "\n" + String.join("\n", report));
    }

    /**
     * ネザーの両端は{@code TerrainFixture#randomRoutes}では拾えない（列のいちばん上＝岩盤天井の上が
     * 返る）。{@link NetherWideRouteTest}と同じく、歩く高さに近い床を選ぶ。
     */
    private static List<BlockPos[]> netherRoutes(FakeCells cells) {
        SearchBounds bounds = cells.bounds();
        Random random = new Random(SEED);
        List<BlockPos[]> routes = new ArrayList<>();
        int attempts = 0;
        while (routes.size() < 6 && attempts++ < 4000) {
            int x = bounds.minX() + 24 + random.nextInt(bounds.maxX() - bounds.minX() - 48);
            int z = bounds.minZ() + 24 + random.nextInt(bounds.maxZ() - bounds.minZ() - 48);
            BlockPos start = walkable(cells, x, z);
            if (start == null) {
                continue;
            }
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int distance = 60 + random.nextInt(101);
            BlockPos goal = walkable(cells, start.getX() + (int) Math.round(distance * Math.cos(angle)),
                    start.getZ() + (int) Math.round(distance * Math.sin(angle)));
            if (goal != null) {
                routes.add(new BlockPos[] {start, goal});
            }
        }
        return routes;
    }

    /** 歩く高さ(74)にいちばん近い、岩盤天井(123〜127)より下の床。 */
    private static BlockPos walkable(FakeCells cells, int x, int z) {
        if (!cells.isInBounds(x, 74, z)) {
            return null;
        }
        for (int offset = 0; offset <= 110; offset++) {
            for (int y : new int[] {74 + offset, 74 - offset}) {
                if (y <= 118 && y > cells.bounds().minY() + 1 && standable(cells, x, y, z)) {
                    return new BlockPos(x, y, z);
                }
            }
        }
        return null;
    }

    private static boolean standable(FakeCells cells, int x, int y, int z) {
        return CellData.standable(cells.cell(x, y - 1, z))
                && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z));
    }
}
