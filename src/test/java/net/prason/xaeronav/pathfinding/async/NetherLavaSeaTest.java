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
 * （{@code -289,72,525}の細い足場から南へ）。ユーザー報告「ネザーのマグマが多いところは渡れない」。
 *
 * <p>地形の中身は<b>40ブロック下が溶岩の、開けた空</b>。足場は{@code z=528}で途切れ、そこから
 * 南は{@code y=35〜100}に何も無い。渡るには50ブロック以上の橋を空中に架けるしかなく、
 * {@code maxLavaBridgeRunBlocks}(30)を超えるので上限緩和まで進まないと道が生えない。
 *
 * <p><b>見るのは深い予算の単発探索</b>（{@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}）。
 * 実機ではここが時間切れになり、その後に走る粗い経由地チェーンは<b>単発より重い</b>
 * （104万 対 57万ノード）ので連鎖して失敗していた——直す場所はチェーンではなくこちら。
 *
 * <p><b>時間の上限は実機より緩く取る。</b>ここで測りたいのは「ノード上限が足りているか」で
 * あって実行速度ではない。実機と同じにするとCIの処理速度の違いだけで結果が変わる。
 * 実機に必要な秒数は{@code DEEP_SEARCH_MAX_MILLIS}のjavadocに書いてある。
 */
@Tag("slow")
class NetherLavaSeaTest {

    /** 実機の深い予算のノード上限（通常予算10万 × {@code DEEP_SEARCH_BUDGET_FACTOR}）。 */
    private static final SearchLimits DEEP_LIMITS = new SearchLimits(800_000, 60_000, 1.5);

    /** 実機報告の地点。ここから南へ行こうとすると足場が尽きる。 */
    private static final BlockPos START = new BlockPos(-289, 72, 525);

    private static final BlockPos GOAL = new BlockPos(-296, 57, 584);

    /**
     * 到達した経路が実際に溶岩を橋で渡っていること。<b>これが「徒歩で回り込める地形」との
     * 切り分け</b>——回り込めるなら橋は要らないので、橋が並ぶこと自体がこの地形の証明になる。
     * 実測は29本。
     */
    private static final int MIN_BRIDGES = 15;

    /**
     * ネザーは天井のある次元なので、実機の探索範囲は<b>次元の全高</b>になる
     * （{@code PathfindingState#verticalSearchMargin}）。箱の上端を岩盤天井の上に置くと
     * 天井の上を歩けてしまい、地形が別物になる。
     */
    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load("/nether_lava_sea.txt.gz", bounds -> FakeCells.empty(bounds)
                .bounds(new SearchBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), 128, bounds.maxZ()))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(6));
    }

    @Test
    void bridgesAcrossTheLavaSeaWithTheDeepBudget() throws Exception {
        FakeCells cells = terrain();
        long began = System.currentTimeMillis();
        PathResult result = new PathfindingExecutor()
                .submit(cells, START, GOAL, DEEP_LIMITS, true, 0).get();
        long bridges = result.steps().stream().filter(PathStep::bridging).count();
        System.out.println(String.format(Locale.ROOT, "%s→%s %s %d手 橋%d本 %dノード %.1f秒",
                START.toShortString(), GOAL.toShortString(), result.termination(),
                result.steps().size(), bridges, result.expandedNodes(),
                (System.currentTimeMillis() - began) / 1000.0));
        assertTrue(result.complete(), "溶岩の海を渡り切れていない: " + result.termination()
                + " (" + result.steps().size() + "手, " + result.expandedNodes() + "ノード)");
        assertTrue(bridges >= MIN_BRIDGES,
                "橋が" + bridges + "本しか無い＝この地形が溶岩の海の対照になっていない");
    }
}
