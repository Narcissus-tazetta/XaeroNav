package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class RetreatWatcherTest {

    private static final BlockPos GOAL = new BlockPos(0, 64, 0);

    private static BlockPos northOf(int blocks) {
        return new BlockPos(0, 64, blocks);
    }

    @Test
    void staysQuietWhileApproaching() {
        RetreatWatcher watcher = new RetreatWatcher();
        for (int distance = 400; distance >= 100; distance -= 20) {
            assertNull(watcher.observe(northOf(distance), GOAL), "近づいている間は黙る: " + distance);
        }
    }

    @Test
    void staysQuietForAnOrdinaryDetour() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        // 溶岩の海や奈落の縁を回る迂回は、目的地から遠ざかりながら正しく進んでいる
        assertNull(watcher.observe(northOf(160), GOAL));
    }

    @Test
    void reportsALargeRetreatWithWhereItTurnedBack() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        RetreatWatcher.Retreat retreat = watcher.observe(northOf(200), GOAL);
        assertNotNull(retreat);
        assertEquals(northOf(100), retreat.closestAt());
        assertEquals(100.0, retreat.closest());
        assertEquals(200.0, retreat.distance());
        assertEquals(100.0, retreat.retreated());
    }

    @Test
    void reportsAgainOnlyAfterRetreatingFurther() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        assertNotNull(watcher.observe(northOf(200), GOAL));
        assertNull(watcher.observe(northOf(210), GOAL), "同じ後退を刻むほど細かく書かない");
        assertNotNull(watcher.observe(northOf(240), GOAL));
    }

    @Test
    void countsTheNextRetreatFromTheNewClosestPoint() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        assertNotNull(watcher.observe(northOf(200), GOAL));
        // 引き返した先で近づき直したら、そこが新しい最接近になる
        watcher.observe(northOf(50), GOAL);
        assertNull(watcher.observe(northOf(120), GOAL));
        RetreatWatcher.Retreat again = watcher.observe(northOf(140), GOAL);
        assertNotNull(again);
        assertEquals(northOf(50), again.closestAt());
    }

    @Test
    void forgetsEverythingOnReset() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        watcher.reset();
        assertNull(watcher.observe(northOf(200), GOAL), "リセット後の最初の位置は最接近そのもの");
    }

    @Test
    void acceptsAGuideThatEndsWithinAnOrdinaryDetour() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(100), GOAL);
        // 溶岩の海の縁を回る迂回は模型実測で最悪67ブロック。ここは通す
        assertFalse(watcher.leadsAway(List.of(northOf(167)), GOAL));
    }

    @Test
    void refusesAGuideThatEndsFurtherThanTheClosestApproach() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(197), GOAL);
        // 実機2026-09-19: 197ブロックの地点から、末端が277ブロックの経路を案内された
        assertTrue(watcher.leadsAway(List.of(northOf(277)), GOAL));
    }

    @Test
    void hasNoBarBeforeTheFirstObservation() {
        // 基準が無いうちに弾くと、最初の1本が出なくなる
        assertFalse(new RetreatWatcher().leadsAway(List.of(northOf(9999)), GOAL));
    }

    @Test
    void movesTheBarWhenThePlayerGetsCloser() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(300), GOAL);
        assertFalse(watcher.leadsAway(List.of(northOf(370)), GOAL));
        watcher.observe(northOf(100), GOAL);
        assertTrue(watcher.leadsAway(List.of(northOf(370)), GOAL), "近づいたら基準もそこへ動く");
    }

    @Test
    void looksAtEveryPointOnTheGuideNotJustItsEnd() {
        RetreatWatcher watcher = new RetreatWatcher();
        watcher.observe(northOf(197), GOAL);
        // 実機2026-09-19: 末端は252(帯の内側)だが、途中で277まで遠ざかる案内だった
        assertTrue(watcher.leadsAway(List.of(northOf(230), northOf(277), northOf(252)), GOAL));
    }
}
