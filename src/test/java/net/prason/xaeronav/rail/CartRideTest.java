package net.prason.xaeronav.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * トロッコの走りの模擬。平らな直線の期待値は、実機と全セル0tick差だった1次元の模擬
 * （調査で使った{@code cartsim.py}の{@code sim_old}）の出力。
 */
class CartRideTest {

    private final Long2IntOpenHashMap rails = new Long2IntOpenHashMap();

    CartRideTest() {
        rails.defaultReturnValue(CartRide.NONE);
    }

    private void put(int x, int y, int z, TrackShape shape, RailKind kind, boolean powered) {
        rails.put(key(x, y, z), RailCell.pack(0, y, 0, shape, kind, powered));
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    /** 東向きの直線。{@code 'P'}=通電パワード、{@code 'B'}=非通電パワード、{@code 'D'}=検知、それ以外は普通のレール。 */
    private void eastLine(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            RailKind kind = c == 'P' || c == 'B' ? RailKind.POWERED : c == 'D' ? RailKind.DETECTOR : RailKind.RAIL;
            put(i, 64, 0, TrackShape.EAST_WEST, kind, c == 'P');
        }
    }

    private record Entry(int x, int y, int z, int tick, boolean forcedExit) {
    }

    private List<Entry> ride(int x, int y, int z, int exit, double speed, boolean pushing, int maxTicks) {
        List<Entry> entries = new ArrayList<>();
        CartRide.ride((rx, ry, rz) -> rails.get(key(rx, ry, rz)), x, y, z, exit, speed, pushing, maxTicks,
                (ex, ey, ez, tick, forced) -> {
                    entries.add(new Entry(ex, ey, ez, tick, forced));
                    return true;
                });
        return entries;
    }

    private static int tickAt(List<Entry> entries, int x) {
        return entries.stream().filter(e -> e.x() == x).findFirst().orElseThrow().tick();
    }

    @Test
    void steadyStateMatchesTheVerifiedModel() {
        // 元の模擬は位置を浮動小数で足し続けるので、始点を境界から僅かに離した（x0=1e-7）出力と比べる。
        // セルを跨ぐtickの位相でパワードの上で過ごすtick数が変わり、間隔48で数%動く
        int[][] expected = {{1, 2500, 7500}, {48, 2739, 8666}, {64, 3456, 11414}};
        for (int[] row : expected) {
            rails.clear();
            int spacing = row[0];
            eastLine(("P" + "R".repeat(spacing - 1)).repeat(4000 / spacing + 1));
            List<Entry> entries = ride(0, 64, 0, 1, 2.0, false, 20_000);
            assertEquals(row[1], tickAt(entries, 1000), "spacing " + spacing);
            assertEquals(row[2], tickAt(entries, 3000), "spacing " + spacing);
        }
    }

    @Test
    void twoBrakesStopTheCart() {
        eastLine("R".repeat(30) + "BB" + "R".repeat(200));
        List<Entry> entries = ride(0, 64, 0, 1, 2.0, false, 20_000);
        int stoppedAt = entries.get(entries.size() - 1).x();
        assertTrue(stoppedAt == 30 || stoppedAt == 31, "stopped at " + stoppedAt);
    }

    @Test
    void pushingCreepsAtPushSpeedAndIgnoresBrakes() {
        eastLine("R".repeat(5) + "BB" + "R".repeat(5));
        List<Entry> entries = ride(0, 64, 0, 1, 0.0, true, 2_000);
        assertEquals(11, entries.get(entries.size() - 1).x());
        // 0.075ブロック/tickで1ブロック約13tick
        assertEquals(14, tickAt(entries, 1));
    }

    @Test
    void detectorTurnsTheNextUnpoweredRailIntoABooster() {
        eastLine("RRDB" + "R".repeat(100));
        List<Entry> boosted = ride(0, 64, 0, 1, 0.2, false, 5_000);
        rails.clear();
        eastLine("RRRB" + "R".repeat(100));
        List<Entry> braked = ride(0, 64, 0, 1, 0.2, false, 5_000);
        assertTrue(boosted.size() > braked.size() + 20, boosted.size() + " vs " + braked.size());
    }

    @Test
    void poweredActivatorEndsTheRide() {
        eastLine("RRRR");
        put(4, 64, 0, TrackShape.EAST_WEST, RailKind.ACTIVATOR, true);
        put(5, 64, 0, TrackShape.EAST_WEST, RailKind.RAIL, false);
        List<Entry> entries = ride(0, 64, 0, 1, 2.0, false, 100);
        Entry last = entries.get(entries.size() - 1);
        assertEquals(4, last.x());
        assertTrue(last.forcedExit());
    }

    @Test
    void followsCurvesAndSlopesBothWays() {
        // 東へ2本 → 坂を1段登る → 北東の曲がりで北へ
        put(0, 64, 0, TrackShape.EAST_WEST, RailKind.POWERED, true);
        put(1, 64, 0, TrackShape.EAST_WEST, RailKind.RAIL, false);
        put(2, 64, 0, TrackShape.ASCENDING_EAST, RailKind.RAIL, false);
        put(3, 65, 0, TrackShape.NORTH_WEST, RailKind.RAIL, false);
        put(3, 65, -1, TrackShape.NORTH_SOUTH, RailKind.RAIL, false);
        put(3, 65, -2, TrackShape.NORTH_SOUTH, RailKind.RAIL, false);
        List<Entry> up = ride(0, 64, 0, 1, 1.0, false, 200);
        assertEquals(List.of(1, 2, 3, 3, 3), up.stream().map(Entry::x).toList());
        assertEquals(new Entry(3, 65, -2, up.get(4).tick(), false), up.get(4));

        List<Entry> down = ride(3, 65, -2, 1, 1.0, false, 200);
        assertEquals(List.of(-1, 0, 0, 0, 0), down.stream().map(Entry::z).toList());
        assertEquals(64, down.get(down.size() - 1).y());
        assertEquals(0, down.get(down.size() - 1).x());
    }

    @Test
    void zigzagIsFasterPerCellThanAStraight() {
        // 北東へのジグザグ: 曲線のセルは長さ√2/2で、軸ごとの上限が両軸に効く
        int x = 0;
        int z = 0;
        for (int i = 0; i < 200; i++) {
            put(x, 64, z, i % 2 == 0 ? TrackShape.NORTH_WEST : TrackShape.SOUTH_EAST, RailKind.RAIL, false);
            if (i % 2 == 0) {
                z--;
            } else {
                x++;
            }
        }
        List<Entry> entries = ride(0, 64, 0, 0, 2.0, false, 1_000);
        // 100セル目まで全速を保つ: 0.4√2/tickで√2/2の長さ＝1.25tick/セル
        assertEquals(125, entries.get(99).tick(), 1);
    }
}
