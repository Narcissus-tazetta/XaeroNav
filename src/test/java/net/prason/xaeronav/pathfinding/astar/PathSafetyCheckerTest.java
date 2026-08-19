package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;

/**
 * 提示直前の安全性チェック（design doc §3-4）。コストで表現しきれない「歩けるが条件がある」区間に
 * 印が付くかを見る。
 */
class PathSafetyCheckerTest {

    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    /**
     * マグマブロックは足場として通行可（スニークすれば無傷）だが、走って踏めば燃える。
     * 通れる以上、条件を伝えないと「案内どおり歩いたら焼かれた」になる。
     */
    @Test
    void marksStepsOverMagmaAsNeedingASneak() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                .....
                #MMM#""")
                .extrudeZ(-1, 1);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), new BlockPos(4, 61, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.complete(), "マグマブロックは通行可");
        assertEquals(3, annotated.steps().stream()
                        .filter(step -> step.risk() == PathRisk.SNEAK_OVER_MAGMA).count(),
                "マグマの上を通る3歩すべてに印が付くはず: " + annotated.steps());
    }

    @Test
    void leavesOrdinaryGroundUnmarked() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                .....
                #####""")
                .extrudeZ(-1, 1);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), new BlockPos(4, 61, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.steps().stream().allMatch(step -> step.risk() == PathRisk.NONE),
                "普通の地面に印を付けてはいけない: " + annotated.steps());
    }
}
