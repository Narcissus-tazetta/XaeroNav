package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.ArrayCells;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 目的地を決めた直後の、航法グラフの初回の組み立て（構築＋ガイド）を実機の保存地形で測る。
 * 地形は{@link ArrayCells}で配列に写す——{@link FakeCells}のままだとセルの読み出しが実機より桁で重く、内訳が実機と合わない。
 *
 * <p>{@code 辺}と{@code 値}は結果のダイジェスト。速さを変える変更の前後でこれが1つでも変わったら、結果を変えている。
 */
@Tag("bench")
class NavGraphFirstBuildBenchTest {

    private static final int WINDOW = 224;
    private static final BlockPos GOAL = new BlockPos(-53, 68, 716);
    private static final BlockPos[] PLAYERS = {new BlockPos(-235, 88, 505), new BlockPos(-193, 50, 559),
            new BlockPos(-152, 65, 521)};

    @Test
    void firstBuild() throws IOException {
        FakeCells cells = NetherTrapBenchTest.cells();
        BlockPos goal = StanceFinder.resolveGoal(cells, GOAL);
        int workers = Runtime.getRuntime().availableProcessors() - 1;
        int rounds = Integer.getInteger("xaeronav.rounds", 3);
        int warmup = Integer.getInteger("xaeronav.warmup", 0);
        if (warmup > 0) {
            // 別の場所・別の目的地で小さな窓を1回組み、JITを温める（本番の結果には触れない）
            BlockPos at = StanceFinder.resolveStart(cells, new BlockPos(-100, 64, 600));
            WindowedCells small = new WindowedCells(new ArrayCells(cells, at, warmup), at, warmup);
            long began = System.currentTimeMillis();
            new NavGraph(at.offset(40, 0, 40), cells.bounds().minY(), cells.bounds().maxY()).refresh(() -> small,
                    at.getX(), at.getZ(), warmup, LoadedArea.square(at.getX(), at.getZ(), warmup),
                    FarField.of((x, y, z) -> 0.0), ForkJoinPool.commonPool(), workers, () -> false);
            System.out.printf(Locale.ROOT, "温め 半径%d %dms%n", warmup, System.currentTimeMillis() - began);
        }
        for (BlockPos raw : PLAYERS) {
            BlockPos player = StanceFinder.resolveStart(cells, raw);
            WindowedCells window = new WindowedCells(new ArrayCells(cells, player, WINDOW), player, WINDOW);
            CostToGo voxel = XaeroMapModel.guide(cells, player, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                    NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L);
            FarField far = FarField.of((x, y, z) -> 1.3 * voxel.estimate(x, y, z));
            for (int round = 0; round < rounds; round++) {
                NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
                NavGraph.Refreshed built = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(),
                        workers, () -> false);
                WindowField field = built.field();
                System.out.printf(Locale.ROOT, "プレイヤー%s 回%d 構築%dms ガイド%dms セクション%d 辺%d ノード%d 値%016x%n",
                        player.toShortString(), round, built.buildMillis(), field.buildMillis(), built.sectionsBuilt(),
                        graph.edgeCount(), field.nodes(), digest(field, player, cells));
            }
        }
    }

    private static long digest(WindowField field, BlockPos player, FakeCells cells) {
        long hash = 1L;
        for (int x = player.getX() - WINDOW; x <= player.getX() + WINDOW; x += 3) {
            for (int z = player.getZ() - WINDOW; z <= player.getZ() + WINDOW; z += 3) {
                for (int y = cells.bounds().minY(); y <= cells.bounds().maxY(); y += 2) {
                    hash = hash * 31 + Double.doubleToLongBits(field.estimate(x, y, z));
                }
            }
        }
        return hash;
    }
}
