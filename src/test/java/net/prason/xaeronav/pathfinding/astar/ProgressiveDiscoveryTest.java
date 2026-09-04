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
import net.prason.xaeronav.pathfinding.world.CellSource;
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
 * <p>再現は{@link WindowedCells}（プレイヤーの周りだけが読み込まれている世界）と、
 * 実装の{@code PathfindingState}と同じ2つの規則:
 * <ul>
 * <li>窓が経路の末端より{@code MIN_DETAIL_REACH}以上先へ届いているあいだは末端から継ぎ足す</li>
 * <li>目的地が地平({@code DETAIL_HORIZON})より遠ければ、その方向へ地平ぶん進んだ点を狙う</li>
 * </ul>
 *
 * <p><b>継ぎ足しを疑うなら、毎回引き直す版と並べること。</b>{@link #extendingAndReplanningCostTheSame}が
 * それで、実測では両者に差が無い——遠回りの出どころは継ぎ目ではなく<b>窓の中しか見えていないこと</b>
 * そのもの。継ぎ足しをやめても直らない。
 */
@Tag("slow")
class ProgressiveDiscoveryTest {

    private static final BooleanSupplier NEVER = () -> false;

    /** {@code PathfindingState#detailHorizonBlocks}の既定。 */
    private static final int DETAIL_HORIZON = 96;

    /** {@code PathfindingState#MIN_DETAIL_REACH_BLOCKS}。これだけ先へ窓が届いていれば継ぎ足す。 */
    private static final int MIN_DETAIL_REACH = 24;

    /** {@code PathfindingState#INTERPOLATED_GOAL_RADIUS_BLOCKS}。補間した中間目標は領域で狙う。 */
    private static final int INTERPOLATED_GOAL_RADIUS = 16;

    /** 1回の計画のあいだにプレイヤーが歩く距離（ブロック）。 */
    private static final int WALK_PER_TICK = 16;

    /** 読み込み済みの窓の半径（ブロック）。描画距離6チャンクと10チャンク相当。 */
    private static final int[] WINDOW_RADII = {96, 160};

    /**
     * 全視界の最適に対して許す倍率。
     *
     * <p>実測は地上1.06〜1.13、エンド1.01、ネザーの素直な区間1.01〜1.02、<b>ネザー2が1.29〜1.31</b>。
     * ネザー2が飛び抜けるのは3D迷路で、窓の外にある通路の有無が大局を決めてしまうため——
     * 窓の中しか見えない以上ここは原理的に詰まらない。<b>この線は「今より悪くなったら気づく」
     * ためのもの</b>で、最適の証明ではない。
     */
    private static final double WORST_LIMIT = 1.40;

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

    private static double cost(List<PathStep> steps) {
        return steps.stream().mapToDouble(PathStep::cost).sum();
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 目的地そのもの、または遠すぎるならその方向へ{@code reach}だけ進んだ点（実装と同じ）。 */
    private static BlockPos aimToward(BlockPos from, BlockPos goal, int reach) {
        double distance = horizontal(from, goal);
        if (distance <= reach) {
            return goal;
        }
        double t = reach / distance;
        return new BlockPos(from.getX() + (int) Math.round((goal.getX() - from.getX()) * t),
                from.getY() + (int) Math.round((goal.getY() - from.getY()) * t),
                from.getZ() + (int) Math.round((goal.getZ() - from.getZ()) * t));
    }

    private static PathResult leg(CellSource view, BlockPos from, BlockPos goal) {
        BlockPos aim = aimToward(from, goal, DETAIL_HORIZON);
        int radius = aim.equals(goal) ? 0 : INTERPOLATED_GOAL_RADIUS;
        CoarseMap map = LiveCoarseSampler.sample(view, view.bounds(), from.getY(), NEVER);
        CostToGo guide = CoarseRouter.costToGo(map, aim, false, CoarseRouter.BridgePolicy.BRIDGE);
        return new AStarPathfinder(view,
                new SearchLimits(100_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT), guide)
                .search(from, aim, NEVER, Carryover.NONE, radius);
    }

    /**
     * 窓を動かしながら目的地まで歩き通し、実際に歩いた経路のコストを返す。
     * 届かなければ{@link Double#POSITIVE_INFINITY}。
     *
     * @param extending trueなら実装どおり末端から継ぎ足す。falseなら計画のたびに手前を捨てて
     *                  プレイヤーから引き直す。<b>歩き方は両方で同じ</b>にしてある
     */
    private static double walkToGoal(FakeCells all, BlockPos start, BlockPos goal, int radius,
                                      boolean extending) {
        List<PathStep> walked = new ArrayList<>();
        List<PathStep> planned = new ArrayList<>();
        BlockPos player = start;
        for (int tick = 0; tick < 200; tick++) {
            CellSource view = new WindowedCells(all, player, radius);
            if (!extending) {
                planned = new ArrayList<>();
            }
            BlockPos end = planned.isEmpty() ? player : planned.get(planned.size() - 1).pos();
            while (horizontal(player, end) <= radius - MIN_DETAIL_REACH && horizontal(end, goal) > 1) {
                PathResult result = leg(view, end, goal);
                if (result.steps().isEmpty()) {
                    break;
                }
                planned.addAll(result.steps());
                BlockPos next = planned.get(planned.size() - 1).pos();
                if (next.equals(end)) {
                    break;
                }
                end = next;
            }
            if (planned.isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }
            int walkTo = 0;
            while (walkTo < planned.size()
                    && horizontal(player, planned.get(walkTo).pos()) < WALK_PER_TICK) {
                walkTo++;
            }
            walkTo = Math.max(1, Math.min(walkTo, planned.size()));
            walked.addAll(planned.subList(0, walkTo));
            planned = new ArrayList<>(planned.subList(walkTo, planned.size()));
            player = walked.get(walked.size() - 1).pos();
            if (horizontal(player, goal) <= 1) {
                return cost(walked);
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static double fullVisibilityBest(FakeCells all, BlockPos start, BlockPos goal) {
        PathResult result = new AStarPathfinder(all, new SearchLimits(3_000_000, 120_000, 1.0))
                .search(start, goal, NEVER);
        return result.complete() ? cost(result.steps()) : Double.POSITIVE_INFINITY;
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
            double best = fullVisibilityBest(all, start, goal);
            for (int radius : WINDOW_RADII) {
                double walked = walkToGoal(all, start, goal, radius, true);
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
            double extending = walkToGoal(all, start, goal, radius, true);
            double replanning = walkToGoal(all, start, goal, radius, false);
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

    /** 引き直す版がこれ以上安くなったら、継ぎ目を疑う価値がある。実測は0.96〜1.02倍。 */
    private static final double REPLAN_ADVANTAGE_LIMIT = 1.10;
}
