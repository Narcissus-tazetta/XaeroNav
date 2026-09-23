package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.PathResult.Termination;
import org.junit.jupiter.api.Test;

/**
 * 途中までの経路どうしは、目的地の近くまで引けている方を残す。実機のネザーでは、目的地まで16ブロックの
 * 所まで引けていた経路が、予算切れで145ブロック手前で切れた引き直しの結果に置き換わっていた。
 */
class PartialProgressTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos GOAL = new BlockPos(200, 64, 0);

    private static PathResult partialTo(int x) {
        BlockPos end = new BlockPos(x, 64, 0);
        PathStep step = new PathStep(end, MovementType.TRAVERSE, 1.0, List.of(end, end.above()), List.of(),
                PathRisk.NONE, null);
        return new PathResult(List.of(step), Termination.NODE_BUDGET, 1, 1);
    }

    @Test
    void keepsTheRouteThatGetsCloser() {
        assertTrue(PartialProgress.compare(partialTo(184), partialTo(55), START, GOAL, null).oldAhead());
    }

    @Test
    void takesTheNewResultWhenItGetsCloser() {
        assertFalse(PartialProgress.compare(partialTo(55), partialTo(184), START, GOAL, null).oldAhead());
    }

    /** 同じ所まで引けたなら新しい方を採る。古い経路は古い地形の読みで引いたものなので。 */
    @Test
    void takesTheNewResultOnATie() {
        assertFalse(PartialProgress.compare(partialTo(100), partialTo(100), START, GOAL, null).oldAhead());
    }
}
