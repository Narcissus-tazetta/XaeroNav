package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>実機ジ・エンド(2481,57,-488)の保存データで、「谷に橋を架けて突っ切る」経路を再現する。</b>
 *
 * <p>ユーザー報告「大きい島から小さい島、また同じ大きい島に戻る」の地形がここにある——プレイヤーの
 * 真東に幅6ブロックの奈落、その向こうに3列だけの飛び石(x2496..2500)、さらにその先が東の陸塊。
 * <b>東西の陸塊はz≒-462で地続き</b>なので、飛び石を渡るのは「同じ島へ渡り直す」ことになる。
 *
 * <p><b>層1は無実。</b>この範囲の陸塊は8近傍で数えると228セルの島がひとつあるだけで、谷そのものが
 * 見えていない（{@code LiveCoarseSampler}は床のある列が1つでもあればそのチャンクをLANDにする）。
 * 小さい島の割増（{@link CoarseRouter}の{@code SMALL_ISLAND_PENALTY}）も発火しようがない。
 *
 * <p><b>真因は重み付きA*の貪欲さだった。</b>{@code f = g + w·h}は目的地から一度遠ざかる経路を
 * 系統的に嫌う。回り込む道は南へ28ブロック下ってから東へ向かうので、hが一度増える。
 * コストモデルの側は最初から回り込む方を安いと言っていた（橋の経路596.3 tick / 回り込み548.2 tick）。
 */
@Tag("slow")
class EndDetourReproTest {

    /** ユーザーが症状を報告した地点。 */
    private static final BlockPos PLAYER = new BlockPos(2481, 57, -488);

    /** 谷を挟んだ真東。回り込む道は南のz≒-462を通る。 */
    private static final BlockPos ACROSS_THE_CHASM = new BlockPos(2520, 0, -488);

    private static final SearchLimits LIMITS =
            new SearchLimits(600_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static FakeCells terrain() throws IOException {
        // 実機の既定に合わせる（maxBridgeRunBlocks/maxVoidBridgeRunBlocks=96、落下許容6）
        return TerrainFixture.load("/end_terrain_columns_2481.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static BlockPos onGround(FakeCells terrain, BlockPos p) {
        return TerrainFixture.onGround(terrain, terrain.bounds(), p);
    }

    private static PathResult solve(FakeCells terrain, BlockPos start, BlockPos goal) throws Exception {
        return new PathfindingExecutor().submit(terrain, start, goal, LIMITS, true, 0).get();
    }

    private static double totalCost(PathResult r) {
        double total = 0;
        for (PathStep step : r.steps()) {
            total += step.cost();
        }
        return total;
    }

    /** 奈落の上を通るステップ数。{@code jump}なら跳躍だけ、そうでなければ橋だけを数える。 */
    private static long overTheVoid(PathResult r, boolean jump) {
        return r.steps().stream()
                .filter(s -> s.risk() == PathRisk.VOID_BELOW)
                .filter(s -> jump ? s.movement() == MovementType.JUMP : s.bridging())
                .count();
    }

    /**
     * <b>症状2の本体。</b>回り込む道があるなら、奈落に橋を架けて突っ切らない。
     *
     * <p>直す前は15マスの橋（うち7マスが奈落の上）を架けて40ステップで渡っていた。
     *
     * <p><b>「たまたま通っている」わけではないことも同時に見る。</b>回り込む道が本当に存在し、
     * しかも<b>コストモデルの上でも安い</b>ことを、ブロックを置けない設定との比較で固定する。
     * ここが要点——橋を架けて突っ切る経路は、回り込む道より<b>高い</b>。値段付けは正しく、探索が
     * 貪欲だっただけだった。この不等号が逆転したら、原因の見立てごと変わっている。
     *
     * <p><b>許容は相対で見る。</b>設置を許すと{@code addBridge}のぶん枝が増えて展開順が変わるので、
     * 重み付きA*が数tick高い経路を確定させることがある。ここで守りたいのは「橋の経路へ倒れていない」
     * ことで、絶対値で締めると経路コストの水準が変わるたびに探索順の揺れを拾う。
     */
    @Test
    void walksAroundTheChasmInsteadOfBridgingIt() throws Exception {
        FakeCells withBlocks = terrain();
        BlockPos start = onGround(withBlocks, PLAYER);
        BlockPos goal = onGround(withBlocks, ACROSS_THE_CHASM);

        PathResult chosen = solve(withBlocks, start, goal);
        assertTrue(chosen.complete(), "回り込めば着けるはず: " + chosen.termination());
        assertEquals(0, overTheVoid(chosen, false),
                "回り込む道があるのに奈落へ橋を架けた: steps=" + chosen.steps().size()
                        + " 合計=" + totalCost(chosen));

        FakeCells withoutBlocks = terrain().canPlaceBlocks(false);
        PathResult walking = solve(withoutBlocks, start, goal);
        assertTrue(walking.complete(), "ブロック無しでも回り込めるはず: " + walking.termination());
        assertTrue(totalCost(chosen) <= totalCost(walking) * 1.01,
                "設置を許した探索が、歩くだけの経路より1%以上高い経路を選んでいる: "
                        + totalCost(chosen) + " vs " + totalCost(walking));
    }

    /**
     * <b>症状1。</b>同じ地形で南西へ向かう経路群。直す前はこのうち複数が奈落の上を跳んでいた——
     * 緩和の梯子が{@code allowRiskyJumps}を無条件に開けるうえ、開いた跳躍に危険料が無かったため。
     */
    @Test
    void neverJumpsOverTheVoidWhereThereIsAWayAround() throws Exception {
        FakeCells terrain = terrain();
        BlockPos start = onGround(terrain, PLAYER);
        for (BlockPos raw : List.of(
                new BlockPos(2460, 0, -300),
                new BlockPos(2480, 0, -320),
                new BlockPos(2500, 0, -340),
                new BlockPos(2500, 0, -300),
                new BlockPos(2440, 0, -580))) {
            BlockPos goal = onGround(terrain, raw);
            PathResult result = solve(terrain, start, goal);
            assertEquals(0, overTheVoid(result, true),
                    goal.toShortString() + " へ向かう経路が奈落の上を跳んだ");
        }
    }
}
