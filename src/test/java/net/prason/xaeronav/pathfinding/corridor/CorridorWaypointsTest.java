package net.prason.xaeronav.pathfinding.corridor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class CorridorWaypointsTest {

    @Test
    void stitchConcatenatesLegsInOrder() {
        List<BlockPos> leg1 = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
        List<BlockPos> leg2 = List.of(new BlockPos(2, 64, 0));

        List<BlockPos> stitched = CorridorWaypoints.stitch(List.of(leg1, leg2));

        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(2, 64, 0)), stitched);
    }

    @Test
    void downsampleDropsPointsCloserThanMinSpacing() {
        List<BlockPos> points = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(10, 64, 0),
                new BlockPos(30, 64, 0));

        List<BlockPos> downsampled = CorridorWaypoints.downsample(points, 24);

        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 0)), downsampled);
    }

    @Test
    void downsampleAlwaysKeepsTheFinalPoint() {
        List<BlockPos> points = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0));

        List<BlockPos> downsampled = CorridorWaypoints.downsample(points, 24);

        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(2, 64, 0)), downsampled);
    }

    @Test
    void downsampleOfSinglePointReturnsThatPoint() {
        List<BlockPos> points = List.of(new BlockPos(5, 64, 5));

        List<BlockPos> downsampled = CorridorWaypoints.downsample(points, 24);

        assertEquals(List.of(new BlockPos(5, 64, 5)), downsampled);
    }
}
