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
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 実機のネザー（2026-09-23）で経路が(-65,47,521)の行き止まりへ入っては東へ引き返した件を、実機の保存から書き出した地形で測る。
 * 窓の外の推定（3D粗層×倍率）の倍率を固定で振った場合と、組み直しのたびに窓の中の値から自己較正した場合を比べる。
 */
@Tag("bench")
class NetherTrapBenchTest {

    private static final int WINDOW = 160;
    private static final BlockPos GOAL = new BlockPos(-53, Integer.getInteger("xaeronav.goalY", 68), 716);
    private static final BlockPos TRAP = new BlockPos(-65, 47, 521);
    private static final List<BlockPos> STARTS = List.of(new BlockPos(-12, 64, 349), new BlockPos(72, 69, 439),
            new BlockPos(131, 39, 652));

    private static FakeCells cells() throws IOException {
        return TerrainFixture.load("/nether_trap.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(0).maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30));
    }

    /**
     * 窓の中で実際に辿った値と、同じ点の推定（倍率を掛ける前）の比の中央値。推定の尺度を窓の中へ揃える倍率になる。
     * 縁の近くは値が推定から来るので除く（{@link WindowField#measuredInWindow}）。
     */
    static double calibrate(FakeCells cells, WindowField field, CostToGo raw, BlockPos center) {
        List<Double> ratios = new ArrayList<>();
        for (int x = center.getX() - WINDOW; x <= center.getX() + WINDOW; x += 4) {
            for (int z = center.getZ() - WINDOW; z <= center.getZ() + WINDOW; z += 4) {
                if (!field.measuredInWindow(x, z)) {
                    continue;
                }
                for (int y = cells.bounds().minY() + 1; y < cells.bounds().maxY(); y++) {
                    double exact = field.exact(x, y, z);
                    double estimate = raw.estimate(x, y, z);
                    if (!Double.isNaN(exact) && estimate > 1) {
                        ratios.add(exact / estimate);
                    }
                }
            }
        }
        if (ratios.isEmpty()) {
            return Double.NaN;
        }
        ratios.sort(Double::compare);
        return ratios.get(ratios.size() / 2);
    }

    /** {@code scale}が正なら固定倍率、0なら組み直しのたびに自己較正（初期値1.3、前回の倍率から1回だけ更新）。 */
    private static Function<BlockPos, CostToGo> guide(FakeCells cells, BlockPos goal, CostToGo raw, double scale,
                                                      List<Double> used) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        double[] k = {scale > 0 ? scale : 1.3};
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        return player -> {
            if (last[0] == null || Math.max(Math.abs(player.getX() - last[0].getX()),
                    Math.abs(player.getZ() - last[0].getZ())) >= 24) {
                WindowedCells window = new WindowedCells(cells, player, WINDOW);
                double current = k[0];
                FarField far = FarField.of((x, y, z) -> current * raw.estimate(x, y, z));
                WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                        LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(),
                        Runtime.getRuntime().availableProcessors(), () -> false).field();
                used.add(current);
                if (scale <= 0) {
                    double measured = calibrate(cells, field, raw, player);
                    if (Double.isFinite(measured)) {
                        k[0] = Math.max(1.0, measured);
                    }
                }
                cached[0] = field;
                last[0] = player;
            }
            return cached[0];
        };
    }

    @Test
    void walkWithFarScales() throws IOException {
        FakeCells cells = cells();
        BlockPos goal = StanceFinder.resolveGoal(cells, GOAL);
        String scales = System.getProperty("xaeronav.farScales", "1.3,2.0,0");
        for (BlockPos rawStart : STARTS) {
            BlockPos start = StanceFinder.resolveStart(cells, rawStart);
            CostToGo raw = XaeroMapModel.guide(cells, start, goal, NetherLiveWalkTest.NETHER_MIN_Y,
                    NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L);
            for (String token : scales.split(",")) {
                double scale = Double.parseDouble(token);
                List<Double> used = new ArrayList<>();
                long began = System.currentTimeMillis();
                ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, start, goal, WINDOW,
                        ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide(cells, goal, raw, scale, used),
                        1.0);
                double trapDistance = Double.POSITIVE_INFINITY;
                double worstRetreat = 0;
                double closest = Double.POSITIVE_INFINITY;
                for (PathStep step : trace.steps()) {
                    BlockPos p = step.pos();
                    trapDistance = Math.min(trapDistance, Math.sqrt(p.distSqr(TRAP)));
                    double left = Math.hypot(p.getX() - goal.getX(), p.getZ() - goal.getZ());
                    closest = Math.min(closest, left);
                    worstRetreat = Math.max(worstRetreat, left - closest);
                }
                BlockPos end = trace.steps().isEmpty() ? start : trace.steps().get(trace.steps().size() - 1).pos();
                System.out.printf(Locale.ROOT,
                        "始点%s 倍率=%s 実費=%.0f tick 到達=%s 罠まで最接近=%.0f 最大の後退=%.0f 描き変わり%d 倍率の推移=%s %ds %s%n",
                        start.toShortString(), scale > 0 ? token : "自己較正",
                        trace.steps().isEmpty() ? Double.NaN : ProgressiveWalk.cost(trace.steps()),
                        end.closerThan(goal, 3), trapDistance, worstRetreat, trace.redraws(),
                        summarize(used), (System.currentTimeMillis() - began) / 1000, trace.stopped());
            }
        }
    }

    private static String summarize(List<Double> used) {
        if (used.isEmpty()) {
            return "-";
        }
        double min = used.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = used.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return String.format(Locale.ROOT, "%.2f〜%.2f(%d回)", min, max, used.size());
    }
}
