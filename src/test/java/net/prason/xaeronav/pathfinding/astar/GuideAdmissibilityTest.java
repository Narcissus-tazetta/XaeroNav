package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>層1のcost-to-goガイドが、実コストの下限になっていることを測る。</b>
 *
 * <p>{@code AStarPathfinder}は幾何学的なHeuristicとガイドの<b>max</b>を取るので、ガイドが実コストを
 * 上回った瞬間に<b>本当は安い道を探索が避ける</b>。経路の質の比（{@code PathOptimalityTest}）は
 * 結果しか見えないが、ここは<b>原因そのもの</b>を見る——比は他の要因でも動くので、ガイドを触った
 * ときに効いたかどうかを比で判断すると必ず読み違える。
 *
 * <p>測り方は「最適経路の各点で、ガイドの見積もりがそこからの実残りコストを超えていないか」。
 * 基準の経路は厳密な最適ではない（{@code PathOptimalityTest}のjavadoc参照）が、
 * <b>実在する経路のコストである以上、真の最適の上限</b>なので、これを超えるガイドは
 * 確実に下限を破っている——見逃しはあっても誤検知は無い、片側だけの検査。
 *
 * <p><b>ゴール手前は測らない</b>（{@link #MIN_REMAINING_TICKS}）。残りが数tickの点では、
 * わずかな絶対誤差が比を跳ね上げるだけで、探索の判断には影響しない。
 */
@Tag("slow")
class GuideAdmissibilityTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final long SEED = 20260906L;

    /** 基準の探索に渡す予算。 */
    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;

    /** これより残りが少ない点は測らない。約10ブロックぶんの疾走。 */
    private static final double MIN_REMAINING_TICKS = 40.0;

    /**
     * 線は<b>1.00</b>——「下限である」がそのまま線になる。実測は0.65〜0.98で、
     * 詰めた余裕がそのまま安全域になっている。
     */
    private static final double LIMIT = 1.00;

    private record Terrain(String name, String resource, boolean ceiling, int count, int min, int max) {
    }

    /**
     * 起伏と水を厚めに取る。ガイドが下限を破るのは<b>セルの要約統計が、そのセルを通る最良の道の
     * 下限になっていない</b>ときで、それが起きるのは尾根（代表高さが鞍部より高い）と
     * 水際（過半数が水でも乾いた帯を通れる）だから。
     */
    private static List<Terrain> terrains() {
        return List.of(
                new Terrain("地上/平原丘陵", "/overworld_terrain_columns.txt.gz", false, 12, 40, 120),
                new Terrain("地上/山岳", "/overworld_mountains.txt.gz", false, 12, 40, 120),
                new Terrain("地上/海岸", "/overworld_coast.txt.gz", false, 12, 40, 120),
                new Terrain("地上/広域", "/overworld_wide.txt.gz", false, 8, 120, 260),
                new Terrain("ネザー", "/nether_terrain_columns.txt.gz", true, 8, 60, 160),
                new Terrain("エンド", "/end_terrain_columns.txt.gz", false, 8, 60, 160));
    }

    /** {@code PathOptimalityTest}と同じ「道具を持って普通に歩いている状態」。 */
    private static FakeCells walkingPlayer(SearchBounds bounds, boolean ceiling) {
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(96)
                .maxFallDamagePoints(6);
        return ceiling ? cells.openSkyYOverride(bounds.maxY()) : cells;
    }

    @Test
    void theCoarseGuideNeverOverestimatesTheRemainingCost() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Terrain terrain : terrains()) {
            FakeCells cells = TerrainFixture.load(terrain.resource(),
                    bounds -> walkingPlayer(bounds, terrain.ceiling()));
            double worst = 0.0;
            String worstAt = "";
            int measured = 0;
            for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED,
                    terrain.count(), terrain.min(), terrain.max())) {
                PathResult best = new AStarPathfinder(cells,
                        new SearchLimits(UNLIMITED_NODE_BUDGET, 120_000, 1.0))
                        .search(route[0], route[1], NEVER);
                if (!best.complete()) {
                    continue;
                }
                measured++;
                // 実機と同じ作り方（`PathfindingExecutor#buildCostToGoGuide`と同じ引数）
                CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), route[0].getY(), NEVER);
                CostToGo guide = CoarseRouter.costToGo(map, route[1], false,
                        CoarseRouter.BridgePolicy.BRIDGE);
                double remaining = best.steps().stream().mapToDouble(PathStep::cost).sum();
                BlockPos at = route[0];
                for (PathStep step : best.steps()) {
                    if (remaining >= MIN_REMAINING_TICKS) {
                        double ratio = guide.estimate(at.getX(), at.getY(), at.getZ()) / remaining;
                        if (ratio > worst) {
                            worst = ratio;
                            worstAt = at.toShortString() + "（残り" + Math.round(remaining) + "tick）";
                        }
                    }
                    remaining -= step.cost();
                    at = step.pos();
                }
            }
            report.add(String.format(Locale.ROOT, "%-12s %2d本 最悪%.3f倍 %s",
                    terrain.name(), measured, worst, worstAt));
            if (measured == 0) {
                failures.add(terrain.name() + ": 経路が1本も出ない（地形か座標がおかしい）");
            } else if (worst > LIMIT) {
                failures.add(String.format(Locale.ROOT,
                        "%s: ガイドが実残りコストを%.3f倍まで上回っている %s",
                        terrain.name(), worst, worstAt));
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
