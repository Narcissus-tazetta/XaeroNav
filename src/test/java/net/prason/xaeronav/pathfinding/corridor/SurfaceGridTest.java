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
}
