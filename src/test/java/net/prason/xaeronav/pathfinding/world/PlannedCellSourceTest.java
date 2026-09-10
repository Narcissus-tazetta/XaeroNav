package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;

class PlannedCellSourceTest {
    @Test
    void extendsFromTheDugEndpointInsteadOfDroppingToAnotherFloor() throws Exception {
        FakeCells raw = FakeCells.empty(new SearchBounds(0, 40, 0, 5, 70, 0));
        for (int x = 0; x <= 5; x++) {
            raw.set(x, 43, 0, FakeCells.BEDROCK);
            raw.set(x, 64, 0, FakeCells.STONE);
        }
        BlockPos end = new BlockPos(1, 65, 0);
        raw.set(1, 65, 0, FakeCells.STONE);
        raw.set(1, 66, 0, FakeCells.STONE);
        PathStep dig = new PathStep(end, MovementType.TRAVERSE, 80,
                List.of(end, end.above()), List.of(end, end.above()), PathRisk.NONE, null);
        assertEquals(new BlockPos(1, 44, 0), StanceFinder.resolveStart(raw, end),
                "The old continuation silently started on the lower floor");
        PlannedCellSource future = new PlannedCellSource(raw, List.of(dig), 0);
        assertEquals(end, StanceFinder.resolveStart(future, end));
        var result = new PathfindingExecutor().submit(future, end, new BlockPos(5, 65, 0),
                new SearchLimits(1_000, 5_000, 1.0), false).get(10, TimeUnit.SECONDS);
        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() == 65));
        assertFalse(CellData.passableEmpty(raw.cell(1, 65, 0)), "Planning must not modify the world");
    }

    @Test
    void preservesPlannedSupportsAndDoesNotInventLoadedChunks() {
        FakeCells raw = FakeCells.empty(new SearchBounds(0, 40, 0, 5, 70, 0));
        BlockPos end = new BlockPos(1, 65, 0);
        PathStep bridge = new PathStep(end, MovementType.TRAVERSE, 20,
                List.of(end, end.above()), List.of(), PathRisk.NONE, end.below());
        PlannedCellSource future = new PlannedCellSource(raw, List.of(bridge), 0);
        assertTrue(StanceFinder.isStance(future, 1, 65, 0));
        assertFalse(StanceFinder.isStance(raw, 1, 65, 0));
        raw.set(1, 64, 0, FakeCells.ABSENT);
        assertFalse(CellData.present(future.cell(1, 64, 0)));
    }

    @Test
    void ignoresPastEditsAndNewUnbreakableObstaclesWithoutReadingDuringConstruction() {
        FakeCells raw = FakeCells.empty(new SearchBounds(0, 40, 0, 5, 70, 0));
        BlockPos end = new BlockPos(1, 65, 0);
        raw.set(1, 65, 0, FakeCells.BEDROCK);
        PathStep dig = new PathStep(end, MovementType.TRAVERSE, 40,
                List.of(end), List.of(end), PathRisk.NONE, null);
        OwnerTrackingCells tracked = new OwnerTrackingCells(raw);
        PlannedCellSource future = new PlannedCellSource(tracked.view(), List.of(dig), 0);
        assertNull(tracked.owner());
        assertFalse(CellData.passableEmpty(future.cell(1, 65, 0)));
        raw.set(1, 65, 0, FakeCells.STONE);
        assertFalse(CellData.passableEmpty(new PlannedCellSource(raw, List.of(dig), 1).cell(1, 65, 0)));
        raw.set(1, 65, 0, FakeCells.WATER);
        assertTrue(CellData.water(new PlannedCellSource(raw, List.of(dig), 0).cell(1, 65, 0)),
                "A flooded passage must not become imaginary air");
    }
}
