package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/** 調査用: 2026-09-25 15:40 の実機エンド。黄色い線（層1のルート）と詳細経路の向き。 */
@Tag("bench")
class EndYellowLineProbeBenchTest {

    private static final int WINDOW = 224;
    private static final BlockPos GOAL = new BlockPos(3017, 59, 1517);
    private static final BlockPos[] PLAYERS = {new BlockPos(1431, 62, 1441), new BlockPos(1501, 59, 1477),
            new BlockPos(1750, 59, 1517), new BlockPos(1772, 45, 1517), new BlockPos(1865, 66, 1505),
            new BlockPos(1888, 57, 1426), new BlockPos(1722, 60, 1459)};

    static FakeCells cells() throws IOException {
        return TerrainFixture.load("/end_stuck_probe2.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .maxVoidBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(0)
                .avoidRiskyJumps(true));
    }

    @Test
    void bridgeTip() throws IOException {
        bridgeTip(cells(), new BlockPos(1865, 66, 1505), new BlockPos(2089, 61, 1517));
    }

    @Test
    void bridgeTipFirst() throws IOException {
        FakeCells first = TerrainFixture.load("/end_stuck_probe.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(0).avoidRiskyJumps(true));
        bridgeTip(first, new BlockPos(1382, 69, 1443), new BlockPos(1535, 57, 1667));
    }

    private static void bridgeTip(FakeCells cells, BlockPos rawPlayer, BlockPos rawTarget) {
        BlockPos player = StanceFinder.resolveStart(cells, rawPlayer);
        BlockPos target = StanceFinder.resolveGoal(cells, rawTarget);
        WindowedCells window = new WindowedCells(cells, player, WINDOW);
        NavGraph graph = new NavGraph(target, cells.bounds().minY(), cells.bounds().maxY());
        WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                LoadedArea.square(player.getX(), player.getZ(), WINDOW), FarField.UNKNOWN,
                ForkJoinPool.commonPool(), Runtime.getRuntime().availableProcessors(), () -> false).field();
        System.out.printf(Locale.ROOT, "始点%s 目標%s 繋がる=%s 始点の値=%.0f%n", player.toShortString(),
                target.toShortString(), field.reachesGoal(), field.exact(player.getX(), player.getY(), player.getZ()));
        SearchBounds box = ProgressiveWalk.searchBox(cells, player, target, WINDOW);
        WindowedCells view = new WindowedCells(cells, player, WINDOW, new SearchBounds(box.minX(),
                cells.bounds().minY(), box.minZ(), box.maxX(), cells.bounds().maxY(), box.maxZ()));
        PathResult r = new AStarPathfinder(view, new SearchLimits(4_000_000, 120_000, 1.0), null)
                .search(player, target, () -> false);
        System.out.printf(Locale.ROOT, "探索: %s 手%d 橋%d%n", r.complete() ? "到達" : r.termination(),
                r.steps().size(), r.steps().stream().filter(PathStep::bridging).count());
        int run = 0;
        for (PathStep st : r.steps()) {
            BlockPos q = st.pos();
            run = st.bridging() ? run + 1 : 0;
            System.out.printf(Locale.ROOT, "  %s %s%s 連続橋%d グラフ=%s%n", q.toShortString(), st.movement(),
                    st.bridging() ? "(橋)" : "", run, Double.isNaN(field.exact(q.getX(), q.getY(), q.getZ())) ? "無し"
                            : String.format(Locale.ROOT, "%.0f", field.exact(q.getX(), q.getY(), q.getZ())));
        }
    }

    @Test
    void trail() throws IOException {
        FakeCells cells = cells();
        BlockPos player = StanceFinder.resolveStart(cells, new BlockPos(1750, 59, 1517));
        FarField far = FarField.forwardOf(FarField.straightLineTo(GOAL), player.getX(), player.getY(), player.getZ());
        WindowedCells window = new WindowedCells(cells, player, WINDOW);
        NavGraph graph = new NavGraph(GOAL, cells.bounds().minY(), cells.bounds().maxY());
        WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                LoadedArea.square(player.getX(), player.getZ(), WINDOW), far,
                ForkJoinPool.commonPool(), Runtime.getRuntime().availableProcessors(), () -> false).field();
        java.util.List<BlockPos> points = new java.util.ArrayList<>();
        field.descend(player.getX(), player.getY(), player.getZ(), (x, y, z) -> points.add(new BlockPos(x, y, z)));
        SearchBounds box = ProgressiveWalk.searchBox(cells, player, GOAL, WINDOW);
        WindowedCells view = new WindowedCells(cells, player, WINDOW, new SearchBounds(box.minX(),
                cells.bounds().minY(), box.minZ(), box.maxX(), cells.bounds().maxY(), box.maxZ()));
        for (int i = 55; i < 80 && i < points.size(); i++) {
            BlockPos t = points.get(i);
            StringBuilder col = new StringBuilder();
            for (int y = t.getY() - 3; y <= t.getY() + 2; y++) {
                long c = cells.cell(t.getX(), y, t.getZ());
                col.append(net.prason.xaeronav.pathfinding.world.CellData.passableEmpty(c) ? '.' : '#');
            }
            System.out.printf(Locale.ROOT, "  歩%d %s 値=%.0f 列(y-3..y+2)=%s%n", i, t.toShortString(),
                    field.estimate(t.getX(), t.getY(), t.getZ()), col);
        }
        // 道筋の10点ごとに、始点からそこへ探索で届くか
        for (int i = 5; i < 70; i += 10) {
            BlockPos t = points.get(i);
            PathResult r = new AStarPathfinder(view, new SearchLimits(400_000, 60_000, 1.0), null)
                    .search(player, t, () -> false);
            double c = r.steps().stream().mapToDouble(PathStep::cost).sum();
            System.out.printf(Locale.ROOT, "  道筋%d %s 下=%s グラフ差=%.0f 探索=%s 実費%.0f 展開%d%n", i, t.toShortString(),
                    net.prason.xaeronav.pathfinding.world.CellData.standable(cells.cell(t.getX(), t.getY() - 1, t.getZ()))
                            ? "床" : "空", field.estimate(player.getX(), player.getY(), player.getZ())
                            - field.estimate(t.getX(), t.getY(), t.getZ()),
                    r.complete() ? "到達" : r.termination(), c, r.expandedNodes());
        }
    }

    @Test
    void probe() throws IOException {
        run(cells(), GOAL, PLAYERS, new SearchBounds(1100, 0, 1000, 3200, 127, 2000));
    }

    @Test
    void probeFirst() throws IOException {
        FakeCells first = TerrainFixture.load("/end_stuck_probe.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(0).avoidRiskyJumps(true));
        run(first, new BlockPos(2338, 59, 2522), new BlockPos[] {new BlockPos(1380, 58, 1124),
                new BlockPos(1381, 57, 1320), new BlockPos(1368, 57, 1322), new BlockPos(1382, 69, 1443),
                new BlockPos(1379, 59, 1117)}, new SearchBounds(1000, 0, 800, 2450, 127, 2600));
    }

    private static void run(FakeCells cells, BlockPos goal, BlockPos[] players, SearchBounds mapBox) {
        mapBox = new SearchBounds(mapBox.minX(), cells.bounds().minY(), mapBox.minZ(), mapBox.maxX(),
                cells.bounds().maxY(), mapBox.maxZ());
        CoarseMap map = LiveCoarseSampler.sample(cells, mapBox, 60, () -> false);
        System.out.printf(Locale.ROOT, "地図 既知%d/%d %s%n", map.knownCells(), map.totalCells(), map.kindBreakdown());
        for (BlockPos raw : players) {
            BlockPos player = StanceFinder.resolveStart(cells, raw);
            System.out.printf(Locale.ROOT, "== %s%n", player.toShortString());
            double unknown = CoarseRouter.unknownMultiplier(map);
            for (String name : new String[] {"実機と同じ(x" + String.format(Locale.ROOT, "%.1f", unknown) + ")"}) {
                double k = unknown;
                FarField base = (x, y, z) -> k * Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
                FarField far = FarField.forwardOf(base, player.getX(), player.getY(), player.getZ());
                WindowedCells window = new WindowedCells(cells, player, WINDOW);
                NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
                long began = System.currentTimeMillis();
                NavGraph.Refreshed refreshed = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), far,
                        ForkJoinPool.commonPool(), Runtime.getRuntime().availableProcessors(), () -> false);
                WindowField field = refreshed.field();
                System.out.printf(Locale.ROOT, "  グラフ ノード%d 辺%d %dMB 構築%dms ガイド%dms 全体%dms%n", field.nodes(),
                        field.edges(), graph.bytes() >> 20, refreshed.buildMillis(), field.buildMillis(),
                        System.currentTimeMillis() - began);
                WindowField.Descent d = field.descend(player.getX(), player.getY(), player.getZ());
                SearchBounds full = new SearchBounds(player.getX() - WINDOW, cells.bounds().minY(),
                        player.getZ() - WINDOW, player.getX() + WINDOW, cells.bounds().maxY(), player.getZ() + WINDOW);
                WindowedCells view = new WindowedCells(cells, player, WINDOW, full);
                PathResult r = new PathfindingExecutor().submit(view, player, goal,
                        new SearchLimits(100_000, 30_000, 1.0), true, 0, Carryover.NONE, field).join();
                String end = r.steps().isEmpty() ? "-" : r.steps().get(r.steps().size() - 1).pos().toShortString();
                // 窓のガイドを辿って、最後に陸に立つ点を狙う
                BlockPos[] landing = {null};
                double[] landingValue = {0};
                field.descend(player.getX(), player.getY(), player.getZ(), (x, y, z) -> {
                    if (net.prason.xaeronav.pathfinding.world.CellData.standable(cells.cell(x, y - 1, z))) {
                        landing[0] = new BlockPos(x, y, z);
                        landingValue[0] = field.estimate(x, y, z);
                    }
                });
                if (landing[0] != null && !landing[0].equals(player)) {
                    double base0 = landingValue[0];
                    CostToGo shifted = new CostToGo() {
                        @Override
                        public double estimate(int x, int y, int z) {
                            return Math.max(0, field.estimate(x, y, z) - base0);
                        }

                        @Override
                        public double searchEstimate(int x, int y, int z) {
                            double v = field.searchEstimate(x, y, z);
                            return Double.isNaN(v) ? v : Math.max(0, v - base0);
                        }
                    };
                    long t = System.currentTimeMillis();
                    PathResult lr = new PathfindingExecutor().submit(view, player, landing[0],
                            new SearchLimits(100_000, 30_000, 1.0), true, 0, Carryover.NONE, shifted).join();
                    System.out.printf(Locale.ROOT, "  %-8s 着地点%s を狙う: %s 手%d 橋%d 展開%d %dms%n", name,
                            landing[0].toShortString(), lr.complete() ? "到達" : lr.termination(), lr.steps().size(),
                            lr.steps().stream().filter(PathStep::bridging).count(), lr.expandedNodes(),
                            System.currentTimeMillis() - t);
                } else {
                    System.out.printf(Locale.ROOT, "  %-8s 着地点なし%n", name);
                }
                System.out.printf(Locale.ROOT, "  %-8s 出口=%s(中%.0f+外%.0f) 探索: %s 手%d 橋%d 末端%s%n", name,
                        d == null ? "-" : d.exit().toShortString(), d == null ? 0 : d.inside(),
                        d == null ? 0 : d.outside(), r.complete() ? "到達" : r.termination(), r.steps().size(),
                        r.steps().stream().filter(PathStep::bridging).count(), end);
            }
        }
    }
}
