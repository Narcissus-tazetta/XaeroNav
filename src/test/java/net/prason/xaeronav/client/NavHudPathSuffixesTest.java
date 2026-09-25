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
    void nextRequiredActionAdvancesWithThePathAndUsesRouteDistance() {
        PathResult path = new PathResult(List.of(
                step(0, MovementType.TRAVERSE, PathRisk.NONE, false),
                step(1, MovementType.TRAVERSE, PathRisk.NONE, false),
                step(2, MovementType.JUMP, PathRisk.NONE, false),
                step(3, MovementType.TRAVERSE, PathRisk.NONE, true),
                step(4, MovementType.CLIMB, PathRisk.NONE, false)),
                PathResult.Termination.REACHED_GOAL, 5, 5);
        NavHud.PathSuffixes suffixes = new NavHud.PathSuffixes(path);

        assertEquals(NavHud.PathSuffixes.Action.JUMP, suffixes.nextAction(1));
        assertEquals(2.0, suffixes.distanceToAction(1));
        assertEquals(NavHud.PathSuffixes.Action.PLACE, suffixes.nextAction(3));
        assertEquals(NavHud.PathSuffixes.Action.CLIMB, suffixes.nextAction(4));
        assertEquals(null, suffixes.nextAction(5));
    }

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

    @Test
    void placingUnderYourOwnFeetIsAPillarNotABridge() {
        BlockPos base = new BlockPos(1, 64, 0);
        PathResult path = new PathResult(List.of(
                new PathStep(base, MovementType.TRAVERSE, 1.0, List.of(base), List.of(), PathRisk.NONE, null),
                new PathStep(base.above(), MovementType.ASCEND, 1.0, List.of(base.above()), List.of(), PathRisk.NONE,
                        base)),
                PathResult.Termination.REACHED_GOAL, 2, 2);

        assertEquals(NavHud.PathSuffixes.Action.PILLAR, new NavHud.PathSuffixes(path).nextAction(0));
    }

    private static PathStep step(int x, MovementType movement, PathRisk risk, boolean bridging) {
        BlockPos pos = new BlockPos(x, 64, 0);
        return new PathStep(pos, movement, 1.0, List.of(pos), List.of(), risk,
                bridging ? pos.below() : null);
    }
}
