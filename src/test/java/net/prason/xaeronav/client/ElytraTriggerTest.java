package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ElytraTriggerTest {

    private static final int REQUIRED_CLEARANCE = 4;
    private static final int HIGH_ABOVE_GROUND = 32;

    private final ElytraTrigger trigger = new ElytraTrigger();

    /**
     * 1マス橋の上で跳ねたときのように、滑空判定が数tickだけ立っても飛行モードにはしない。
     *
     * <p>高さは十分ある（橋の上＝真下は奈落）状態で見るのが要点——高さのヒステリシスでは
     * この場合を止められないので、時間で止まっていなければ通ってしまう。
     */
    @Test
    void ignoresBriefGlidesFromJumping() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS - 1; tick++) {
            assertFalse(trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE),
                    "継続tick " + tick + " で早々に飛行モードへ入った");
        }
        // 着地すればカウントは振り出しに戻る
        assertFalse(trigger.update(false, 0, REQUIRED_CLEARANCE));
        assertFalse(trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE),
                "跳ね直した1tick目で入った＝継続の数え直しができていない");
    }

    /** 本物の滑空（継続して高さもある）は飛行モードになる。 */
    @Test
    void turnsOnForASustainedGlide() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS - 1; tick++) {
            trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE);
        }
        assertTrue(trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE));
    }

    /** 継続していても地面すれすれなら入らない。 */
    @Test
    void staysOffWhileHuggingTheGround() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS * 2; tick++) {
            assertFalse(trigger.update(true, REQUIRED_CLEARANCE - 1, REQUIRED_CLEARANCE),
                    "継続tick " + tick + " で入った");
        }
    }

    /** 入った後は閾値が下がる。境界の上を滑空している間ずっと往復しないため。 */
    @Test
    void keepsGlidingBelowTheEntryClearance() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS; tick++) {
            trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE);
        }
        assertTrue(trigger.update(true, REQUIRED_CLEARANCE - 1, REQUIRED_CLEARANCE),
                "入るのと同じ高さで抜けている");
        assertFalse(trigger.update(true, 0, REQUIRED_CLEARANCE), "地面に着くほど下がっても抜けない");
    }

    /** 滑空が終わったら即座に抜ける（着地）。 */
    @Test
    void turnsOffAsSoonAsTheGlideEnds() {
        for (int tick = 0; tick < ElytraTrigger.SUSTAIN_TICKS; tick++) {
            trigger.update(true, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE);
        }
        assertFalse(trigger.update(false, HIGH_ABOVE_GROUND, REQUIRED_CLEARANCE));
    }

    /** 高さを問わない設定（0）でも、継続の条件だけは残る。 */
    @Test
    void stillRequiresSustainWhenClearanceIsNotChecked() {
        assertFalse(trigger.update(true, 0, 0));
        for (int tick = 1; tick < ElytraTrigger.SUSTAIN_TICKS; tick++) {
            trigger.update(true, 0, 0);
        }
        assertTrue(trigger.update(true, 0, 0));
    }
}
