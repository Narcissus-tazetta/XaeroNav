package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;

/**
 * 並列フォールバックの通常予算側に渡す上限。
 *
 * <p>重みだけを落とし、予算と時間には触らない——通常予算側は<b>失敗してよい</b>探索で、
 * 失敗したぶんは同時に走っている深い予算が拾う。予算まで削ると、拾える経路まで減らすことになる。
 */
class QualityPassTest {

    @Test
    void lowersOnlyTheWeight() {
        SearchLimits configured = new SearchLimits(100_000, 2_000,
                AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        SearchLimits quality = PathfindingState.qualityLimits(configured);

        assertEquals(configured.maxExpandedNodes(), quality.maxExpandedNodes());
        assertEquals(configured.timeLimitMillis(), quality.timeLimitMillis());
        assertEquals(1.2, quality.heuristicWeight(), 1e-9);
    }

    /** 設定で既定より軽い重みにしている人の値は、こちらの都合で戻さない。 */
    @Test
    void neverRaisesAWeightTheUserAlreadyLowered() {
        SearchLimits configured = new SearchLimits(100_000, 2_000, 1.05);

        assertEquals(1.05, PathfindingState.qualityLimits(configured).heuristicWeight(), 1e-9);
    }
}
