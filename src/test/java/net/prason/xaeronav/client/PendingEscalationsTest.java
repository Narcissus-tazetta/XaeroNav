package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.client.PendingEscalations.SearchAttempt;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathResult.Termination;

/** 探索が届かなかった理由ごとに、一度きりの再挑戦だけが立ち、無限往復にならない。 */
class PendingEscalationsTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos TARGET = new BlockPos(100, 64, 0);
    private static final SearchAttempt PLAIN = new SearchAttempt(false, false, false, false);

    private static PathResult ended(Termination termination) {
        return new PathResult(List.of(), termination, 0, 0);
    }

    private final PendingEscalations escalations = new PendingEscalations();

    @Test
    void reachedGoalReservesNothing() {
        escalations.noteResult(ended(Termination.REACHED_GOAL), START, TARGET, PLAIN, true);
        assertFalse(escalations.any());
        assertEquals(Escalation.NONE, escalations.take());
    }

    @Test
    void outOfRangeSearchIsRetriedOnceWithAWiderBox() {
        escalations.noteResult(ended(Termination.EXHAUSTED), START, TARGET, PLAIN, true);
        assertEquals(Escalation.WIDE, escalations.take());
        assertEquals(Escalation.NONE, escalations.take());

        // 広げた探索でも届かなかったときに、もう一度広げ直す予約は立たない
        escalations.noteResult(ended(Termination.EXHAUSTED), START, TARGET,
                new SearchAttempt(true, false, false, false), true);
        assertEquals(Escalation.NONE, escalations.take());
    }

    @Test
    void budgetExhaustionRaisesTheBudgetBeforeSplittingTheRoute() {
        escalations.noteResult(ended(Termination.NODE_BUDGET), START, TARGET, PLAIN, true);
        assertEquals(Escalation.DEEP, escalations.take());

        escalations.noteResult(ended(Termination.NODE_BUDGET), START, TARGET,
                new SearchAttempt(false, false, true, false), true);
        assertEquals(Escalation.COARSE_GUIDED, escalations.take());
    }

    @Test
    void aNavGraphGuidedSearchIsNeverHandedToTheCoarseChain() {
        escalations.noteResult(ended(Termination.TIME_LIMIT), START, TARGET,
                new SearchAttempt(false, false, true, true), true);
        assertEquals(Escalation.NONE, escalations.take());
    }

    @Test
    void theCoarseChainDoesNotEscalateFurther() {
        escalations.noteResult(ended(Termination.NODE_BUDGET), START, TARGET,
                new SearchAttempt(false, true, true, false), true);
        assertFalse(escalations.any());
    }

    @Test
    void aTargetOutsideTheDrawBoxReservesNothing() {
        escalations.noteResult(ended(Termination.NODE_BUDGET), START, TARGET, PLAIN, false);
        assertFalse(escalations.any());
    }

    @Test
    void aNewResultDropsTheReservationOfTheOneBefore() {
        escalations.noteResult(ended(Termination.EXHAUSTED), START, TARGET, PLAIN, true);
        escalations.noteResult(ended(Termination.REACHED_GOAL), START, TARGET, PLAIN, true);
        assertFalse(escalations.any());
    }

    @Test
    void reservedTargetMatchesNearbyPointsOnly() {
        escalations.noteResult(ended(Termination.EXHAUSTED), START, TARGET, PLAIN, true);
        assertTrue(escalations.wideReserved(new BlockPos(100 + 24, 70, 0)));
        assertFalse(escalations.wideReserved(new BlockPos(100 + 25, 70, 0)));
    }

    @Test
    void plainSearchIsSkippedNearWhereItRanOutOfBudgetAndRetriedAfterWalkingAway() {
        escalations.noteResult(ended(Termination.NODE_BUDGET), START, TARGET, PLAIN, true);
        assertTrue(escalations.plainSearchHopeless(new BlockPos(31, 64, 0)));
        assertFalse(escalations.plainSearchHopeless(new BlockPos(32, 64, 0)));
    }

    @Test
    void aCoarseChainResultDoesNotChangeWhatWeKnowAboutThePlainSearch() {
        escalations.noteResult(ended(Termination.NODE_BUDGET), START, TARGET, PLAIN, true);
        escalations.noteResult(ended(Termination.REACHED_GOAL), START, TARGET,
                new SearchAttempt(false, true, false, false), true);
        assertTrue(escalations.plainSearchHopeless(START));
    }

    @Test
    void resetForgetsThePlainBudgetButKeepsTheReservedTargetMemory() {
        escalations.noteResult(ended(Termination.NODE_BUDGET), START, TARGET, PLAIN, true);
        escalations.noteResult(ended(Termination.EXHAUSTED), START, TARGET, PLAIN, true);
        escalations.reset();
        assertFalse(escalations.any());
        assertFalse(escalations.plainSearchHopeless(START));
        assertTrue(escalations.wideReserved(TARGET));
    }
}
