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
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * <b>歩きながらチャンクが読み込まれ、見えた分だけ経路が伸びていく</b>状況で、出来上がる経路が
 * どれだけ遠回りになるかを測る。
 *
 * <p>{@code PathOptimalityTest}は世界が丸ごと見えている前提で1回の探索を測る。実機はそうでは
 * ない——最初の経路は読み込み済みの窓の中だけで決まり、その末端から継ぎ足していく。<b>手前の
 * 区間は二度と見直されない</b>ので、後から見えた地形からすれば遠回りな道に乗ったまま歩き続けうる。
 * ユーザー報告「先に決まっていたルートと、新しく決まったルートの間が最適じゃない」がこれ。
 *
 * <p>再現は{@link WindowedCells}（プレイヤーの周りだけが読み込まれている世界）と
 * {@link ProgressiveWalk}（実機と同じ区間分割・継ぎ足し）。
 *
 * <p><b>継ぎ足しを疑うなら、毎回引き直す版と並べること。</b>{@link #extendingAndReplanningCostTheSame}が
 * それで、実測では両者に差が無い——遠回りの出どころは継ぎ目ではなく<b>窓の中しか見えていないこと</b>
 * そのもの。継ぎ足しをやめても直らない。
 *
 * <p>ここは手で選んだ5本を見る。<b>種を固定した乱数で長距離を統計的に測るのは
 * {@code LongRouteOptimalityTest}</b>で、そちらは窓の取り分と層1の取り分を分けて出す。
 */
@Tag("slow")
class ProgressiveDiscoveryTest {

    /** 読み込み済みの窓の半径（ブロック）。描画距離6チャンクと10チャンク相当。 */
    private static final int[] WINDOW_RADII = {96, 160};

    /**
     * 全視界の最適に対して許す倍率。
     *
     * <p>実測は地上1.08〜1.11、エンド1.01、ネザーの素直な区間1.02〜1.03、<b>ネザー2が1.30〜1.36</b>。
     * ネザー2が飛び抜けるのは3D迷路で、窓の外にある通路の有無が大局を決めてしまうため——
     * 窓の中しか見えない以上ここは原理的に詰まらない。<b>この線は「今より悪くなったら気づく」
     * ためのもの</b>で、最適の証明ではない。
     */
    private static final double WORST_LIMIT = 1.40;

    /** 引き直す版がこれ以上安くなったら、継ぎ目を疑う価値がある。実測は0.95〜1.07倍。 */
    private static final double REPLAN_ADVANTAGE_LIMIT = 1.10;

    private record Route(String name, String resource, BlockPos start, BlockPos goal) {
    }

    private static List<Route> routes() {
        return List.of(
                new Route("地上", "/overworld_terrain_columns.txt.gz",
                        new BlockPos(30, 0, 30), new BlockPos(230, 0, 220)),
                new Route("地上2", "/overworld_terrain_columns.txt.gz",
                        new BlockPos(230, 0, 30), new BlockPos(40, 0, 210)),
                new Route("ネザー", "/nether_terrain_columns.txt.gz",
                        new BlockPos(-180, 0, -180), new BlockPos(-20, 0, -20)),
                // 3D迷路で、窓の外の通路の有無が大局を決める——この方式でいちばん苦しい形
                new Route("ネザー2", "/nether_terrain_columns.txt.gz",
                        new BlockPos(-20, 0, -180), new BlockPos(-180, 0, -30)),
                new Route("エンド", "/end_terrain_columns.txt.gz",
                        new BlockPos(1160, 0, 1240), new BlockPos(1260, 0, 1160)));
    }

    private static FakeCells terrain(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    @Test
    void routesBuiltWhileWalkingStayUsable() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Route route : routes()) {
            FakeCells all = terrain(route.resource());
            SearchBounds bounds = all.bounds();
            BlockPos start = TerrainFixture.onGround(all, bounds, route.start());
            BlockPos goal = TerrainFixture.onGround(all, bounds, route.goal());
            double best = ProgressiveWalk.fullVisibilityBest(all, start, goal);
            for (int radius : WINDOW_RADII) {
                double walked = ProgressiveWalk.walkToGoal(all, start, goal, radius, true);
                double ratio = walked / best;
                report.add(String.format(Locale.ROOT, "%s 窓=%d 全視界=%.0f 歩いた経路=%.0f (%.3f倍)",
                        route.name(), radius, best, walked, ratio));
                if (!(ratio <= WORST_LIMIT)) {
                    failures.add(String.format(Locale.ROOT,
                            "%s 窓=%d が %.3f倍（読み込みながら歩くと遠回りになりすぎている）",
                            route.name(), radius, ratio));
                }
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }

    /**
     * <b>継ぎ足し（手前を見直さない）と、毎回引き直す版のコストが変わらないこと。</b>
     *
     * <p>「先に決まっていた区間との継ぎ目が悪い」という見立ての検証。差が付かないなら、
     * 遠回りの原因は継ぎ目ではなく窓の狭さで、継ぎ足しをやめても直らない——<b>直す先を
     * 間違えないための番人</b>。ここが崩れた（引き直す版の方がはっきり安くなった）ときは、
     * 手前の区間を見直す仕組みに手を入れる価値が出たということ。
     */
    @Test
    void extendingAndReplanningCostTheSame() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Route route : routes()) {
            FakeCells all = terrain(route.resource());
            SearchBounds bounds = all.bounds();
            BlockPos start = TerrainFixture.onGround(all, bounds, route.start());
            BlockPos goal = TerrainFixture.onGround(all, bounds, route.goal());
            int radius = WINDOW_RADII[0];
            double extending = ProgressiveWalk.walkToGoal(all, start, goal, radius, true);
            double replanning = ProgressiveWalk.walkToGoal(all, start, goal, radius, false);
            report.add(String.format(Locale.ROOT, "%s 継ぎ足し=%.0f 毎回引き直し=%.0f (%.3f倍)",
                    route.name(), extending, replanning, extending / replanning));
            if (extending > replanning * REPLAN_ADVANTAGE_LIMIT) {
                failures.add(String.format(Locale.ROOT,
                        "%s: 引き直す方が %.0f→%.0f と安い。継ぎ目に手を入れる価値が出ている",
                        route.name(), extending, replanning));
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(),
                String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
