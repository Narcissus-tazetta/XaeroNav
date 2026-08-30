package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
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
class EndDetourReproTest {

    /** ユーザーが症状を報告した地点。 */
    private static final BlockPos PLAYER = new BlockPos(2481, 57, -488);

    /** 谷を挟んだ真東。回り込む道は南のz≒-462を通る。 */
    private static final BlockPos ACROSS_THE_CHASM = new BlockPos(2520, 0, -488);

    private static final SearchLimits LIMITS =
            new SearchLimits(600_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private record Terrain(FakeCells cells, SearchBounds bounds) {
    }

    private static Terrain terrain() throws IOException {
        try (InputStream in = EndDetourReproTest.class
                .getResourceAsStream("/end_terrain_columns_2481.txt.gz")) {
            assertNotNull(in, "地形データが見つからない");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] h = reader.readLine().trim().split(" ");
            SearchBounds bounds = new SearchBounds(
                    Integer.parseInt(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                    Integer.parseInt(h[3]), Integer.parseInt(h[4]), Integer.parseInt(h[5]));
            // 実機の既定に合わせる（maxBridgeRunBlocks/maxVoidBridgeRunBlocks=96、落下許容6）
            FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxFallDamagePoints(6)
                    .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split(" ");
                int x = Integer.parseInt(p[0]);
                int z = Integer.parseInt(p[1]);
                for (int i = 2; i < p.length; i++) {
                    int comma = p[i].indexOf(',');
                    int from = Integer.parseInt(p[i].substring(0, comma));
                    int to = Integer.parseInt(p[i].substring(comma + 1));
                    for (int y = from; y <= to; y++) {
                        cells.set(x, y, z, FakeCells.STONE);
                    }
                }
            }
            return new Terrain(cells, bounds);
        }
    }

    private static int standableY(CellSource cells, SearchBounds bounds, int x, int z) {
        for (int y = bounds.maxY() - 1; y > bounds.minY(); y--) {
            if (CellData.standable(cells.cell(x, y - 1, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static BlockPos onGround(Terrain t, BlockPos p) {
        int y = standableY(t.cells(), t.bounds(), p.getX(), p.getZ());
        assertTrue(y != Integer.MIN_VALUE, p.toShortString() + " に立てない＝地形データがずれている");
        return new BlockPos(p.getX(), y, p.getZ());
    }

    private static PathResult solve(Terrain t, BlockPos start, BlockPos goal) throws Exception {
        return new PathfindingExecutor().submit(t.cells(), start, goal, LIMITS, true, 0).get();
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
     */
    @Test
    void doesNotBridgeTheChasmWhenWalkingAroundIsCheaper() throws Exception {
        Terrain t = terrain();
        BlockPos start = onGround(t, PLAYER);
        BlockPos goal = onGround(t, ACROSS_THE_CHASM);

        PathResult result = solve(t, start, goal);

        assertTrue(result.complete(), "回り込めば着けるはず: " + result.termination());
        assertEquals(0, overTheVoid(result, false),
                "回り込む道があるのに奈落へ橋を架けた: steps=" + result.steps().size()
                        + " 合計=" + totalCost(result));
    }

    /**
     * <b>上のテストが「たまたま」通っていないことの担保。</b>回り込む道が本当に存在し、しかも
     * <b>コストモデルの上でも安い</b>ことを、ブロックを置けない設定との比較で固定する。
     *
     * <p>ここが要点——橋を架けて突っ切る経路は、回り込む道より<b>高い</b>。値段付けは正しく、
     * 探索が貪欲だっただけだった。この不等号が逆転したら、原因の見立てごと変わっている。
     *
     * <p><b>許容は相対で見る。</b>設置を許すと{@code addBridge}のぶん枝が増えて展開順が変わるので、
     * 重み付きA*が数tick高い経路を確定させることがある。ここで守りたいのは「橋の経路へ倒れていない」
     * ことで、絶対値で締めると経路コストの水準が変わるたびに探索順の揺れを拾う。
     */
    @Test
    void theWalkAroundIsGenuinelyCheaperThanBridging() throws Exception {
        Terrain withBlocks = terrain();
        BlockPos start = onGround(withBlocks, PLAYER);
        BlockPos goal = onGround(withBlocks, ACROSS_THE_CHASM);

        Terrain withoutBlocks = terrain();
        withoutBlocks.cells().canPlaceBlocks(false);
        PathResult walking = solve(withoutBlocks, start, goal);
        assertTrue(walking.complete(), "ブロック無しでも回り込めるはず: " + walking.termination());

        PathResult chosen = solve(withBlocks, start, goal);
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
        Terrain t = terrain();
        BlockPos start = onGround(t, PLAYER);
        for (BlockPos raw : List.of(
                new BlockPos(2460, 0, -300),
                new BlockPos(2480, 0, -320),
                new BlockPos(2500, 0, -340),
                new BlockPos(2500, 0, -300),
                new BlockPos(2440, 0, -580))) {
            BlockPos goal = onGround(t, raw);
            PathResult result = solve(t, start, goal);
            assertEquals(0, overTheVoid(result, true),
                    goal.toShortString() + " へ向かう経路が奈落の上を跳んだ");
        }
    }
}
