package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.OwnerTrackingCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link PathfindingExecutor#submitWithDeepFallback}——通常予算と深い予算を並列に試す経路。
 *
 * <p>直列（通常予算の失敗を確認してから次tickで深い予算を投げ直す）だと2回分の待ち時間が
 * 足し算になる（実測は{@link net.prason.xaeronav.client.PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}
 * のjavadoc参照）。ここでは<b>正しさ</b>——通常予算で届く地形は通常予算の結果を、通常予算では
 * 届かず深い予算でだけ届く地形は深い予算の結果を、どちらも届かない地形は失敗を返すこと、
 * および新しいリクエストが来たら両方とも打ち切られることを見る。
 *
 * <p>結果の選び方を見るケースは1つの{@link FakeCells}を両方の探索へ渡している。{@code FakeCells}は
 * 探索中に書き換わらないので共有してよい——本物の{@code ChunkView}はセルのキャッシュを持つので
 * 共有できず、それを見るのが{@link #givesEachParallelSearchItsOwnView()}。
 *
 * <p>{@code PathfindingExecutorLooseningTest}と同じ大きい島（径80）を使うため1ケースが
 * 数秒かかる。{@code @Tag("slow")}で既定の{@code test}から外す。
 */
@Tag("slow")
class PathfindingExecutorDeepFallbackTest {

    private static final int BRIDGE_RUN_CAP = 30;
    private static final int VOID_GAP = 45;
    private static final int LARGE_ISLAND_RADIUS = 80;

    /**
     * 通常予算では{@code NODE_BUDGET}で終わる小さめの上限。<b>この地形の実測は2万で予算切れ・
     * 3万で到達</b>（28,408ノード）なので、帯の下側を採る。
     *
     * <p>コストモデルが動くと必要ノード数も動く。<b>ここが「届いてしまう」側へ落ちると、
     * 深い予算へフォールバックする経路を一度も通らないまま緑になる</b>ので、対照の
     * {@code assertFalse}を各ケースの先頭に置いてある。
     */
    private static final SearchLimits NORMAL =
            new SearchLimits(20_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /** 同じ地形を舐め尽くせる大きい上限。 */
    private static final SearchLimits DEEP =
            new SearchLimits(300_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /** 出発の島 → 奈落{@link #VOID_GAP}マス → 同じ高さの島。{@code PathfindingExecutorLooseningTest}と同じ地形。 */
    private static FakeCells twoIslands(int islandRadius) {
        SearchBounds bounds = new SearchBounds(-8, 20, -8,
                islandRadius * 2 + VOID_GAP + 16, 93, islandRadius * 2 + 8);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(BRIDGE_RUN_CAP);
        for (int x = 0; x <= islandRadius; x++) {
            for (int z = 0; z <= islandRadius * 2; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        for (int x = islandRadius + VOID_GAP + 1; x <= islandRadius * 2 + VOID_GAP + 8; x++) {
            for (int z = 0; z <= islandRadius * 2; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /**
     * 通常予算では{@code NODE_BUDGET}で終わり、深い予算なら渡れる地形。並列フォールバックは
     * 深い方の結果を採用して渡り切ること。
     */
    @Test
    void fallsBackToTheDeepBudgetWhenTheNormalOneRunsOut() throws Exception {
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        // 対照。通常予算だけでは本当に届かないことがこのテストの前提そのもの
        PathResult normalOnly = new PathfindingExecutor().submit(cells, start, goal, NORMAL, true, 0).get();
        assertFalse(normalOnly.complete(),
                "通常予算だけで届いてしまう＝深い予算にフォールバックする効果を確かめられない: "
                        + normalOnly.termination());

        PathResult result = new PathfindingExecutor()
                .submitWithDeepFallback(cells, cells, start, goal, NORMAL, DEEP, true, 0).get();

        assertTrue(result.complete(), "深い予算までフォールバックすれば渡れるはず: " + result.termination());
    }

    /** 通常予算で届く地形では、素直にその結果を返すこと（深い方を律儀に待たない）。 */
    @Test
    void usesTheNormalResultWhenItAlreadyReachesTheGoal() throws Exception {
        FakeCells cells = twoIslands(10);
        BlockPos start = new BlockPos(10, 61, 10);
        BlockPos goal = new BlockPos(10 + VOID_GAP + 5, 61, 10);

        PathResult result = new PathfindingExecutor()
                .submitWithDeepFallback(cells, cells, start, goal, NORMAL, DEEP, true, 0).get();

        assertTrue(result.complete(), "小さい島からは元から渡れていたはず: " + result.termination());
    }

    /** どちらの予算でも届かない地形では、通常予算の打ち切り理由をそのまま返すこと。 */
    @Test
    void reportsTheNormalTerminationWhenNeitherBudgetReachesTheGoal() throws Exception {
        // 奈落そのものを渡れない上限（0=無制限ではなく、橋を架けさせない）にして、
        // 深い予算をもってしても本当に道が無い地形を作る
        SearchBounds bounds = new SearchBounds(-8, 20, -8, 40, 93, 40);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(false);
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 10; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        // ゴールの島は孤立させたまま置かない＝そもそも到達可能セルがゴールに届かない
        BlockPos start = new BlockPos(5, 61, 5);
        BlockPos goal = new BlockPos(35, 61, 35);

        PathResult result = new PathfindingExecutor()
                .submitWithDeepFallback(cells, cells, start, goal, NORMAL, DEEP, true, 0).get();

        assertFalse(result.complete(), "孤立した目的地に届いてしまっている: " + result.termination());
    }

    /**
     * 2つの探索が<b>それぞれ別のビューを占有する</b>こと。
     *
     * <p>同じ{@link net.prason.xaeronav.pathfinding.world.CellSource}を両方へ渡していた頃、実機で
     * {@code ChunkView}のセルキャッシュ（{@code Long2LongOpenHashMap}）が並行に書き換わって
     * {@code ArrayIndexOutOfBoundsException}が出ていた。例外が出るかどうかはタイミング次第なので、
     * ここでは<b>どのビューも1スレッドしか触っていないこと</b>を直接見る。
     *
     * <p>「2つのビューの占有者が違う」まで見るのが要点——深い探索が実は走っていなければ
     * 違反ゼロは当たり前に成立してしまい、番人として空振りになる。
     */
    @Test
    void givesEachParallelSearchItsOwnView() throws Exception {
        // 通常予算では届かない地形。深い探索が最後まで走るので、2つが本当に重なる
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        OwnerTrackingCells normalView = new OwnerTrackingCells(cells);
        OwnerTrackingCells deepView = new OwnerTrackingCells(cells);

        new PathfindingExecutor()
                .submitWithDeepFallback(normalView.view(), deepView.view(), start, goal, NORMAL, DEEP, true, 0)
                .get();

        assertNull(normalView.intruder(), "通常予算のビューを占有者以外のスレッドが触っている");
        assertNull(deepView.intruder(), "深い予算のビューを占有者以外のスレッドが触っている");
        assertNotNull(normalView.owner(), "通常予算のビューが一度も使われていない");
        assertNotNull(deepView.owner(), "深い予算のビューが一度も使われていない＝並列に走っていない");
        assertNotSame(normalView.owner(), deepView.owner(),
                "2つの探索が同じスレッドで走っている＝並列フォールバックが働いていない");
    }

    /** 新しいリクエストが来たら、通常・深い両方の探索が打ち切られること。 */
    @Test
    void cancelsBothSearchesWhenSupersededByANewRequest() throws Exception {
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        PathfindingExecutor executor = new PathfindingExecutor();
        var superseded = executor.submitWithDeepFallback(cells, cells, start, goal, NORMAL, DEEP, true, 0);
        // 同じexecutorへの次のsubmitが前のジョブ(通常・深い両方)を打ち切る
        PathResult next = executor.submit(cells, start, goal, NORMAL, true, 0).get();

        assertTrue(superseded.isCancelled() || superseded.isCompletedExceptionally(),
                "前のリクエストが打ち切られていない");
        try {
            superseded.get();
        } catch (CancellationException expected) {
            // 期待どおり
        } catch (ExecutionException e) {
            throw new AssertionError("キャンセルではなく別の例外で終わった", e);
        }
        // 新しいリクエスト自体は普通に完了する（打ち切られたのは前のジョブだけ）
        assertEquals(PathResult.Termination.NODE_BUDGET, next.termination());
    }
}
