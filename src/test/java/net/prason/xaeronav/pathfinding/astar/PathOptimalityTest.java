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
 * <b>実機の地形で、案内される経路が最良からどれだけ離れているかを測る。</b>
 * 地形は{@code tools/dump_terrain_columns.py}で保存データから書き出したもの。
 *
 * <p>ユーザー報告「最適じゃないルートを案内されている気がする」に対する番人。個別の地形で
 * 「この経路が出てほしい」を書く他のテストと違い、<b>基準との比</b>だけを見る——地上・ネザー・
 * エンドを16方向へ、という人間が期待経路を書けない規模で崩れを捕まえるため。
 *
 * <p><b>基準は同じ{@code CellSource}に対する重み1.0・層1ガイド無し・予算実質無制限の探索。</b>
 * 実運用の構成（重み{@link AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}・ガイド有り・既定予算）との
 * 差は、まるごと<b>実装が持ち込んだ最適でなさ</b>になる。基準も同じ探索器なので厳密な最適では
 * ない（{@code orderingCost}の量子化とclosedを開き直さない性質のぶん）——測っているのは
 * 「この探索器が到達しうる最良から、実運用の構成がどれだけ離れるか」。
 *
 * <p><b>捕まえられないもの: コスト模型そのものの間違い。</b>両側が同じ模型なので比は1.00のまま
 * 出る。「掘るか迂回するか」の交換レートのような値の妥当性は人間が見るしかない
 * （{@code TerrainEditVersusDetourTest}）。
 */
@Tag("slow")
class PathOptimalityTest {

    private static final BooleanSupplier NEVER = () -> false;

    /** 実機の既定予算（{@code PathfindingState}）。 */
    private static final int PRODUCTION_NODE_BUDGET = 100_000;

    /** 基準の探索に渡す予算。この地形なら実測で最大30万ノード程度なので、実質無制限。 */
    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;

    /** 全体の悪化を捕まえる線。実測は地上1.034・ネザー0.995・エンド0.998。 */
    private static final double MEAN_LIMIT = 1.06;

    /**
     * 1本でも破滅的なら落とす線。実測は地上1.223・ネザー1.096・<b>エンド1.417</b>。
     *
     * <p>エンドの1本だけ大きいのは島渡り（奈落を43本の橋で渡る区間）で、層1の16ブロック解像度が
     * 「どの島を踏むか」を決めてしまうため原理的に詰まらない。その1本を許す位置に置いてあるので、
     * <b>他の2次元では実質1.2倍が上限</b>として効く。
     */
    private static final double WORST_LIMIT = 1.45;

    /** 中心から振る距離（ブロック）。近距離と、詳細探索の地平(96)に近い距離。 */
    private static final int[] RADII = {40, 70};

    private static final int DIRECTIONS = 16;

    private record Dimension(String name, String resource, TerrainFixture.Configure configure,
                              List<BlockPos> centers) {
    }

    /** 道具を持って普通に歩いている状態。次元によらず同じにして、差が地形だけから出るようにする。 */
    private static FakeCells walkingPlayer(SearchBounds bounds) {
        return FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(96)
                .maxFallDamagePoints(6);
    }

    private static List<Dimension> dimensions() {
        return List.of(
                new Dimension("地上", "/overworld_terrain_columns.txt.gz",
                        PathOptimalityTest::walkingPlayer,
                        List.of(new BlockPos(80, 0, 80), new BlockPos(170, 0, 120),
                                new BlockPos(110, 0, 180))),
                // ネザーは岩盤天井が書き出した箱より上にある。実装の`ChunkView`と同じく、
                // 空が開けていない状態として見せる
                new Dimension("ネザー", "/nether_terrain_columns.txt.gz",
                        bounds -> walkingPlayer(bounds).openSkyYOverride(bounds.maxY()),
                        List.of(new BlockPos(-110, 0, -110), new BlockPos(-60, 0, -140),
                                new BlockPos(-140, 0, -60))),
                new Dimension("エンド", "/end_terrain_columns.txt.gz",
                        PathOptimalityTest::walkingPlayer,
                        List.of(new BlockPos(1230, 0, 1200), new BlockPos(1200, 0, 1210),
                                new BlockPos(1260, 0, 1190))));
    }

    private static double cost(PathResult result) {
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    private static PathResult solve(FakeCells cells, BlockPos start, BlockPos goal, double weight,
                                     boolean guided, int budget) {
        CostToGo guide = null;
        if (guided) {
            CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), NEVER);
            guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE);
        }
        return new AStarPathfinder(cells, new SearchLimits(budget, 120_000, weight), guide)
                .search(start, goal, NEVER);
    }

    /**
     * 中心から{@link #DIRECTIONS}方向×{@link #RADII}。<b>方向を振るのが要点</b>——1本の経路では、
     * たまたまその地形が素直だっただけなのか実装が正しいのかを区別できない。
     * 立てない座標（水面・空中・箱の外）は飛ばす。
     */
    private static List<BlockPos[]> routes(FakeCells cells, List<BlockPos> centers) {
        SearchBounds bounds = cells.bounds();
        List<BlockPos[]> routes = new ArrayList<>();
        for (BlockPos rawCenter : centers) {
            if (TerrainFixture.standableY(cells, bounds, rawCenter.getX(), rawCenter.getZ())
                    == Integer.MIN_VALUE) {
                continue;
            }
            BlockPos center = TerrainFixture.onGround(cells, bounds, rawCenter);
            for (int radius : RADII) {
                for (int direction = 0; direction < DIRECTIONS; direction++) {
                    double angle = direction * 2.0 * Math.PI / DIRECTIONS;
                    int x = center.getX() + (int) Math.round(radius * Math.cos(angle));
                    int z = center.getZ() + (int) Math.round(radius * Math.sin(angle));
                    if (TerrainFixture.standableY(cells, bounds, x, z) == Integer.MIN_VALUE) {
                        continue;
                    }
                    routes.add(new BlockPos[] {center,
                            TerrainFixture.onGround(cells, bounds, new BlockPos(x, 0, z))});
                }
            }
        }
        return routes;
    }

    /**
     * 実運用の構成が基準から離れすぎないこと。
     *
     * <p><b>平均と最悪の両方を見る。</b>平均だけだと1本の破滅的な経路が薄まって見えず、
     * 最悪だけだと「全体が少しずつ悪くなった」を見逃す——層1ガイドの歪みは実際に両方の形で
     * 出ていた（1本1.48倍、かつ全体が平均1.13倍）。
     */
    @Test
    void routesStayCloseToTheBestThisSearcherCanFind() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Dimension dimension : dimensions()) {
            // 探索は`cells`を読むだけなので1つを使い回す（1本ごとに読み直すと展開より読み込みの方が重い）
            FakeCells cells = TerrainFixture.load(dimension.resource(), dimension.configure());
            double worst = 1.0;
            double total = 0.0;
            String worstRoute = "";
            int measured = 0;
            for (BlockPos[] route : routes(cells, dimension.centers())) {
                PathResult best = solve(cells, route[0], route[1], 1.0, false, UNLIMITED_NODE_BUDGET);
                if (!best.complete()) {
                    continue;
                }
                PathResult production = solve(cells, route[0], route[1],
                        AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, true, PRODUCTION_NODE_BUDGET);
                measured++;
                double ratio = cost(production) / cost(best);
                total += ratio;
                if (ratio > worst) {
                    worst = ratio;
                    worstRoute = route[0].toShortString() + "→" + route[1].toShortString()
                            + String.format(Locale.ROOT, " (基準%.0f 実運用%.0f)",
                                    cost(best), cost(production));
                }
            }
            double mean = total / measured;
            report.add(String.format(Locale.ROOT, "%s: %d本 平均%.3f倍 最悪%.3f倍 %s",
                    dimension.name(), measured, mean, worst, worstRoute));
            if (mean > MEAN_LIMIT) {
                failures.add(dimension.name() + ": 経路が全体に遠回りになっている");
            }
            if (worst > WORST_LIMIT) {
                failures.add(dimension.name() + ": 破滅的に遠回りな経路がある " + worstRoute);
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
