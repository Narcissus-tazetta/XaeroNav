package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class RecentFailuresTest {

    @Test
    void remembersTheCellSoTheNextSearchCanAvoidIt() {
        RecentFailures failures = new RecentFailures();
        assertTrue(failures.avoided().isEmpty());

        BlockPos cell = new BlockPos(2051, 62, 1283);
        failures.note(cell);
        assertEquals(List.of(cell), failures.avoided());
    }

    @Test
    void keepsOnlyTheLatestCellsSoTheSearchIsNotFencedIn() {
        RecentFailures failures = new RecentFailures();
        for (int i = 0; i < 12; i++) {
            failures.note(new BlockPos(i, 64, 0));
        }
        List<BlockPos> avoided = failures.avoided();
        assertEquals(8, avoided.size());
        assertFalse(avoided.contains(new BlockPos(0, 64, 0)), "古い方から捨てる");
        assertTrue(avoided.contains(new BlockPos(11, 64, 0)));
    }

    @Test
    void notingTheSameCellTwiceDoesNotConsumeTwoSlots() {
        RecentFailures failures = new RecentFailures();
        BlockPos cell = new BlockPos(0, 64, 0);
        failures.note(cell);
        failures.note(cell);
        assertEquals(List.of(cell), failures.avoided());
    }

    @Test
    void forgetsEverythingWhenTheGoalChanges() {
        RecentFailures failures = new RecentFailures();
        failures.note(new BlockPos(0, 64, 0));
        failures.clear();
        assertTrue(failures.avoided().isEmpty());
    }
}
