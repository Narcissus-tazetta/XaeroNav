package net.prason.xaeronav.pathfinding.corridor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;

/** {@link SurfaceGrid#resolveStandable}の振る舞い。 */
class SurfaceGridTest {

    @Test
    void resolvesLandToOneAboveGround() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 4, 4);
        builder.put(1, 1, CoarseMap.LAND, 64);

        assertEquals(new BlockPos(1, 65, 1), builder.build().resolveStandable(1, 1));
    }

    @Test
    void resolvesWaterToSurfaceHeightNotGroundPlusOne() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 4, 4);
        builder.put(1, 1, CoarseMap.WATER, 55, 64);

        assertEquals(new BlockPos(1, 64, 1), builder.build().resolveStandable(1, 1));
    }

    /**
     * 目的地の解決だけは水面と水底の2択になる。海底(55)を指した目的地を水面(64)へ丸めると、
     * 海の上で「到着」になってしまう。
     */
    @Test
    void resolvesAnUnderwaterGoalToTheSeabedRatherThanTheSurface() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 4, 4);
        builder.put(1, 1, CoarseMap.WATER, 55, 64);
        SurfaceGrid grid = builder.build();

        assertEquals(new BlockPos(1, 56, 1), grid.resolveStandableNear(1, 1, 55),
                "水底を指したなら水底の1つ上（足元が砂で体が水）");
        assertEquals(new BlockPos(1, 64, 1), grid.resolveStandableNear(1, 1, 63),
                "水面付近を指したなら水面のまま");
    }

    @Test
    void returnsNullWhenColumnHasNoData() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 4, 4);

        assertNull(builder.build().resolveStandable(1, 1));
    }

    @Test
    void returnsNullForLavaInsteadOfOneAboveTheLavaSurface() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 4, 4);
        builder.put(1, 1, CoarseMap.LAVA, 31);

        assertNull(builder.build().resolveStandable(1, 1));
    }

    @Test
    void resolveNearestStandableFallsBackToTheClosestLandColumnWhenTheEndpointIsLava() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 8, 8);
        builder.put(4, 4, CoarseMap.LAVA, 31);
        builder.put(6, 4, CoarseMap.LAND, 64);

        SurfaceGrid grid = builder.build();

        assertEquals(new BlockPos(6, 65, 4), grid.resolveNearestStandable(4, 4, 4));
    }

    @Test
    void resolveNearestStandablePicksTheCloserOfTwoLandColumns() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 8, 8);
        builder.put(4, 4, CoarseMap.LAVA, 31);
        builder.put(5, 4, CoarseMap.LAND, 60);
        builder.put(7, 4, CoarseMap.LAND, 70);

        SurfaceGrid grid = builder.build();

        assertEquals(new BlockPos(5, 61, 4), grid.resolveNearestStandable(4, 4, 4));
    }

    @Test
    void resolveNearestStandableReturnsNullWhenNothingIsFoundWithinRadius() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 16, 16);
        builder.put(8, 8, CoarseMap.LAVA, 31);
        builder.put(15, 15, CoarseMap.LAND, 64);

        SurfaceGrid grid = builder.build();

        assertNull(grid.resolveNearestStandable(8, 8, 2));
    }

    @Test
    void resolveNearestStandableReturnsTheDirectHitWithoutSearchingWhenPossible() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 4, 4);
        builder.put(1, 1, CoarseMap.LAND, 64);

        assertEquals(new BlockPos(1, 65, 1), builder.build().resolveNearestStandable(1, 1, 4));
    }
}
