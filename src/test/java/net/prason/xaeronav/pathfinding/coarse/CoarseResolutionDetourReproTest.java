package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 「無駄なルート（遠回り）・謎にわたらせる」のオフライン再現。ユーザー報告:
 * 「謎にわたらせたりしている」「無駄なルート（遠回り）が多い。<b>近距離だと起きない</b>」。
 *
 * <p>見立て（[[xaeronav-next-plans]]）: チャンク解像度の層1（{@link CoarseMap}）は16ブロック
 * 未満の陸の渡りを表現できない。狭い地峡は{@link LiveCoarseSampler}の集計で
 * {@code waterSamples*2 >= samples}に倒れて{@code WATER}チャンクになり、地峡が地図から消える。
 * すると層1は「水を泳いで渡る／大きく迂回する」中間目標しか出せず、詳細探索(層3)はその
 * 中間目標へ忠実に橋を架ける・泳ぐ＝「謎にわたらせる」。
 *
 * <p>このテストは断定ではなく観測。ブロック解像度の地形で詳細探索が何をするか、同じ地形を
 * {@link LiveCoarseSampler}で潰した粗い地図で層1が何を出すか、を並べて出力する。
 */
class CoarseResolutionDetourReproTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int GROUND_Y = 63;
    private static final int STAND_Y = 64;
    private static final int WATER_SURFACE_Y = 63;

    private static final int MIN_X = -16;
    private static final int MAX_X = 208;
    private static final int MIN_Z = -16;
    private static final int MAX_Z = 96;

    /**
     * 開始側の陸(x<64) と 目的地側の陸(x>=144) を、幅{@code isthmusWidth}ブロックの地峡だけが
     * 繋いでいる。地峡以外の x∈[64,144) は水路。地峡の北({@code northBridge}が真なら) には
     * z∈[64,80) に幅16ブロック＝チャンク解像度でも見える陸の橋を置く。
     */
    private static FakeCells terrain(int isthmusWidth, boolean northBridge) {
        SearchBounds bounds = new SearchBounds(MIN_X, 40, MIN_Z, MAX_X, 110, MAX_Z);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(0);
        for (int x = MIN_X; x < MAX_X; x++) {
            for (int z = MIN_Z; z < MAX_Z; z++) {
                boolean inChannel = x >= 64 && x < 144;
                boolean onIsthmus = z >= 0 && z < isthmusWidth;
                boolean onNorthBridge = northBridge && z >= 64 && z < 80;
                if (inChannel && !onIsthmus && !onNorthBridge) {
                    // 水路: 石の底 + 水2マス
                    cells.set(x, GROUND_Y - 2, z, FakeCells.STONE);
                    cells.set(x, WATER_SURFACE_Y - 1, z, FakeCells.WATER);
                    cells.set(x, WATER_SURFACE_Y, z, FakeCells.WATER);
                } else {
                    cells.set(x, GROUND_Y, z, FakeCells.STONE);
                }
            }
        }
        return cells;
    }

    private static CoarseMap coarseOf(FakeCells cells) {
        SearchBounds b = new SearchBounds(MIN_X, 40, MIN_Z, MAX_X - 1, 110, MAX_Z - 1);
        return LiveCoarseSampler.sample(cells, b);
    }

    private static int chunkKind(CoarseMap map, int chunkX, int chunkZ) {
        if (map.floorCount(chunkX, chunkZ) == 0) {
            return -1;
        }
        return map.kindAtFloor(chunkX, chunkZ, 0);
    }

    private static String kindName(int k) {
        return switch (k) {
            case -1 -> "----";
            case CoarseMap.NO_DATA -> "no  ";
            case CoarseMap.LAND -> "land";
            case CoarseMap.WATER -> "WATR";
            case CoarseMap.LAVA -> "lava";
            case CoarseMap.LAVA_MIXED -> "lavm";
            case CoarseMap.VOID -> "void";
            default -> "?" + k;
        };
    }

    /** 地峡の走る帯(chunkZ=0..) を x方向に一列プリントする。 */
    private static void dumpIsthmusRow(String label, CoarseMap map) {
        StringBuilder sb = new StringBuilder(label).append("  chunkZ=0: ");
        for (int cx = MIN_X >> 4; cx <= (MAX_X - 1) >> 4; cx++) {
            sb.append(kindName(chunkKind(map, cx, 0))).append(' ');
        }
        System.out.println(sb);
    }

    private static int waterSteps(FakeCells cells, PathResult result) {
        int n = 0;
        for (PathStep step : result.steps()) {
            BlockPos p = step.pos();
            if (CellData.water(cells.cell(p.getX(), p.getY(), p.getZ()))
                    || CellData.water(cells.cell(p.getX(), p.getY() - 1, p.getZ()))) {
                n++;
            }
        }
        return n;
    }

    private static int bridgeSteps(PathResult result) {
        int n = 0;
        for (PathStep step : result.steps()) {
            if (step.bridging()) {
                n++;
            }
        }
        return n;
    }

    private static void reportDetail(String label, FakeCells cells, BlockPos start, BlockPos goal,
                                      int goalRadius) {
        SearchLimits limits = new SearchLimits(1_000_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        PathResult r = new AStarPathfinder(cells, limits).search(start, goal, NEVER, goalRadius);
        int maxZ = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (PathStep s : r.steps()) {
            maxZ = Math.max(maxZ, s.pos().getZ());
            minZ = Math.min(minZ, s.pos().getZ());
        }
        System.out.printf("%-40s complete=%-5s term=%-13s steps=%-3d water=%-3d bridge=%-3d zRange=[%d,%d]%n",
                label, r.complete(), r.termination(), r.steps().size(),
                waterSteps(cells, r), bridgeSteps(r), minZ, maxZ);
    }

    private static int throughWaterCells(CoarseMap map, CoarseRouter.Route route) {
        int n = 0;
        BlockPos prev = null;
        for (BlockPos w : route.waypoints()) {
            if (prev != null) {
                int steps = Math.max(Math.abs(w.getX() - prev.getX()), Math.abs(w.getZ() - prev.getZ())) / 16;
                for (int i = 0; i <= steps; i++) {
                    int x = steps == 0 ? w.getX() : prev.getX() + (w.getX() - prev.getX()) * i / steps;
                    int z = steps == 0 ? w.getZ() : prev.getZ() + (w.getZ() - prev.getZ()) * i / steps;
                    if (chunkKind(map, x >> 4, z >> 4) == CoarseMap.WATER) {
                        n++;
                    }
                }
            }
            prev = w;
        }
        return n;
    }

    private static CoarseRouter.Route reportCoarse(String label, CoarseMap map, BlockPos start, BlockPos goal) {
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false,
                CoarseRouter.BridgePolicy.AVOID);
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos w : route.waypoints()) {
            maxZ = Math.max(maxZ, w.getZ());
        }
        System.out.printf("%-40s reached=%-5s waypoints=%-2d throughWaterCells=%-2d maxWaypointZ=%d%n",
                label, route.reachedGoal(), route.waypoints().size(), throughWaterCells(map, route), maxZ);
        System.out.println("   " + route.waypoints());
        return route;
    }

    /**
     * 主シナリオ: 幅4ブロックの地峡が唯一の陸路。詳細探索は地峡を歩く。層1は地峡を
     * 見失って水路を泳ぐ／橋の中間目標を出す。
     */
    @Test
    void narrowIsthmusVanishesFromCoarseMapAndForcesAWaterCrossing() {
        FakeCells narrow = terrain(4, false);
        FakeCells wide = terrain(16, false);

        BlockPos start = new BlockPos(8, STAND_Y, 2);
        BlockPos goal = new BlockPos(200, STAND_Y, 2);

        System.out.println("--- detail search (block resolution) ---");
        reportDetail("narrow isthmus (4 wide) -> goal", narrow, start, goal, 0);
        reportDetail("wide isthmus  (16 wide) -> goal", wide, start, goal, 0);

        CoarseMap narrowCoarse = coarseOf(narrow);
        CoarseMap wideCoarse = coarseOf(wide);

        System.out.println("--- coarse map (chunk resolution) ---");
        System.out.println("narrow: " + narrowCoarse.kindBreakdown());
        dumpIsthmusRow("narrow", narrowCoarse);
        System.out.println("wide:   " + wideCoarse.kindBreakdown());
        dumpIsthmusRow("wide  ", wideCoarse);

        System.out.println("--- coarse route (layer 1 waypoints) ---");
        CoarseRouter.Route narrowRoute = reportCoarse("narrow isthmus", narrowCoarse, start, goal);
        CoarseRouter.Route wideRoute = reportCoarse("wide isthmus", wideCoarse, start, goal);

        SearchLimits limits = new SearchLimits(1_000_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        PathResult detail = new AStarPathfinder(narrow, limits).search(start, goal, NEVER, 0);

        // 詳細探索は幅4の地峡を水に入らず歩き切る＝陸路は実在する
        assertTrue(detail.complete(), "詳細探索は地峡を歩いて到達できる: " + detail.termination());
        assertEquals(0, waterSteps(narrow, detail), "陸路があるのに詳細探索が水に入った");

        // それでも層1は地峡を見失って水路を横断する中間目標を出す
        assertTrue(throughWaterCells(narrowCoarse, narrowRoute) > 0,
                "層1が水路を横断していない＝再現できていない: " + narrowRoute.waypoints());
        // 地峡がチャンク解像度で見える幅なら、同じ地形で層1は水に入らない
        assertEquals(0, throughWaterCells(wideCoarse, wideRoute),
                "幅16の地峡なら層1は陸路を通るはず: " + wideRoute.waypoints());
    }

    /**
     * 遠回りシナリオ: 幅4の地峡(z∈[0,4)、見えない) の北に、z≥{@code openFrom}で開ける
     * 陸のブロックがある(チャンク解像度で見える)。地峡が見えないぶん、層1は北へ迂回するか
     * 水路を泳ぐしかない。詳細探索は地峡を歩く。
     */
    private static FakeCells terrainWithNorthGap(int isthmusWidth, int channelMaxX, int openFrom) {
        SearchBounds bounds = new SearchBounds(MIN_X, 40, MIN_Z, MAX_X, 110, MAX_Z);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(0);
        for (int x = MIN_X; x < MAX_X; x++) {
            for (int z = MIN_Z; z < MAX_Z; z++) {
                boolean inChannel = x >= 64 && x < channelMaxX;
                boolean onIsthmus = z >= 0 && z < isthmusWidth;
                boolean northOfGap = z >= openFrom;
                if (inChannel && !onIsthmus && !northOfGap) {
                    cells.set(x, GROUND_Y - 2, z, FakeCells.STONE);
                    cells.set(x, WATER_SURFACE_Y - 1, z, FakeCells.WATER);
                    cells.set(x, WATER_SURFACE_Y, z, FakeCells.WATER);
                } else {
                    cells.set(x, GROUND_Y, z, FakeCells.STONE);
                }
            }
        }
        return cells;
    }

    @Test
    void coarseMapDetoursOrSwimsBecauseTheShortcutIsSubChunk() {
        FakeCells cells = terrainWithNorthGap(4, 112, 32);
        BlockPos start = new BlockPos(8, STAND_Y, 2);
        BlockPos goal = new BlockPos(200, STAND_Y, 2);

        System.out.println("--- detail vs coarse (short isthmus + near north gap) ---");
        reportDetail("detail -> goal", cells, start, goal, 0);

        SearchLimits limits = new SearchLimits(1_000_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        PathResult detail = new AStarPathfinder(cells, limits).search(start, goal, NEVER, 0);
        int detailMaxZ = detail.steps().stream().mapToInt(s -> s.pos().getZ()).max().orElse(0);

        CoarseMap coarse = coarseOf(cells);
        System.out.println(coarse.kindBreakdown());
        dumpIsthmusRow("isthmus row", coarse);
        CoarseRouter.Route route = reportCoarse("coarse route", coarse, start, goal);
        int coarseMaxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(0);

        assertTrue(detail.complete() && detailMaxZ <= 8, "詳細探索は地峡沿い(z≈2)を歩く: maxZ=" + detailMaxZ);
        assertTrue(coarseMaxZ >= 24,
                "層1が地峡を見失って北へ迂回する（遠回り）はず: coarseMaxZ=" + coarseMaxZ);
    }
}
