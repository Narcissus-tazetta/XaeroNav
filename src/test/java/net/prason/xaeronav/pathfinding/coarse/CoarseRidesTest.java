package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.rail.TestRails;

/** 層1が、覚えた線路をトロッコで走る辺を使うか。 */
class CoarseRidesTest {

    private static final int RADIUS = 40;
    private static final int RAIL_Z = 10 * 16 + 8;

    private static CoarseMap flatLand() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }
        return builder.build();
    }

    private static BlockPos atChunk(int chunkX, int chunkZ) {
        return new BlockPos(chunkX * 16 + 8, 65, chunkZ * 16 + 8);
    }

    /** 東西に約960ブロック、8本おきに通電パワード。 */
    private static final CoarseRides LINE =
            CoarseRides.of(new TestRails().eastWest(-30 * 16, 30 * 16, 65, RAIL_Z, 8).network());

    @Test
    void detoursToALineThatIsFasterThanWalkingStraight() {
        CoarseMap map = flatLand();
        BlockPos start = atChunk(-30, 4);
        BlockPos goal = atChunk(30, 4);

        CoarseRouter.Route walking = CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.ALLOW);
        CoarseRouter.Route riding = CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.ALLOW,
                LINE);

        assertTrue(walking.reachedGoal() && riding.reachedGoal());
        assertTrue(walking.waypoints().stream().noneMatch(pos -> pos.getZ() == RAIL_Z));
        // 6チャンク北の線路まで歩き、乗って東へ走る。地図の線が線路を外れないよう、乗っている間は線路の点を並べる
        long onRail = riding.waypoints().stream().filter(pos -> pos.getZ() == RAIL_Z).count();
        assertTrue(onRail >= 10, "線路の上の経由地: " + riding.waypoints());
    }

    @Test
    void theGuideIsCheaperAlongTheLine() {
        CoarseMap map = flatLand();
        BlockPos goal = atChunk(30, 10);
        BlockPos start = atChunk(-30, 10);

        double walking = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE)
                .estimate(start.getX(), start.getY(), start.getZ());
        double riding = CoarseRouter.farEstimate(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE, LINE)
                .estimate(start.getX(), start.getY(), start.getZ());

        // 歩けば60チャンク×57tick≈3400tick、走れば960ブロック×2.5tick≈2400tickに乗り降り
        assertTrue(riding < walking - 600, riding + " vs " + walking);
    }

    @Test
    void noEdgesForALineThatCanOnlyBePushed() {
        assertTrue(CoarseRides.of(new TestRails().eastWest(0, 400, 65, 8, 0).network()).size() == 0);
    }
}
