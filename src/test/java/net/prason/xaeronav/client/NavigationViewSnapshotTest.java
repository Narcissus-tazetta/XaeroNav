package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;

class NavigationViewSnapshotTest {

    @Test
    void emptyViewHasNullLegPreviewAndStopProgress() {
        PathfindingState.NavigationView view = new PathfindingState.NavigationView(
                null, false, false, false, null, null, null, null, 0, false, null,
                FlightRoute.NONE, null, null, null, null);

        assertNull(view.legPreview());
        assertNull(view.legPreviewPath());
        assertNull(view.legPreviewFrom());
        assertNull(view.stopProgress());
    }

    @Test
    void viewExposesLegPreviewSnapshotAndConvenienceAccessors() {
        BlockPos from = new BlockPos(10, 64, 20);
        PathResult result = new PathResult(List.of(), PathResult.Termination.REACHED_GOAL, 10, 5);
        PathfindingState.LegPreviewSnapshot preview = new PathfindingState.LegPreviewSnapshot(result, from);
        PathfindingState.StopProgress stops = new PathfindingState.StopProgress(2, 5);

        PathfindingState.NavigationView view = new PathfindingState.NavigationView(
                new BlockPos(100, 64, 200), false, false, false, null, null, null, null, 0, false, null,
                FlightRoute.NONE, null, null, preview, stops);

        assertNotNull(view.legPreview());
        assertSame(preview, view.legPreview());
        assertSame(result, view.legPreviewPath());
        assertEquals(from, view.legPreviewFrom());

        assertNotNull(view.stopProgress());
        assertEquals(2, view.stopProgress().index());
        assertEquals(5, view.stopProgress().total());
    }
}
