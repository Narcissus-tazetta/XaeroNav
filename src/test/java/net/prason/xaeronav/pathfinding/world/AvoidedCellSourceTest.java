package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;

class AvoidedCellSourceTest {

    private static final SearchLimits LIMITS = new SearchLimits(10_000, 5_000, 1.0);

    @Test
    void routesAroundTheAvoidedFootingInsteadOfThroughIt() throws Exception {
        // 3マス幅の平らな床。まっすぐ歩くのが最短だが、その途中の足場を1マス避ければ
        // 隣の列へ逸れて回り込むしかなくなる
        FakeCells cells = FakeCells.of(0, 60, 0, """
                .......
                #######""").extrudeZ(0, 2);
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(6, 61, 0);
        BlockPos footing = new BlockPos(3, 60, 0);

        PathResult direct = search(cells, start, goal);
        assertTrue(direct.complete());
        assertTrue(steppedOn(direct, footing), "前提: 避けなければこの足場を通る");

        PathResult avoided = search(AvoidedCellSource.wrap(cells, List.of(footing)), start, goal);
        assertTrue(avoided.complete(), "回り道が残っているなら経路は引ける");
        assertFalse(steppedOn(avoided, footing), "避けたセルを選び直してはいけない");
        assertTrue(CellData.standable(cells.cell(3, 60, 0)), "ワールドは書き換えない");
    }

    @Test
    void wrappingNothingReturnsTheSourceItself() {
        FakeCells cells = FakeCells.of(0, 60, 0, """
                ...
                ###""");
        assertSame(cells, AvoidedCellSource.wrap(cells, List.of()));
    }

    private static PathResult search(CellSource cells, BlockPos start, BlockPos goal) throws Exception {
        return new PathfindingExecutor().submit(cells, start, goal, LIMITS, false)
                .get(10, TimeUnit.SECONDS);
    }

    private static boolean steppedOn(PathResult result, BlockPos footing) {
        return result.steps().stream().anyMatch(step -> step.pos().equals(footing.above()));
    }
}
