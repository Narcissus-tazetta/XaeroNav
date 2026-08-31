package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwimTriggerTest {

    private final SwimTrigger trigger = new SwimTrigger();

    /** 水没していないうちは有効にならない。 */
    @Test
    void staysOffUntilFullySubmerged() {
        assertFalse(trigger.update(true, false, false));
        assertFalse(trigger.update(true, false, true), "水に触れただけ（頭は出ている）では入らない");
    }

    /** 目が水中に入った瞬間に有効になる。 */
    @Test
    void turnsOnWhenTheHeadGoesUnder() {
        assertTrue(trigger.update(true, true, true));
    }

    /** 水から完全に出たら即座に抜ける。 */
    @Test
    void turnsOffImmediatelyWhenLeavingTheWater() {
        trigger.update(true, true, true);
        assertFalse(trigger.update(true, false, false));
    }

    /** 頭が一瞬だけ水面から出るくらいでは抜けない（波・視点の揺れ）。 */
    @Test
    void toleratesBriefHeadBreaches() {
        trigger.update(true, true, true);
        for (int tick = 0; tick < SwimTrigger.HEAD_OUT_GRACE_TICKS - 1; tick++) {
            assertTrue(trigger.update(true, false, true), "猶予tick " + tick + " で早々に抜けた");
        }
        // 再び潜れば猶予はリセットされる
        assertTrue(trigger.update(true, true, true));
        assertTrue(trigger.update(true, false, true));
    }

    /** 頭を出したまま猶予を超えて泳ぎ続けたら抜ける（意図した浮上）。 */
    @Test
    void turnsOffAfterSustainedHeadOut() {
        trigger.update(true, true, true);
        boolean active = true;
        for (int tick = 0; tick < SwimTrigger.HEAD_OUT_GRACE_TICKS; tick++) {
            active = trigger.update(true, false, true);
        }
        assertFalse(active);
    }

    /** 設定offなら状態に関わらず常に無効。 */
    @Test
    void staysOffWhenDisabled() {
        assertFalse(trigger.update(false, true, true));
    }
}
