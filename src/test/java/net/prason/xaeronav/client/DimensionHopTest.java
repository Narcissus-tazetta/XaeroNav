package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/** 地上⇄ネザーで目的地をバニラのポータルと同じ換算で持ち越し、戻ったら元の正確な座標で続ける。 */
class DimensionHopTest {

    private static final double TO_NETHER = 1.0 / 8.0;
    private static final int NETHER_MIN_Y = 1;
    private static final int NETHER_MAX_Y = 126;
    private static final int OVERWORLD_MIN_Y = -63;
    private static final int OVERWORLD_MAX_Y = 318;

    @Test
    void overworldPointsGoToTheSameNetherBlockAsAPortalWould() {
        DimensionHop.Converted converted = DimensionHop.leave(
                List.of(new BlockPos(1000, 70, -1000), new BlockPos(-1, 64, 7), new BlockPos(-9, 64, 8)),
                TO_NETHER, NETHER_MIN_Y, NETHER_MAX_Y, false);
        assertEquals(List.of(new BlockPos(125, 70, -125), new BlockPos(-1, 64, 0), new BlockPos(-2, 64, 1)),
                converted.points());
    }

    @Test
    void heightAboveTheNetherRoofIsBroughtUnderIt() {
        DimensionHop.Converted converted = DimensionHop.leave(List.of(new BlockPos(0, 200, 0)),
                TO_NETHER, NETHER_MIN_Y, NETHER_MAX_Y, false);
        assertEquals(126, converted.points().get(0).getY());
    }

    @Test
    void comingBackRestoresTheExactOriginalPoints() {
        List<BlockPos> original = List.of(new BlockPos(1003, 70, -997), new BlockPos(5000, 90, 5000));
        DimensionHop.Converted converted = DimensionHop.leave(original, TO_NETHER, NETHER_MIN_Y, NETHER_MAX_Y, false);
        assertEquals(original, converted.hop().back(converted.points(), OVERWORLD_MIN_Y, OVERWORLD_MAX_Y));
    }

    @Test
    void stopAddedInTheNetherComesBackScaledUp() {
        DimensionHop.Converted converted = DimensionHop.leave(List.of(new BlockPos(1000, 70, 0)),
                TO_NETHER, NETHER_MIN_Y, NETHER_MAX_Y, false);
        List<BlockPos> back = converted.hop().back(List.of(new BlockPos(10, 40, -3), converted.points().get(0)),
                OVERWORLD_MIN_Y, OVERWORLD_MAX_Y);
        assertEquals(List.of(new BlockPos(84, 40, -20), new BlockPos(1000, 70, 0)), back);
    }
}
