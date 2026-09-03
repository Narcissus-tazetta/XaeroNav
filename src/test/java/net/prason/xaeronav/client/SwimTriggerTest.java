package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwimTriggerTest {

    /** 追尾ナビに入れる深さ。 */
    private static final int DEEP = SwimTrigger.MIN_DEPTH_BLOCKS;

    /** 水面すれすれ（ユーザー報告「見えてるのに追尾の線が邪魔」）。 */
    private static final int SHALLOW = SwimTrigger.MIN_DEPTH_BLOCKS - 1;

    private final SwimTrigger trigger = new SwimTrigger();

    /** 水没していないうちは有効にならない。 */
    @Test
    void staysOffUntilFullySubmerged() {
        assertFalse(trigger.update(true, false, false, DEEP));
        assertFalse(trigger.update(true, false, true, DEEP), "水に触れただけ（頭は出ている）では入らない");
    }

    /** 目が水中に入った瞬間に有効になる。 */
    @Test
    void turnsOnWhenTheHeadGoesUnder() {
        assertTrue(trigger.update(true, true, true, DEEP));
    }

    /** 水から完全に出たら即座に抜ける。 */
    @Test
    void turnsOffImmediatelyWhenLeavingTheWater() {
        trigger.update(true, true, true, DEEP);
        assertFalse(trigger.update(true, false, false, DEEP));
    }

    /** 頭が一瞬だけ水面から出るくらいでは抜けない（波・視点の揺れ）。 */
    @Test
    void toleratesBriefHeadBreaches() {
        trigger.update(true, true, true, DEEP);
        for (int tick = 0; tick < SwimTrigger.HEAD_OUT_GRACE_TICKS - 1; tick++) {
            assertTrue(trigger.update(true, false, true, DEEP), "猶予tick " + tick + " で早々に抜けた");
        }
        // 再び潜れば猶予はリセットされる
        assertTrue(trigger.update(true, true, true, DEEP));
        assertTrue(trigger.update(true, false, true, DEEP));
    }

    /** 頭を出したまま猶予を超えて泳ぎ続けたら抜ける（意図した浮上）。 */
    @Test
    void turnsOffAfterSustainedHeadOut() {
        trigger.update(true, true, true, DEEP);
        boolean active = true;
        for (int tick = 0; tick < SwimTrigger.HEAD_OUT_GRACE_TICKS; tick++) {
            active = trigger.update(true, false, true, DEEP);
        }
        assertFalse(active);
    }

    /** 水面すれすれ（1〜2マス）では、目が水中でも追尾ナビに入らない。 */
    @Test
    void staysOffInShallowWater() {
        assertFalse(trigger.update(true, true, true, SHALLOW));
    }

    /** 深く潜ってから浮上して水面すれすれになったら、猶予のあと抜ける。 */
    @Test
    void turnsOffAfterSurfacingToShallowWater() {
        assertTrue(trigger.update(true, true, true, DEEP));
        boolean active = true;
        for (int tick = 0; tick < SwimTrigger.HEAD_OUT_GRACE_TICKS; tick++) {
            active = trigger.update(true, true, true, SHALLOW);
        }
        assertFalse(active, "浅くなったまま猶予を超えても追尾ナビが続いている");
    }

    /** 浅い場所を一瞬通っただけでは抜けない（頭が出るのと同じ猶予）。 */
    @Test
    void toleratesBriefShallows() {
        trigger.update(true, true, true, DEEP);
        assertTrue(trigger.update(true, true, true, SHALLOW));
        assertTrue(trigger.update(true, true, true, DEEP));
    }

    /** 設定offなら状態に関わらず常に無効。 */
    @Test
    void staysOffWhenDisabled() {
        assertFalse(trigger.update(false, true, true, DEEP));
    }
}
