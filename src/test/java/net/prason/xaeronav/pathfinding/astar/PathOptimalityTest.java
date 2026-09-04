package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
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
 * <p>個別の地形で「この経路が出てほしい」を書く他のテストと違い、<b>基準との比</b>だけを見る。
 * 人間が期待経路を書けない規模——9つの地形へ、種を固定した乱数で振った始点・終点——で崩れを
 * 捕まえるため。
 *
 * <p><b>地形はバイオームごとに性格が違う</b>ので、1つの地形で測っても足りない。山岳（起伏168）は
 * 昇降の値付けを、海岸（水65%）は水の渡り方を、玄武岩の三角州は溶岩と細かい起伏を、
 * それぞれ別の形で突く。1つずつ「この地形では」と書くのではなく、まとめて比だけ見るのがこの形の狙い。
 *
 * <p><b>基準は同じ{@code CellSource}に対する重み1.0・層1ガイド無し・予算実質無制限の探索。</b>
 * 実運用の構成（重み{@link AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}・ガイド有り・既定予算）との
 * 差は、まるごと<b>実装が持ち込んだ最適でなさ</b>になる。基準も同じ探索器なので厳密な最適では
 * ない（{@code orderingCost}の量子化とclosedを開き直さない性質のぶん）。
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

    /**
     * 実機が<b>この距離の経路に対して実際に使う</b>重み。{@code PathfindingState}は通常予算と
     * 深い予算を並列に走らせ、通常側だけ軽い重み(1.2)にしている——ここで測る40〜90ブロックの
     * 経路はまず通常側が勝つので、そちらの重みで測るのが実機に近い。
     * 通常側が届かない長距離では深い側の{@link AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}に落ちる。
     */
    private static final double PRODUCTION_WEIGHT = 1.2;

    /** 基準の探索に渡す予算。実測で最大30万ノード程度なので、実質無制限。 */
    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;

    /**
     * 乱数の種。<b>固定するのが要点</b>——毎回違う経路を測ると、落ちたときに再現できないうえ、
     * たまたま厳しい組が引かれただけなのか本当に悪化したのかを区別できない。
     */
    private static final long SEED = 20260904L;

    /** 地形ごとに測る経路の本数。 */
    private static final int ROUTES_PER_TERRAIN = 20;

    private static final int MIN_ROUTE_BLOCKS = 40;
    private static final int MAX_ROUTE_BLOCKS = 90;

    /** 全体の悪化を捕まえる線。実測は0.83〜1.026。 */
    private static final double MEAN_LIMIT = 1.05;

    /** 1本でも破滅的なら落とす線。実測は1.025〜1.123。 */
    private static final double WORST_LIMIT = 1.25;

    /**
     * 無駄な上下（正味の高低差を引いた上り＋下り）が、基準の経路より何倍まで許されるか。
     *
     * <p>ユーザー報告「平地で1〜2マス下がって上がるルートが出る」に対する番人。<b>地形そのものの
     * 起伏ではなく、基準との比で見る</b>のが要点——山岳では上下して当たり前なので絶対量では測れない。
     * コスト模型が上下に値段を付けている（{@code ActionCosts#STEP_TRANSITION_TICKS}）以上、
     * 基準の経路は無駄な上下を避けているはずで、そこから離れるぶんは実装側の取り分。
     *
     * <p><b>1.00にはならない。</b>残っているのは重み（{@link #PRODUCTION_WEIGHT}）そのもので、
     * 下げるほど真っ直ぐになる（サバンナ: 1.5→1.62倍 / 1.2→1.24倍 / 1.0→1.05倍）。ただし
     * 下げると展開ノードが3〜5倍に増え、山岳の長距離では既定予算で届かない経路が出る
     * （20本中1本→3本）。実機はそのぶんを深い予算の並列探索で受けている
     * （{@code PathfindingState#QUALITY_HEURISTIC_WEIGHT}）。
     *
     * <p>実測は地上/平原1.17・山岳1.06・サバンナ1.24・海岸1.28・森0.67・
     * ネザー0.43〜1.00・エンド1.40。
     */
    private static final double WOBBLE_LIMIT = 1.50;

    private record Terrain(String name, String resource, boolean ceiling) {
    }

    /** 道具を持って普通に歩いている状態。地形によらず同じにして、差が地形だけから出るようにする。 */
    private static FakeCells walkingPlayer(SearchBounds bounds, boolean ceiling) {
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(96)
                .maxFallDamagePoints(6);
        // ネザーは岩盤天井が書き出した箱より上にある。実装の`ChunkView`と同じく空が開けていない状態にする
        return ceiling ? cells.openSkyYOverride(bounds.maxY()) : cells;
    }

    private static List<Terrain> terrains() {
        return List.of(
                new Terrain("地上/平原丘陵", "/overworld_terrain_columns.txt.gz", false),
                new Terrain("地上/山岳", "/overworld_mountains.txt.gz", false),
                new Terrain("地上/サバンナ", "/overworld_savanna.txt.gz", false),
                new Terrain("地上/海岸", "/overworld_coast.txt.gz", false),
                new Terrain("地上/森", "/overworld_forest.txt.gz", false),
                new Terrain("ネザー/荒地", "/nether_terrain_columns.txt.gz", true),
                new Terrain("ネザー/玄武岩", "/nether_basalt_deltas.txt.gz", true),
                new Terrain("ネザー/ソウル", "/nether_soul_sand_valley.txt.gz", true),
                new Terrain("ネザー/深紅の森", "/nether_crimson_forest.txt.gz", true),
                new Terrain("エンド", "/end_terrain_columns.txt.gz", false));
    }

    private static double cost(PathResult result) {
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    /** 正味の高低差を引いた上り＋下り。まっすぐ登る経路では0になる。 */
    private static int wobble(BlockPos start, PathResult result) {
        int up = 0;
        int down = 0;
        BlockPos previous = start;
        for (PathStep step : result.steps()) {
            int dy = step.pos().getY() - previous.getY();
            up += Math.max(0, dy);
            down += Math.max(0, -dy);
            previous = step.pos();
        }
        int net = Math.abs(up - down);
        return up + down - net;
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
     * 箱の中から立てる点を種固定の乱数で拾い、{@link #MIN_ROUTE_BLOCKS}〜{@link #MAX_ROUTE_BLOCKS}
     * 離れた組を作る。<b>中心から放射状に振るのではなく散らす</b>のは、1つの中心の周りだけを見ると
     * その地点の地形の癖しか測れないため。
     */
    private static List<BlockPos[]> routes(FakeCells cells, long seed) {
        SearchBounds bounds = cells.bounds();
        Random random = new Random(seed);
        List<BlockPos[]> routes = new ArrayList<>();
        int attempts = 0;
        while (routes.size() < ROUTES_PER_TERRAIN && attempts++ < 4000) {
            BlockPos start = randomStandable(cells, bounds, random);
            if (start == null) {
                continue;
            }
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int distance = MIN_ROUTE_BLOCKS + random.nextInt(MAX_ROUTE_BLOCKS - MIN_ROUTE_BLOCKS + 1);
            int x = start.getX() + (int) Math.round(distance * Math.cos(angle));
            int z = start.getZ() + (int) Math.round(distance * Math.sin(angle));
            int y = TerrainFixture.standableY(cells, bounds, x, z);
            if (y == Integer.MIN_VALUE) {
                continue;
            }
            routes.add(new BlockPos[] {start, new BlockPos(x, y, z)});
        }
        return routes;
    }

    /** 箱の内側（縁から16ブロック入った所）で立てる点。 */
    private static BlockPos randomStandable(FakeCells cells, SearchBounds bounds, Random random) {
        int x = bounds.minX() + 16 + random.nextInt(Math.max(1, bounds.maxX() - bounds.minX() - 32));
        int z = bounds.minZ() + 16 + random.nextInt(Math.max(1, bounds.maxZ() - bounds.minZ() - 32));
        int y = TerrainFixture.standableY(cells, bounds, x, z);
        return y == Integer.MIN_VALUE ? null : new BlockPos(x, y, z);
    }

    @Test
    void routesStayCloseToTheBestThisSearcherCanFind() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Terrain terrain : terrains()) {
            FakeCells cells = TerrainFixture.load(terrain.resource(),
                    bounds -> walkingPlayer(bounds, terrain.ceiling()));
            double worst = 1.0;
            double total = 0.0;
            String worstRoute = "";
            int measured = 0;
            int bestWobble = 0;
            int productionWobble = 0;
            for (BlockPos[] route : routes(cells, SEED)) {
                PathResult best = solve(cells, route[0], route[1], 1.0, false, UNLIMITED_NODE_BUDGET);
                if (!best.complete()) {
                    continue;
                }
                PathResult production = solve(cells, route[0], route[1], PRODUCTION_WEIGHT, true,
                        PRODUCTION_NODE_BUDGET);
                measured++;
                bestWobble += wobble(route[0], best);
                productionWobble += wobble(route[0], production);
                double ratio = cost(production) / cost(best);
                total += ratio;
                if (ratio > worst) {
                    worst = ratio;
                    worstRoute = route[0].toShortString() + "→" + route[1].toShortString()
                            + String.format(Locale.ROOT, " (基準%.0f 実運用%.0f)",
                                    cost(best), cost(production));
                }
            }
            if (measured == 0) {
                failures.add(terrain.name() + ": 経路が1本も出ない（地形か座標がおかしい）");
                continue;
            }
            double mean = total / measured;
            double wobbleRatio = bestWobble == 0 ? 1.0 : (double) productionWobble / bestWobble;
            report.add(String.format(Locale.ROOT,
                    "%-12s %2d本 平均%.3f倍 最悪%.3f倍 無駄な上下%d/%d(%.2f倍) %s",
                    terrain.name(), measured, mean, worst, productionWobble, bestWobble,
                    wobbleRatio, worstRoute));
            if (mean > MEAN_LIMIT) {
                failures.add(terrain.name() + ": 経路が全体に遠回りになっている");
            }
            if (worst > WORST_LIMIT) {
                failures.add(terrain.name() + ": 破滅的に遠回りな経路がある " + worstRoute);
            }
            if (wobbleRatio > WOBBLE_LIMIT) {
                failures.add(terrain.name() + ": 無駄な上下が基準より多い " + productionWobble
                        + " 対 " + bestWobble);
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
