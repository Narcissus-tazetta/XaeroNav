package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>「地図に無いセル」が層1にとって最安クラスの通り道になっていないかを、実機データで測る。</b>
 *
 * <p><b>結論（2026-08-29）: 「Xaeroの地図が部分的にしか読めていないせいで層1が変な経路を出す」は
 * 実機ジ・エンドの地形では否定された。</b>読めている半径を4チャンクまで削っても、経路は
 * 完全な地図のときと<b>同一</b>だった（どちらも奈落3セルを通る）。未知が一様に安いので、
 * 部分的な地図でも最短の帯を選ぶ結果が変わらない。
 *
 * <p>ただし{@link #unknownCellsAreFarCheaperThanKnownVoid}が示すとおり、
 * <b>未知が既知の奈落より約6倍安いこと自体は事実</b>——地形の配置しだいでは効きうるので、
 * 特性として固定しておく。
 *
 * <p>{@code CoarseRouter}の倍率: {@code NO_DATA}=1.6、陸=1.0、奈落={@code VOID_BRIDGE_MULTIPLIER}
 * （≒10）。つまり<b>未知のセルは「床が無いと分かっているセル」より約6倍安い</b>。
 * 未知を通行可能にしておくこと自体は意図的な設計（そうしないと未探索の方角へ一切ルートが
 * 出ない）だが、<b>ジ・エンドでは「まだ地図に無い」の実体はほぼ奈落</b>なので、
 * 層1が未知の帯をまっすぐ突っ切る中間目標を並べうる。
 *
 * <p>Xaeroの地図はリージョン単位で非同期に読まれるので、{@code goto}した直後の層1は
 * <b>部分的にしか埋まっていない地図</b>で経路を決める。しかも{@code PathfindingState}は
 * その結果を{@code COARSE_ROUTE_RETRY_MOVE_BLOCKS}(32ブロック)歩くまで作り直さない。
 *
 * <p>ここでは実機ジ・エンドの地形から作った「完全な地図」を基準に、始点まわりだけを残して
 * 他を未知に落とした「部分的な地図」で経路を引き、<b>後者が前者では奈落と分かっているセルを
 * どれだけ突っ切るか</b>を数える。
 */
class UnknownCellRoutingTest {

    private static FakeCells endTerrain(SearchBounds[] outBounds) throws IOException {
        try (InputStream in = UnknownCellRoutingTest.class
                .getResourceAsStream("/end_terrain_columns.txt.gz")) {
            assertNotNull(in, "地形データが見つからない");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] h = reader.readLine().trim().split(" ");
            SearchBounds bounds = new SearchBounds(
                    Integer.parseInt(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                    Integer.parseInt(h[3]), Integer.parseInt(h[4]), Integer.parseInt(h[5]));
            outBounds[0] = bounds;
            FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxFallDamagePoints(6)
                    .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96);
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
            return cells;
        }
    }

    /**
     * {@code full}のうち、{@code (centerChunkX, centerChunkZ)}から{@code radiusChunks}以内の
     * セルだけを残した地図。残りは「まだ地図に無い」＝床0＝{@code NO_DATA}になる。
     * Xaeroのリージョンが部分的にしか読み込まれていない状態の代用。
     */
    private static CoarseMap maskedTo(CoarseMap full, int centerChunkX, int centerChunkZ, int radiusChunks) {
        CoarseMapBuilder builder = new CoarseMapBuilder(full.minChunkX(), full.minChunkZ(),
                full.chunksX(), full.chunksZ());
        for (int cx = full.minChunkX(); cx < full.minChunkX() + full.chunksX(); cx++) {
            for (int cz = full.minChunkZ(); cz < full.minChunkZ() + full.chunksZ(); cz++) {
                int dx = cx - centerChunkX;
                int dz = cz - centerChunkZ;
                if (dx * dx + dz * dz > radiusChunks * radiusChunks) {
                    continue;
                }
                for (int floor = 0; floor < full.floorCount(cx, cz); floor++) {
                    builder.putFloor(cx, cz, full.kindAtFloor(cx, cz, floor),
                            full.heightAtFloor(cx, cz, floor),
                            full.minHeightAtFloor(cx, cz, floor), full.maxHeightAtFloor(cx, cz, floor));
                }
            }
        }
        return builder.build();
    }

    /** ルートの折れ線が通るセルを、真実の地図({@code truth})の種別で数える。 */
    private static int[] cellsCrossed(CoarseMap truth, CoarseRouter.Route route, BlockPos start) {
        int[] counts = new int[CoarseMap.VOID + 2];
        BlockPos prev = start;
        for (BlockPos w : route.waypoints()) {
            int steps = Math.max(Math.abs(w.getX() - prev.getX()), Math.abs(w.getZ() - prev.getZ())) / 16;
            for (int i = 1; i <= Math.max(steps, 1); i++) {
                int x = steps == 0 ? w.getX() : prev.getX() + (w.getX() - prev.getX()) * i / steps;
                int z = steps == 0 ? w.getZ() : prev.getZ() + (w.getZ() - prev.getZ()) * i / steps;
                int cx = x >> 4;
                int cz = z >> 4;
                if (truth.floorCount(cx, cz) == 0) {
                    counts[counts.length - 1]++;
                } else {
                    counts[truth.kindAtFloor(cx, cz, 0)]++;
                }
            }
            prev = w;
        }
        return counts;
    }

    /**
     * <b>本命の測定。</b>部分的にしか読めていない地図で引いた層1のルートが、
     * 完全な地図では奈落と分かっているセルをどれだけ突っ切るか。
     */
    @Test
    void partiallyLoadedMapsRouteStraightThroughWhatIsActuallyVoid() throws IOException {
        SearchBounds[] out = new SearchBounds[1];
        FakeCells cells = endTerrain(out);
        SearchBounds b = out[0];
        CoarseMap truth = LiveCoarseSampler.sample(cells, b);

        System.out.println("=== 部分的な地図で層1が奈落を突っ切るか（実機ジ・エンド） ===");
        System.out.println("真実の地図: " + truth.kindBreakdown());
        System.out.println();

        BlockPos start = new BlockPos(1233, 57, 1142);
        BlockPos goal = new BlockPos(1360, 57, 1020);
        int startChunkX = start.getX() >> 4;
        int startChunkZ = start.getZ() >> 4;

        System.out.printf("%-18s %-8s %-10s %s%n", "読めている半径", "到達", "中間目標", "折れ線が通るセル(真実の種別)");
        for (int radius : new int[] {4, 6, 8, 12, 16, 100}) {
            CoarseMap map = radius >= 100 ? truth : maskedTo(truth, startChunkX, startChunkZ, radius);
            CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false,
                    CoarseRouter.BridgePolicy.ALLOW);
            int[] crossed = cellsCrossed(truth, route, start);
            System.out.printf("%-18s %-8s %-10d 陸=%-3d 奈落=%-3d 未踏=%-3d%n",
                    radius >= 100 ? "全部(基準)" : radius + "チャンク",
                    route.reachedGoal(), route.waypoints().size(),
                    crossed[CoarseMap.LAND], crossed[CoarseMap.VOID], crossed[crossed.length - 1]);
        }

        // 基準（完全な地図）が通る奈落セル数
        CoarseRouter.Route reference = CoarseRouter.findRoute(truth, start, goal, false,
                CoarseRouter.BridgePolicy.ALLOW);
        int referenceVoid = cellsCrossed(truth, reference, start)[CoarseMap.VOID];

        // 部分的な地図で最も奈落を突っ切った回数
        int worstVoid = 0;
        for (int radius : new int[] {4, 6, 8, 12, 16}) {
            CoarseMap map = maskedTo(truth, startChunkX, startChunkZ, radius);
            CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false,
                    CoarseRouter.BridgePolicy.ALLOW);
            worstVoid = Math.max(worstVoid, cellsCrossed(truth, route, start)[CoarseMap.VOID]);
        }
        System.out.printf("%n完全な地図が通る奈落セル=%d / 部分的な地図の最悪=%d%n", referenceVoid, worstVoid);

        // 2026-08-29: この仮説は<b>否定された</b>。この地形では読めている半径を4チャンクまで
        // 削っても経路が完全に同一（奈落3セルを通る）だった——未知が一様に安いので、部分的な
        // 地図でも最短の帯を選ぶ結果が変わらない。値を固定して、将来変わったら気付けるようにする。
        assertEquals(referenceVoid, worstVoid,
                "部分的な地図が完全な地図と違う経路を出すようになった＝この仮説を再検討する価値がある"
                        + " (基準=" + referenceVoid + ", 最悪=" + worstVoid + ")");
    }

    /**
     * 上の効果の出どころを倍率そのもので確かめる。未知セルは「床が無いと分かっているセル」より
     * どれだけ安いか——ここが逆転していれば、部分的な地図でも奈落を避けた経路が出るはず。
     */
    @Test
    void unknownCellsAreFarCheaperThanKnownVoid() {
        int radius = 20;
        // 始点と目的地の間を、奈落の帯か未知の帯のどちらかで塞ぐ。どちらを選ぶかを見る
        CoarseMapBuilder builder = new CoarseMapBuilder(-radius, -radius, radius * 2, radius * 2);
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                // x∈[4,8] の帯: z<0 は奈落（床が無いと分かっている）、z>=0 は未知（書かない）
                if (x >= 4 && x <= 8) {
                    if (z < 0) {
                        builder.putFloor(x, z, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                                CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
                    }
                    continue;
                }
                builder.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }
        CoarseMap map = builder.build();

        // 始点・目的地とも z=-8（奈落の帯の正面）。未知の帯(z>=0)へ迂回するかどうか
        BlockPos start = new BlockPos(0 * 16 + 8, 64, -8 * 16 + 8);
        BlockPos goal = new BlockPos(12 * 16 + 8, 64, -8 * 16 + 8);
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false,
                CoarseRouter.BridgePolicy.ALLOW);

        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(Integer.MIN_VALUE);
        System.out.println("=== 未知の帯 vs 奈落の帯、どちらを通るか ===");
        System.out.println("waypoints = " + route.waypoints());
        System.out.printf("最大Z=%d （0以上なら未知の帯へ迂回した＝未知の方が安い）%n", maxZ);

        assertTrue(route.reachedGoal());
        assertTrue(maxZ >= 0,
                "未知の帯へ迂回しなかった＝未知が奈落より安いという前提が崩れている: " + route.waypoints());
    }
}
