package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

class NavGuidanceTest {

    private static PathResult path(int length, boolean complete) {
        List<PathStep> steps = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            BlockPos pos = new BlockPos(i, 60, 0);
            steps.add(new PathStep(pos, MovementType.TRAVERSE, 4.0,
                    List.of(), List.of(), PathRisk.NONE, null));
        }
        return new PathResult(steps,
                complete ? PathResult.Termination.REACHED_GOAL : PathResult.Termination.NODE_BUDGET,
                length, length);
    }

    @Test
    void reportsRouteDistanceAndTimeWithoutTurnInstructions() {
        NavGuidance guidance = NavGuidance.forPath(path(30, true), new BlockPos(0, 60, 0), 0.0);

        assertEquals(29, guidance.remainingBlocks);
        assertTrue(guidance.remainingSeconds > 0);
        assertFalse(guidance.nearEnd);
        assertTrue(guidance.complete);
    }

    @Test
    void incompleteRouteHasANearEndWithoutClaimingArrival() {
        NavGuidance guidance = NavGuidance.forPath(path(2, false), new BlockPos(0, 60, 0), 0.0);

        assertTrue(guidance.nearEnd);
        assertFalse(guidance.complete);
    }

    @Test
    void timeBeyondTheRouteIsAddedWithoutChangingTheDistance() {
        PathResult result = path(30, false);
        NavGuidance routeOnly = NavGuidance.forPath(result, new BlockPos(0, 60, 0), 0.0);
        // 実線の先に60秒ぶん
        NavGuidance withBeyond = NavGuidance.forPath(result, new BlockPos(0, 60, 0), 1200.0);

        assertEquals(routeOnly.remainingBlocks, withBeyond.remainingBlocks);
        int added = withBeyond.remainingSeconds - routeOnly.remainingSeconds;
        assertTrue(added >= 40 && added <= 80, "足された秒数: " + added);
    }

    @Test
    void dotsFollowTheWaypointsAheadAndSkipThePassedOnes() {
        List<BlockPos> waypoints = List.of(new BlockPos(0, 60, 0), new BlockPos(100, 60, 0), new BlockPos(100, 60, 100));

        // 始点は1本目の区間の上。0,0の中間目標は通過済み
        double length = GoalEta.alongDots(new BlockPos(50, 60, 0), new BlockPos(100, 60, 200), waypoints);

        assertEquals(50 + 100 + 100, length, 1e-6);
    }
}
