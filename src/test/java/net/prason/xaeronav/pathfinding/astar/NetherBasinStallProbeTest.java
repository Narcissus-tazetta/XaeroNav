package net.prason.xaeronav.pathfinding.astar;

import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 測定用プローブ（アサート無し）。実機run#2（2026-09-09）の停止地点から単発A\*を撃って、
 * 何手・どの向きへ出るかを print する。
 *
 * <p>分かったこと（[[xaeronav-nether-3d-realmap-failure]] の「3回目のセッション」）:
 * <ul>
 *   <li>{@code (-341,24,601)} は溶岩の柱の中で後継ゼロ＝EXHAUSTED 展開=1。A\*は正しい。</li>
 *   <li>{@code (-323,34,520)} の実機の「ステップ=0」は<b>オフラインでは再現しない</b>——
 *       ここは w=1.5 でも30手・目的地方向へ30ブロック出る。</li>
 *   <li>この地形は溶岩の大洋。溶岩際の床を割増する fix は貪欲重みで悪化した。</li>
 * </ul>
 */
@Tag("slow")
class NetherBasinStallProbeTest {

    private static final BlockPos START = new BlockPos(-328, 64, 696);
    private static final BlockPos GOAL = new BlockPos(-259, 64, 379);
    private static final int[] LAYERS = {4};
    private static final double VISITED = 0.68;
    private static final int WINDOW = 240;

    private static FakeCells terrain() throws Exception {
        return TerrainFixture.load("/nether_wide.txt.gz", b -> FakeCells.empty(b)
                .canPlaceBlocks(true).maxFallDamagePoints(0).fatalFallBlocks(23)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .avoidRiskyJumps(true).boatAvailable(true)
                .minDescentTicksPerBlock(ActionCosts.descentBoundForMaxDrop(3)));
    }

    private void probe(String label, FakeCells cells, VoxelCostToGo guide, BlockPos from) {
        SearchBounds box = ProgressiveWalk.searchBox(cells, from, GOAL, WINDOW);
        WindowedCells view = new WindowedCells(cells, from, WINDOW, box);
        for (double weight : new double[] {AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, 2.5, 3.0}) {
            PathResult r = new AStarPathfinder(view, new SearchLimits(100_000, 30_000, weight), guide)
                    .search(from, GOAL, () -> false, 0);
            BlockPos end = r.steps().isEmpty() ? from : r.steps().get(r.steps().size() - 1).pos();
            System.out.printf(Locale.ROOT,
                    "%-26s w=%.1f -> %s 展開=%d ステップ=%d 末端=%s guide.est=%.0f%n",
                    label, weight, r.termination(), r.expandedNodes(), r.steps().size(),
                    end.toShortString(),
                    guide.estimate(from.getX(), from.getY(), from.getZ()));
        }
    }

    private static VoxelCostToGo thinGuide(FakeCells cells, BlockPos start, BlockPos goal) {
        VoxelTerrain grid = VoxelTerrain.of(
                XaeroMapModel.guideBox(start, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                        NetherLiveWalkTest.NETHER_MAX_Y), true);
        XaeroMapModel.fill(grid, cells, LAYERS, VISITED, 1L);
        return VoxelCostToGo.build(grid, goal, () -> false);
    }

    @Test
    void printsWhatEachStopSpotDoesOffline() throws Exception {
        FakeCells cells = terrain();
        VoxelCostToGo original = thinGuide(cells, START, GOAL);

        probe("y64 始点(正常確認)", cells, original, START);
        probe("y34 (-323,520) 元ガイド", cells, original, new BlockPos(-323, 34, 520));
        probe("y24 (-341,601) 元ガイド", cells, original, new BlockPos(-341, 24, 601));

        // 実機は詰まると player を中心に組み直す
        VoxelCostToGo rebuilt = thinGuide(cells, new BlockPos(-323, 34, 520), GOAL);
        probe("y34 (-323,520) 再組み", cells, rebuilt, new BlockPos(-323, 34, 520));
    }
}
