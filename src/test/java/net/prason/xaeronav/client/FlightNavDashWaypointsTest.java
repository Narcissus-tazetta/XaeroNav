package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class FlightNavDashWaypointsTest {

    @Test
    void dashWaypointsReturnsEmptyWhenNotAirborneOrDoneOrGoalNull() {
        FlightNavState state = new FlightNavState((g, d) -> true, () -> {});
        BlockPos goal = new BlockPos(100, 64, 200);

        List<Vec3> whenNotAirborne = state.dashWaypoints(false, false, goal);
        assertTrue(whenNotAirborne.isEmpty());

        List<Vec3> whenDone = state.dashWaypoints(true, true, goal);
        assertTrue(whenDone.isEmpty());

        List<Vec3> whenGoalNull = state.dashWaypoints(true, false, null);
        assertTrue(whenGoalNull.isEmpty());
    }

    @Test
    void dashWaypointsReturnsSameInstanceAcrossCallsWithoutAllocations() {
        FlightNavState state = new FlightNavState((g, d) -> true, () -> {});
        BlockPos goal = new BlockPos(100, 64, 200);

        List<Vec3> first = state.dashWaypoints(true, false, goal);
        List<Vec3> second = state.dashWaypoints(true, false, goal);

        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    void dropRouteAndResetClearDashWaypoints() {
        FlightNavState state = new FlightNavState((g, d) -> true, () -> {});
        BlockPos goal = new BlockPos(100, 64, 200);

        state.dropRoute();
        assertTrue(state.dashWaypoints(true, false, goal).isEmpty());

        state.reset();
        assertTrue(state.dashWaypoints(true, false, goal).isEmpty());
    }
}
