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
        NavGuidance guidance = NavGuidance.forPath(path(30, true), new BlockPos(0, 60, 0));

        assertEquals(29, guidance.remainingBlocks);
        assertTrue(guidance.remainingSeconds > 0);
        assertFalse(guidance.nearEnd);
        assertTrue(guidance.complete);
    }

    @Test
    void incompleteRouteHasANearEndWithoutClaimingArrival() {
        NavGuidance guidance = NavGuidance.forPath(path(2, false), new BlockPos(0, 60, 0));

        assertTrue(guidance.nearEnd);
        assertFalse(guidance.complete);
    }
}
