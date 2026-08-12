package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class CoarseRouterTest {

    private static final int RADIUS = 40;

    /** 全面が平坦な陸のマップ。ここへ海や崖を書き込んでいく。 */
    private static CoarseMapBuilder flatLand() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.put(x, z, CoarseMap.LAND, 64);
            }
        }
        return builder;
    }

    private static BlockPos atChunk(int chunkX, int chunkZ) {
        return new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8);
    }

    @Test
    void routesStraightAcrossOpenLand() {
        CoarseMap map = flatLand().build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0));

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        // 平坦な陸を横切るだけなので、Zは出発点の帯から外れない
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getZ() >= -16 && waypoint.getZ() <= 32,
                    "平坦な陸なのに逸れた: " + waypoint);
        }
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    @Test
    void detoursAroundWaterInsteadOfSwimming() {
        CoarseMapBuilder builder = flatLand();
        // 目的地との間を塞ぐ湾。北側(Z<-6)は開いているので、そちらへ迂回できる
        for (int x = 4; x <= 16; x++) {
            for (int z = -6; z <= RADIUS - 1; z++) {
                builder.put(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0));

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        // 迂回するなら、湾を跨ぐ区間では必ず北へ出ている
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() < -6 * 16),
                "湾を迂回せず突っ切った: " + route.waypoints());
    }

    @Test
    void swimsWhenDetourIsFarLonger() {
        CoarseMapBuilder builder = flatLand();
        // 端から端まで塞ぐ海峡。迂回路が無いので、遠回りより泳ぐ方が安い
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.put(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0));

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    @Test
    void neverRoutesThroughLava() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.put(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0));

        // 溶岩で完全に分断されているので、目的地へは到達できない
        assertFalse(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getX() < 4 * 16, "溶岩帯に踏み込んだ: " + waypoint);
        }
    }

    @Test
    void prefersKnownGroundOverUnmappedShortcut() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        // 地図に無い一帯を、既知の陸の帯が1本だけ横切っている。少し逸れれば乗れる位置に置くのは、
        // 未知のペナルティが「遠回りしてでも避ける」ほど重くはないため（遠い帯なら直進が正しい）
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = 2; z <= 4; z++) {
                builder.put(x, z, CoarseMap.LAND, 64);
            }
        }
        builder.put(0, 0, CoarseMap.LAND, 64);
        builder.put(20, 0, CoarseMap.LAND, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0));

        assertTrue(route.reachedGoal());
        // 未知を突っ切る直線より、分かっている陸の帯へ寄る
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() >= 2 * 16),
                "既知の陸を使わず未知を突っ切った: " + route.waypoints());
    }

    @Test
    void reportsFailureWhenGoalIsOutsideTheMap() {
        CoarseMap map = flatLand().build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(RADIUS + 10, 0));

        assertFalse(route.reachedGoal());
        assertTrue(route.isEmpty());
    }

    @Test
    void prefersFlatGroundOverClimbingWhenDistanceIsSimilar() {
        CoarseMapBuilder builder = flatLand();
        // 目的地へ一直線の帯だけが高い尾根。1マス北へ避ければ平坦
        for (int x = 1; x <= 19; x++) {
            builder.put(x, 0, CoarseMap.LAND, 140);
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0));

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getY() < 140, "尾根の上を通った: " + waypoint);
        }
    }

    private static BlockPos last(CoarseRouter.Route route) {
        List<BlockPos> waypoints = route.waypoints();
        return waypoints.get(waypoints.size() - 1);
    }
}
