package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * {@link PathfindingExecutor#submitCoarseGuided}の2つの性質を確認する（design doc外・層3の
 * 局所障害対策）。
 *
 * <ol>
 * <li>粗い経由地が役に立つ地形では、単一の詳細探索が予算切れになる状況でも目的地まで届く
 * <li>粗い経由地が到達不能な点を指す地形でも、チェーンが破綻せず目的地まで届く
 * </ol>
 *
 * <p>層3が常に有利なわけではない。平地では展開ノード数がほぼ同じ（実測: 直接201 / チェーン203）で
 * 分割そのものの利得は無く、区間ごとに予算を取り直せるぶんだけ僅かに遠くまで届く。
 */
class PathfindingExecutorCoarseGuidedTest {

    /**
     * 横に長い湖。ヒューリスティックは陸のスプリント速度で残りを見積もるので直進＝遊泳を強く推すが、
     * 遊泳の実コストは約2.55倍あり、その差のぶん詳細探索は水域を無駄に広く展開する。粗い地図は湖を
     * {@code WATER}として認識して北へ迂回する経由地を置くため、区間ごとの探索は陸の上だけを短く辿れる。
     */
    @Test
    void reachesTheGoalOnABudgetThatDefeatsASingleSearch() throws Exception {
        SearchBounds bounds = new SearchBounds(-16, 0, -112, 216, 100, 112);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = -16; x <= 216; x++) {
            for (int z = -112; z <= 112; z++) {
                cells.set(x, 62, z, FakeCells.STONE);
            }
        }
        for (int x = 60; x <= 160; x++) {
            for (int z = -24; z <= 24; z++) {
                cells.set(x, 63, z, FakeCells.WATER);
            }
        }
        BlockPos start = new BlockPos(0, 63, 0);
        BlockPos goal = new BlockPos(200, 63, 0);
        // 直接探索には足りず（1997要る）、1区間ぶんには足りる（211）予算
        SearchLimits limits = new SearchLimits(1_000, 30_000, 1.5);

        PathfindingExecutor executor = new PathfindingExecutor();
        PathResult direct = executor.submit(cells, start, goal, limits).get(60, TimeUnit.SECONDS);
        assertFalse(direct.complete(), "この予算では直接探索が届いてしまい、チェーンの利得を確かめられない");

        PathResult chain = executor.submitCoarseGuided(cells, bounds, start, goal, limits)
                .get(60, TimeUnit.SECONDS);

        assertReachesGoal(chain, goal);
    }

    /**
     * チャンクを丸ごと埋める垂直な壁。粗い地図は溶岩以外に「通行不能」を表現できず、この壁は
     * {@code min=max}＝起伏0の平坦な台地に見えるため、経由地が壁の天面という到達不能な点に落ちる。
     * 届かない経由地は飛ばして次を狙うので、最後の区間（本来の目的地）で直接探索と同じ結果に
     * 落ち着く——1つ届かないだけでチェーンごと捨てていた頃は、10倍の予算を与えても未到達だった。
     */
    @Test
    void skipsUnreachableWaypointsInsteadOfAbandoningTheChain() throws Exception {
        SearchBounds bounds = new SearchBounds(-16, 0, -112, 216, 100, 112);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = -16; x <= 216; x++) {
            for (int z = -112; z <= 112; z++) {
                cells.set(x, 63, z, FakeCells.STONE);
            }
        }
        // z>=64 だけ開いた掘れない壁。迂回は64ブロック以上の横移動になる
        for (int x = 96; x <= 111; x++) {
            for (int z = -112; z < 64; z++) {
                for (int y = 64; y <= 80; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(200, 64, 0);

        PathResult chain = new PathfindingExecutor()
                .submitCoarseGuided(cells, bounds, start, goal, new SearchLimits(10_000, 30_000, 1.5))
                .get(60, TimeUnit.SECONDS);

        assertReachesGoal(chain, goal);
    }

    private static void assertReachesGoal(PathResult result, BlockPos goal) {
        assertTrue(result.complete(), "経由地チェーンが目的地まで届かなかった");
        List<PathStep> steps = result.steps();
        PathStep last = steps.get(steps.size() - 1);
        assertEquals(goal.getX(), last.pos().getX(), "経路の終端が目的地に届いていない");
        assertEquals(goal.getZ(), last.pos().getZ(), "経路の終端が目的地に届いていない");
        // 区間の継ぎ目で経路が飛んでいないこと（連結を間違えるとここが跳ぶ）
        for (int i = 1; i < steps.size(); i++) {
            BlockPos previous = steps.get(i - 1).pos();
            BlockPos current = steps.get(i).pos();
            assertTrue(previous.distSqr(current) <= 4.0,
                    "区間の継ぎ目で経路が飛んでいる: " + previous + " -> " + current);
        }
    }
}
