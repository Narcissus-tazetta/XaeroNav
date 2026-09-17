package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 本番の{@link NavGraph}を歩き通しに組み込み、実測した「窓は正確＋外は推定」と同じ質が出るか、構築とガイドに
 * 何msかかるかを測る。判定を持たない計測。
 *
 * <p>窓の縁のセクションは周りが欠けたまま仮に組み、窓が動いて周りが読めるようになったら組み直す（{@link NavGraph}）。
 */
@Tag("bench")
class NavGraphWalkBenchTest {

    private static final int WINDOW = 160;

    private static final long SEED = 20260917L;

    private record Stats(long[] buildMillis, long[] fieldMillis, int[] maxEdges, long[] maxBytes) {
    }

    private static Function<BlockPos, CostToGo> guide(FakeCells cells, BlockPos goal, FarField far, Stats stats) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        // 実機はガイドを作り直している間も古いガイドで探す。前に作った中心からこれだけ歩くまで作り直さない
        int lag = Integer.getInteger("xaeronav.navGraphLag", 0);
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        return player -> {
            if (last[0] == null || (!player.equals(last[0]) && Math.max(Math.abs(player.getX() - last[0].getX()),
                    Math.abs(player.getZ() - last[0].getZ())) >= lag)) {
                CellSource window = new WindowedCells(cells, player, WINDOW);
                // 実機と同じく並列に組む。FakeCellsは読むだけなら共有してよい
                NavGraph.Refreshed refreshed = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(),
                        Runtime.getRuntime().availableProcessors(), () -> false);
                WindowField field = refreshed.field();
                if (Boolean.getBoolean("xaeronav.navGraphVerbose")) {
                    System.out.printf(Locale.ROOT, "  組み直し %s セクション%d 構築%dms ガイド%dms%n", player.toShortString(),
                            refreshed.sectionsBuilt(), refreshed.buildMillis(), field.buildMillis());
                }
                stats.buildMillis()[0] += refreshed.buildMillis();
                stats.fieldMillis()[0] += field.buildMillis();
                stats.fieldMillis()[1] = Math.max(stats.fieldMillis()[1], field.buildMillis());
                stats.maxEdges()[0] = Math.max(stats.maxEdges()[0], field.edges());
                stats.maxBytes()[0] = Math.max(stats.maxBytes()[0], graph.bytes());
                stats.maxBytes()[1] = Math.max(stats.maxBytes()[1], field.bytes());
                cached[0] = field;
                last[0] = player;
            }
            return cached[0];
        };
    }

    /** 頭の上に空が見えているか。実機は地中から始まる案内を先に地上へ出す区間（ガイドを使わない）で解く。 */
    private static boolean underSky(FakeCells cells, BlockPos pos) {
        for (int y = pos.getY() + 2; y <= cells.bounds().maxY(); y++) {
            long cell = cells.cell(pos.getX(), y, pos.getZ());
            if (!net.prason.xaeronav.pathfinding.world.CellData.passableEmpty(cell)
                    && !net.prason.xaeronav.pathfinding.world.CellData.water(cell)) {
                return false;
            }
        }
        return true;
    }

    /** 列の地表から8ブロック以上下で、掘らずに立てる洞窟の床。無ければ{@code null}。 */
    private static BlockPos caveBelow(FakeCells cells, BlockPos surface) {
        for (int y = surface.getY() - 8; y > cells.bounds().minY() + 1; y--) {
            long below = cells.cell(surface.getX(), y - 1, surface.getZ());
            long feet = cells.cell(surface.getX(), y, surface.getZ());
            long head = cells.cell(surface.getX(), y + 1, surface.getZ());
            if (net.prason.xaeronav.pathfinding.world.CellData.standable(below)
                    && net.prason.xaeronav.pathfinding.world.CellData.passableEmpty(feet)
                    && net.prason.xaeronav.pathfinding.world.CellData.passableEmpty(head)
                    && !underSky(cells, new BlockPos(surface.getX(), y, surface.getZ()))) {
                return new BlockPos(surface.getX(), y, surface.getZ());
            }
        }
        return null;
    }

    /** 始点・終点とも空の下にあるルート。 */
    static List<BlockPos[]> surfaceRoutes(FakeCells cells, int count, int min, int max) {
        List<BlockPos[]> routes = new ArrayList<>();
        for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, count * 10, min, max)) {
            if (routes.size() < count && underSky(cells, route[0]) && underSky(cells, route[1])) {
                routes.add(route);
            }
        }
        return routes;
    }

    /** 閉包で作った窓のガイドで歩く。閉包は大きいので、航法グラフを測る前に手放せるよう分けてある。 */
    private static ProgressiveWalk.Trace closureWalk(FakeCells cells, BlockPos start, BlockPos goal,
                                                     ProgressiveWalk.Mode mode, FarField far) {
        ClosureGraph closure = ClosureGraph.build(cells, start, goal,
                ClosureGraph.box(cells, start, goal, 224, cells.bounds().minY(), cells.bounds().maxY()));
        BlockPos[] lastPlayer = {null};
        CostToGo[] closureGuide = {null};
        return ProgressiveWalk.trace(cells, start, goal, WINDOW, mode, ProgressiveWalk.Aim.GOAL,
                (Function<BlockPos, CostToGo>) player -> {
                    if (!player.equals(lastPlayer[0])) {
                        closureGuide[0] = closure.windowGuide(goal, player, WINDOW, far::at);
                        lastPlayer[0] = player;
                    }
                    return closureGuide[0];
                }, 1.0);
    }

    private static void measure(String name, FakeCells cells, List<BlockPos[]> routes, ProgressiveWalk.Mode mode,
                                ProgressiveWalk.Aim currentAim, Function<BlockPos[], FarField> farFor) {
        List<List<Double>> ratios = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (BlockPos[] route : routes.subList(0, Math.min(routes.size(), Integer.getInteger("xaeronav.routeLimit", 99)))) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            double best = ProgressiveWalk.fullVisibilityBest(cells, start, goal);
            FarField far = farFor.apply(new BlockPos[] {start, goal});

            // 航法グラフの実装だけを測り直すときは、重い2本（現行・閉包の窓）を飛ばす
            boolean graphOnly = Boolean.getBoolean("xaeronav.navGraphOnly");
            ProgressiveWalk.Trace current = graphOnly ? null
                    : ProgressiveWalk.trace(cells, start, goal, WINDOW, mode, currentAim, null);
            ProgressiveWalk.Trace closureWalk = graphOnly ? null : closureWalk(cells, start, goal, mode, far);

            Stats stats = new Stats(new long[1], new long[2], new int[1], new long[2]);
            ProgressiveWalk.UNGUIDED_LEGS.set(0);
            ProgressiveWalk.Trace graphWalk = ProgressiveWalk.trace(cells, start, goal, WINDOW, mode,
                    ProgressiveWalk.Aim.GOAL, guide(cells, goal, far, stats), 1.0);
            double[] values = new double[3];
            ProgressiveWalk.Trace[] traces = {current, closureWalk, graphWalk};
            for (int i = 0; i < 3; i++) {
                values[i] = traces[i] == null || traces[i].steps().isEmpty() ? Double.POSITIVE_INFINITY
                        : ProgressiveWalk.cost(traces[i].steps()) / best;
                ratios.get(i).add(values[i]);
            }
            System.out.printf(Locale.ROOT,
                    "%s %s→%s 現行%.3f 閉包の窓%.3f 航法グラフ%.5f 使えない区間%d 繋ぎ目%d 構築計%dms ガイド計%dms(最大%dms) 辺最大%d グラフ最大%dMB ガイド最大%dMB %s%n",
                    name, start.toShortString(), goal.toShortString(), values[0], values[1], values[2],
                    ProgressiveWalk.UNGUIDED_LEGS.get(), graphWalk.joints().size(), stats.buildMillis()[0], stats.fieldMillis()[0], stats.fieldMillis()[1],
                    stats.maxEdges()[0], stats.maxBytes()[0] >> 20, stats.maxBytes()[1] >> 20, graphWalk.stopped());
        }
        String[] names = {"現行", "閉包の窓", "航法グラフ"};
        for (int i = 0; i < 3; i++) {
            List<Double> list = ratios.get(i);
            System.out.printf(Locale.ROOT, "== %s %s 平均%.3f 最悪%.3f%n", name, names[i],
                    list.stream().filter(Double::isFinite).mapToDouble(Double::doubleValue).average().orElse(0),
                    list.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        }
    }

    private static FakeCells overworld(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    @Test
    void wideLong() throws IOException {
        FakeCells cells = overworld("/overworld_wide.txt.gz");
        measure("地上/広域(長)", cells, surfaceRoutes(cells, 4, 200, 450), ProgressiveWalk.Mode.EXTEND,
                ProgressiveWalk.Aim.HORIZON, route -> FarField.of(CoarseRouter.costToGo(LiveCoarseSampler.sample(
                        cells, cells.bounds(), route[0].getY(), () -> false), route[1], false,
                        CoarseRouter.BridgePolicy.BRIDGE)));
    }

    /**
     * 始点が空の下に無い（洞窟・水中・岩の下）ルート。地中の始点は殻に繋がらないことがあり、そのときは航法グラフを使わず
     * 従来の区間で解く（{@link ProgressiveWalk#trace}の{@code unguided}）。
     */
    @Test
    void wideLongUnderground() throws IOException {
        FakeCells cells = overworld("/overworld_wide.txt.gz");
        List<BlockPos[]> routes = new ArrayList<>();
        for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, 400, 200, 450)) {
            BlockPos cave = caveBelow(cells, route[0]);
            if (routes.size() < 4 && cave != null) {
                routes.add(new BlockPos[] {cave, route[1]});
            }
        }
        measure("地上/広域(長・地中始点)", cells, routes, ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.HORIZON,
                route -> FarField.of(CoarseRouter.costToGo(LiveCoarseSampler.sample(cells, cells.bounds(),
                        route[0].getY(), () -> false), route[1], false, CoarseRouter.BridgePolicy.BRIDGE)));
    }

    @Test
    void end() throws IOException {
        FakeCells cells = overworld("/end_terrain_columns.txt.gz");
        measure("エンド", cells, TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, 4, 120, 220),
                ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.HORIZON,
                route -> "unknown".equals(System.getProperty("xaeronav.navGraphFar")) ? FarField.UNKNOWN
                        : FarField.straightLineTo(route[1]));
    }

    @Test
    void nether() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain();
        // 3D粗層は真の残りの0.77倍前後に縮んでいて、窓の中の正確な値と尺度が食い違う。その倍率を戻して測り分ける
        double scale = Double.parseDouble(System.getProperty("xaeronav.navGraphFarScale", "1.0"));
        measure("ネザー(外=3D粗層x" + scale + ")", cells, NetherLiveWalkTest.routes(), ProgressiveWalk.Mode.REPAIR,
                ProgressiveWalk.Aim.GOAL, route -> {
                    CostToGo voxel = XaeroMapModel.guide(cells, route[0], route[1], NetherLiveWalkTest.NETHER_MIN_Y,
                            NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L);
                    return FarField.of((x, y, z) -> scale * voxel.estimate(x, y, z));
                });
    }
}
