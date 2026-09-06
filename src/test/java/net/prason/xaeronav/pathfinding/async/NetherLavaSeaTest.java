package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>ネザーの溶岩の海を、橋を架けて渡り切れること。</b>地形は実機報告そのままの座標
 * （{@code -296,509} → {@code -296,584}、列の90%に溶岩がある）。
 *
 * <p>ユーザー報告「ネザーのマグマが多いところは渡れない」の再現。実機ログでは粗い経由地チェーンが
 * 30秒おきに{@code TIME_LIMIT}で終わり、ステップ数0〜48しか返っていなかった。
 *
 * <p><b>縛っていたのは時間ではなくノード上限。</b>深い予算(60万)のまま時間を30秒・60秒に伸ばしても
 * 15手で止まり、上限だけ上げると7.4秒で到達した（使ったのは73万ノード）。
 * 対処は{@code PathfindingExecutor#LEG_NODE_BUDGET_FACTOR}。
 *
 * <p><b>「渡れない地形」と切り分けるのがこのテストの要点。</b>橋を禁止すれば到達しないことまで
 * 見ないと、上限を上げただけで何でも渡れるようになっていないかが分からない。
 */
@Tag("slow")
class NetherLavaSeaTest {

    /**
     * 展開ノードの上限は実機の深い予算（{@code PathfindingState}の通常予算×
     * {@code DEEP_SEARCH_BUDGET_FACTOR}）そのまま。<b>時間の上限だけ実機より緩く取る</b>——
     * ここで測りたいのは「ノード上限が足りているか」であって実行速度ではない。実機と同じにすると、
     * 他のテストと並べて走らせたときの処理速度の違いだけで結果が変わる（実測: 単独なら7.9秒で
     * 到達、フルビルド中は同じ73万ノードに11.5秒かかる）。
     */
    private static final SearchLimits DEEP_LIMITS = new SearchLimits(600_000, 30_000, 1.5);

    /**
     * 到達した経路が実際に溶岩を橋で渡っていること。<b>これが「渡れない地形」との切り分け</b>——
     * 徒歩で回り込めるなら橋は要らないので、橋がこれだけ並ぶこと自体がこの地形の証明になる。
     * 実測は67本。
     */
    private static final int MIN_BRIDGES = 30;

    private static final BlockPos START = new BlockPos(-296, 0, 509);
    private static final BlockPos GOAL = new BlockPos(-296, 0, 584);

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load("/nether_lava_sea.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(6)
                // ネザーは岩盤天井が書き出した箱より上にある。実装の`ChunkView`と同じく空を塞ぐ
                .openSkyYOverride(bounds.maxY()));
    }

    private static PathResult solve(FakeCells cells) throws Exception {
        SearchBounds bounds = cells.bounds();
        BlockPos start = TerrainFixture.onGround(cells, bounds, START);
        BlockPos goal = TerrainFixture.onGround(cells, bounds, GOAL);
        long began = System.currentTimeMillis();
        PathResult result = new PathfindingExecutor()
                .submitCoarseGuided(cells, bounds, start, goal, DEEP_LIMITS, true, 0).get();
        System.out.println(String.format(Locale.ROOT,
                "%s→%s %s %d手 橋%d本 %dノード %.1f秒",
                start.toShortString(), goal.toShortString(), result.termination(),
                result.steps().size(), result.steps().stream().filter(PathStep::bridging).count(),
                result.expandedNodes(), (System.currentTimeMillis() - began) / 1000.0));
        return result;
    }

    @Test
    void bridgesAcrossTheLavaSeaWithTheDeepBudget() throws Exception {
        PathResult result = solve(terrain());
        assertTrue(result.complete(), "溶岩の海を渡り切れていない: " + result.termination()
                + " (" + result.steps().size() + "手, " + result.expandedNodes() + "ノード)");
        long bridges = result.steps().stream().filter(PathStep::bridging).count();
        assertTrue(bridges >= MIN_BRIDGES,
                "橋が" + bridges + "本しか無い＝この地形が溶岩の海の対照になっていない");
    }
}
