package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 使い捨ての計測。3D粗層の<b>箱のYが実機のログで見た範囲で振れたとき</b>、ガイドと
 * 実際に歩く経路がどれだけ変わるかを測る。
 */
@Tag("bench")
class LoadedLayersProbeTest {

    private static final int WINDOW = 160;

    /** 実機ログ（2026-09-18）で実際に出た箱のY。 */
    private static final int[][] Y_RANGES = {
            {0, 89}, {7, 103}, {9, 89}, {19, 98}, {25, 122}, {43, 122},
    };

    private static Function<BlockPos, CostToGo> guide(FakeCells cells, BlockPos goal, FarField far) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        return player -> {
            if (last[0] == null || !player.equals(last[0])) {
                CellSource window = new WindowedCells(cells, player, WINDOW);
                cached[0] = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), far,
                        ForkJoinPool.commonPool(), Runtime.getRuntime().availableProcessors(),
                        () -> false).field();
                last[0] = player;
            }
            return cached[0];
        };
    }

    @Test
    void boxHeightDecidesTheCorridor() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain().maxLavaBridgeRunBlocks(30);
        List<BlockPos[]> routes = List.of(
                new BlockPos[] {new BlockPos(-222, 64, 356), new BlockPos(-335, 65, 706)},
                new BlockPos[] {new BlockPos(-120, 64, 380), new BlockPos(-335, 65, 706)},
                new BlockPos[] {new BlockPos(-40, 64, 380), new BlockPos(-335, 65, 706)});
        for (BlockPos[] route : routes) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            double straight = Heuristic.estimate(start.getX(), start.getY(), start.getZ(),
                    goal.getX(), goal.getY(), goal.getZ());
            System.out.printf(Locale.ROOT, "%n== %s → %s (直線 %.0ftick) ==%n",
                    start.toShortString(), goal.toShortString(), straight);
            for (int[] range : Y_RANGES) {
                SearchBounds box = XaeroMapModel.guideBox(start, goal, range[0], range[1]);
                VoxelTerrain terrain = VoxelTerrain.of(box, true);
                if (terrain == null) {
                    System.out.printf(Locale.ROOT, "  Y=%d..%d 箱が大きすぎる%n", range[0], range[1]);
                    continue;
                }
                XaeroMapModel.fill(terrain, cells, 1.0, 20260918L);
                VoxelCostToGo voxel = VoxelCostToGo.build(terrain, goal, () -> false);
                FarField far = FarField.of((x, y, z) -> 1.3 * voxel.estimate(x, y, z));
                double estimate = voxel.estimate(start.getX(), start.getY(), start.getZ());
                ProgressiveWalk.Trace walk = ProgressiveWalk.trace(cells, start, goal, WINDOW,
                        ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL,
                        guide(cells, goal, far), 1.0);
                int westmost = start.getX();
                int backtrack = 0;
                double closest = Double.MAX_VALUE;
                for (PathStep step : walk.steps()) {
                    westmost = Math.min(westmost, step.pos().getX());
                    double distance = ProgressiveWalk.horizontal(step.pos(), goal);
                    closest = Math.min(closest, distance);
                    backtrack = (int) Math.max(backtrack, distance - closest);
                }
                System.out.printf(Locale.ROOT,
                        "  Y=%3d..%3d 辺=%d %s 見積=%5.0f(%.2f倍) 歩き%6.0f 最西X=%d 最大後退%d %s%n",
                        range[0], range[1], terrain.cellBlocks(), terrain.breakdown(), estimate,
                        estimate / straight,
                        walk.steps().isEmpty() ? -1 : ProgressiveWalk.cost(walk.steps()),
                        westmost, backtrack, walk.stopped());
            }
        }
    }
}
