package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import org.junit.jupiter.api.Test;

/**
 * {@link PathfindingState}から切り出した長距離ルートの持ち方。地図を読む処理（Xaeroが要る）は通さず、
 * 解いた結果を採用したあとの状態遷移——目的地での持ち主の見分け、目的地の寄せ直し、地図の読み込み待ちの
 * 引き直しの回数、片付け——だけを確かめる。
 *
 * <p>採用は{@code ceilingDimension=true}で行う。天井のある次元では層2の精緻化を掛けないので、
 * Xaeroの地表データへ触れずに済む。
 */
class CoarseRoutesTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos GOAL = new BlockPos(400, 70, 0);
    private static final List<BlockPos> WAYPOINTS = List.of(
            new BlockPos(104, 66, 8), new BlockPos(200, 68, 8), new BlockPos(392, 72, 8));

    private final AtomicInteger replaced = new AtomicInteger();
    private final AtomicInteger changed = new AtomicInteger();
    private final CoarseRoutes routes = new CoarseRoutes(Runnable::run, replaced::incrementAndGet,
            changed::incrementAndGet);

    private void adopt(boolean reachedGoal, int pendingRegions) {
        routes.adoptCoarseRoute(START, GOAL,
                new CoarseRoutes.CoarseAttempt(new CoarseRouter.Route(WAYPOINTS, reachedGoal), pendingRegions), true);
    }

    /** 目的地まで届いたルートは、粗い終点（チャンク中心）を本来の目的地へ差し替えて持つ。 */
    @Test
    void aRouteThatReachesTheGoalEndsExactlyAtTheGoal() {
        adopt(true, 0);

        List<BlockPos> waypoints = routes.waypointsFor(GOAL);
        assertEquals(WAYPOINTS.size(), waypoints.size());
        assertEquals(GOAL, waypoints.get(waypoints.size() - 1));
        assertEquals(WAYPOINTS.subList(0, 2), waypoints.subList(0, 2));
        assertFalse(routes.unmappedFor(GOAL));
    }

    /** 届かなかったルートは終点をそのまま残し、詰みの理由の材料として「届いていない」と答える。 */
    @Test
    void aRouteThatFallsShortKeepsItsLastWaypointAndReportsItself() {
        adopt(false, 0);

        assertEquals(WAYPOINTS, routes.waypointsFor(GOAL));
        assertTrue(routes.unmappedFor(GOAL));
    }

    /** 列を入れ替えるたびに、通過済みの数を数え直させる（添字の意味が変わるため）。 */
    @Test
    void adoptingARouteAsksToRecountPassedWaypoints() {
        adopt(true, 0);
        adopt(true, 0);

        assertEquals(2, replaced.get());
    }

    /** 持ち主は目的地の座標で見分ける。別の目的地へのルートは無いものとして扱う。 */
    @Test
    void aRouteBelongsOnlyToItsOwnGoal() {
        adopt(true, 3);
        BlockPos other = GOAL.offset(1, 0, 0);

        assertEquals(List.of(), routes.waypointsFor(other));
        assertEquals(-1, routes.pendingRegionsFor(other));
        assertFalse(routes.unmappedFor(other));
        assertEquals(3, routes.pendingRegionsFor(GOAL));
        assertNull(routes.mapFor(GOAL), "地図を読んでいないのに地図がある");
    }

    /** 目的地を同じ列の別の高さへ寄せ直しても、ルートは捨てずに新しい目的地へ付け替える。 */
    @Test
    void retargetingMovesTheRouteToTheNewGoal() {
        adopt(true, 0);
        BlockPos resolved = GOAL.above(5);

        routes.retarget(resolved);

        assertEquals(List.of(), routes.waypointsFor(GOAL));
        List<BlockPos> waypoints = routes.waypointsFor(resolved);
        assertEquals(resolved, waypoints.get(waypoints.size() - 1));
    }

    /** 地図が揃って引いたルートは、読み込み待ちの引き直しをしない。 */
    @Test
    void aRouteDrawnOnACompleteMapIsNotRedrawn() {
        adopt(true, 0);

        assertFalse(routes.beginMapRetry(GOAL));
    }

    /**
     * 地図が欠けたまま引いたルートは引き直すが、1つの目的地につき回数に上限がある（読み込みが終わらない
     * リージョンがあっても、3秒ごとの全引き直しが目的地まで続かないように）。
     */
    @Test
    void redrawsForTheLoadedMapStopAfterTheLimit() {
        adopt(true, 2);

        int retries = 0;
        while (routes.beginMapRetry(GOAL)) {
            retries++;
            if (retries > 100) {
                break;
            }
        }

        assertEquals(10, retries);
    }

    /** 片付けたら何も残さない。引き直しの回数も目的地ごとに数え直す。 */
    @Test
    void resetForgetsTheRouteAndTheRetryCount() {
        adopt(true, 2);
        while (routes.beginMapRetry(GOAL)) {
            // 上限まで使い切る
        }

        routes.reset();

        assertNull(routes.route());
        assertNull(routes.refined());
        assertEquals(List.of(), routes.waypointsFor(GOAL));
        assertEquals(-1, routes.pendingRegionsFor(GOAL));
        adopt(true, 2);
        assertTrue(routes.beginMapRetry(GOAL), "片付けた後も前の目的地の回数が残っている");
    }

    /** 精緻化が終わっていなければ、採用するものは無い。 */
    @Test
    void nothingToAdoptBeforeRefinementFinishes() {
        adopt(true, 0);

        assertFalse(routes.adoptRefinedIfReady());
        assertFalse(routes.refinedRouteInUse());
        assertNull(routes.refiningFor(GOAL), "天井のある次元で精緻化を始めている");
    }
}
