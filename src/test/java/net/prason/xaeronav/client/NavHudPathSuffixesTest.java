package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

class NavHudPathSuffixesTest {

    @Test
    void warningsAndPlacementsDisappearAfterTheirStepWasPassed() {
        PathResult path = new PathResult(List.of(
                step(1, MovementType.TRAVERSE, PathRisk.DROWNING, true),
                step(2, MovementType.BOAT, PathRisk.NONE, false),
                step(3, MovementType.TRAVERSE, PathRisk.MLG_REQUIRED, true),
                step(4, MovementType.TRAVERSE, PathRisk.NONE, false)),
                PathResult.Termination.REACHED_GOAL, 4, 4);
        NavHud.PathSuffixes suffixes = new NavHud.PathSuffixes(path);

        assertTrue(suffixes.hasRisk(0, PathRisk.DROWNING));
        assertTrue(suffixes.usesBoat(0));
        assertEquals(2, suffixes.placements(0));

        assertFalse(suffixes.hasRisk(1, PathRisk.DROWNING));
        assertTrue(suffixes.usesBoat(1));
        assertEquals(1, suffixes.placements(1));

        assertFalse(suffixes.usesBoat(2));
        assertTrue(suffixes.hasRisk(2, PathRisk.MLG_REQUIRED));
        assertFalse(suffixes.hasRisk(3, PathRisk.MLG_REQUIRED));
        assertEquals(0, suffixes.placements(3));
    }

    private static PathStep step(int x, MovementType movement, PathRisk risk, boolean bridging) {
        BlockPos pos = new BlockPos(x, 64, 0);
        return new PathStep(pos, movement, 1.0, List.of(pos), List.of(), risk,
                bridging ? pos.below() : null);
    }
}
