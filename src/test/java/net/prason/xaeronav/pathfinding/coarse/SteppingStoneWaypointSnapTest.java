package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code CoarseRouter#toBlockPos}が飛び石セルでもチャンク中心を返すことの実害を測る。</b>
 *
 * <p>層1は「床のある列が1つでもあればLAND」なので、ジ・エンドには床が0〜10%しかないセルが
 * びっしり詰まったセルと同じ値段で並ぶ。そこへ中間目標を置くと座標は<b>奈落の真上</b>になり、
 * 層3はその点へ届くために奈落へ橋を架ける。
 *
 * <p>倍率で避けさせる案（{@code CoarseMap.VOID_MIXED}）は実機で「めっちゃ大回りする」と却下済み。
 * 残る案は<b>ルートの形を変えずに座標だけ直す</b>こと——層1が選んだセル列はそのままに、
 * {@code toBlockPos}が実際に床のある列へ寄せる。ここではその案で救える数を、実装前に測る。
 *
 * <p>寄せ先の候補を{@link LiveCoarseSampler}のサンプル16列（4×4格子）に限るのは、
 * 層1の地図がそこしか見ていないから。全256列を見れば救える数は増えるが、地図を作る側の
 * コストが16倍になる。
 */
class SteppingStoneWaypointSnapTest {

    /** {@code LiveCoarseSampler#SAMPLE_STEP}。層1が1チャンクにつき実際に読む列の間隔。 */
    private static final int SAMPLE_STEP = 4;

    /** 層2が中間目標を立てる場所へ寄せる半径（{@code CorridorLegSolver#ENDPOINT_FALLBACK_RADIUS_BLOCKS}）。 */
    private static final int LAYER2_SNAP_RADIUS = 8;

    private record Terrain(FakeCells cells, SearchBounds bounds) {
    }

    private static Terrain terrain(String resource) throws IOException {
        try (InputStream in = SteppingStoneWaypointSnapTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "地形データが見つからない: " + resource);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] h = reader.readLine().trim().split(" ");
            SearchBounds bounds = new SearchBounds(
                    Integer.parseInt(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                    Integer.parseInt(h[3]), Integer.parseInt(h[4]), Integer.parseInt(h[5]));
            FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxFallDamagePoints(6);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split(" ");
                int x = Integer.parseInt(p[0]);
                int z = Integer.parseInt(p[1]);
                for (int i = 2; i < p.length; i++) {
                    int comma = p[i].indexOf(',');
                    int from = Integer.parseInt(p[i].substring(0, comma));
                    int to = Integer.parseInt(p[i].substring(comma + 1));
                    for (int y = from; y <= to; y++) {
                        cells.set(x, y, z, FakeCells.STONE);
                    }
                }
            }
            return new Terrain(cells, bounds);
        }
    }

    private static int standableY(CellSource cells, SearchBounds bounds, int x, int z) {
        for (int y = bounds.maxY() - 1; y > bounds.minY(); y--) {
            if (CellData.standable(cells.cell(x, y - 1, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int standableColumns(CellSource cells, SearchBounds bounds, int chunkX, int chunkZ) {
        int n = 0;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                if (standableY(cells, bounds, (chunkX << 4) + dx, (chunkZ << 4) + dz) != Integer.MIN_VALUE) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 層2の寄せ（半径{@value #LAYER2_SNAP_RADIUS}）が届くか。 */
    private static boolean layer2CanSnap(CellSource cells, SearchBounds bounds, int x, int z) {
        for (int dx = -LAYER2_SNAP_RADIUS; dx <= LAYER2_SNAP_RADIUS; dx++) {
            for (int dz = -LAYER2_SNAP_RADIUS; dz <= LAYER2_SNAP_RADIUS; dz++) {
                if (Math.hypot(dx, dz) > LAYER2_SNAP_RADIUS) {
                    continue;
                }
                if (standableY(cells, bounds, x + dx, z + dz) != Integer.MIN_VALUE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 層1のサンプル16列のうち、立てる列で中心(8,8)に最も近いもののチャンク内オフセット。
     * 見つからなければ{@code null}。
     */
    private static int[] snapCandidate(CellSource cells, SearchBounds bounds, int chunkX, int chunkZ) {
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = 0; dx < 16; dx += SAMPLE_STEP) {
            for (int dz = 0; dz < 16; dz += SAMPLE_STEP) {
                int x = (chunkX << 4) + dx;
                int z = (chunkZ << 4) + dz;
                if (standableY(cells, bounds, x, z) == Integer.MIN_VALUE) {
                    continue;
                }
                double d = Math.hypot(dx - 8, dz - 8);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new int[] {dx, dz};
                }
            }
        }
        return best;
    }

    private void measure(String label, String resource) throws IOException {
        Terrain t = terrain(resource);
        SearchBounds b = t.bounds();
        CoarseMap map = LiveCoarseSampler.sample(t.cells(), b);

        int land = 0;
        int steppingStone = 0;
        int centerNotStandable = 0;
        int savedBySnap = 0;
        int savedByLayer2 = 0;
        int hopelessEvenWithSnap = 0;
        double snapShiftSum = 0;
        int snapShiftCount = 0;

        for (int cx = map.minChunkX(); cx < map.minChunkX() + map.chunksX(); cx++) {
            for (int cz = map.minChunkZ(); cz < map.minChunkZ() + map.chunksZ(); cz++) {
                if (map.floorCount(cx, cz) == 0 || map.kindAtFloor(cx, cz, 0) != CoarseMap.LAND) {
                    continue;
                }
                land++;
                if (standableColumns(t.cells(), b, cx, cz) < 64) {
                    steppingStone++;
                }
                int centerX = (cx << 4) + 8;
                int centerZ = (cz << 4) + 8;
                if (standableY(t.cells(), b, centerX, centerZ) != Integer.MIN_VALUE) {
                    continue;
                }
                centerNotStandable++;
                if (layer2CanSnap(t.cells(), b, centerX, centerZ)) {
                    savedByLayer2++;
                }
                int[] snap = snapCandidate(t.cells(), b, cx, cz);
                if (snap == null) {
                    hopelessEvenWithSnap++;
                } else {
                    savedBySnap++;
                    snapShiftSum += Math.hypot(snap[0] - 8, snap[1] - 8);
                    snapShiftCount++;
                }
            }
        }

        System.out.printf("%n===== %s =====%n", label);
        System.out.printf("LANDセル = %d（うち歩ける列が1/4未満の飛び石 = %d, %.0f%%）%n",
                land, steppingStone, 100.0 * steppingStone / land);
        System.out.printf("チャンク中心に立てない       = %-4d (%.0f%%)%n",
                centerNotStandable, 100.0 * centerNotStandable / land);
        if (centerNotStandable > 0) {
            System.out.printf("  ↳ 層2の半径%dで救える      = %-4d (%.0f%%)%n",
                    LAYER2_SNAP_RADIUS, savedByLayer2, 100.0 * savedByLayer2 / centerNotStandable);
            System.out.printf("  ↳ サンプル16列への寄せで救える = %-4d (%.0f%%)  平均 %.1f ブロック動く%n",
                    savedBySnap, 100.0 * savedBySnap / centerNotStandable,
                    snapShiftCount == 0 ? 0 : snapShiftSum / snapShiftCount);
            System.out.printf("  ↳ 寄せても床が見つからない   = %-4d (%.0f%%)  ← 層1の地図の解像度の限界%n",
                    hopelessEvenWithSnap, 100.0 * hopelessEvenWithSnap / centerNotStandable);
            System.out.printf("  ↳ 層2が届かず寄せなら救える  = %-4d  ← 寄せの<b>追加</b>の価値はここ%n",
                    Math.max(0, savedBySnap - savedByLayer2));
        }
        assertTrue(land > 0, "LANDセルが1つも無い＝読み込みが失敗している");
    }

    @Test
    void endTerrain1130() throws IOException {
        measure("ジ・エンド 1130-1390", "/end_terrain_columns.txt.gz");
    }

    @Test
    void endTerrain2481() throws IOException {
        measure("ジ・エンド 2384-2688（ユーザーが症状を報告した一帯）", "/end_terrain_columns_2481.txt.gz");
    }
}
