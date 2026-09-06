package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class PathLoopsTest {

    private static PathStep at(int x) {
        return new PathStep(new BlockPos(x, 64, 0), MovementType.TRAVERSE, 4.0,
                List.of(), List.of(), PathRisk.NONE, null);
    }

    /** ブロックを置いて渡る手。畳むと足場ごと消えるので、この手を含む区間は畳めない。 */
    private static PathStep bridgeAt(int x) {
        return new PathStep(new BlockPos(x, 64, 0), MovementType.TRAVERSE, 20.0,
                List.of(), List.of(), PathRisk.NONE, new BlockPos(x, 63, 0));
    }

    private static List<BlockPos> positions(List<PathStep> steps) {
        return steps.stream().map(PathStep::pos).toList();
    }

    @Test
    void keepsAPathThatNeverRevisitsACell() {
        List<PathStep> steps = List.of(at(1), at(2), at(3));
        PathLoops.Folded folded = PathLoops.fold(steps);
        assertFalse(folded.changed());
        assertEquals(steps, folded.steps());
        assertArrayEquals(new int[] {0, 1, 2}, folded.newIndex());
    }

    @Test
    void dropsTheStepsBetweenTwoVisitsToTheSameCell() {
        // 1→2→3→2→4 は、2で折り返しているので 1→2→4 と同じ場所を通る
        List<PathStep> folded = PathLoops.fold(List.of(at(1), at(2), at(3), at(2), at(4))).steps();
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(2, 64, 0), new BlockPos(4, 64, 0)),
                positions(folded));
    }

    /** 区間の境目を張り直せるように、消えたステップの添字は残った方を指す。 */
    @Test
    void mapsDroppedIndexesOntoTheSurvivingStep() {
        PathLoops.Folded folded = PathLoops.fold(List.of(at(1), at(2), at(3), at(2), at(4)));
        assertArrayEquals(new int[] {0, 1, 1, 1, 2}, folded.newIndex());
    }

    @Test
    void keepsALoopThatPlacedABlockTheLaterStepsStandOn() {
        List<PathStep> steps = List.of(at(1), at(2), bridgeAt(3), at(2), at(4));
        assertFalse(PathLoops.fold(steps).changed());
    }

    @Test
    void foldsRepeatedlyWhenALoopHidesAnotherLoop() {
        // 1→2→3→4→3→2→5。内側(3で折り返し)を畳むと外側(2で折り返し)が現れる
        List<PathStep> folded =
                PathLoops.fold(List.of(at(1), at(2), at(3), at(4), at(3), at(2), at(5))).steps();
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(2, 64, 0), new BlockPos(5, 64, 0)),
                positions(folded));
        assertTrue(folded.size() == 3);
    }
}
