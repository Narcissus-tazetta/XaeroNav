package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VehicleAirTriggerTest {

    @Test
    void groundedWheelsNeverCountAsFlying() {
        VehicleAirTrigger trigger = new VehicleAirTrigger();
        for (int i = 0; i < 100; i++) {
            // 実機で測った、車輪で接地している車の隙間
            assertFalse(trigger.update(0.97));
        }
    }

    @Test
    void aShortHopDoesNotSwitchToFlight() {
        VehicleAirTrigger trigger = new VehicleAirTrigger();
        for (int i = 0; i < VehicleAirTrigger.SUSTAIN_TICKS - 1; i++) {
            assertFalse(trigger.update(5.0));
        }
        assertFalse(trigger.update(1.0));
        assertFalse(trigger.update(5.0), "途切れたら数え直す");
    }

    @Test
    void sustainedHeightSwitchesAndLandingSwitchesBack() {
        VehicleAirTrigger trigger = new VehicleAirTrigger();
        boolean flying = false;
        for (int i = 0; i < VehicleAirTrigger.SUSTAIN_TICKS; i++) {
            flying = trigger.update(19.0);
        }
        assertTrue(flying);
        assertTrue(trigger.update(3.2), "入りより低くても抜けの閾値までは飛んだまま");
        assertFalse(trigger.update(0.0), "着地で地上へ戻る");
    }
}
