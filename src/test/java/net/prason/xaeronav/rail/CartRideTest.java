package net.prason.xaeronav.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/** トロッコの走りの模擬。速さはバニラサーバーでの実測と、線路の辿り方・止まり方は仕様と突き合わせる。 */
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

    /**
     * バニラ1.21.1サーバーで測った17本（平らな直線・パワードの間隔・ブレーキ・検知レール・曲線のジグザグ・登り・下り・起伏）と、
     * 各セルへ入るtickが全セル一致する。出発はパワードを壁から押し出す形なので、パワードの区間を抜けた最初のセルを基準に比べる。
     */
    @Test
    void matchesVanillaMeasurementsCellByCell() throws IOException {
        List<String> lines;
        try (InputStream in = new GZIPInputStream(Objects.requireNonNull(
                CartRideTest.class.getResourceAsStream("/vanilla_1.21.1_minecart.txt.gz")))) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("#")).toList();
        }
        int lanes = 0;
        for (int i = 0; i < lines.size(); lanes++) {
            String[] header = lines.get(i++).split(" ");
            int n = Integer.parseInt(header[2]);
            int start = Integer.parseInt(header[3]);
            rails.clear();
            int[][] pos = new int[n][];
            int[] measured = new int[n];
            Long2IntOpenHashMap index = new Long2IntOpenHashMap();
            index.defaultReturnValue(-1);
            for (int k = 0; k < n; k++) {
                String[] f = lines.get(i++).split(" ");
                pos[k] = new int[] {Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])};
                put(pos[k][0], pos[k][1], pos[k][2], TrackShape.valueOf(f[3]), RailKind.valueOf(f[4]), f[5].equals("1"));
                measured[k] = Integer.parseInt(f[6]);
                index.put(key(pos[k][0], pos[k][1], pos[k][2]), k);
            }
            int[] simulated = new int[n];
            Arrays.fill(simulated, -1);
            int exit = CartRide.exitDx(RailCell.shape(rails.get(key(pos[start][0], pos[start][1], pos[start][2]))), 0)
                    == pos[start + 1][0] - pos[start][0]
                    && CartRide.exitDz(RailCell.shape(rails.get(key(pos[start][0], pos[start][1], pos[start][2]))), 0)
                    == pos[start + 1][2] - pos[start][2] ? 0 : 1;
            // 壁の前のパワードは止まっているトロッコを0.02で押し出す
            boolean launched = rails.get(key(pos[start][0], pos[start][1], pos[start][2])) != CartRide.NONE
                    && RailCell.powered(rails.get(key(pos[start][0], pos[start][1], pos[start][2])));
            CartRide.ride((x, y, z) -> rails.get(key(x, y, z)), pos[start][0], pos[start][1], pos[start][2], exit,
                    launched ? 0.02 : 0.0, false, 20_000, (x, y, z, tick, forced) -> {
                        int k = index.get(key(x, y, z));
                        if (k >= 0 && simulated[k] < 0) {
                            simulated[k] = tick;
                        }
                        return true;
                    });
            int ref = start;
            while (ref < n - 1 && RailCell.powered(rails.get(key(pos[ref][0], pos[ref][1], pos[ref][2])))) {
                ref++;
            }
            int compared = 0;
            for (int k = ref + 1; k < n; k++) {
                if (measured[k] >= 0 && simulated[k] >= 0) {
                    assertEquals(measured[k] - measured[ref], simulated[k] - simulated[ref],
                            header[1] + " cell " + k);
                    compared++;
                }
            }
            assertTrue(compared > 5, header[1] + " compared only " + compared);
        }
        assertEquals(16, lanes);
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
        // 押しの速さ0.1に落ち着いた後は、0.075ブロック/tickで1ブロック約13tick
        int perBlock = tickAt(entries, 10) - tickAt(entries, 9);
        assertTrue(perBlock >= 13 && perBlock <= 14, "per block " + perBlock);
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
        assertEquals(125, entries.get(99).tick(), 2);
    }
}
