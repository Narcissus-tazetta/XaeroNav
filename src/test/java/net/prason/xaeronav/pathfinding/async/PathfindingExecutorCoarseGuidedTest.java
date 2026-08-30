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
 * {@link PathfindingExecutor#submitCoarseGuided}の2つの性質を確認する（層3の
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
     *
     * <p>cost-to-goガイドは明示的に無効化する——このテストが確かめたいのは「waypoint分割そのものの
     * 利得」で、段階4で追加したガイドが効くと直接探索もこの予算で届くようになり
     * （ガイド自体が湖を回避する見積もりを返すため）、比較の前提が崩れる。ガイドの効果は
     * 別テストで確認する。
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
        PathResult direct = executor.submit(cells, start, goal, limits, false).get(60, TimeUnit.SECONDS);
        assertFalse(direct.complete(), "この予算では直接探索が届いてしまい、チェーンの利得を確かめられない");

        PathResult chain = executor.submitCoarseGuided(cells, bounds, start, goal, limits, false)
                .get(60, TimeUnit.SECONDS);

        assertReachesGoal(chain, goal);
    }

    /**
     * cost-to-goガイド（{@code XaeroNavConfig#costToGoGuideEnabled}）を有効にすると、壁で
     * 大きく迂回が要る地形で<b>直接探索</b>（waypoint分割無し）だけでも目的地へ届きやすくなる。
     * 幾何学的な直線距離のヒューリスティックは壁の存在を知らず、まず壁へ向かって展開してから
     * 引き返す無駄を払う——層1の粗い地図はチャンク単位の起伏として壁を大まかに捉えているので、
     * その見積もりを併用すると引き返しが減る。
     *
     * <p>{@code reachesTheGoalOnABudgetThatDefeatsASingleSearch}と同じ湖の地形では試さない——
     * 湖は迂回してもコストの差が小さく（水を渡っても致命的に高いわけではない）、粗い地図の
     * チャンク粒度の粗さがかえってノイズになり、この地形では逆にガイド併用の方が展開数が
     * 増えることを実測した（1997→2375）。壁のように「迂回しないと届かない・届いても
     * 大幅に高くつく」地形でこそ効く、という条件付きの改善であることに注意。
     */
    @Test
    void costToGoGuideLetsADirectSearchSucceedOnTheSameBudgetThatDefeatedItWithoutTheGuide() throws Exception {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(200, 64, 0);
        // 素の直接探索は7932ノード要る（幾何学的な直線距離は壁の存在を知らず、z<64側へ
        // 突っ込んでから引き返す展開をする）。層1は壁をNO_DATAではなく起伏として大まかに
        // 捉え、迂回側を早くから示すのでガイド併用は6921ノードで届く。
        //
        // 予算は両者の間に置く必要があるので、探索の展開順を変える修正を入れたら測り直すこと。
        // {@code AStarPathfinder#LINE_TIE_BREAK_TICKS}（fを刻みに量子化して引き分けだけ解く）は
        // ここをほとんど動かさない（無効時 7840/6927）——fに直接加算する実装だと 9013/8474 まで
        // 膨らみ、この予算では両方失敗していた
        SearchLimits limits = new SearchLimits(7_200, 30_000, 1.5);

        PathResult unguided = new PathfindingExecutor().submit(wallCells(), start, goal, limits, false)
                .get(60, TimeUnit.SECONDS);
        assertFalse(unguided.complete(), "この予算ではガイド無しでも届いてしまい、比較にならない");

        PathResult guided = new PathfindingExecutor().submit(wallCells(), start, goal, limits, true)
                .get(60, TimeUnit.SECONDS);

        assertTrue(guided.complete(), "ガイド併用でも直接探索がこの予算で届かなかった");
    }

    /** {@code z&gt;=64}だけ開いた掘れない壁。迂回は64ブロック以上の横移動になる。 */
    private static final SearchBounds WALL_BOUNDS = new SearchBounds(-16, 0, -112, 216, 100, 112);

    private static FakeCells wallCells() {
        FakeCells cells = FakeCells.empty(WALL_BOUNDS);
        for (int x = -16; x <= 216; x++) {
            for (int z = -112; z <= 112; z++) {
                cells.set(x, 63, z, FakeCells.STONE);
            }
        }
        for (int x = 96; x <= 111; x++) {
            for (int z = -112; z < 64; z++) {
                for (int y = 64; y <= 80; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    /**
     * チャンクを丸ごと埋める垂直な壁。粗い地図は溶岩以外に「通行不能」を表現できず、この壁は
     * {@code min=max}＝起伏0の平坦な台地に見えるため、経由地が壁の天面という到達不能な点に落ちる。
     * 届かない経由地は飛ばして次を狙うので、最後の区間（本来の目的地）で直接探索と同じ結果に
     * 落ち着く——1つ届かないだけでチェーンごと捨てていた頃は、10倍の予算を与えても未到達だった。
     */
    @Test
    void skipsUnreachableWaypointsInsteadOfAbandoningTheChain() throws Exception {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos goal = new BlockPos(200, 64, 0);

        PathResult chain = new PathfindingExecutor()
                .submitCoarseGuided(wallCells(), WALL_BOUNDS, start, goal, new SearchLimits(10_000, 30_000, 1.5))
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
