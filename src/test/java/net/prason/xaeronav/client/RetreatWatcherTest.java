package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
