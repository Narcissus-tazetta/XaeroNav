package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * {@link PathfindingExecutor#submitCoarseGuided}が、直接探索では届かない予算でも
 * 経由地チェーンで区間を分割することで届くことを確認する（design doc外・層3の局所障害対策）。
 */
class PathfindingExecutorCoarseGuidedTest {

    @Test
    void coarseGuidedReachesFartherThanADirectSearchOnTheSameTinyBudget() throws Exception {
        SearchBounds bounds = new SearchBounds(-16, 0, -16, 216, 100, 32);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = -16; x <= 216; x++) {
            for (int z = 0; z <= 15; z++) {
                cells.set(x, 63, z, FakeCells.STONE);
            }
        }
        BlockPos start = new BlockPos(0, 64, 8);
        BlockPos goal = new BlockPos(200, 64, 8);
        // 平地でも200ブロックには遠く足りない、直接探索が絶対に届かない予算
        SearchLimits tinyLimits = new SearchLimits(50, 2_000, 1.5);

        PathfindingExecutor executor = new PathfindingExecutor();
        PathResult direct = executor.submit(cells, start, goal, tinyLimits).get(5, TimeUnit.SECONDS);
        assertFalse(direct.complete());

        PathResult coarseGuided = executor.submitCoarseGuided(cells, bounds, start, goal, tinyLimits)
                .get(5, TimeUnit.SECONDS);
        assertTrue(coarseGuided.complete());
    }
}
