package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 上限（橋の連続長・潜水・落下ダメージ）を段階的に緩める梯子が、<b>予算切れで終わった探索でも</b>
 * 走ることの検証。
 *
 * <p>以前は{@code EXHAUSTED}（範囲内の到達可能セルを舐め尽くした）でしか緩めていなかった。
 * それだと「舐め尽くせるほど狭い地形」でしか緩和が発動せず、広い地形では上限のせいで道が
 * 無いのに上限を緩めないまま失敗し続ける。
 */
class PathfindingExecutorLooseningTest {

    /** 橋の連続長の上限（実機の既定値）。 */
    private static final int BRIDGE_RUN_CAP = 30;

    /** 上限のままでは絶対に渡れない幅の奈落。 */
    private static final int VOID_GAP = 45;

    /**
     * 到達可能セルだけで展開ノード数の上限を使い切る大きさの島。ジ・エンドの島はこちら側で、
     * この違いだけで緩和が走ったり走らなかったりしていた。
     */
    private static final int LARGE_ISLAND_RADIUS = 80;

    /**
     * 時間上限は実機（2秒）より緩く取る。ここで測りたいのは「緩和の段が走るか」であって
     * 実行速度ではなく、CIの速度差で結果が変わるテストにしたくない。
     */
    private static final SearchLimits LIMITS =
            new SearchLimits(300_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * <b>実機で踏んだ形</b>（ジ・エンド、2026-08-27）。大きい島の崖ぎわから、上限より広い奈落の
     * 向こうを目指す。予算は到達可能セルだけで尽きるので探索は{@code NODE_BUDGET}で終わり、
     * 梯子が{@code EXHAUSTED}限定だった頃は<b>ステップ数0＝線が1本も出ない</b>で終わっていた。
     */
    @Test
    void crossesAVoidWiderThanTheBridgeCapFromTheEdgeOfALargeIsland() throws Exception {
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "崖ぎわからでも渡れるはず: " + result.termination());
        assertTrue(longestBridgeRun(result) > BRIDGE_RUN_CAP,
                "上限を超える橋が架かっている＝緩和の段が走った: " + longestBridgeRun(result));
    }

    /**
     * <b>実機で実際に走っているのはこちら</b>。粗い経由地チェーンの区間探索は
     * {@code COARSE_GUIDED_LEG_TIME_LIMIT_MILLIS}(800ms)しか持っていないので、緩和の期限を
     * 区間の時間上限で取ると最初の探索がそれを使い切って<b>緩和の段が一度も走らない</b>。
     * 緩和はチェーン全体の期限で縛る。
     */
    @Test
    void crossesTheSameVoidThroughTheCoarseGuidedChain() throws Exception {
        FakeCells cells = twoIslands(LARGE_ISLAND_RADIUS);
        BlockPos start = new BlockPos(LARGE_ISLAND_RADIUS, 61, LARGE_ISLAND_RADIUS);
        BlockPos goal = new BlockPos(LARGE_ISLAND_RADIUS + VOID_GAP + 5, 61, LARGE_ISLAND_RADIUS);

        PathResult result = new PathfindingExecutor()
                .submitCoarseGuided(cells, cells.bounds(), start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "区間探索からでも渡れるはず: " + result.termination());
        assertTrue(longestBridgeRun(result) > BRIDGE_RUN_CAP,
                "上限を超える橋が架かっている＝緩和の段が走った: " + longestBridgeRun(result));
    }

    /** 島が小さいうち（{@code EXHAUSTED}に届く側）も従来どおり渡れる。 */
    @Test
    void stillCrossesFromASmallIslandWhereTheSearchExhaustsInstead() throws Exception {
        FakeCells cells = twoIslands(20);
        BlockPos start = new BlockPos(20, 61, 20);
        BlockPos goal = new BlockPos(20 + VOID_GAP + 5, 61, 20);

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "小さい島からは元から渡れていた: " + result.termination());
    }

    /** 出発の島 → 奈落{@link #VOID_GAP}マス → 同じ高さの島。始点は出発の島の崖ぎわに置く。 */
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

    private static int longestBridgeRun(PathResult result) {
        int longest = 0;
        int run = 0;
        for (PathStep step : result.steps()) {
            run = step.bridging() ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
