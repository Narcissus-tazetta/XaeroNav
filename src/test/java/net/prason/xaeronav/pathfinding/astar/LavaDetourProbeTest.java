package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelProbe;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 使い捨ての計測。ネザーの溶岩の海で<b>渡るのと大回りするののどちらが安いか</b>、そして
 * 歩き通しがどちらを選んでいるかを測る。
 *
 * <p>比べるのは3つ。どれも同じ地形・同じコストモデル:
 * <ul>
 *   <li>{@code 渡ってよい最適}＝全視界・重み1.0・予算無制限（{@code ProgressiveWalk#fullVisibilityBest}）</li>
 *   <li>{@code 大回りだけの最適}＝溶岩に橋を架けることを禁じた同じ探索。<b>必ず渡ってよい最適以上</b>なので、
 *       比が1.0に近いほど「渡る利得が小さい＝大回りさせても損しない」</li>
 *   <li>{@code 歩き通し}＝実機と同じ窓・箱・継ぎ足しで航法グラフのガイドを使って歩いた結果</li>
 * </ul>
 */
@Tag("bench")
class LavaDetourProbeTest {

    private static final int WINDOW = Integer.getInteger("xaeronav.window", 160);

    /** 実機の既定（{@code XaeroNavConfig}）。 */
    private static final int LAVA_BRIDGE_BLOCKS = 30;

    /**
     * 基準（全視界・重み1.0・ガイド無し）に渡す予算。{@code ProgressiveWalk#fullVisibilityBest}の
     * 300万ノードでは溶岩の海の3本が1本も返らなかったので、ここで積み直す。
     */
    private static final SearchLimits REFERENCE_LIMITS = new SearchLimits(20_000_000, 600_000, 1.0);

    /** 全視界・重み1.0・ガイド無しの1回の探索。届かなければ{@link Double#POSITIVE_INFINITY}。 */
    private static double best(CellSource cells, BlockPos start, BlockPos goal) {
        PathResult result = new AStarPathfinder(cells, REFERENCE_LIMITS).search(start, goal, () -> false);
        return result.complete() ? ProgressiveWalk.cost(result.steps()) : Double.POSITIVE_INFINITY;
    }

    private static Function<BlockPos, CostToGo> guide(FakeCells cells, BlockPos goal, FarField far) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        return player -> {
            if (last[0] == null || !player.equals(last[0])) {
                CellSource window = new WindowedCells(cells, player, WINDOW);
                cached[0] = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), far,
                        ForkJoinPool.commonPool(), Runtime.getRuntime().availableProcessors(), () -> false).field();
                last[0] = player;
            }
            return cached[0];
        };
    }

    /**
     * 本番と同じ形の3D粗層を組んで{@link FarField}にする。箱の余白だけ差し替えられるようにしてある
     * （本番は{@code VoxelTerrain.MARGIN_BLOCKS}=128）。
     */
    private static VoxelTerrain voxelTerrain(FakeCells cells, BlockPos start, BlockPos goal) {
        int margin = Integer.getInteger("xaeronav.voxelMargin", VoxelTerrain.MARGIN_BLOCKS);
        SearchBounds box = new SearchBounds(
                Math.min(start.getX(), goal.getX()) - margin, NetherLiveWalkTest.NETHER_MIN_Y,
                Math.min(start.getZ(), goal.getZ()) - margin,
                Math.max(start.getX(), goal.getX()) + margin, NetherLiveWalkTest.NETHER_MAX_Y,
                Math.max(start.getZ(), goal.getZ()) + margin);
        VoxelTerrain terrain = VoxelTerrain.of(box, !Boolean.getBoolean("xaeronav.blockLava"));
        // 実機のXaero地図は一部しか既知でない（層1で43%だった）。落とすと粗層の推定がどう変わるかを測る
        double keep = Double.parseDouble(System.getProperty("xaeronav.keepFraction", "1.0"));
        // 実機は洞窟レイヤーが1枚しか保存されていない（mapdataで確認: layer 2だけが3110セル）。
        // 全高から8枚拾うモデルでは探索が詰まる条件そのものが再現しない（XaeroMapModel#fillのjavadoc）
        XaeroMapModel.fill(terrain, cells, caveLayers(), keep, 20260918L);
        return terrain;
    }

    /** 地図に残っている洞窟レイヤー（{@code -Pxaeronav.caveLayers=4} など）。無指定なら全高から8枚。 */
    private static int @org.jspecify.annotations.Nullable [] caveLayers() {
        String value = System.getProperty("xaeronav.caveLayers");
        if (value == null || value.isEmpty()) {
            return null;
        }
        return java.util.Arrays.stream(value.split(",")).map(String::trim)
                .mapToInt(Integer::parseInt).toArray();
    }

    private static FarField farField(VoxelTerrain terrain, BlockPos goal) {
        VoxelCostToGo voxel = VoxelCostToGo.build(terrain, goal, () -> false);
        double scale = Double.parseDouble(System.getProperty("xaeronav.navGraphFarScale", "1.3"));
        return FarField.of((x, y, z) -> scale * voxel.estimate(x, y, z));
    }

    /** 溶岩の上に架けた橋の本数と、いちばん長い連続の橋。 */
    private static int[] lavaBridges(FakeCells cells, List<PathStep> steps) {
        int total = 0;
        int longest = 0;
        int run = 0;
        for (PathStep step : steps) {
            BlockPos placed = step.placedBlockPos();
            boolean overLava = placed != null
                    && CellData.lava(cells.cell(placed.getX(), placed.getY(), placed.getZ()));
            if (overLava) {
                total++;
                longest = Math.max(longest, ++run);
            } else {
                run = 0;
            }
        }
        return new int[] {total, longest};
    }

    /**
     * 閉包で作った「窓の中は完璧」なガイドで歩く（{@code NavGraphWalkBenchTest#closureWalk}と同じ）。
     * これが最適に届くなら、足りないのはガイドの精度。届かないなら、足りないのは計画の組み立て方。
     */
    private static ProgressiveWalk.Trace closureWalk(FakeCells cells, BlockPos start, BlockPos goal,
                                                     FarField far) {
        ClosureGraph closure = ClosureGraph.build(cells, start, goal,
                ClosureGraph.box(cells, start, goal, Integer.getInteger("xaeronav.closureBox", 224),
                        cells.bounds().minY(), cells.bounds().maxY()));
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        return ProgressiveWalk.trace(cells, start, goal, WINDOW, ProgressiveWalk.Mode.REPAIR,
                ProgressiveWalk.Aim.GOAL, (Function<BlockPos, CostToGo>) player -> {
                    if (!player.equals(last[0])) {
                        // 半径を箱いっぱいまで広げると「窓の外まで完璧」なガイドになる。
                        // 既定（WINDOW）なら窓の中だけ完璧で、外は歩き通しと同じ3D粗層
                        cached[0] = closure.windowGuide(goal, player,
                                Integer.getInteger("xaeronav.closureRadius", WINDOW), far::at);
                        last[0] = player;
                    }
                    return cached[0];
                }, 1.0);
    }

    private void measure(String name, FakeCells cells, List<BlockPos[]> routes) {
        for (BlockPos[] route : routes.subList(0,
                Math.min(routes.size(), Integer.getInteger("xaeronav.routeLimit", 99)))) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            double best = best(cells, start, goal);
            // 地形は1つだけ読む（ネザーのフィクスチャは1690万セルで、2つ持つとヒープが足りない）。
            // 溶岩の橋の可否は{@link FakeCells}の設定なので、同じ表のまま切り替えて測れる
            double around = best(cells.lavaBridgingEnabled(false), start, goal);
            cells.lavaBridgingEnabled(true);

            VoxelTerrain terrain = voxelTerrain(cells, start, goal);
            FarField far = farField(terrain, goal);
            double scale = Double.parseDouble(System.getProperty("xaeronav.navGraphFarScale", "1.3"));
            ProgressiveWalk.Mode mode = ProgressiveWalk.Mode.valueOf(
                    System.getProperty("xaeronav.walkMode", "REPAIR"));
            ProgressiveWalk.Trace walk = ProgressiveWalk.trace(cells, start, goal, WINDOW,
                    mode, ProgressiveWalk.Aim.GOAL, guide(cells, goal, far), 1.0);
            double walked = walk.steps().isEmpty() ? Double.POSITIVE_INFINITY
                    : ProgressiveWalk.cost(walk.steps());
            int[] bridges = lavaBridges(cells, walk.steps());
            // 閉包は重いので、必要なときだけ組む
            double closure = Double.NaN;
            if (Boolean.getBoolean("xaeronav.closure")) {
                ProgressiveWalk.Trace trace = closureWalk(cells, start, goal, far);
                closure = trace.steps().isEmpty() ? Double.POSITIVE_INFINITY : ProgressiveWalk.cost(trace.steps());
                if (trace.steps().isEmpty()) {
                    System.out.printf(Locale.ROOT, "  完璧なガイドが届かず: %s%n", trace.stopped());
                }
            }

            System.out.printf(Locale.ROOT,
                    "%s[%s x%.1f 窓%d 粗層の余白%d/辺%d] %s→%s 渡ってよい最適%.0f 大回りだけ%.0f(%.3f倍) 歩き通し%.0f(%.3f倍) 完璧なガイド%.3f倍 "
                            + "溶岩の橋%d本(最長%d) 描き変わり%d回(%.0fブロック分, 足元%d回) 踏み直し%d手 %s%n",
                    name, mode, scale, WINDOW, Integer.getInteger("xaeronav.voxelMargin",
                            VoxelTerrain.MARGIN_BLOCKS), terrain.cellBlocks(), start.toShortString(), goal.toShortString(), best, around, around / best,
                    walked, walked / best, closure / best, bridges[0], bridges[1], walk.redraws(),
                    walk.redrawnBlocks(), walk.nearRedraws(), ProgressiveWalk.selfOverlaps(walk.steps()),
                    walk.stopped());
        }
    }

    /**
     * 実機のぐるぐる（2026-09-18、始点0,90,0→目的地-335,65,706）と同じ目的地へ、フィクスチャの中で
     * 遠くから近づくルート。<b>実機の始点はフィクスチャの外</b>なので、実機がフィクスチャの範囲へ
     * 入ってきた地点とその東・南を並べてある。
     */
    private static final List<BlockPos[]> FAR_ROUTES = List.of(
            new BlockPos[] {new BlockPos(-222, 64, 356), new BlockPos(-335, 65, 706)},
            new BlockPos[] {new BlockPos(-120, 64, 380), new BlockPos(-335, 65, 706)},
            new BlockPos[] {new BlockPos(-40, 64, 380), new BlockPos(-335, 65, 706)});

    /** 窓の縁（この距離）で2つの回廊を比べる。窓160のすこし内側に置く。 */
    private static final int EDGE_BLOCKS = 150;

    /**
     * 経路が踏む足元のセルを、3D粗層がどの種別として持っているかの内訳。
     * <b>床なら1.0倍、空洞・溶岩なら約11倍</b>（{@code VoxelCostToGo#rate}）なので、
     * 実際には歩ける道が空洞に見えていれば推定が膨らみ、渡れない所が床に見えていれば縮む。
     */
    private static String kindHistogram(VoxelTerrain terrain, List<PathStep> steps) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (PathStep step : steps) {
            counts.merge(VoxelProbe.kindAt(terrain, step.pos()), 1, Integer::sum);
        }
        return counts.toString();
    }

    /** 経路の上で、始点から{@link #EDGE_BLOCKS}以上離れた最初の点と、そこまでの実費。 */
    private static Object[] crossing(List<PathStep> steps, BlockPos start) {
        double cost = 0;
        for (PathStep step : steps) {
            cost += step.cost();
            if (ProgressiveWalk.horizontal(start, step.pos()) >= EDGE_BLOCKS) {
                return new Object[] {step.pos(), cost};
            }
        }
        return null;
    }

    /**
     * <b>窓の縁で、3D粗層が2つの回廊をどう値付けしているか。</b>
     *
     * <p>窓の中のガイドは正確なので、探索が選ぶ回廊は「縁までの実費＋縁に置いた外の推定」が最小のもの。
     * つまり<b>推定が最も楽観的な回廊</b>が選ばれる。最適な道と実際に歩いた道それぞれについて、
     * 窓の縁を越える点での「推定」と「そこからの真の残り」を並べれば、ずれの向きと大きさがそのまま出る。
     */
    @Test
    void farFieldBias() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain().maxLavaBridgeRunBlocks(LAVA_BRIDGE_BLOCKS);
        List<BlockPos[]> routes = "far".equals(System.getProperty("xaeronav.routes"))
                ? FAR_ROUTES
                : List.of(
                new BlockPos[] {new BlockPos(-271, 64, 395), new BlockPos(-333, 59, 694)},
                new BlockPos[] {new BlockPos(-261, 66, 448), new BlockPos(-333, 59, 694)});
        for (BlockPos[] route : routes.subList(0,
                Math.min(routes.size(), Integer.getInteger("xaeronav.routeLimit", 99)))) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            boolean lavaPassable = !Boolean.getBoolean("xaeronav.blockLava");
            int margin = Integer.getInteger("xaeronav.voxelMargin", VoxelTerrain.MARGIN_BLOCKS);
            VoxelTerrain terrain = voxelTerrain(cells, start, goal);
            FarField far = farField(terrain, goal);
            double scale = Double.parseDouble(System.getProperty("xaeronav.navGraphFarScale", "1.3"));

            PathResult optimal = new AStarPathfinder(cells, REFERENCE_LIMITS).search(start, goal, () -> false);
            ProgressiveWalk.Trace walk = ProgressiveWalk.trace(cells, start, goal, WINDOW,
                    ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide(cells, goal, far), 1.0);
            double total = ProgressiveWalk.cost(optimal.steps());
            System.out.printf(Locale.ROOT, "[溶岩%s x%.1f 粗層の余白%d セル辺%d] %s→%s 最適%.0f 歩き通し%.0f 始点の推定%.0f(真値の%.2f倍)%n",
                    lavaPassable ? "通常" : "8倍", scale, margin, terrain.cellBlocks(), start.toShortString(), goal.toShortString(), total,
                    ProgressiveWalk.cost(walk.steps()), far.at(start.getX(), start.getY(), start.getZ()),
                    far.at(start.getX(), start.getY(), start.getZ()) / total);

            String[] names = {"最適な道", "歩いた道"};
            List<List<PathStep>> paths = List.of(optimal.steps(), walk.steps());
            double[] guided = new double[2];
            double[] truth = new double[2];
            for (int i = 0; i < 2; i++) {
                Object[] at = crossing(paths.get(i), start);
                if (at == null) {
                    System.out.printf(Locale.ROOT, "  %s: 窓の縁を越えていない%n", names[i]);
                    guided[i] = Double.NaN;
                    truth[i] = Double.NaN;
                    continue;
                }
                BlockPos edge = (BlockPos) at[0];
                double prefix = (Double) at[1];
                double estimate = far.at(edge.getX(), edge.getY(), edge.getZ());
                double remaining = best(cells, edge, goal);
                guided[i] = prefix + estimate;
                truth[i] = prefix + remaining;
                System.out.printf(Locale.ROOT,
                        "  %s: 縁=%s 手前の実費%.0f 推定%.0f 真の残り%.0f (推定/真=%.2f) → 合計 推定%.0f / 真%.0f%n",
                        names[i], edge.toShortString(), prefix, estimate, remaining, estimate / remaining,
                        guided[i], truth[i]);
                System.out.printf(Locale.ROOT, "    3D粗層がこの道の足元をどう見ているか: %s%n",
                        kindHistogram(terrain, paths.get(i)));
            }
            System.out.printf(Locale.ROOT, "  → 推定で選ぶと「%s」、真値で選ぶと「%s」%n",
                    guided[0] <= guided[1] ? names[0] : names[1],
                    truth[0] <= truth[1] ? names[0] : names[1]);
        }
    }

    /**
     * <b>行ったり帰ったりの量。</b>いちばん近づいた所からどれだけ遠ざかったか（最悪）と、
     * 遠ざかった量の合計。実機のぐるぐるは「約300ブロックの往復」だったので、
     * 模型で再現できているかはこの2つで見る。
     */
    private static double[] backtracking(List<PathStep> steps, BlockPos start, BlockPos goal) {
        double previous = ProgressiveWalk.horizontal(start, goal);
        double closest = previous;
        double retreat = 0;
        double worst = 0;
        for (PathStep step : steps) {
            double distance = ProgressiveWalk.horizontal(step.pos(), goal);
            if (distance > previous) {
                retreat += distance - previous;
            }
            worst = Math.max(worst, distance - closest);
            closest = Math.min(closest, distance);
            previous = distance;
        }
        return new double[] {worst, retreat};
    }

    /**
     * <b>遠くから同じ目的地へ近づく。</b>実機のぐるぐる（2026-09-18、始点0,90,0→目的地-335,65,706）は
     * 溶岩の海の3本より遠くから来たときだけ出た。実機の始点はフィクスチャの外なので、
     * フィクスチャの中で同じ目的地へ遠くから近づくルートを並べて、東寄りの回廊へ入ってから
     * 引き返すのが再現できるかを見る。
     */
    @Test
    void farApproach() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain().maxLavaBridgeRunBlocks(LAVA_BRIDGE_BLOCKS);
        List<BlockPos[]> routes = FAR_ROUTES;
        double keep = Double.parseDouble(System.getProperty("xaeronav.keepFraction", "1.0"));
        for (BlockPos[] route : routes.subList(0,
                Math.min(routes.size(), Integer.getInteger("xaeronav.routeLimit", 99)))) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            VoxelTerrain terrain = voxelTerrain(cells, start, goal);
            FarField far = farField(terrain, goal);
            ProgressiveWalk.Mode mode = ProgressiveWalk.Mode.valueOf(
                    System.getProperty("xaeronav.walkMode", "REPAIR"));
            long began = System.currentTimeMillis();
            ProgressiveWalk.Trace walk = ProgressiveWalk.trace(cells, start, goal, WINDOW,
                    mode, ProgressiveWalk.Aim.GOAL, guide(cells, goal, far), 1.0);
            double[] back = backtracking(walk.steps(), start, goal);
            int[] bridges = lavaBridges(cells, walk.steps());
            System.out.printf(Locale.ROOT, "  3D粗層 (%s, セル=%d, 辺=%d, レイヤー=%s, 膨らみ%.2f倍)%n",
                    terrain.breakdown(), terrain.cellCount(), terrain.cellBlocks(),
                    caveLayers() == null ? "全高8枚" : java.util.Arrays.toString(caveLayers()),
                    far.at(start.getX(), start.getY(), start.getZ())
                            / (ProgressiveWalk.horizontal(start, goal) * 3.564));
            System.out.printf(Locale.ROOT,
                    "遠くから[%s 窓%d 粗層の余白%d/辺%d 既知%.0f%%] %s→%s 直線%.0f 歩き通し%.0f "
                            + "最悪の後退%.0f 後退の合計%.0f 溶岩の橋%d本(最長%d) 描き変わり%d回(%.0fブロック分, 足元%d回) "
                            + "踏み直し%d手 %.0f秒 %s%n",
                    mode, WINDOW, Integer.getInteger("xaeronav.voxelMargin", VoxelTerrain.MARGIN_BLOCKS),
                    terrain.cellBlocks(), keep * 100, start.toShortString(), goal.toShortString(),
                    ProgressiveWalk.horizontal(start, goal),
                    walk.steps().isEmpty() ? Double.NaN : ProgressiveWalk.cost(walk.steps()),
                    back[0], back[1], bridges[0], bridges[1], walk.redraws(), walk.redrawnBlocks(),
                    walk.nearRedraws(), ProgressiveWalk.selfOverlaps(walk.steps()),
                    (System.currentTimeMillis() - began) / 1000.0, walk.stopped());
        }
    }

    /** 実機のネザーで溶岩の海の周りを行き来した区間（実機セーブから切り出したもの）。 */
    @Test
    void lavaSea() throws IOException {
        List<BlockPos[]> routes = List.of(
                new BlockPos[] {new BlockPos(-271, 64, 395), new BlockPos(-333, 59, 694)},
                new BlockPos[] {new BlockPos(-261, 66, 448), new BlockPos(-333, 59, 694)},
                new BlockPos[] {new BlockPos(-212, 48, 553), new BlockPos(-333, 59, 694)});
        measure("ネザー溶岩の海", NetherLiveWalkTest.terrain().maxLavaBridgeRunBlocks(LAVA_BRIDGE_BLOCKS), routes);
    }

    /** ふだんのネザー4本。溶岩の海が絡まない区間でも橋を架けているかを見る。 */
    @Test
    void nether() throws IOException {
        measure("ネザー", NetherLiveWalkTest.terrain(), new ArrayList<>(NetherLiveWalkTest.routes()));
    }
}
