package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.OwnerTrackingCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class OutsideGoalBudgetTest {
    @Test
    void usesTheFullNormalBudgetWithoutLaunchingAnUnfinishableDeepSearch() throws Exception {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 64, 0, 128, 67, 0));
        for (int x = 0; x <= 128; x++) {
            cells.set(x, 64, 0, FakeCells.STONE);
        }
        BlockPos start = new BlockPos(0, 65, 0);
        BlockPos goal = new BlockPos(500, 65, 0);
        SearchLimits normal = new SearchLimits(100, 10_000, 1.2);
        SearchLimits deep = new SearchLimits(800, 10_000, 1.5);
        PathResult expected = new PathfindingExecutor().submit(cells, start, goal, normal, true)
                .get(15, TimeUnit.SECONDS);
        OwnerTrackingCells deepView = new OwnerTrackingCells(cells);
        PathResult actual = new PathfindingExecutor()
                .submitWithDeepFallback(cells, deepView.view(), start, goal, normal, deep, true, 0)
                .get(15, TimeUnit.SECONDS);
        assertFalse(actual.complete());
        assertTrue(expected.steps().size() > 90, "The normal budget can reach much farther than 40% allows");
        assertEquals(expected.steps().getLast().pos(), actual.steps().getLast().pos());
        assertEquals(expected.expandedNodes(), actual.expandedNodes());
        assertNull(deepView.owner(), "Neither budget can finish an out-of-bounds goal");
    }
    @Test
    void stillUsesTheDeepBudgetWhenTheGoalRadiusOverlapsTheBounds() throws Exception {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 64, 0, 128, 67, 0));
        for (int x = 0; x <= 128; x++) {
            cells.set(x, 64, 0, FakeCells.STONE);
        }
        OwnerTrackingCells deepView = new OwnerTrackingCells(cells);
        PathResult result = new PathfindingExecutor().submitWithDeepFallback(cells, deepView.view(),
                new BlockPos(0, 65, 0), new BlockPos(132, 65, 0),
                new SearchLimits(100, 10_000, 1.2), new SearchLimits(800, 10_000, 1.5), true, 8)
                .get(15, TimeUnit.SECONDS);
        assertTrue(result.complete(), "The goal area is reachable even though its center is outside");
        assertTrue(deepView.owner() != null, "The normal budget cannot reach the goal area");
    }

    @Test
    void skipsDeepSearchForAnUnloadedGoalInsideTheBox() throws Exception {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 64, 0, 600, 67, 0))
                .fillWith(FakeCells.ABSENT);
        for (int x = 0; x <= 128; x++) {
            cells.set(x, 64, 0, FakeCells.STONE);
            for (int y = 65; y <= 67; y++) {
                cells.set(x, y, 0, FakeCells.AIR);
            }
        }
        OwnerTrackingCells deepView = new OwnerTrackingCells(cells);
        PathResult result = new PathfindingExecutor().submitWithDeepFallback(cells, deepView.view(),
                new BlockPos(0, 65, 0), new BlockPos(500, 65, 0),
                new SearchLimits(100, 10_000, 1.2), new SearchLimits(800, 10_000, 1.5), true, 0)
                .get(15, TimeUnit.SECONDS);
        assertFalse(result.complete());
        assertTrue(result.steps().size() > 90);
        assertNull(deepView.owner());
    }

    @Test
    void keepsDeepSearchForVerticalOverlapAndResolvedGoals() throws Exception {
        for (int radius : new int[] {0, 8}) {
            FakeCells cells = FakeCells.empty(new SearchBounds(0, 64, 0, 128, 67, 0));
            for (int x = 0; x <= 128; x++) {
                cells.set(x, 64, 0, FakeCells.STONE);
            }
            OwnerTrackingCells deepView = new OwnerTrackingCells(cells);
            // Exact goal resolves down to the floor; radius goal has an absent center
            // beyond X bounds but its cylinder overlaps the floor.
            BlockPos goal = new BlockPos(radius == 0 ? 128 : 132, 80, 0);
            PathResult result = new PathfindingExecutor().submitWithDeepFallback(cells, deepView.view(),
                    new BlockPos(0, 65, 0), goal,
                    new SearchLimits(100, 10_000, 1.2), new SearchLimits(800, 10_000, 1.5), true, radius)
                    .get(15, TimeUnit.SECONDS);
            assertTrue(result.complete(), "Reachable goal, radius=" + radius);
            assertTrue(deepView.owner() != null);
        }
    }

}
