package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 経路の末端を目的地と取り違えないHUD文言の選択。 */
class NavHudTextTest {

    @Test
    void onlyAPathEndingAtTheDestinationUsesTheUnqualifiedRemainingLabel() {
        assertEquals("hud.xaeronav.remaining", NavHud.remainingKey(true, false));
        assertEquals("hud.xaeronav.path_remaining", NavHud.remainingKey(false, false));
        assertEquals("hud.xaeronav.path_remaining_eta", NavHud.remainingKey(false, true));
    }

    @Test
    void arrivalTextDistinguishesDestinationSurfaceAndIntermediateEnds() {
        assertEquals("hud.xaeronav.arriving",
                NavHud.endpointKey(false, true));
        assertEquals("hud.xaeronav.surface_ahead",
                NavHud.endpointKey(true, false));
        assertEquals("hud.xaeronav.route_continues",
                NavHud.endpointKey(false, false));
    }
}
