package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/** 遠い目的地にだけ、ネザーでの換算距離（1/8）を出す。 */
class NetherShortcutTest {

    @Test
    void farDestinationShowsOneEighthOfTheDistance() {
        assertEquals(OptionalInt.of(500), NetherShortcut.netherBlocks(4000));
    }

    @Test
    void thresholdIsInclusive() {
        assertEquals(OptionalInt.of(125), NetherShortcut.netherBlocks(NetherShortcut.MIN_OVERWORLD_BLOCKS));
    }

    @Test
    void nearDestinationShowsNothing() {
        assertTrue(NetherShortcut.netherBlocks(NetherShortcut.MIN_OVERWORLD_BLOCKS - 1).isEmpty());
    }
}
