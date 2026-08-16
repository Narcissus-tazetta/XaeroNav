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

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

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

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        // 迂回するなら、湾を跨ぐ区間では必ず北へ出ている
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() < -6 * 16),
                "湾を迂回せず突っ切った: " + route.waypoints());
    }

    @Test
    void crossesWaterDirectlyWhenBoatIsAvailable() {
        CoarseMapBuilder builder = flatLand();
        // 迂回できる湾（detoursAroundWaterInsteadOfSwimmingと同じ地形）。ボート無しでは迂回するが、
        // ボートは徒歩より速いので、ボートがあれば迂回せず突っ切る方が安くなるはず
        for (int x = 4; x <= 16; x++) {
            for (int z = -6; z <= RADIUS - 1; z++) {
                builder.put(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), true,
                CoarseRouter.LavaPolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        assertTrue(route.waypoints().stream().noneMatch(waypoint -> waypoint.getZ() < -6 * 16),
                "ボートがあるのに迂回した: " + route.waypoints());
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

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

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

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

        // 溶岩で完全に分断されているので、目的地へは到達できない
        assertFalse(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getX() < 4 * 16, "溶岩帯に踏み込んだ: " + waypoint);
        }
    }

    /**
     * 溶岩が混じるだけのセルは通れる。ネザーは既知セルの過半数がこれになるので、
     * 通行不能にすると経路がまったく繋がらない。
     */
    @Test
    void crossesMixedLavaWhenItIsTheOnlyWay() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.put(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    /** ただし迂回できるなら迂回する——「通れる」と「選ぶ」は別。 */
    @Test
    void detoursAroundMixedLavaWhenCleanGroundExists() {
        CoarseMapBuilder builder = flatLand();
        // 進路上に溶岩混じりの帯を置くが、Z方向に少し逸れれば素の陸で回り込める
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.put(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            int chunkX = waypoint.getX() >> 4;
            int chunkZ = waypoint.getZ() >> 4;
            boolean insideMixedLava = chunkX >= 4 && chunkX <= 6 && chunkZ >= -2 && chunkZ <= 2;
            assertFalse(insideMixedLava, "迂回できるのに溶岩混じりを突っ切った: " + waypoint);
        }
    }

    /**
     * {@link CoarseRouter.LavaPolicy#AVOID}は溶岩混じりも通行不能にする。ネザーではこれで
     * 経路が繋がらなくなることが多いが、それは呼び出し側が次の段へ進む合図になる。
     */
    @Test
    void avoidPolicyRefusesMixedLavaEvenWhenItIsTheOnlyWay() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.put(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.AVOID);

        assertFalse(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getX() < 4 * 16, "溶岩混じりに踏み込んだ: " + waypoint);
        }
    }

    /** 迂回路があるなら{@code AVOID}でも当然そちらを通って到達する。 */
    @Test
    void avoidPolicyStillReachesGoalByDetouring() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.put(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.AVOID);

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    /** {@code BRIDGE}は、他のどのポリシーでも通れない溶岩の帯を橋で渡る前提で横断する。 */
    @Test
    void bridgePolicyCrossesFullLavaThatBlocksEveryOtherPolicy() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.put(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        assertFalse(CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW).reachedGoal());

        CoarseRouter.Route bridged = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.BRIDGE);

        assertTrue(bridged.reachedGoal());
        assertEquals(20 * 16 + 8, last(bridged).getX());
    }

    /** {@code BRIDGE}でも、溶岩を避けられるならそちらを通る——最後の手段であって近道ではない。 */
    @Test
    void bridgePolicyStillPrefersCleanGround() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.put(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.BRIDGE);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            int chunkX = waypoint.getX() >> 4;
            int chunkZ = waypoint.getZ() >> 4;
            boolean insideLava = chunkX >= 4 && chunkX <= 6 && chunkZ >= -2 && chunkZ <= 2;
            assertFalse(insideLava, "迂回できるのに溶岩を渡った: " + waypoint);
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

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

        assertTrue(route.reachedGoal());
        // 未知を突っ切る直線より、分かっている陸の帯へ寄る
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() >= 2 * 16),
                "既知の陸を使わず未知を突っ切った: " + route.waypoints());
    }

    @Test
    void reportsFailureWhenGoalIsOutsideTheMap() {
        CoarseMap map = flatLand().build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(RADIUS + 10, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

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

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getY() < 140, "尾根の上を通った: " + waypoint);
        }
    }

    @Test
    void avoidsCliffyCellsEvenWhenAverageHeightMatchesSurroundings() {
        CoarseMapBuilder builder = flatLand();
        // 平均高さは周囲と同じ64だが、セル内の起伏（0〜128）が大きい＝崖のチャンク。
        // 平均だけを見る旧ロジックでは検出できず、1マス北の平坦な迂回路と無差別だった
        for (int x = 1; x <= 19; x++) {
            builder.put(x, 0, CoarseMap.LAND, 64, 0, 128);
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.LavaPolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() != 8),
                "起伏の大きいセルを避けず素通りした: " + route.waypoints());
    }

    private static BlockPos last(CoarseRouter.Route route) {
        List<BlockPos> waypoints = route.waypoints();
        return waypoints.get(waypoints.size() - 1);
    }
}
