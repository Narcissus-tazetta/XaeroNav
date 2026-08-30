package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>層1のセルが、それが代表する16×16チャンクをどれだけ忠実に表しているかを測る。</b>
 *
 * <p>「遠回り・謎にわたらせる」はオーバーワールドの水だけの問題ではない（エンド・ネザーでも
 * 起きるとユーザー報告）。水の閾値（{@code waterSamples*2 >= samples}）はエンドにも
 * ネザーにも存在しないので、次元に依らない別の原因があるはず——という仮説を測った。
 *
 * <p><b>結論: 「中間目標が立てない場所に落ちる」は真因ではなかった（測定2が否定した）。</b>
 * 機構そのものは実在し、実際に高くつく（測定5のA/B: 橋8本 vs 0本）。しかし実機のエンド
 * 保存データで107ルート・中間目標190個を調べると、<b>層2の8ブロック寄せ
 * （{@code CorridorLegSolver.ENDPOINT_FALLBACK_RADIUS_BLOCKS}）が全部を救っていた</b>——
 * 層2が使える限り、生のチャンク中心が層3へ渡ることは無い。
 *
 * <p><b>したがって残る容疑は「層2が使えないとき」に絞られる</b>: Xaeroの地図データが
 * その区間に無い／まだメモリに載っていないと{@code CorridorLegSolver.prepare}が
 * {@code view=null}を返し、{@code PathfindingState#solveLeg}が<b>生のチャンク中心</b>へ
 * フォールバックする。<b>ここはオフラインでは測れない</b>（Xaeroのリージョン読み込み状態に
 * 依存する）ので、実機の診断ログでしか詰められない。
 *
 * <p>測っているもの:
 * <ol>
 *   <li>測定1 — セルの種別が過大評価か過小評価か。{@link LiveCoarseSampler}は種別ごとに
 *       集計規則が違う（水・溶岩は<b>過半数の多数決</b>、奈落は<b>床が1つも無いとき「だけ」</b>）</li>
 *   <li>測定2 — <b>中間目標に実際に立てるか（この仮説を否定した本体）</b></li>
 *   <li>測定3・4 — 分類の非対称そのもの（溶岩の割合／柱1本）</li>
 *   <li>測定5 — 立てない中間目標を層3へ渡した場合の実コスト（機構が実在することの確認）</li>
 * </ol>
 */
class CoarseWaypointFidelityTest {

    /** waypointの半径（{@code PathfindingState}が層1のwaypointへ与える{@code goalRadius}）。 */
    private static final int WAYPOINT_GOAL_RADIUS = 6;

    /**
     * 層2が中間目標を立てる場所へ寄せる半径（{@code CorridorLegSolver.ENDPOINT_FALLBACK_RADIUS_BLOCKS}）。
     * ここで見つからなければ{@code prepare}が{@code view=null}を返し、
     * {@code PathfindingState#solveLeg}が<b>生のチャンク中心</b>へフォールバックする。
     */
    private static final int LAYER2_SNAP_RADIUS = 8;

    // ---- 実機のジ・エンド保存データ ----------------------------------------------------

    private record Terrain(FakeCells cells, SearchBounds bounds) {
    }

    private static Terrain endTerrain() throws IOException {
        try (InputStream in = CoarseWaypointFidelityTest.class
                .getResourceAsStream("/end_terrain_columns.txt.gz")) {
            assertNotNull(in, "地形データが見つからない");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] h = reader.readLine().trim().split(" ");
            SearchBounds bounds = new SearchBounds(
                    Integer.parseInt(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                    Integer.parseInt(h[3]), Integer.parseInt(h[4]), Integer.parseInt(h[5]));
            // 実機の既定に合わせる（maxBridgeRunBlocks/maxVoidBridgeRunBlocks=96、落下許容6）
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
            return new Terrain(cells, bounds);
        }
    }

    // ---- ブロック解像度の「本当に立てるか」 --------------------------------------------

    /** {@code (x,z)}で立てるYを{@code bounds}の全高から探す。無ければ{@link Integer#MIN_VALUE}。 */
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

    /** チャンク内で実際に立てる列の数（16×16のうち）。 */
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

    /** {@code waypoint}から、実際に立てる最寄りの列までの水平距離。無ければ{@link Double#NaN}。 */
    private static double distanceToNearestStandable(CellSource cells, SearchBounds bounds,
                                                      BlockPos waypoint, int searchRadius) {
        double best = Double.NaN;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                int x = waypoint.getX() + dx;
                int z = waypoint.getZ() + dz;
                if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) {
                    continue;
                }
                if (standableY(cells, bounds, x, z) == Integer.MIN_VALUE) {
                    continue;
                }
                double d = Math.hypot(dx, dz);
                if (Double.isNaN(best) || d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    /**
     * <b>測定1: エンドで層1のLANDセルは、実際にはどれだけ空っぽか。</b>
     *
     * <p>{@link LiveCoarseSampler#sampleChunk}は「床のある列が1つでもあれば」そのチャンクを
     * {@code LAND}にする（{@code VOID}になるのは{@code floors.isEmpty()}のときだけ）。
     * 水・溶岩の<b>過半数</b>判定とは規則が違う。この非対称が、エンドで
     * 「ほぼ奈落なのにLANDと見えるセル」を生んでいないかを実データで測る。
     */
    @Test
    void endLandCellsAreOftenAlmostEntirelyVoid() throws IOException {
        Terrain terrain = endTerrain();
        SearchBounds b = terrain.bounds();
        CoarseMap map = LiveCoarseSampler.sample(terrain.cells(), b);

        System.out.println("=== 測定1: 実機ジ・エンドのLANDセルの中身 ===");
        System.out.println("bounds = " + b.minX() + ".." + b.maxX() + " x " + b.minZ() + ".." + b.maxZ()
                + " (Y " + b.minY() + ".." + b.maxY() + ")");
        System.out.println(map.kindBreakdown());

        int land = 0;
        int centerNotStandable = 0;
        int mostlyVoid = 0;
        int beyondGoalRadius = 0;
        int heightWrongByOver8 = 0;
        // 立てる列の割合の分布（0-12%, 12-25%, 25-50%, 50-100%）
        int[] histogram = new int[4];

        for (int cx = map.minChunkX(); cx < map.minChunkX() + map.chunksX(); cx++) {
            for (int cz = map.minChunkZ(); cz < map.minChunkZ() + map.chunksZ(); cz++) {
                if (map.floorCount(cx, cz) == 0 || map.kindAtFloor(cx, cz, 0) != CoarseMap.LAND) {
                    continue;
                }
                land++;
                int standable = standableColumns(terrain.cells(), b, cx, cz);
                double fraction = standable / 256.0;
                if (fraction < 0.125) {
                    histogram[0]++;
                    mostlyVoid++;
                } else if (fraction < 0.25) {
                    histogram[1]++;
                } else if (fraction < 0.5) {
                    histogram[2]++;
                } else {
                    histogram[3]++;
                }
                // waypointが置かれるチャンク中心そのもの
                BlockPos waypoint = new BlockPos((cx << 4) + 8, map.heightAtFloor(cx, cz, 0), (cz << 4) + 8);
                int centerY = standableY(terrain.cells(), b, waypoint.getX(), waypoint.getZ());
                if (centerY == Integer.MIN_VALUE) {
                    centerNotStandable++;
                    // 半径6の円柱の中に立てる場所があるか（層3はここへ向かう）
                    double nearest = distanceToNearestStandable(terrain.cells(), b, waypoint, 16);
                    if (Double.isNaN(nearest) || nearest > WAYPOINT_GOAL_RADIUS) {
                        beyondGoalRadius++;
                    }
                } else if (Math.abs(centerY - waypoint.getY()) > 8) {
                    // 中心には立てるが、層1の代表高さがそこと大きくずれている
                    heightWrongByOver8++;
                }
            }
        }

        System.out.printf("LANDセル=%d%n", land);
        System.out.printf("  中心に立てない            = %-4d (%.0f%%)%n",
                centerNotStandable, 100.0 * centerNotStandable / land);
        System.out.printf("  ↳ 半径%dにも立てる場所が無い = %-4d (%.0f%%)  <= 層3はここへ橋を架ける%n",
                WAYPOINT_GOAL_RADIUS, beyondGoalRadius, 100.0 * beyondGoalRadius / land);
        System.out.printf("  代表高さが実際と8超ずれる  = %-4d (%.0f%%)%n",
                heightWrongByOver8, 100.0 * heightWrongByOver8 / land);
        System.out.printf("  歩ける列が12%%未満         = %-4d (%.0f%%)%n",
                mostlyVoid, 100.0 * mostlyVoid / land);
        System.out.printf("  歩ける列の割合の分布: <12%%=%d  12-25%%=%d  25-50%%=%d  >50%%=%d%n",
                histogram[0], histogram[1], histogram[2], histogram[3]);

        assertTrue(land > 0, "実機データにLANDセルが1つも無い＝読み込みが失敗している");
    }

    /**
     * <b>測定2: 実機の始点・目的地で層1が出すwaypointに、本当に立てるのか。</b>
     *
     * <p>{@code CoarseRouter#toBlockPos}は種別に関わらず常にチャンク中心を返す。
     * 詳細探索(層3)はそこへ{@code goalRadius}=6で向かう——半径6の円柱の中に立てる場所が
     * 無ければ、層3は<b>そこへ橋を架けて</b>近づこうとする。
     */
    @Test
    void endWaypointsLandOnPlacesYouCannotStand() throws IOException {
        Terrain terrain = endTerrain();
        SearchBounds b = terrain.bounds();
        CoarseMap map = LiveCoarseSampler.sample(terrain.cells(), b);

        System.out.println("=== 測定2: 実機ジ・エンドの中間目標に立てるか ===");
        System.out.println("※ 最後のwaypointは PathfindingState#freshRoute の replaceLast が");
        System.out.println("   本来の目的地で上書きするので、中間目標だけを数える。");
        System.out.println("※ 判定半径は層2の ENDPOINT_FALLBACK_RADIUS_BLOCKS=" + LAYER2_SNAP_RADIUS
                + "。ここで見つからなければ CorridorLegSolver.prepare が view=null を返し、");
        System.out.println("   solveLeg が List.of(rawTarget)＝生のチャンク中心へフォールバックする。");

        // データのある範囲(1130..1390 x 990..1250)に始点・目的地の格子を張って多数のルートを引く
        List<BlockPos> anchors = new ArrayList<>();
        for (int x = 1150; x <= 1370; x += 55) {
            for (int z = 1010; z <= 1230; z += 55) {
                int y = standableY(terrain.cells(), b, x, z);
                if (y != Integer.MIN_VALUE) {
                    anchors.add(new BlockPos(x, y, z));
                }
            }
        }

        int routes = 0;
        int intermediates = 0;
        int layer2WouldFail = 0;
        int onCrossingCell = 0;
        List<String> examples = new ArrayList<>();
        for (BlockPos from : anchors) {
            for (BlockPos to : anchors) {
                if (from.equals(to)) {
                    continue;
                }
                CoarseRouter.Route route = CoarseRouter.findRoute(map, from, to, false,
                        CoarseRouter.BridgePolicy.ALLOW);
                if (!route.reachedGoal() || route.waypoints().size() < 2) {
                    continue;
                }
                routes++;
                // 最後は replaceLast が目的地で上書きするので除く
                for (int i = 0; i < route.waypoints().size() - 1; i++) {
                    BlockPos w = route.waypoints().get(i);
                    intermediates++;
                    int cx = w.getX() >> 4;
                    int cz = w.getZ() >> 4;
                    byte kind = map.floorCount(cx, cz) == 0 ? -1 : map.kindAtFloor(cx, cz, 0);
                    if (kind == CoarseMap.VOID || kind == CoarseMap.WATER || kind == CoarseMap.LAVA) {
                        onCrossingCell++;
                    }
                    double nearest = distanceToNearestStandable(terrain.cells(), b, w, LAYER2_SNAP_RADIUS);
                    if (Double.isNaN(nearest)) {
                        layer2WouldFail++;
                        if (examples.size() < 6) {
                            examples.add(String.format("%s kind=%s", w.toShortString(), kindName(kind)));
                        }
                    }
                }
            }
        }

        System.out.printf("到達したルート=%d  中間目標=%d個%n", routes, intermediates);
        System.out.printf("  横断セル(VOID/WATER/LAVA)の上に載った中間目標 = %-4d (%.1f%%)%n",
                onCrossingCell, 100.0 * onCrossingCell / intermediates);
        System.out.printf("  半径%d以内に立てる場所が無い（層2が救えない）  = %-4d (%.1f%%)"
                        + "  <= 生のチャンク中心が層3へ渡る%n",
                LAYER2_SNAP_RADIUS, layer2WouldFail, 100.0 * layer2WouldFail / intermediates);
        System.out.println("  例: " + examples);

        // これが仮説を否定している本体。将来この前提が崩れたら（層1のコスト・間引き間隔・
        // 層2の寄せ半径のどれかを変えたら）ここが落ちて気付ける
        assertEquals(0, layer2WouldFail,
                "層2の寄せ半径" + LAYER2_SNAP_RADIUS + "で救えない中間目標が出た＝"
                        + "「立てない中間目標」仮説が実データで成立する条件に変わった");
    }

    /**
     * <b>測定3: 同じ非対称をネザーの溶岩で確かめる（合成地形）。</b>
     *
     * <p>溶岩は過半数で{@code LAVA}、25%以上で{@code LAVA_MIXED}。つまり
     * 「24%が溶岩、残りは陸」のチャンクは<b>素の{@code LAND}（倍率1.0＝最安）</b>に見える。
     * その陸が実際には繋がっていない（溶岩の池で分断されている）場合、層1は素通りする。
     */
    @Test
    void netherLandCellsCanHideLavaThatSplitsTheChunk() {
        // 1チャンク＝16×16。中央に溶岩の帯を置き、幅を変えて種別がどう倒れるかを見る
        System.out.println("=== 測定3: 溶岩の割合と層1の種別 ===");
        for (int lavaWidth : new int[] {2, 3, 4, 6, 8, 12}) {
            SearchBounds bounds = new SearchBounds(0, 40, 0, 63, 100, 63);
            FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true);
            for (int x = 0; x < 64; x++) {
                for (int z = 0; z < 64; z++) {
                    boolean lava = z >= 8 && z < 8 + lavaWidth;
                    cells.set(x, 63, z, lava ? FakeCells.LAVA : FakeCells.STONE);
                }
            }
            CoarseMap map = LiveCoarseSampler.sample(cells, bounds);
            byte kind = map.floorCount(0, 0) == 0 ? -1 : map.kindAtFloor(0, 0, 0);
            System.out.printf("溶岩の帯 幅%-3d (%.0f%%)  ->  層1の種別 = %s%n",
                    lavaWidth, 100.0 * lavaWidth / 16, kindName(kind));
        }
    }

    /**
     * <b>測定4: 奈落の非対称そのもの。</b>1本の柱があるだけのチャンクが{@code LAND}になるか。
     */
    @Test
    void aSinglePillarMakesAnEntirelyVoidChunkLookLikeLand() {
        System.out.println("=== 測定4: 奈落のチャンクに柱を1本立てると ===");
        for (int pillars : new int[] {0, 1, 2, 4, 16}) {
            SearchBounds bounds = new SearchBounds(0, 40, 0, 15, 100, 15);
            FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true);
            // SAMPLE_STEP=4 なので、サンプルされるのは (0,4,8,12)×(0,4,8,12) の16列。
            // そこへ柱を立てた数だけ「床のある列」になる
            int placed = 0;
            outer:
            for (int dx = 0; dx < 16; dx += 4) {
                for (int dz = 0; dz < 16; dz += 4) {
                    if (placed >= pillars) {
                        break outer;
                    }
                    cells.set(dx, 63, dz, FakeCells.STONE);
                    placed++;
                }
            }
            CoarseMap map = LiveCoarseSampler.sample(cells, bounds);
            byte kind = map.floorCount(0, 0) == 0 ? -1 : map.kindAtFloor(0, 0, 0);
            System.out.printf("柱%-3d本 (サンプル16列中)  ->  層1の種別 = %-5s  中心(8,8)に立てるか=%s%n",
                    pillars, kindName(kind),
                    standableY(cells, bounds, 8, 8) != Integer.MIN_VALUE);
        }
    }

    /** {@code waypoint}に最も近い、実際に立てる位置。無ければ{@code null}。 */
    private static BlockPos nearestStandable(CellSource cells, SearchBounds bounds, BlockPos waypoint,
                                              int searchRadius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                int x = waypoint.getX() + dx;
                int z = waypoint.getZ() + dz;
                if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) {
                    continue;
                }
                int y = standableY(cells, bounds, x, z);
                if (y == Integer.MIN_VALUE) {
                    continue;
                }
                double d = Math.hypot(dx, dz);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new BlockPos(x, y, z);
                }
            }
        }
        return best;
    }

    /**
     * <b>測定5: 立てないwaypointを層3に渡すと何が起きるか（実機ジ・エンドの地形で通しで確認）。</b>
     *
     * <p>{@code goalRadius}=6の円柱の中に立てる場所が無い中間目標を渡すと、層3は
     * <b>そこへ橋を架けて近づこうとする</b>。これが「謎にわたらせる」の正体かを、
     * 同じ地形・同じ始点で「生のwaypoint」と「最寄りの立てる場所へ寄せたwaypoint」の
     * A/Bで確かめる。
     */
    @Test
    void layer3BridgesIntoNothingWhenTheWaypointIsNotStandable() throws IOException {
        Terrain terrain = endTerrain();
        SearchBounds b = terrain.bounds();

        System.out.println("=== 測定5: 立てないwaypointを層3へ渡すと ===");
        // 測定2の3本目の経路から。始点は1つ前の中間目標（立てる）、狙いは次の中間目標（VOIDセル）
        BlockPos start = new BlockPos(1352, 60, 1064);
        BlockPos rawWaypoint = new BlockPos(1368, 60, 1016);
        BlockPos snapped = nearestStandable(terrain.cells(), b, rawWaypoint, 24);

        System.out.println("始点          = " + start.toShortString()
                + " (立てるY=" + standableY(terrain.cells(), b, start.getX(), start.getZ()) + ")");
        System.out.println("生のwaypoint  = " + rawWaypoint.toShortString() + " (VOIDセル・立てるY=none)");
        System.out.println("寄せた先      = " + (snapped == null ? "none" : snapped.toShortString()));

        report("生のwaypointを狙う", terrain, start, rawWaypoint);
        if (snapped != null) {
            report("寄せたwaypointを狙う", terrain, start, snapped);
        }
    }

    private static void report(String label, Terrain terrain, BlockPos start, BlockPos goal) {
        SearchLimits limits = new SearchLimits(600_000, 30_000,
                AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        PathResult result;
        try {
            // 実機と同じ入口（上限緩和の梯子を含む）を通す
            result = new net.prason.xaeronav.pathfinding.async.PathfindingExecutor()
                    .submit(terrain.cells(), start, goal, limits, true, WAYPOINT_GOAL_RADIUS).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        int bridges = 0;
        int longestRun = 0;
        int run = 0;
        for (PathStep step : result.steps()) {
            if (step.bridging()) {
                bridges++;
                run++;
                longestRun = Math.max(longestRun, run);
            } else {
                run = 0;
            }
        }
        BlockPos end = result.steps().isEmpty() ? start
                : result.steps().get(result.steps().size() - 1).pos();
        System.out.printf("%-22s complete=%-5s term=%-13s steps=%-4d 橋=%-4d 最長橋=%-3d 展開=%-7d 終点=%s%n",
                label, result.complete(), result.termination(), result.steps().size(),
                bridges, longestRun, result.expandedNodes(), end.toShortString());
    }

    private static String kindName(int k) {
        return switch (k) {
            case -1 -> "(none)";
            case CoarseMap.NO_DATA -> "NO_DATA";
            case CoarseMap.LAND -> "LAND";
            case CoarseMap.WATER -> "WATER";
            case CoarseMap.LAVA -> "LAVA";
            case CoarseMap.LAVA_MIXED -> "LAVA_MIXED";
            case CoarseMap.VOID -> "VOID";
            default -> "?" + k;
        };
    }
}
