package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>200ブロックを超える経路で、遠回りが「層1＋区間分割」と「窓の狭さ」のどちらから来ているかを
 * 分けて測る。</b>
 *
 * <p>{@code PathOptimalityTest}が測るのは40〜90ブロックで、しかも<b>1回のA*で解いた経路</b>。
 * 実機はその距離を1回では解かない——層1のcost-to-goガイドで大局を決め、
 * {@link ProgressiveWalk#DETAIL_HORIZON}ごとに区間へ切り、末端から継ぎ足す。長距離の遠回りは
 * ほとんどがその組み立て方から出るので、<b>組み立てを再現しないと発生源が測れない</b>。
 *
 * <p>1本につき3通り測る:
 * <ul>
 * <li><b>基準</b> — 全視界・重み1.0・ガイド無しの1回の探索</li>
 * <li><b>全視界</b> — 世界が丸ごと見えている状態で実機と同じ組み立て。基準との差は
 *     <b>層1の解像度（16ブロック）と区間分割</b>の取り分</li>
 * <li><b>窓160</b> — さらに描画距離10チャンク相当の窓を掛ける。全視界との差が<b>窓の狭さ</b>の取り分</li>
 * </ul>
 *
 * <p><b>実測では窓の取り分がほぼ無い（むしろ窓ありの方が安いことが多い）。</b>遠回りは
 * 層1と区間分割から出ていて、窓を広げても縮まらない——層1の解像度に手を入れる前に、
 * それを数字で確かめるのがこのテストの役目。窓ありが安くなるのは、歩くたびに現在地から層1を
 * 引き直すため。全視界側は出発点で1回引いた粗い地図に最後まで従う。
 *
 * <p><b>ジ・エンドをここに入れていない</b>のは、200ブロックを超える島渡りが
 * {@code PathfindingExecutor}の緩和の梯子（橋の連続長の上限外しなど）を必要とし、
 * {@link ProgressiveWalk}はそこまで再現していないため（実測6本中3本が経路無しで終わる）。
 * 壁時計で縛る梯子を持ち込むとCIの速度で結果が変わるので、番人としては入れない方を採った。
 * ジ・エンドの長距離は{@code ProgressiveDiscoveryTest}と{@code RealEndTerrainTest}が見ている。
 */
@Tag("slow")
class LongRouteOptimalityTest {

    /** 種を固定する理由は{@link TerrainFixture#randomRoutes}に書いてある。 */
    private static final long SEED = 20260906L;

    /** 読み込み済みの窓の半径。描画距離10チャンク相当。 */
    private static final int WINDOW_RADIUS = 160;

    /**
     * 海と陸が半々で、512ブロックの外洋横断を含む地形。<b>1つに絞っている</b>のは、基準側の
     * 重み1.0・ガイド無しの探索が1本あたり1〜7秒かかるため——地形を増やすより、
     * 同じ地形で距離を振る方が層1の解像度に効く（層1のセルを何個またぐかが効き目を決める）。
     */
    private static final String TERRAIN = "/overworld_wide.txt.gz";

    private static final int ROUTES = 8;
    private static final int MIN_ROUTE_BLOCKS = 200;
    private static final int MAX_ROUTE_BLOCKS = 450;

    /** 全体の悪化を捕まえる線。実測は全視界1.119・窓1.092。 */
    private static final double MEAN_LIMIT = 1.20;

    /** 1本でも破滅的なら落とす線。実測は全視界1.166・窓1.166。 */
    private static final double WORST_LIMIT = 1.30;

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load(TERRAIN, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    @Test
    void longRoutesShowWhereTheDetourComesFrom() throws IOException {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Double> openRatios = new ArrayList<>();
        List<Double> windowedRatios = new ArrayList<>();
        for (BlockPos[] route : TerrainFixture.randomRoutes(cells, cells.bounds(), SEED, ROUTES,
                MIN_ROUTE_BLOCKS, MAX_ROUTE_BLOCKS)) {
            String name = route[0].toShortString() + "→" + route[1].toShortString();
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            double open = ProgressiveWalk.walkToGoal(cells, route[0], route[1],
                    ProgressiveWalk.NO_WINDOW, true);
            double windowed = ProgressiveWalk.walkToGoal(cells, route[0], route[1],
                    WINDOW_RADIUS, true);
            if (!Double.isFinite(best) || !Double.isFinite(open) || !Double.isFinite(windowed)) {
                failures.add(name + ": 経路が返らない（基準" + best + " 全視界" + open + " 窓" + windowed + "）");
                continue;
            }
            openRatios.add(open / best);
            windowedRatios.add(windowed / best);
            report.add(String.format(Locale.ROOT,
                    "%3.0fブロック 基準%6.0f 全視界%6.0f(%.3f倍) 窓%6.0f(%.3f倍) 窓の取り分%.3f倍 %s",
                    ProgressiveWalk.horizontal(route[0], route[1]), best, open, open / best,
                    windowed, windowed / best, windowed / open, name));
        }
        if (openRatios.size() < ROUTES) {
            failures.add("測れた経路が" + openRatios.size() + "本しかない");
        }
        report.add(check("層1＋区間分割", openRatios, failures));
        report.add(check("窓160まで込み", windowedRatios, failures));
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }

    private static String check(String what, List<Double> ratios, List<String> failures) {
        if (ratios.isEmpty()) {
            return what + ": 測れた経路が無い";
        }
        double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
        double worst = ratios.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (mean > MEAN_LIMIT) {
            failures.add(what + ": 長距離の経路が全体に遠回りになっている " + String.format(Locale.ROOT, "%.3f倍", mean));
        }
        if (worst > WORST_LIMIT) {
            failures.add(what + ": 破滅的に遠回りな長距離経路がある " + String.format(Locale.ROOT, "%.3f倍", worst));
        }
        return String.format(Locale.ROOT, "%s: 平均%.3f倍 最悪%.3f倍", what, mean, worst);
    }
}
