package net.prason.xaeronav.pathfinding.coarse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 3D粗層（{@link VoxelCostToGo}）の見積もりが真値からどうずれるかを、ずれの原因ごとに分けて測る。
 *
 * <p>真値は、地形全体を1つの窓で覆った航法グラフの値。3D粗層の表を下った道筋をセルごとにたどり、隣り合う2点の間で
 * 「真値の差 − 表の差」を足していくと、始点での「真値 − 表」が区間ごとの寄与に分かれる（途中は打ち消し合う）。
 * 区間を「床から床へ上る・下る・平ら」「床の無いセルを渡る」に分けて合計する。
 *
 * <p>{@code ./gradlew --offline :1.21.1-neoforge:bench --tests '*VoxelEstimateErrorBenchTest' -Pxaeronav.heap=8g}（全部で約2分）。
 * {@code -Pxaeronav.navGraphVerbose=true}で差の大きい区間を座標つきで出す。
 * {@code -Pxaeronav.alongPoints="罠:3,78,345 45,93,380"}で、歩いた道筋などの点の比を並べる（{@code ;}区切りで複数）。
 */
@Tag("bench")
class VoxelEstimateErrorBenchTest {

    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 127;
    private static final int SAMPLES = 400;

    private static FakeCells load(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(0).maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30));
    }

    @Test
    void trap() throws IOException {
        FakeCells cells = load("/nether_trap.txt.gz");
        measure("罠", cells, new BlockPos(-12, 64, 349), new BlockPos(-53, 68, 716), List.of(
                new BlockPos(-65, 47, 521), new BlockPos(72, 69, 439), new BlockPos(-12, 44, 483),
                new BlockPos(-2, 59, 444), new BlockPos(-12, 64, 349), new BlockPos(-99, 90, 559),
                new BlockPos(-43, 66, 618)));
    }

    @Test
    void lavaSea() throws IOException {
        FakeCells cells = load("/nether_wide.txt.gz");
        measure("溶岩の海", cells, new BlockPos(-212, 48, 553), new BlockPos(-333, 59, 694), List.of(
                new BlockPos(-212, 48, 553), new BlockPos(-271, 64, 395), new BlockPos(-261, 66, 448)));
    }

    /** 溶岩の海と同じ地形の、別の目的地2つ（ネザー4本の目的地）。上の2つに合わせすぎていないかを見る。 */
    @Test
    void wide() throws IOException {
        FakeCells cells = load("/nether_wide.txt.gz");
        measure("広域(北の目的地)", cells, new BlockPos(-447, 74, 525), new BlockPos(-259, 65, 379), List.of());
        measure("広域(西の目的地)", cells, new BlockPos(-505, 71, 836), new BlockPos(-538, 67, 496), List.of());
    }

    private static void measure(String name, FakeCells cells, BlockPos rawStart, BlockPos rawGoal, List<BlockPos> points) {
        BlockPos goal = StanceFinder.resolveGoal(cells, rawGoal);
        BlockPos start = StanceFinder.resolveStart(cells, rawStart);
        SearchBounds world = cells.bounds();
        int centerX = (world.minX() + world.maxX()) / 2;
        int centerZ = (world.minZ() + world.maxZ()) / 2;
        int radius = Math.max(world.maxX() - world.minX(), world.maxZ() - world.minZ()) / 2 + 40;
        long began = System.currentTimeMillis();
        WindowedCells window = new WindowedCells(cells, new BlockPos(centerX, 64, centerZ), radius);
        WindowField truth = new NavGraph(goal, world.minY(), world.maxY()).refresh(() -> window, centerX, centerZ,
                radius, LoadedArea.square(centerX, centerZ, radius), FarField.of((x, y, z) -> 0.0),
                ForkJoinPool.commonPool(), Runtime.getRuntime().availableProcessors(), () -> false).field();
        System.out.printf(Locale.ROOT, "%s: 真値の窓 中心%d,%d 半径%d %dms%n", name, centerX, centerZ, radius,
                System.currentTimeMillis() - began);

        VoxelTerrain terrain = VoxelTerrain.of(XaeroMapModel.guideBox(start, goal, NETHER_MIN_Y, NETHER_MAX_Y), true);
        XaeroMapModel.fill(terrain, cells);
        VoxelCostToGo voxel = VoxelCostToGo.build(terrain, goal, () -> false);
        Probe probe = new Probe(terrain, voxel, truth);
        System.out.printf(Locale.ROOT, "  3D粗層 辺%d %s 届いた%d/%d%n", terrain.cellBlocks(), terrain.breakdown(),
                voxel.reachableCells(), voxel.cellCount());

        for (BlockPos point : points) {
            BlockPos at = StanceFinder.resolveStart(cells, point);
            Breakdown one = new Breakdown();
            one.verbose = Boolean.getBoolean("xaeronav.navGraphVerbose");
            probe.decompose(at, one);
            System.out.printf(Locale.ROOT, "  %s 真%.0f 表%.0f 比%.2f  %s%n", at.toShortString(), truth.exact(at.getX(), at.getY(), at.getZ()),
                    probe.value(at), probe.value(at) / truth.exact(at.getX(), at.getY(), at.getZ()), one);
        }

        // 始点からの最適な道筋の上で、表/真の比がどう動くか。窓の外の推定が道筋の途中で安く見える回廊へ
        // 引かれるなら、ここで比が下がる区間がある
        List<BlockPos> optimal = new ArrayList<>();
        truth.descend(start.getX(), start.getY(), start.getZ(), (x, y, z) -> optimal.add(new BlockPos(x, y, z)));
        StringBuilder along = new StringBuilder();
        BlockPos last = null;
        for (BlockPos at : optimal) {
            if (last == null || Math.max(Math.abs(at.getX() - last.getX()), Math.abs(at.getZ() - last.getZ())) >= 24) {
                along.append(String.format(Locale.ROOT, " %d,%d,%d=%.2f", at.getX(), at.getY(), at.getZ(),
                        probe.value(at) / truth.exact(at.getX(), at.getY(), at.getZ())));
                last = at;
            }
        }
        System.out.printf(Locale.ROOT, "  最適な道筋の比:%s%n", along);
        for (String spec : System.getProperty("xaeronav.alongPoints", "").split(";")) {
            if (spec.isBlank() || !spec.startsWith(name + ":")) {
                continue;
            }
            StringBuilder path = new StringBuilder();
            for (String token : spec.substring(name.length() + 1).split(" ")) {
                String[] xyz = token.split(",");
                BlockPos at = StanceFinder.resolveStart(cells,
                        new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
                double exact = truth.exact(at.getX(), at.getY(), at.getZ());
                path.append(String.format(Locale.ROOT, " %s 真%.0f 比%.2f", at.toShortString(), exact, probe.value(at) / exact));
            }
            System.out.printf(Locale.ROOT, "  指定の道筋の比:%s%n", path);
        }

        List<BlockPos> nodes = new ArrayList<>();
        for (int x = world.minX(); x <= world.maxX(); x += 4) {
            for (int z = world.minZ(); z <= world.maxZ(); z += 4) {
                for (int y = world.minY(); y <= world.maxY(); y++) {
                    if (terrain.contains(x, y, z) && Double.isFinite(truth.exact(x, y, z))) {
                        nodes.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        Random random = new Random(1L);
        Breakdown all = new Breakdown();
        List<Double> ratios = new ArrayList<>();
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < SAMPLES && !nodes.isEmpty(); i++) {
            BlockPos at = nodes.get(random.nextInt(nodes.size()));
            double exact = truth.exact(at.getX(), at.getY(), at.getZ());
            if (exact < 200) {
                continue;
            }
            ratios.add(probe.value(at) / exact);
            pairs.add(new double[] {exact, probe.value(at)});
            probe.decompose(at, all);
        }
        ratios.sort(Double::compare);
        // 同じ真値の差がある2点を、表が同じ順に並べるか。窓の外の分かれ道で効くのはこの順序だけ
        int agree = 0;
        int compared = 0;
        for (int a = 0; a < pairs.size(); a++) {
            for (int b = a + 1; b < pairs.size(); b++) {
                double trueGap = pairs.get(a)[0] - pairs.get(b)[0];
                if (Math.abs(trueGap) < 300) {
                    continue;
                }
                compared++;
                if (Math.signum(trueGap) == Math.signum(pairs.get(a)[1] - pairs.get(b)[1])) {
                    agree++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "  無作為%d点 表/真 p10=%.2f p50=%.2f p90=%.2f 幅p90/p10=%.2f 順序一致(真の差300超)=%.3f%n",
                ratios.size(), ratios.get(ratios.size() / 10), ratios.get(ratios.size() / 2),
                ratios.get(ratios.size() * 9 / 10), ratios.get(ratios.size() * 9 / 10) / ratios.get(ratios.size() / 10),
                (double) agree / compared);
        System.out.printf(Locale.ROOT, "  内訳（真−表の合計、正＝表が安すぎる）%s%n", all);
    }

    /** 区間の種類ごとの「真値の差 − 表の差」の合計と、区間の数。 */
    private static final class Breakdown {
        private final Map<String, double[]> sums = new TreeMap<>();
        boolean verbose;

        void add(String kind, double delta) {
            double[] slot = sums.computeIfAbsent(kind, key -> new double[2]);
            slot[0] += delta;
            slot[1]++;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            sums.forEach((kind, slot) -> text.append(String.format(Locale.ROOT, " %s=%.0f(%d)", kind, slot[0], (int) slot[1])));
            return text.toString();
        }
    }

    private static final class Probe {
        private final VoxelTerrain terrain;
        private final VoxelCostToGo voxel;
        private final WindowField truth;
        private final double slack;
        private final double openRate;

        Probe(VoxelTerrain terrain, VoxelCostToGo voxel, WindowField truth) {
            this.terrain = terrain;
            this.voxel = voxel;
            this.truth = truth;
            this.slack = terrain.cellBlocks() * Math.sqrt(3.0) * ActionCosts.SPRINT_ONE_BLOCK;
            this.openRate = ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS + ActionCosts.SPRINT_ONE_BLOCK;
        }

        /** 表の生の値（{@link VoxelCostToGo#estimate}は{@code slack}を引いてある）。 */
        double value(BlockPos at) {
            return voxel.estimate(at.getX(), at.getY(), at.getZ()) + slack;
        }

        private double cellCost(int index) {
            int x = terrain.box().minX() + terrain.cellX(index) * terrain.cellBlocks();
            int y = terrain.box().minY() + terrain.cellY(index) * terrain.cellBlocks();
            int z = terrain.box().minZ() + terrain.cellZ(index) * terrain.cellBlocks();
            double estimate = voxel.estimate(x, y, z);
            return estimate > 0 ? estimate + slack : Double.NaN;
        }

        /** セルの中の航法グラフのノードのうち、いちばん安い真値。1つも無ければNaN。 */
        private double cellTruth(int index) {
            int x0 = terrain.box().minX() + terrain.cellX(index) * terrain.cellBlocks();
            int y0 = terrain.box().minY() + terrain.cellY(index) * terrain.cellBlocks();
            int z0 = terrain.box().minZ() + terrain.cellZ(index) * terrain.cellBlocks();
            double best = Double.NaN;
            for (int x = x0; x < x0 + terrain.cellBlocks(); x++) {
                for (int y = y0; y < y0 + terrain.cellBlocks(); y++) {
                    for (int z = z0; z < z0 + terrain.cellBlocks(); z++) {
                        double exact = truth.exact(x, y, z);
                        if (Double.isFinite(exact) && !(exact >= best)) {
                            best = exact;
                        }
                    }
                }
            }
            return best;
        }

        /** 逆向きDijkstraでこのセルへ値を渡したセル。表が尽きた（目的地の近く）なら-1。 */
        private int parent(int index) {
            double here = cellCost(index);
            if (Double.isNaN(here)) {
                return -1;
            }
            double rate = terrain.kindAt(index) == VoxelTerrain.STANDABLE ? ActionCosts.SPRINT_ONE_BLOCK : openRate;
            int i = terrain.cellX(index);
            int j = terrain.cellY(index);
            int k = terrain.cellZ(index);
            int best = -1;
            double bestGap = Double.POSITIVE_INFINITY;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    for (int dk = -1; dk <= 1; dk++) {
                        int ni = i + di;
                        int nj = j + dj;
                        int nk = k + dk;
                        if ((di | dj | dk) == 0 || ni < 0 || nj < 0 || nk < 0
                                || ni >= terrain.nx() || nj >= terrain.ny() || nk >= terrain.nz()) {
                            continue;
                        }
                        int neighbor = terrain.index(ni, nj, nk);
                        double there = cellCost(neighbor);
                        if (Double.isNaN(there)) {
                            continue;
                        }
                        double edge = terrain.cellBlocks() * Math.sqrt(di * di + dj * dj + dk * dk) * rate;
                        double gap = Math.abs(there + edge - here);
                        if (gap < bestGap) {
                            bestGap = gap;
                            best = neighbor;
                        }
                    }
                }
            }
            return bestGap < 1e-6 ? best : -1;
        }

        private String corner(int index) {
            return (terrain.box().minX() + terrain.cellX(index) * terrain.cellBlocks()) + ","
                    + (terrain.box().minY() + terrain.cellY(index) * terrain.cellBlocks()) + ","
                    + (terrain.box().minZ() + terrain.cellZ(index) * terrain.cellBlocks());
        }

        void decompose(BlockPos at, Breakdown out) {
            int cell = terrain.indexOfBlock(at.getX(), at.getY(), at.getZ());
            double atTruth = truth.exact(at.getX(), at.getY(), at.getZ());
            double atValue = value(at);
            int anchor = cell;
            double anchorTruth = atTruth;
            double anchorValue = atValue;
            boolean crossedOpen = false;
            int guard = 0;
            for (int next = parent(cell); next >= 0 && guard < 10_000; next = parent(next), guard++) {
                crossedOpen |= terrain.kindAt(next) != VoxelTerrain.STANDABLE;
                double nextTruth = cellTruth(next);
                if (Double.isNaN(nextTruth)) {
                    continue;
                }
                double nextValue = cellCost(next);
                String kind;
                if (crossedOpen) {
                    kind = "床無しを渡る";
                } else {
                    int dy = terrain.cellY(next) - terrain.cellY(anchor);
                    boolean adjacent = Math.abs(terrain.cellX(next) - terrain.cellX(anchor)) <= 1
                            && Math.abs(terrain.cellZ(next) - terrain.cellZ(anchor)) <= 1 && Math.abs(dy) <= 1;
                    kind = !adjacent ? "床→床(離れ)" : dy > 0 ? "床→床(上り)" : dy < 0 ? "床→床(下り)" : "床→床(平ら)";
                }
                double delta = (anchorTruth - nextTruth) - (anchorValue - nextValue);
                if (out.verbose && Math.abs(delta) > 25) {
                    System.out.printf(Locale.ROOT, "      %s %s→%s 真%.0f→%.0f 表%.0f→%.0f 差%.0f%n", kind, corner(anchor),
                            corner(next), anchorTruth, nextTruth, anchorValue, nextValue, delta);
                }
                out.add(kind, delta);
                anchor = next;
                anchorTruth = nextTruth;
                anchorValue = nextValue;
                crossedOpen = false;
            }
            out.add("終点の残り", anchorTruth - anchorValue);
        }
    }
}
