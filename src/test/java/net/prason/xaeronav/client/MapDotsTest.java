package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 地図の点列は、XZが同じ連続ステップ（階段・掘り下げ）を1点に潰す。そのため通り過ぎた区間を
 * 飛ばすには、ステップの添字から点の添字への引き直しが要る。
 */
class MapDotsTest {

    private static PathResult path(List<BlockPos> positions) {
        List<PathStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new PathStep(pos, MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null));
        }
        return new PathResult(steps, PathResult.Termination.REACHED_GOAL, positions.size(), positions.size());
    }

    @Test
    void mapsStepIndicesOntoDotsThatCollapsedVerticalRuns() {
        // ステップ1〜3はXZが同じ（真上へ登る階段）ので1点に潰れる
        MapDots dots = MapDots.forPath(path(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(2, 66, 0))));

        assertEquals(3, dots.count);
        assertEquals(0, dots.firstDotFrom(0));
        assertEquals(1, dots.firstDotFrom(1));
        // 潰された区間の途中を指されたら、その区間を作った点から描き始める
        assertEquals(2, dots.firstDotFrom(2));
        assertEquals(2, dots.firstDotFrom(4));
    }

    @Test
    void returnsTheEndWhenEverythingIsBehind() {
        MapDots dots = MapDots.forPath(path(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0))));

        assertEquals(dots.count, dots.firstDotFrom(99));
    }
}
