package net.prason.xaeronav.pathfinding.navgraph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class RouteReviewTest {

    private static final int FLOOR_Y = 64;
    private static final double MIN_EXTRA_TICKS = 40.0;
    private static final double MIN_EXTRA_RATIO = 0.05;

    private static FakeCells flat() {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 60, 0, 63, 80, 63));
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                for (int y = 60; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    private static List<PathStep> path(FakeCells cells, BlockPos... points) {
        List<PathStep> steps = new ArrayList<>();
        for (int i = 0; i + 1 < points.length; i++) {
            PathResult leg = new AStarPathfinder(cells, new SearchLimits(1_000_000, 60_000, 1.0))
                    .search(points[i], points[i + 1], () -> false);
            assertTrue(leg.complete());
            steps.addAll(leg.steps());
        }
        return steps;
    }

    private static WindowField field(FakeCells cells, BlockPos goal) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        LoadedArea everything = LoadedArea.square(32, 32, 1 << 20);
        long[] keys = graph.missingSections(32, 32, 64, everything);
        assertTrue(graph.build(cells, keys, 0, keys.length, everything, () -> false));
        WindowField field = graph.field(32, 32, 64, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        return field;
    }

    @Test
    void flagsAPathThatDetoursAwayFromTheGoal() {
        FakeCells cells = flat();
        BlockPos start = new BlockPos(8, FLOOR_Y + 1, 8);
        BlockPos goal = new BlockPos(56, FLOOR_Y + 1, 56);
        // 斜めに行けばよいところを、先に東へ端まで行ってから北へ上がる
        List<PathStep> detour = path(cells, start, new BlockPos(56, FLOOR_Y + 1, 8), goal);
        RouteReview.Detour review = RouteReview.detour(field(cells, goal), start, detour, 0);
        assertTrue(review.worthReplanning(MIN_EXTRA_TICKS, MIN_EXTRA_RATIO), "遠回りを見逃した: " + review);
    }

    @Test
    void leavesAnOptimalPathAlone() {
        FakeCells cells = flat();
        BlockPos start = new BlockPos(8, FLOOR_Y + 1, 8);
        BlockPos goal = new BlockPos(56, FLOOR_Y + 1, 56);
        List<PathStep> optimal = path(cells, start, goal);
        RouteReview.Detour review = RouteReview.detour(field(cells, goal), start, optimal, 0);
        assertFalse(review.worthReplanning(MIN_EXTRA_TICKS, MIN_EXTRA_RATIO), "最短の経路を遠回りとした: " + review);
    }
}
