package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/** 目的地の寄せ直し（-53,49,716 → -53,68,716）の直後に経路が出るまでの内訳を、実機の保存地形で測る。 */
@Tag("bench")
class ResnapBenchTest {

    private static final int WINDOW = 224;
    private static final BlockPos IN_ROCK = new BlockPos(-53, 49, 716);
    private static final BlockPos GOAL = new BlockPos(-53, 68, 716);
    private static final BlockPos[] PLAYERS = {new BlockPos(-235, 88, 505), new BlockPos(-193, 50, 559),
            new BlockPos(-152, 65, 521)};

    @Test
    void resnap() throws IOException {
        FakeCells cells = NetherTrapBenchTest.cells();
        BlockPos goal = StanceFinder.resolveGoal(cells, GOAL);
        int workers = Runtime.getRuntime().availableProcessors() - 1;
        for (BlockPos raw : PLAYERS) {
            BlockPos player = StanceFinder.resolveStart(cells, raw);
            WindowedCells window = new WindowedCells(cells, player, WINDOW);
            CostToGo voxel = XaeroMapModel.guide(cells, player, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                    NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L);
            FarField far = FarField.of((x, y, z) -> 1.3 * voxel.estimate(x, y, z));
            NavGraph graph = new NavGraph(IN_ROCK, cells.bounds().minY(), cells.bounds().maxY());
            long began = System.currentTimeMillis();
            NavGraph.Refreshed cold = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                    LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(), workers,
                    () -> false);
            long coldMillis = System.currentTimeMillis() - began;
            graph.retarget(goal);
            began = System.currentTimeMillis();
            WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                    LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(), workers,
                    () -> false).field();
            long warmMillis = System.currentTimeMillis() - began;

            SearchBounds box = ProgressiveWalk.searchBox(cells, player, goal, WINDOW);
            WindowedCells view = new WindowedCells(cells, player, WINDOW, box);
            PathfindingExecutor executor = new PathfindingExecutor();
            SearchLimits limits = new SearchLimits(AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
                    AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS, 1.0);
            began = System.currentTimeMillis();
            PathResult guided = executor.submit(view, player, goal, limits, true, 0, Carryover.NONE, field).join();
            long guidedMillis = System.currentTimeMillis() - began;
            SearchLimits voxelLimits = new SearchLimits(AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
                    AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
            began = System.currentTimeMillis();
            PathResult fallback = executor.submit(view, player, goal, voxelLimits, true, 0, Carryover.NONE, voxel).join();
            long fallbackMillis = System.currentTimeMillis() - began;
            System.out.printf(Locale.ROOT,
                    "プレイヤー%s 岩の中の目的地で組み立て%dms（構築%dms ガイド%dms セクション%d） 寄せ直し後のガイド%dms 航法グラフで探索%dms(%d手) 3D粗層で探索%dms(%d手)%n",
                    player.toShortString(), coldMillis, cold.buildMillis(), cold.field().buildMillis(), cold.sectionsBuilt(),
                    warmMillis, guidedMillis, guided.steps().size(), fallbackMillis, fallback.steps().size());
        }
    }

    /** 実機（2026-09-23 20:50）で継ぎ足しが北東へ遠ざかった地点から、ガイドの値の出どころを辿る。 */
    @Test
    void whyNorthEast() throws IOException {
        FakeCells cells = NetherTrapBenchTest.cells();
        BlockPos goal = StanceFinder.resolveGoal(cells, GOAL);
        BlockPos[][] cases = {
                {new BlockPos(-163, 73, 438), new BlockPos(-129, 71, 445), new BlockPos(-106, 60, 410)},
                {new BlockPos(-129, 71, 445), new BlockPos(-129, 71, 445), new BlockPos(-106, 60, 410)}};
        for (BlockPos[] c : cases) {
            BlockPos player = StanceFinder.resolveStart(cells, c[0]);
            WindowedCells window = new WindowedCells(cells, player, WINDOW);
            CostToGo voxel = XaeroMapModel.guide(cells, player, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                    NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L);
            FarField far = FarField.of((x, y, z) -> 1.3 * voxel.estimate(x, y, z));
            NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
            WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                    LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(),
                    Runtime.getRuntime().availableProcessors(), () -> false).field();
            for (int i = 0; i < 3; i++) {
                BlockPos p = i == 0 ? player : StanceFinder.resolveStart(cells, c[i]);
                WindowField.Descent d = field.descend(p.getX(), p.getY(), p.getZ());
                System.out.printf(Locale.ROOT, "窓の中心%s 点%s 値=%.0f 出どころ=%s%n", player.toShortString(),
                        p.toShortString(), field.estimate(p.getX(), p.getY(), p.getZ()),
                        d == null ? "-" : "%s 窓の中%.0f+外%.0f 3D粗層の素の値%.0f 縁から目的地まで直線%.0f".formatted(
                                d.exit().toShortString(), d.inside(), d.outside(),
                                voxel.estimate(d.exit().getX(), d.exit().getY(), d.exit().getZ()),
                                Math.hypot(d.exit().getX() - goal.getX(), d.exit().getZ() - goal.getZ())));
            }
        }
        // 比較: 目的地までの本当の最短（窓無し）
        BlockPos from = StanceFinder.resolveStart(cells, new BlockPos(-163, 73, 438));
        System.out.printf(Locale.ROOT, "真の最短(窓無し) %s→目的地 = %.0f%n", from.toShortString(),
                ProgressiveWalk.fullVisibilityBest(cells, from, goal));
    }
}
