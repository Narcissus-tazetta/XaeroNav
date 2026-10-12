package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** チャットの文から、押して目的地にできる「X Y Z」だけを拾う。 */
class ChatCoordinatesTest {

    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;

    private static List<ChatCoordinates.Match> find(String text) {
        return ChatCoordinates.find(text, MIN_Y, MAX_Y);
    }

    @Test
    void threeNumbersSeparatedBySpacesOrCommasAreACoordinate() {
        String text = "base at 90 215 521, nether hub -12, 70, -3";
        List<ChatCoordinates.Match> found = find(text);
        assertEquals(List.of(new ChatCoordinates.Match(8, 18, 90, 215, 521),
                new ChatCoordinates.Match(31, 42, -12, 70, -3)), found);
        assertEquals("90 215 521", text.substring(8, 18));
        assertEquals("-12, 70, -3", text.substring(31, 42));
    }

    @Test
    void labeledAxesAreACoordinateAndTheLabelsAreInsideTheLink() {
        String text = "Waypoint X: 100, Y: 64, Z: -200 here";
        List<ChatCoordinates.Match> found = find(text);
        assertEquals(List.of(new ChatCoordinates.Match(9, 31, 100, 64, -200)), found);
        assertEquals("X: 100, Y: 64, Z: -200", text.substring(9, 31));
        assertEquals(List.of(new ChatCoordinates.Match(0, 11, 1, 2, 3)), find("x=1 y=2 z=3"));
    }

    @Test
    void twoOrFourNumbersAreNotACoordinate() {
        assertEquals(List.of(), find("I need 10 20 more"));
        assertEquals(List.of(), find("1 2 3 4"));
        assertEquals(List.of(), find("90 64 100 200 300"));
    }

    @Test
    void decimalsAndNumbersStuckToWordsAreNotACoordinate() {
        assertEquals(List.of(), find("XYZ: 90.512 / 64.000 / 100.3"));
        assertEquals(List.of(), find("90.5 64 100"));
        assertEquals(List.of(), find("v1 2 3"));
        assertEquals(List.of(), find("1 2 3rd"));
    }

    @Test
    void heightOutsideTheWorldIsNotACoordinate() {
        assertEquals(List.of(), find("0 320 0"));
        assertEquals(List.of(), find("0 -65 0"));
        assertEquals(List.of(new ChatCoordinates.Match(0, 7, 0, -64, 0)), find("0 -64 0"));
        assertEquals(List.of(), ChatCoordinates.find("0 200 0", 0, 128));
    }

    @Test
    void numbersBeyondTheWorldBorderAreNotACoordinate() {
        assertEquals(List.of(), find("30000001 64 0"));
        assertEquals(List.of(), find("99999999999 64 0"));
        assertEquals(List.of(new ChatCoordinates.Match(0, 21, -30000000, 64, 30000000)),
                find("-30000000 64 30000000"));
    }
}
