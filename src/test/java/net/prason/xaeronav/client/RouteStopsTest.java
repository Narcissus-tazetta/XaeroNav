package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/** 「経由地に追加」は最終目的地を変えずに、寄り道が一番短くなる位置へ入る。 */
class RouteStopsTest {

    private static final BlockPos PLAYER = new BlockPos(0, 64, 0);

    @Test
    void stopOnTheWayToTheGoalGoesBeforeTheGoal() {
        List<BlockPos> route = List.of(new BlockPos(1000, 64, 0));
        assertEquals(0, RouteStops.cheapestInsertion(PLAYER, route, new BlockPos(500, 64, 20)));
    }

    @Test
    void stopBetweenTwoLaterPointsGoesBetweenThem() {
        List<BlockPos> route = List.of(new BlockPos(100, 64, 0), new BlockPos(500, 64, 0), new BlockPos(1000, 64, 0));
        assertEquals(2, RouteStops.cheapestInsertion(PLAYER, route, new BlockPos(750, 64, 30)));
    }

    @Test
    void stopBehindThePlayerStillNeverGoesAfterTheFinalGoal() {
        List<BlockPos> route = List.of(new BlockPos(1000, 64, 0));
        assertEquals(0, RouteStops.cheapestInsertion(PLAYER, route, new BlockPos(2000, 64, 0)));
    }

    @Test
    void reachingALaterPointSkipsTheStopsBeforeIt() {
        RouteStops stops = new RouteStops();
        stops.append(new BlockPos(1, 64, 0));
        stops.append(new BlockPos(2, 64, 0));
        stops.append(new BlockPos(3, 64, 0));
        assertEquals(new BlockPos(2, 64, 0), stops.advanceTo(1));
        assertEquals(2, stops.passed());
        assertEquals(List.of(new BlockPos(3, 64, 0)), stops.ahead());
    }

    @Test
    void aWaypointAtAnotherHeightDoesNotRemoveTheFirstStopInTheColumn() {
        BlockPos lower = new BlockPos(10, 64, 20);
        BlockPos upper = new BlockPos(10, 94, 20);
        BlockPos unknownHeight = new BlockPos(10, 32767, 20);

        assertEquals(1, RouteStops.indexOfMarker(List.of(lower, upper), upper));
        assertEquals(-1, RouteStops.indexOfMarker(List.of(lower, upper), unknownHeight));
        assertEquals(0, RouteStops.indexOfMarker(List.of(lower), unknownHeight));
    }
}
