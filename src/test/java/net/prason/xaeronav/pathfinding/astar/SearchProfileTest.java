package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>計測専用。番人ではない</b>ので、何かを主張して落ちることはしない（`bench`タスクで明示的に回す）。
 *
 * <p>測るのは3つ。<b>1ノードあたりのセル読み回数と縦走査の割合</b>、
 * <b>重みを振ったときの質と速さの交換比</b>、<b>層1ガイドが展開ノードを減らしているか</b>。
 *
 * <p>結果はファイルにも書く。Gradleのテストは標準出力を握り潰すため。
 */
@Tag("bench")
class SearchProfileTest {

    private static final long SEED = 20260906L;

    /** 時間を測る回数。最小値を採る。 */
    private static final int RUNS = 3;

    /** セル読みを数えるだけの見張り。実装を触らずに済ませるため動的プロキシで包む。 */
    private static final class CellCounter implements InvocationHandler {

        private final CellSource delegate;
        private final LongOpenHashSet columns = new LongOpenHashSet();
        /** 走査の長さ別の本数。添字は floor(log2(長さ))。 */
        private final long[] runsByLength = new long[16];
        private long calls;
        private long runs;
        private long runCalls;
        private int maxRun;
        private int prevX = Integer.MIN_VALUE;
        private int prevY;
        private int prevZ;
        private int runLength;

        CellCounter(CellSource delegate) {
            this.delegate = delegate;
        }

        CellSource view() {
            return (CellSource) Proxy.newProxyInstance(CellSource.class.getClassLoader(),
                    new Class<?>[] {CellSource.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("cell".equals(method.getName()) && args != null && args.length == 3) {
                int x = (Integer) args[0];
                int y = (Integer) args[1];
                int z = (Integer) args[2];
                calls++;
                columns.add(((long) x << 32) ^ (z & 0xffffffffL));
                if (x == prevX && z == prevZ && y == prevY - 1) {
                    runLength++;
                } else {
                    closeRun();
                    runLength = 1;
                }
                prevX = x;
                prevY = y;
                prevZ = z;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void closeRun() {
            if (runLength >= 2) {
                runs++;
                runCalls += runLength;
                maxRun = Math.max(maxRun, runLength);
                runsByLength[31 - Integer.numberOfLeadingZeros(runLength)]++;
            }
        }

        String report(int expandedNodes) {
            closeRun();
            runLength = 0;
            StringBuilder histogram = new StringBuilder();
            for (int i = 1; i < runsByLength.length; i++) {
                if (runsByLength[i] > 0) {
                    histogram.append(String.format(Locale.ROOT, " %d〜%d:%d",
                            1 << i, (1 << (i + 1)) - 1, runsByLength[i]));
                }
            }
            // 列ごとの索引を1つ作れば、1本の縦走査は1回の問い合わせで済む。
            // つまり (走査に使った読み - 走査の本数) が消せる読みの上限になる
            long removable = runCalls - runs;
            return String.format(Locale.ROOT,
                    "    セル読み %,d (%.0f回/ノード)  縦走査 %,d本 %,d回 (全読みの%.0f%%) 最長%d"
                            + "%n    消せる読みの上限 %,d回 (全体の%.0f%%)  触れた列 %,d"
                            + "%n    走査長の分布:%s",
                    calls, calls / (double) Math.max(1, expandedNodes),
                    runs, runCalls, 100.0 * runCalls / Math.max(1, calls), maxRun,
                    removable, 100.0 * removable / Math.max(1, calls), columns.size(),
                    histogram.isEmpty() ? " (2以上の走査なし)" : histogram.toString());
        }
    }

    private record Scenario(String name, FakeCellsFactory terrain, BlockPos start, BlockPos goal,
                            SearchLimits limits) {
    }

    @FunctionalInterface
    private interface FakeCellsFactory {
        FakeCells create() throws IOException;
    }

    private static FakeCells overworldWide() throws IOException {
        return TerrainFixture.load("/overworld_wide.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    private static FakeCells mountains() throws IOException {
        return TerrainFixture.load("/overworld_mountains.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    /** {@code NetherLavaSeaTest}と同じ条件。 */
    private static FakeCells netherLavaSea() throws IOException {
        return TerrainFixture.load("/nether_lava_sea.txt.gz", bounds -> FakeCells.empty(bounds)
                .bounds(new SearchBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                        bounds.maxX(), 128, bounds.maxZ()))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(6));
    }

    /** {@code RealEndTerrainTest}と同じ条件。 */
    private static FakeCells endVoid() throws IOException {
        return TerrainFixture.load("/end_terrain_columns.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .placedBlockBudget(0)
                .maxFallDamagePoints(6));
    }

    private static PathResult run(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                  boolean guide) {
        try {
            return new PathfindingExecutor().submit(view, start, goal, limits, guide, 0).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Scenario> scenarios() throws IOException {
        List<Scenario> scenarios = new ArrayList<>();
        BlockPos[] wide = TerrainFixture.randomRoutes(overworldWide(), overworldWide().bounds(),
                SEED, 1, 140, 160).get(0);
        scenarios.add(new Scenario("現世・海と陸(" + (int) horizontal(wide[0], wide[1]) + "ブロック)",
                SearchProfileTest::overworldWide, wide[0], wide[1],
                new SearchLimits(300_000, 600_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT)));
        BlockPos[] mountain = TerrainFixture.randomRoutes(mountains(), mountains().bounds(),
                SEED, 1, 100, 130).get(0);
        scenarios.add(new Scenario("現世・山岳(" + (int) horizontal(mountain[0], mountain[1]) + "ブロック)",
                SearchProfileTest::mountains, mountain[0], mountain[1],
                new SearchLimits(300_000, 600_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT)));
        scenarios.add(new Scenario("ネザー・溶岩の海(深い予算)", SearchProfileTest::netherLavaSea,
                new BlockPos(-289, 72, 525), new BlockPos(-296, 57, 584),
                new SearchLimits(800_000, 600_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT)));
        scenarios.add(new Scenario("エンド・奈落越え", SearchProfileTest::endVoid,
                new BlockPos(1233, 57, 1142), new BlockPos(1288, 57, 1080),
                new SearchLimits(250_000, 600_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT)));
        return scenarios;
    }

    @Test
    void whereTheTimeGoes() throws IOException {
        List<String> report = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            // 1本目はJITが温まっておらず、同じ探索で3〜7倍遅く出る。最小値で比べる
            PathResult result = null;
            double millis = Double.MAX_VALUE;
            for (int attempt = 0; attempt < RUNS; attempt++) {
                FakeCells timed = scenario.terrain().create();
                long began = System.nanoTime();
                result = run(timed, scenario.start(), scenario.goal(), scenario.limits(), true);
                millis = Math.min(millis, (System.nanoTime() - began) / 1e6);
            }

            FakeCells counted = scenario.terrain().create();
            CellCounter counter = new CellCounter(counted);
            PathResult countedResult = run(counter.view(), scenario.start(), scenario.goal(),
                    scenario.limits(), true);

            report.add(String.format(Locale.ROOT,
                    "%n■ %s%n    %s %d手 展開%,dノード %.0fms (%,.0fノード/秒)%s",
                    scenario.name(), result.termination(), result.steps().size(),
                    result.expandedNodes(), millis,
                    result.expandedNodes() / (millis / 1000.0),
                    countedResult.expandedNodes() == result.expandedNodes() ? ""
                            : String.format(Locale.ROOT, "  ※計数側は%,dノード（比較不可）",
                                    countedResult.expandedNodes())));
            report.add(counter.report(countedResult.expandedNodes()));
        }
        emit("profile-hotspots.txt", String.join("\n", report));
    }

    /** 実機の読み込み済みの窓（描画距離10チャンク相当）に合わせた見え方。 */
    private static final int WINDOW_RADIUS = 160;

    private static CellSource windowed(FakeCells all, BlockPos from, BlockPos to) {
        return new net.prason.xaeronav.pathfinding.world.WindowedCells(all, from, WINDOW_RADIUS,
                ProgressiveWalk.searchBox(all, from, to, WINDOW_RADIUS));
    }

    /**
     * 緩和の梯子を挟まない生のA*。{@code PathfindingExecutor}経由だと、重みを変えたときに
     * 別の段（貪欲な再挑戦）の結果が返ってきて、重みと質の対応が測れない。
     */
    private static PathResult raw(CellSource view, BlockPos start, BlockPos goal, double weight,
                                   CostToGo guide) {
        SearchLimits limits = new SearchLimits(2_000_000, 600_000, weight);
        AStarPathfinder finder = new AStarPathfinder(view, limits, guide);
        return finder.search(
                net.prason.xaeronav.pathfinding.world.StanceFinder.resolveStart(view, start),
                net.prason.xaeronav.pathfinding.world.StanceFinder.resolveGoal(view, goal),
                () -> false, Carryover.NONE, 0);
    }

    @Test
    void qualityAgainstSpeed() throws IOException {
        double[] weights = {1.0, 1.1, 1.25, 1.5, 1.75, 2.0};
        List<String> report = new ArrayList<>();
        FakeCells probe = overworldWide();
        List<BlockPos[]> routes = TerrainFixture.randomRoutes(probe, probe.bounds(), SEED, 4, 100, 160);
        for (BlockPos[] route : routes) {
            report.add(String.format(Locale.ROOT, "%n■ %s→%s (%.0fブロック)",
                    route[0].toShortString(), route[1].toShortString(),
                    horizontal(route[0], route[1])));
            double best = Double.NaN;
            for (double weight : weights) {
                CellSource view = windowed(overworldWide(), route[0], route[1]);
                long began = System.nanoTime();
                PathResult result = raw(view, route[0], route[1], weight, null);
                double millis = (System.nanoTime() - began) / 1e6;
                double cost = ProgressiveWalk.cost(result.steps());
                if (Double.isNaN(best)) {
                    best = cost;
                }
                report.add(String.format(Locale.ROOT,
                        "    重み%.2f ガイド無  %-12s コスト%8.0f (%.3f倍) 展開%,8dノード %6.0fms",
                        weight, result.complete() ? "到達" : result.termination().toString(),
                        cost, cost / best, result.expandedNodes(), millis));
            }
            // 層1ガイドの効き目と値段を分けて測る。構築は探索の前に1回走る
            for (double weight : new double[] {1.0, 1.5}) {
                FakeCells cells = overworldWide();
                CellSource view = windowed(cells, route[0], route[1]);
                BlockPos resolvedStart =
                        net.prason.xaeronav.pathfinding.world.StanceFinder.resolveStart(view, route[0]);
                long guideBegan = System.nanoTime();
                net.prason.xaeronav.pathfinding.coarse.CoarseMap coarseMap =
                        net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler.sample(
                                view, view.bounds(), resolvedStart.getY(), () -> false);
                CostToGo guide = net.prason.xaeronav.pathfinding.coarse.CoarseRouter.costToGo(coarseMap,
                        route[1], false,
                        view.lavaBridgingEnabled()
                                ? net.prason.xaeronav.pathfinding.coarse.CoarseRouter.BridgePolicy.BRIDGE
                                : net.prason.xaeronav.pathfinding.coarse.CoarseRouter.BridgePolicy.ALLOW);
                double guideMillis = (System.nanoTime() - guideBegan) / 1e6;
                long began = System.nanoTime();
                PathResult result = raw(view, route[0], route[1], weight, guide);
                double millis = (System.nanoTime() - began) / 1e6;
                double cost = ProgressiveWalk.cost(result.steps());
                report.add(String.format(Locale.ROOT,
                        "    重み%.2f ガイド有  %-12s コスト%8.0f (%.3f倍) 展開%,8dノード %6.0fms"
                                + " (＋ガイド構築%.0fms)",
                        weight, result.complete() ? "到達" : result.termination().toString(),
                        cost, cost / best, result.expandedNodes(), millis, guideMillis));
            }
        }
        emit("profile-quality-speed.txt", String.join("\n", report));
    }

    /** 層1ガイド（{@code CoarseRouter#costToGo}）が展開ノードを実際に減らしているか。 */
    @Test
    void guideValue() throws IOException {
        List<String> report = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            report.add(String.format(Locale.ROOT, "%n■ %s", scenario.name()));
            for (boolean guide : new boolean[] {false, true}) {
                FakeCells cells = scenario.terrain().create();
                long began = System.nanoTime();
                PathResult result = run(cells, scenario.start(), scenario.goal(), scenario.limits(), guide);
                double millis = (System.nanoTime() - began) / 1e6;
                report.add(String.format(Locale.ROOT,
                        "    ガイド%s %-12s コスト%8.0f 展開%,9dノード %7.0fms",
                        guide ? "有" : "無", result.complete() ? "到達" : result.termination().toString(),
                        ProgressiveWalk.cost(result.steps()), result.expandedNodes(), millis));
            }
        }
        emit("profile-guide.txt", String.join("\n", report));
    }

    /** 結果はGradleが握り潰すので、標準出力とファイルの両方へ出す。 */
    private static void emit(String name, String text) {
        System.out.println(text);
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("xaeronav.profileOut", System.getProperty("java.io.tmpdir")));
            java.nio.file.Files.createDirectories(out);
            java.nio.file.Files.writeString(out.resolve(name), text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
