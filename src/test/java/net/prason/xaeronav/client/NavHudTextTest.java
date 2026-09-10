package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 経路の末端を目的地と取り違えないHUD文言の選択。 */
class NavHudTextTest {

    @Test
    void onlyAPathEndingAtTheDestinationUsesTheUnqualifiedRemainingLabel() {
        assertEquals("hud.xaeronav.remaining", NavHud.remainingKey(true));
        assertEquals("hud.xaeronav.path_remaining", NavHud.remainingKey(false));
    }

    @Test
    void arrivalTextDistinguishesDestinationSurfaceAndIntermediateEnds() {
        assertEquals("hud.xaeronav.arriving",
                NavHud.instructionKey(NavGuidance.Turn.ARRIVE, false, true));
        assertEquals("hud.xaeronav.surface_ahead",
                NavHud.instructionKey(NavGuidance.Turn.ARRIVE, true, false));
        assertEquals("hud.xaeronav.route_continues",
                NavHud.instructionKey(NavGuidance.Turn.ARRIVE, false, false));
    }

    @Test
    void ordinaryDirectionsDoNotDependOnTheKindOfEndpoint() {
        assertEquals("hud.xaeronav.straight",
                NavHud.instructionKey(NavGuidance.Turn.STRAIGHT, false, false));
        assertEquals("hud.xaeronav.turn_left",
                NavHud.instructionKey(NavGuidance.Turn.LEFT, true, false));
        assertEquals("hud.xaeronav.turn_right",
                NavHud.instructionKey(NavGuidance.Turn.RIGHT, false, true));
    }
}
