package net.prason.xaeronav.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 「前回と同じ値なら黙る」重複ログ抑制の挙動。 */
class ChangeGateTest {

    @Test
    void theFirstValueIsAlwaysReportedEvenThoughNothingWasSeenBefore() {
        ChangeGate<String> gate = new ChangeGate<>();
        assertTrue(gate.changed("a"));
    }

    @Test
    void repeatingTheSameValueIsSuppressed() {
        ChangeGate<String> gate = new ChangeGate<>();
        assertTrue(gate.changed("a"));
        assertFalse(gate.changed("a"));
        assertFalse(gate.changed("a"));
    }

    @Test
    void aDifferentValueIsReportedAgain() {
        ChangeGate<String> gate = new ChangeGate<>();
        assertTrue(gate.changed("a"));
        assertTrue(gate.changed("b"));
    }

    @Test
    void resetMakesTheNextValueReportedAgainEvenIfItRepeats() {
        ChangeGate<String> gate = new ChangeGate<>();
        assertTrue(gate.changed("a"));
        gate.reset();
        assertTrue(gate.changed("a"));
    }

    @Test
    void theTimedOverloadSuppressesTheSameValueOnlyWithinTheInterval() {
        ChangeGate<String> gate = new ChangeGate<>();
        assertTrue(gate.changed("a", 1000L, 500L));
        assertFalse(gate.changed("a", 1200L, 500L));
        assertTrue(gate.changed("a", 1600L, 500L));
    }

    @Test
    void theTimedOverloadAlwaysReportsADifferentValueRegardlessOfInterval() {
        ChangeGate<String> gate = new ChangeGate<>();
        assertTrue(gate.changed("a", 1000L, 500L));
        assertTrue(gate.changed("b", 1001L, 500L));
    }
}
