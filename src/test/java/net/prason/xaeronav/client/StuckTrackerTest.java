package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathResult.Termination;

/**
 * {@link StuckTracker}の単体テスト（ARCH-01の最初の抽出）。
 *
 * <p>{@code noteOutcome}の最初の呼び出しは必ず「前進した」扱いになる（比較対象となる
 * 最接近距離がまだ無いため）。詰みの連続をテストするときは、まず1回ベースラインを
 * 作ってから、同じ地点・進んでいない結果を繰り返す。
 */
class StuckTrackerTest {

    private static final BlockPos GOAL = new BlockPos(1000, 64, 0);
    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos FAR_START = new BlockPos(0, 64, 500);

    private static PathResult incomplete(Termination termination) {
        return new PathResult(List.of(), termination, 0, 0);
    }

    @Test
    void firstOutcomeNeverCountsAsStalled() {
        StuckTracker tracker = new StuckTracker();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false,
                new NetherVoxelGuide());

        assertFalse(tracker.stranded(), "比較対象がまだ無い最初の探索は前進扱いになる");
        assertNull(tracker.reason());
    }

    @Test
    void repeatedNonProgressFromTheSameSpotEventuallyGetsStuck() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        // 1回目でベースライン(1000ブロック)を作る
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);

        // 同じ地点・同じ距離のまま3回はまだ詰みと判断しない（SEARCH_STREAK=4回目で確定）
        for (int i = 0; i < 3; i++) {
            tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
            assertNull(tracker.reason(), "streak " + i + "回目ではまだ確定しない");
            assertTrue(tracker.stranded());
        }

        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertEquals(PathfindingState.StuckReason.NO_WAY_THROUGH, tracker.reason());
        assertEquals(PathfindingState.StuckReason.NO_WAY_THROUGH, tracker.takePendingNotice(),
                "詰みが確定した回はチャット通知も一緒に立つ");
        assertNull(tracker.takePendingNotice(), "通知は1度取り出したら消える（2回出さない）");
    }

    @Test
    void routeUnmappedTakesPriorityOverTerminationReason() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.NODE_BUDGET), true, voxelGuide);
        for (int i = 0; i < 4; i++) {
            tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.NODE_BUDGET), true, voxelGuide);
        }
        assertEquals(PathfindingState.StuckReason.UNMAPPED, tracker.reason(),
                "層1が目的地まで届いていないなら、打ち切り理由に関わらずUNMAPPEDを優先する");
    }

    @Test
    void resourceExhaustionWithoutUnmappedRouteIsSearchTooHard() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.NODE_BUDGET), false, voxelGuide);
        for (int i = 0; i < 4; i++) {
            tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.NODE_BUDGET), false, voxelGuide);
        }
        assertEquals(PathfindingState.StuckReason.SEARCH_TOO_HARD, tracker.reason());
    }

    @Test
    void movingFarBetweenAttemptsResetsTheStreak() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertTrue(tracker.stranded());

        // 遠く離れた地点からの失敗は「別の実験」なので連続に数えない
        tracker.noteOutcome(FAR_START, FAR_START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(FAR_START, FAR_START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(FAR_START, FAR_START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertNull(tracker.reason(), "同じ地点で4連続にならない限り確定しない");
    }

    @Test
    void meaningfulProgressResetsTheStreak() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertTrue(tracker.stranded());

        // 目的地に10ブロック（PROGRESS_BLOCKS=8を超える）近づいた地点からの探索は前進とみなす
        BlockPos closer = new BlockPos(10, 64, 0);
        tracker.noteOutcome(closer, closer, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertFalse(tracker.stranded(), "意味のある前進で連続カウントが戻る");
    }

    @Test
    void completeGroundRouteOverridesStuckState() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertEquals(PathfindingState.StuckReason.NO_WAY_THROUGH, tracker.reason());

        // 完走した地上経路が出ている間は、詰みの探索がその先で何回失敗しても詰みではない
        tracker.noteOutcome(START, START, GOAL, true, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertNull(tracker.reason());
        assertFalse(tracker.stranded());
    }

    @Test
    void resetClearsEverythingIncludingReason() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertEquals(PathfindingState.StuckReason.NO_WAY_THROUGH, tracker.reason());

        tracker.reset();
        assertNull(tracker.reason());
        assertFalse(tracker.stranded());
        assertNull(tracker.takePendingNotice());

        // resetの後は最接近距離もリセットされているので、遠い目的地からでもまたベースラインを作り直す
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertFalse(tracker.stranded(), "reset後の最初の探索はまた前進扱いになる");
    }

    @Test
    void clearReasonOnlyClearsTheVerdictNotTheStreak() {
        StuckTracker tracker = new StuckTracker();
        NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        tracker.noteOutcome(START, START, GOAL, false, incomplete(Termination.EXHAUSTED), false, voxelGuide);
        assertTrue(tracker.stranded());

        // 到着時はclearReason()だけを呼ぶ（reason判定は無いのでこの時点で影響は無いが、
        // 到着後にすぐ同じ座標へ再度向かう場合を想定した継続性の確認）
        tracker.clearReason();
        assertNull(tracker.reason());
        assertTrue(tracker.stranded(), "clearReasonは連続カウントまでは戻さない（PathfindingState#arrive参照）");
    }

    @Test
    void retryDueRequiresEitherMovementOrElapsedInterval() {
        StuckTracker tracker = new StuckTracker();
        BlockPos lastStart = new BlockPos(0, 64, 0);

        assertTrue(tracker.retryDue(null, lastStart, false), "始点が無ければいつでも再挑戦してよい");
        assertTrue(tracker.retryDue(lastStart, new BlockPos(20, 64, 0), false), "16ブロック以上動けば再挑戦してよい");
        assertFalse(tracker.retryDue(lastStart, new BlockPos(5, 64, 0), false),
                "動いておらず間隔もまだなら再挑戦しない");
        assertTrue(tracker.retryDue(lastStart, new BlockPos(5, 64, 0), true), "間隔が経てば動いていなくても再挑戦する");
    }
}
