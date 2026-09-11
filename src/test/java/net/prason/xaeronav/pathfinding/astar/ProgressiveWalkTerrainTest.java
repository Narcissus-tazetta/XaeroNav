package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class ProgressiveWalkTerrainTest {
    @Test
    void requiresArrivalOnTheCorrectFloor() {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 0, 0, 0, 7, 0));
        cells.set(0, 0, 0, FakeCells.BEDROCK);
        for (int y = 1; y <= 5; y++) {
            cells.set(0, y, 0, FakeCells.LADDER);
        }
        BlockPos goal = new BlockPos(0, 5, 0);
        var trace = ProgressiveWalk.trace(cells, new BlockPos(0, 1, 0), goal,
                64, ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.GOAL);
        assertFalse(trace.steps().isEmpty(), trace.stopped());
        assertEquals(goal, trace.steps().get(trace.steps().size() - 1).pos());
    }

    @Test
    void walkingADugRouteDoesNotChangeTheFixtureForTheNextComparison() {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 0, 0, 24, 3, 0));
        for (int x = 0; x <= 24; x++) {
            cells.set(x, 0, 0, FakeCells.BEDROCK);
            cells.set(x, 3, 0, FakeCells.BEDROCK);
            if (x > 0) {
                cells.set(x, 1, 0, FakeCells.STONE);
                cells.set(x, 2, 0, FakeCells.STONE);
            }
        }
        BlockPos start = new BlockPos(0, 1, 0);
        BlockPos goal = new BlockPos(24, 1, 0);
        var first = ProgressiveWalk.trace(cells, start, goal, 64,
                ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.GOAL);
        assertFalse(first.steps().isEmpty(), first.stopped());
        assertFalse(CellData.passableEmpty(cells.cell(16, 1, 0)));
        var second = ProgressiveWalk.trace(cells, start, goal, 64,
                ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.GOAL);
        assertEquals(first.steps(), second.steps());
    }
}
