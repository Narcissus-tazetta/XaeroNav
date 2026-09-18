package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/** 航法グラフのガイドの値を、最適経路の上で完璧な値・閉包で作った窓のガイドと並べる診断。 */
@Tag("bench")
class NavGraphDiagnosisBenchTest {

    private static final int WINDOW = 160;

    /** 歩き通しの区間ごとに、閉包の窓と航法グラフのガイドを同じ点で引き比べる。 */
    @Test
    void compareLegByLeg() throws IOException {
        FakeCells cells = TerrainFixture.load("/overworld_wide.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
        List<BlockPos[]> routes = TerrainFixture.randomRoutes(cells, cells.bounds(), 20260917L, 4, 200, 450);
        BlockPos start = StanceFinder.resolveStart(cells, routes.get(0)[0]);
        BlockPos goal = StanceFinder.resolveGoal(cells, routes.get(0)[1]);
        ClosureGraph closure = ClosureGraph.build(cells, start, goal,
                ClosureGraph.box(cells, start, goal, 224, cells.bounds().minY(), cells.bounds().maxY()));
        FarField far = FarField.of(CoarseRouter.costToGo(LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(),
                () -> false), goal, false, CoarseRouter.BridgePolicy.BRIDGE));
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        int[] leg = {0};
        java.util.function.Function<BlockPos, CostToGo> guide = player -> {
            if (!player.equals(last[0])) {
                WindowedCells view = new WindowedCells(cells, player, WINDOW);
                NavGraph.Refreshed refreshed = graph.refresh(() -> view, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(),
                        Runtime.getRuntime().availableProcessors(), () -> false);
                WindowField field = refreshed.field();
                CostToGo closureWindow = closure.windowGuide(goal, player, WINDOW, far::at);
                int worse = 0;
                int sampled = 0;
                double worst = 0;
                String worstAt = "";
                for (int dx = -WINDOW; dx <= WINDOW; dx += 8) {
                    for (int dz = -WINDOW; dz <= WINDOW; dz += 8) {
                        int x = player.getX() + dx;
                        int z = player.getZ() + dz;
                        for (int y = cells.bounds().minY(); y <= cells.bounds().maxY(); y++) {
                            if (closure.idAt(x, y, z) < 0) {
                                continue;
                            }
                            double a = closureWindow.estimate(x, y, z);
                            double b = field.estimate(x, y, z);
                            sampled++;
                            double gap = Math.abs(a - b) / Math.max(1.0, a);
                            if (gap > 0.05) {
                                worse++;
                            }
                            if (gap > worst) {
                                worst = gap;
                                worstAt = String.format(Locale.ROOT, "%d,%d,%d 閉包%.0f 航法%.0f", x, y, z, a, b);
                            }
                        }
                    }
                }
                System.out.printf(Locale.ROOT, "区間%d 位置%s 5%%超の食い違い%d/%d 最大%.2f(%s) 再構築%d%n", leg[0]++,
                        player.toShortString(), worse, sampled, worst, worstAt, refreshed.sectionsBuilt());
                cached[0] = field;
                last[0] = player;
            }
            return cached[0];
        };
        ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, start, goal, WINDOW, ProgressiveWalk.Mode.EXTEND,
                ProgressiveWalk.Aim.GOAL, guide, 1.0);
        System.out.printf(Locale.ROOT, "歩き通し %.3f倍 %s%n",
                ProgressiveWalk.cost(trace.steps()) / ProgressiveWalk.fullVisibilityBest(cells, start, goal),
                trace.stopped());
    }

    @Test
    void compareAlongTheOptimalPath() throws IOException {
        FakeCells cells = TerrainFixture.load("/overworld_wide.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
        List<BlockPos[]> routes = TerrainFixture.randomRoutes(cells, cells.bounds(), 20260917L, 4, 200, 450);
        BlockPos start = StanceFinder.resolveStart(cells, routes.get(0)[0]);
        BlockPos goal = StanceFinder.resolveGoal(cells, routes.get(0)[1]);
        PathResult optimal = new AStarPathfinder(cells, new SearchLimits(3_000_000, 120_000, 1.0))
                .search(start, goal, () -> false);
        ClosureGraph closure = ClosureGraph.build(cells, start, goal,
                ClosureGraph.box(cells, start, goal, 224, cells.bounds().minY(), cells.bounds().maxY()));
        double[] perfect = closure.distancesTo(goal, null);
        CostToGo layer1 = CoarseRouter.costToGo(LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(),
                () -> false), goal, false, CoarseRouter.BridgePolicy.BRIDGE);
        FarField far = FarField.of(layer1);
        CostToGo closureWindow = closure.windowGuide(goal, start, WINDOW, far::at);

        NavGraph windowed = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        WindowedCells view = new WindowedCells(cells, start, WINDOW);
        LoadedArea loaded = LoadedArea.square(start.getX(), start.getZ(), WINDOW);
        long[] keys = windowed.missingSections(start.getX(), start.getZ(), WINDOW, loaded);
        windowed.build(view, keys, 0, keys.length, loaded, () -> false);
        WindowField field = windowed.field(start.getX(), start.getZ(), WINDOW, far, () -> false);

        NavGraph full = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        LoadedArea everything = LoadedArea.square(start.getX(), start.getZ(), 1 << 20);
        long[] fullKeys = full.missingSections(start.getX(), start.getZ(), WINDOW, everything);
        full.build(cells, fullKeys, 0, fullKeys.length, everything, () -> false);
        WindowField fullField = full.field(start.getX(), start.getZ(), WINDOW, far, () -> false);

        System.out.printf(Locale.ROOT, "%s→%s 最適%.0f 閉包窓の辺? 航法グラフ辺%d(全視界で組むと%d)%n",
                start.toShortString(), goal.toShortString(),
                optimal.steps().stream().mapToDouble(PathStep::cost).sum(), field.edges(), fullField.edges());
        List<PathStep> steps = optimal.steps();
        for (int i = 0; i < steps.size(); i += Math.max(1, steps.size() / 40)) {
            BlockPos p = steps.get(i).pos();
            System.out.printf(Locale.ROOT, "  #%d %s 完璧%.0f 閉包の窓%.0f 航法グラフ%.0f 全視界で組んだ航法グラフ%.0f 層1%.0f 窓の中%b%n",
                    i, p.toShortString(), ClosureGraph.exact(closure, perfect, p.getX(), p.getY(), p.getZ()),
                    closureWindow.estimate(p.getX(), p.getY(), p.getZ()), field.estimate(p.getX(), p.getY(), p.getZ()),
                    fullField.estimate(p.getX(), p.getY(), p.getZ()), layer1.estimate(p.getX(), p.getY(), p.getZ()),
                    Math.abs(p.getX() - start.getX()) <= WINDOW && Math.abs(p.getZ() - start.getZ()) <= WINDOW);
        }
    }
}
