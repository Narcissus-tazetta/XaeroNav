package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 使い捨ての計測。3D粗層の<b>中身</b>が組み直しのたびに入れ替わる（Xaeroが載せている洞窟レイヤーが
 * 振れる）ことが、歩く経路をどれだけ荒らすかを測る。
 *
 * <p>比べるのは2つ。箱は同じ（{@code 0e1d57e}で積み上げ式にしたあとの落ち着いた箱）:
 * <ul>
 *   <li><b>そのつど</b>＝いま載っているレイヤーだけで格子を組み直す（現行）</li>
 *   <li><b>積み上げ</b>＝これまでに見えたレイヤーを全部合わせて組む</li>
 * </ul>
 */
@Tag("bench")
class LayerChurnProbeTest {

    private static final int WINDOW = 160;

    /** 実機で見たレイヤーの振れ方を模したもの。深い層（溶岩の海）が載ったり落ちたりする。 */
    private static final List<int[]> CHURN = List.of(
            new int[] {4, 5},
            new int[] {2, 3},
            new int[] {4, 5, 6},
            new int[] {3},
            new int[] {2, 3, 4},
            new int[] {5, 6});

    /** これだけ歩いたら3D粗層を組み直す（実機は15秒間隔＝おおむねこの距離）。 */
    private static final int REBUILD_EVERY_BLOCKS = 64;

    private static int[] union(List<int[]> sets, int upTo) {
        return sets.subList(0, upTo + 1).stream().flatMapToInt(Arrays::stream).distinct().sorted().toArray();
    }

    /** 箱を固定したまま、レイヤーの集合だけを変えた3D粗層のガイド。 */
    private static List<FarField> fields(FakeCells cells, SearchBounds box, BlockPos goal,
                                          List<int[]> sets, boolean accumulate) {
        List<FarField> out = new ArrayList<>();
        for (int i = 0; i < sets.size(); i++) {
            int[] layers = accumulate ? union(sets, i) : sets.get(i);
            VoxelTerrain terrain = VoxelTerrain.of(box, true);
            XaeroMapModel.fill(terrain, cells, layers, 1.0, 20260918L);
            VoxelCostToGo voxel = VoxelCostToGo.build(terrain, goal, () -> false);
            out.add(voxel == null ? FarField.of((x, y, z) -> 0.0)
                    : FarField.of((x, y, z) -> 1.3 * voxel.estimate(x, y, z)));
        }
        return out;
    }

    /** 歩いた距離で{@code far}を切り替える、実機と同じ形のガイド。 */
    private static Function<BlockPos, CostToGo> guide(FakeCells cells, BlockPos start, BlockPos goal,
                                                       List<FarField> fields) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        BlockPos[] last = {start};
        CostToGo[] cached = {null};
        double[] walked = {0};
        int[] switches = {0};
        return player -> {
            if (cached[0] == null || !player.equals(last[0])) {
                walked[0] += ProgressiveWalk.horizontal(last[0], player);
                int index = Math.min(fields.size() - 1, (int) (walked[0] / REBUILD_EVERY_BLOCKS));
                switches[0] = index;
                CellSource window = new WindowedCells(cells, player, WINDOW);
                cached[0] = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), fields.get(index),
                        ForkJoinPool.commonPool(), Runtime.getRuntime().availableProcessors(),
                        () -> false).field();
                last[0] = player;
            }
            return cached[0];
        };
    }

    @Test
    void accumulatingTheLayersSteadiesTheRoute() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain().maxLavaBridgeRunBlocks(30);
        List<BlockPos[]> routes = List.of(
                new BlockPos[] {new BlockPos(-222, 64, 356), new BlockPos(-335, 65, 706)},
                new BlockPos[] {new BlockPos(-120, 64, 380), new BlockPos(-335, 65, 706)},
                new BlockPos[] {new BlockPos(-40, 64, 380), new BlockPos(-335, 65, 706)});
        for (BlockPos[] route : routes) {
            BlockPos start = StanceFinder.resolveStart(cells, route[0]);
            BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
            double best = new AStarPathfinder(cells, new SearchLimits(20_000_000, 600_000, 1.0))
                    .search(start, goal, () -> false).steps().stream().mapToDouble(PathStep::cost).sum();
            // 箱は本番と同じ2段構え（全レイヤー＝積み上げが落ち着いたあとの箱）
            VoxelTerrain settled = XaeroMapModel.grid(cells, start, goal,
                    XaeroMapModel.height(0, 127), null, 1.0, 20260918L);
            SearchBounds box = settled.box();
            System.out.printf(Locale.ROOT, "%n== %s → %s 最適%.0f 箱Y=%d..%d 辺=%d ==%n",
                    start.toShortString(), goal.toShortString(), best, box.minY(), box.maxY(),
                    settled.cellBlocks());
            for (boolean accumulate : new boolean[] {false, true}) {
                List<FarField> fields = fields(cells, box, goal, CHURN, accumulate);
                ProgressiveWalk.Trace walk = ProgressiveWalk.trace(cells, start, goal, WINDOW,
                        ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL,
                        guide(cells, start, goal, fields), 1.0);
                double cost = walk.steps().isEmpty() ? Double.POSITIVE_INFINITY
                        : ProgressiveWalk.cost(walk.steps());
                double closest = Double.MAX_VALUE;
                double backtrack = 0;
                for (PathStep step : walk.steps()) {
                    double distance = ProgressiveWalk.horizontal(step.pos(), goal);
                    closest = Math.min(closest, distance);
                    backtrack = Math.max(backtrack, distance - closest);
                }
                System.out.printf(Locale.ROOT,
                        "  %-8s 歩き%6.0f(%.3f倍) 最大後退%3.0f 描き変わり%d回(%.0fブロック分, 足元%d回) %s%n",
                        accumulate ? "積み上げ" : "そのつど", cost, cost / best, backtrack,
                        walk.redraws(), walk.redrawnBlocks(), walk.nearRedraws(), walk.stopped());
            }
        }
    }
}
