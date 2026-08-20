package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseMapBuilder;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;

/**
 * 空中の長距離ルート。地形はXaero非依存の{@link CoarseMapBuilder}で直接組める。
 *
 * <p>ネザーを想定して、床(y=32)と岩盤天井(y=120)のあいだを飛ぶ形で書く。
 */
class CoarseFlightRouterTest {

    private static final int MIN_CHUNK = -20;
    private static final int CHUNKS = 41;
    private static final int MIN_Y = 34;
    /** 岩盤天井の下。天井そのものは不透明なので床としては記録されない。 */
    private static final int MAX_Y = 118;

    private static final int FLOOR = 32;

    /** 全セルに床を1枚だけ置いた、開けたネザー。 */
    private static CoarseMapBuilder openNether() {
        CoarseMapBuilder builder = new CoarseMapBuilder(MIN_CHUNK, MIN_CHUNK, CHUNKS, CHUNKS);
        for (int x = MIN_CHUNK; x < MIN_CHUNK + CHUNKS; x++) {
            for (int z = MIN_CHUNK; z < MIN_CHUNK + CHUNKS; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, FLOOR);
            }
        }
        return builder;
    }

    private static CoarseAirMap air(CoarseMapBuilder builder) {
        return CoarseAirMap.from(builder.build(), MIN_Y, MAX_Y);
    }

    private static CoarseRouter.Route route(CoarseMapBuilder builder, BlockPos start, BlockPos goal) {
        return CoarseFlightRouter.findRoute(air(builder), start, goal, true);
    }

    @Test
    void derivesOneWideBandOverAFlatFloor() {
        CoarseAirMap map = air(openNether());

        assertEquals(1, map.bandCount(0, 0));
        assertTrue(map.bandBottom(0, 0, 0) > FLOOR, "床のすぐ上を帯に含めている");
        assertEquals(MAX_Y, map.bandTop(0, 0, 0), "最上段の帯が天井まで伸びていない");
    }

    @Test
    void dropsBandsThatAreTooThinToFlyThrough() {
        // 床32のすぐ上、y=44 に天井（＝次の床）。あいだは薄すぎて飛べない
        CoarseMapBuilder builder = openNether();
        builder.putFloor(0, 0, CoarseMap.LAND, 44);
        CoarseAirMap map = air(builder);

        // 32の上の帯は 36..40 で薄いので捨てられ、44の上の帯だけが残る
        assertEquals(1, map.bandCount(0, 0));
        assertTrue(map.bandBottom(0, 0, 0) > 44, "薄い帯の方が残っている");
    }

    @Test
    void routesStraightAcrossOpenNether() {
        CoarseRouter.Route route = route(openNether(), new BlockPos(-300, 70, 0), new BlockPos(300, 70, 0));

        assertTrue(route.reachedGoal(), "開けた地形で届いていない");
        assertFalse(route.isEmpty());
        assertTrue(route.waypoints().stream().allMatch(point -> Math.abs(point.getZ()) < 64),
                "まっすぐ行ける所で横に振れている: " + route.waypoints());
    }

    /**
     * 粗い層が「壁」を表現できる唯一の形——床が天井近くまで詰まっていて、飛べる厚みの帯が
     * 1つも残らないセル。逆に言えば、床が4層までしか無い以上、低い所から天井まで完全に
     * 塞がった列はこの層では表現しきれない（層3の担当）。
     */
    private static void sealColumn(CoarseMapBuilder builder, int chunkX, int chunkZ) {
        // 天井直下に床を置くと最上段の帯が消え、その下の帯も薄くして潰す
        builder.putFloor(chunkX, chunkZ, CoarseMap.LAND, MAX_Y - 2);
        builder.putFloor(chunkX, chunkZ, CoarseMap.LAND, MAX_Y - 14);
    }

    @Test
    void goesAroundAWallOfSealedColumns() {
        CoarseMapBuilder builder = new CoarseMapBuilder(MIN_CHUNK, MIN_CHUNK, CHUNKS, CHUNKS);
        for (int x = MIN_CHUNK; x < MIN_CHUNK + CHUNKS; x++) {
            for (int z = MIN_CHUNK; z < MIN_CHUNK + CHUNKS; z++) {
                if (x == 0 && z <= 4) {
                    sealColumn(builder, x, z);
                } else {
                    builder.putFloor(x, z, CoarseMap.LAND, MAX_Y - 20);
                }
            }
        }
        CoarseAirMap map = air(builder);
        assertTrue(map.blocked(0, 0), "塞いだ列が壁になっていない");
        assertFalse(map.blocked(0, 8), "壁でない列まで塞がっている");

        CoarseRouter.Route route = CoarseFlightRouter.findRoute(map,
                new BlockPos(-300, 110, 0), new BlockPos(300, 110, 0), true);

        assertTrue(route.reachedGoal(), "壁を回り込めていない");
        assertTrue(route.waypoints().stream().anyMatch(point -> point.getZ() > 70),
                "壁の端（チャンクz>4）を回っていない: " + route.waypoints());
    }

    @Test
    void tellsAWallApartFromUnvisitedGround() {
        CoarseMapBuilder builder = new CoarseMapBuilder(MIN_CHUNK, MIN_CHUNK, CHUNKS, CHUNKS);
        sealColumn(builder, 0, 0);
        CoarseAirMap map = air(builder);

        assertTrue(map.blocked(0, 0), "床が詰まったセルが壁と判定されていない");
        assertFalse(map.unknown(0, 0), "データがあるのに未訪問扱いになっている");
        assertTrue(map.unknown(5, 5), "何も書いていないセルが未訪問扱いになっていない");
        assertFalse(map.blocked(5, 5), "未訪問のセルが壁になっている");
    }

    @Test
    void staysInTheLowerBandWhenTheUpperOneIsSealedOff() {
        // 全域に2層。下の層(32)の上と、上の層(80)の上に帯ができる。
        // 上の層は x=0 の列で天井まで塞ぐので、上の帯を通る道は無い
        CoarseMapBuilder builder = openNether();
        for (int x = MIN_CHUNK; x < MIN_CHUNK + CHUNKS; x++) {
            for (int z = MIN_CHUNK; z < MIN_CHUNK + CHUNKS; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, 80);
            }
        }
        CoarseAirMap map = air(builder);
        assertEquals(2, map.bandCount(0, 0), "2層の床から帯が2つできていない");

        CoarseRouter.Route route = CoarseFlightRouter.findRoute(map,
                new BlockPos(-300, 40, 0), new BlockPos(300, 40, 0), true);

        assertTrue(route.reachedGoal());
        assertTrue(route.waypoints().stream().allMatch(point -> point.getY() < 80),
                "下の帯から出発したのに上の帯へ飛び移っている（床＝岩を突き抜けている）: "
                        + route.waypoints());
    }

    @Test
    void treatsUnmappedGroundAsPassable() {
        // 何も書かれていない地図＝未訪問。飛行では「行けないと決まった」わけではない
        CoarseMapBuilder builder = new CoarseMapBuilder(MIN_CHUNK, MIN_CHUNK, CHUNKS, CHUNKS);

        CoarseRouter.Route route = route(builder, new BlockPos(-300, 70, 0), new BlockPos(300, 70, 0));

        assertTrue(route.reachedGoal(), "未訪問領域が壁になっている");
    }

    @Test
    void thinsWaypointsToTheSameSpacingAsTheWalkingLayer() {
        CoarseRouter.Route route = route(openNether(), new BlockPos(-300, 70, 0), new BlockPos(300, 70, 0));

        List<BlockPos> waypoints = route.waypoints();
        for (int i = 1; i < waypoints.size() - 1; i++) {
            double spacing = Math.hypot(waypoints.get(i).getX() - waypoints.get(i - 1).getX(),
                    waypoints.get(i).getZ() - waypoints.get(i - 1).getZ());
            assertTrue(spacing >= 32.0 && spacing <= 128.0,
                    "中間目標の間隔が想定（64ブロック前後）から外れている: " + spacing);
        }
    }

    @Test
    void reportsNotReachedWhenTheGoalIsOutsideTheMap() {
        CoarseRouter.Route route = route(openNether(), new BlockPos(0, 70, 0), new BlockPos(9000, 70, 0));

        assertFalse(route.reachedGoal());
        assertTrue(route.isEmpty());
    }
}
