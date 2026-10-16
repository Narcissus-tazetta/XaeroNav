package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 経路が分からない区間を結ぶ点線は、短すぎれば描かず、長くても点の数に上限がある。 */
class StraightDotsTest {

    private static List<int[]> dots(int fromX, int fromZ, int toX, int toZ) {
        List<int[]> dots = new ArrayList<>();
        StraightDots.forEach(fromX, fromZ, toX, toZ, (x, z) -> dots.add(new int[] {x, z}));
        return dots;
    }

    @Test
    void anythingShorterThanOnePeriodDrawsNothing() {
        assertTrue(dots(0, 0, 3, 0).isEmpty());
        assertTrue(dots(5, 5, 5, 5).isEmpty());
    }

    @Test
    void dashesCoverTheFirstTwoOfEveryFourBlocks() {
        List<int[]> dots = dots(0, 0, 8, 0);
        assertEquals(4, dots.size());
        assertEquals(List.of(0, 1, 4, 5), dots.stream().map(dot -> dot[0]).toList());
    }

    @Test
    void dashPhaseIsCountedFromTheStartInNegativeDirectionsToo() {
        List<int[]> dots = dots(0, 0, -8, 0);
        assertEquals(List.of(0, -1, -4, -5), dots.stream().map(dot -> dot[0]).toList());
    }

    @Test
    void farDestinationsStopAtTheDotLimit() {
        List<int[]> dots = dots(0, 0, 100_000, 0);
        assertTrue(dots.get(dots.size() - 1)[0] < 4096);
    }
}
