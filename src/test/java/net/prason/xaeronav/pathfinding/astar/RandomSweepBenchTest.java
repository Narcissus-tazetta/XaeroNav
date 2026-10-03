package net.prason.xaeronav.pathfinding.astar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.coarse.CoarseMapBuilder;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/** 保存ワールドから書き出した箱の中で、ランダムな始点・目的地を本番の設定で歩き通す。 */
@Tag("bench")
public class RandomSweepBenchTest {

    /** 書き出した箱（{@code <箱>.txt.gz}）を置いたディレクトリ。{@code -Pxaeronav.sweepDir=...}で渡す。 */
    private static final Path DIR = Path.of(System.getProperty("xaeronav.sweepDir", "."));
    private static final int WINDOW = 224;
    private static final int ROUTES = Integer.getInteger("xaeronav.routes", 8);
    private static final boolean UNKNOWN_MAP = Boolean.getBoolean("xaeronav.unknownMap");
    private static final int MIN_BLOCKS = Integer.getInteger("xaeronav.sweepMin", 100);
    private static final int MAX_BLOCKS = Integer.getInteger("xaeronav.sweepMax", 350);
    /** {@code random}なら実行ごとに変える。引いた値は出力の見出しに残すので、同じ組を後から再現できる。 */
    private static final long SEED = seed(System.getProperty("xaeronav.sweepSeed", "0"));
    /** 始点・目的地を置く範囲（箱の中心からの半径）。窓が箱の外を見ないよう、既定は768四方の箱で±200。 */
    private static final int SPREAD = Integer.getInteger("xaeronav.sweepSpread", 200);
    private static final String TAG = System.getProperty("xaeronav.sweepTag", "");

    public enum Dim { OVERWORLD, NETHER, END }

    @Test
    void overworld() throws IOException {
        sweep(Dim.OVERWORLD, boxes("ow1,ow2,ow3"));
    }

    @Test
    void nether() throws IOException {
        sweep(Dim.NETHER, boxes("ne1,ne2,ne3"));
    }

    @Test
    void end() throws IOException {
        sweep(Dim.END, boxes("en0,en1,en2,en3,en4"));
    }

    /** {@code -Pxaeronav.alongPoints=箱:x,y,z:x,y,z;...}のルートを1本ずつ、全視界の最適と比べて歩く。 */
    @Test
    void focus() throws IOException {
        Path out = Path.of(System.getProperty("xaeronav.profileOut", "."), "sweep-focus.txt");
        Files.deleteIfExists(out);
        for (String spec : System.getProperty("xaeronav.alongPoints", "").split(";")) {
            String[] p = spec.split(":");
            Dim dim = p[0].startsWith("ow") ? Dim.OVERWORLD : p[0].startsWith("ne") ? Dim.NETHER : Dim.END;
            ProgressiveWalk.END_LANDING = dim == Dim.END;
            FakeCells cells = load(DIR.resolve(p[0] + ".txt.gz"), dim);
            BlockPos start = parse(p[1]);
            BlockPos goal = parse(p[2]);
            long began = System.currentTimeMillis();
            double best = Double.NaN;
            if (!Boolean.getBoolean("xaeronav.skipClosure")) {
                PathResult full = new net.prason.xaeronav.pathfinding.async.PathfindingExecutor().submit(cells,
                        StanceFinder.resolveStart(cells, start), StanceFinder.resolveGoal(cells, goal),
                        new SearchLimits(3_000_000, 300_000, 1.0), true, 0, Carryover.NONE, null).join();
                best = full.complete() ? ProgressiveWalk.cost(full.steps()) : Double.POSITIVE_INFINITY;
                log(out, String.format(Locale.ROOT, "  全視界 %s 展開%d 手%d 値段%.0f", full.termination(),
                        full.expandedNodes(), full.steps().size(), best));
                StringBuilder path = new StringBuilder("  最適の道筋");
                for (int i = 0; i < full.steps().size(); i += 40) {
                    path.append(' ').append(full.steps().get(i).pos().toShortString().replace(" ", ""));
                }
                log(out, path.toString());
            }
            log(out, String.format(Locale.ROOT, "# %s %s→%s 全視界の最適%.0f (%ds)", p[0], start.toShortString(),
                    goal.toShortString(), best, (System.currentTimeMillis() - began) / 1000));
            CoarseMap sampled = dim == Dim.NETHER ? null : LiveCoarseSampler.sample(cells, cells.bounds());
            ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, start, goal, WINDOW, ProgressiveWalk.Mode.REPAIR,
                    ProgressiveWalk.Aim.GOAL, guide(cells, dim, start, goal, sampled), 1.0);
            double cost = trace.steps().isEmpty() ? Double.POSITIVE_INFINITY : ProgressiveWalk.cost(trace.steps());
            log(out, String.format(Locale.ROOT, "  歩き通し 値段%.0f 最適比%.3f 後退%.0f 重複%d %s", cost, cost / best,
                    worstRetreat(trace.steps(), goal), ProgressiveWalk.selfOverlaps(trace.steps()), trace.stopped()));
        }
    }

    private static BlockPos parse(String s) {
        String[] v = s.split(",");
        return new BlockPos(Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()), Integer.parseInt(v[2].trim()));
    }

    private static long seed(String value) {
        return value.equals("random") ? new Random().nextLong() : Long.parseLong(value);
    }

    private static List<String> boxes(String defaults) {
        return List.of(System.getProperty("xaeronav.sweepBoxes", defaults).split(","));
    }

    private static void sweep(Dim dim, List<String> boxes) throws IOException {
        ProgressiveWalk.END_LANDING = dim == Dim.END;
        Path out = Path.of(System.getProperty("xaeronav.profileOut", "."), "sweep-" + dim + (UNKNOWN_MAP ? "-unknown" : "") + TAG + ".txt");
        Files.deleteIfExists(out);
        for (String box : boxes) {
            FakeCells cells = load(DIR.resolve(box + ".txt.gz"), dim);
            List<BlockPos[]> routes = routes(cells, dim, box.hashCode() + SEED);
            log(out, String.format(Locale.ROOT, "# 箱%s %s ルート%d本 種%d", box, cells.bounds(), routes.size(), SEED));
            CoarseMap sampled = dim == Dim.NETHER ? null : LiveCoarseSampler.sample(cells, cells.bounds());
            for (BlockPos[] route : routes) {
                BlockPos start = StanceFinder.resolveStart(cells, route[0]);
                BlockPos goal = StanceFinder.resolveGoal(cells, route[1]);
                Function<BlockPos, CostToGo> guide = guide(cells, dim, start, goal, sampled);
                int[] unguided = {0};
                long began = System.currentTimeMillis();
                ProgressiveWalk.UNGUIDED_LEGS.set(0);
                ProgressiveWalk.Trace trace;
                try {
                    trace = ProgressiveWalk.trace(cells, start, goal, WINDOW, ProgressiveWalk.Mode.REPAIR,
                            ProgressiveWalk.Aim.GOAL, player -> {
                                CostToGo g = guide.apply(player);
                                if (g instanceof WindowField f && f.descend(player.getX(), player.getY(), player.getZ()) == null) {
                                    unguided[0]++;
                                }
                                return g;
                            }, 1.0);
                } catch (RuntimeException | OutOfMemoryError e) {
                    log(out, String.format(Locale.ROOT, "%s→%s 例外 %s", start.toShortString(), goal.toShortString(), e));
                    continue;
                }
                long secs = (System.currentTimeMillis() - began) / 1000;
                List<PathStep> steps = trace.steps();
                double lower = Heuristic.estimate(start.getX(), start.getY(), start.getZ(), goal.getX(), goal.getY(), goal.getZ());
                double cost = steps.isEmpty() ? Double.POSITIVE_INFINITY : ProgressiveWalk.cost(steps);
                int edge = edgeSteps(cells, start, steps);
                log(out, String.format(Locale.ROOT,
                        "%s %s→%s 直線%.0f 到達=%s 値段%.0f 下限比%.2f 手%d 後退%.0f 重複%d 描き変わり%d(足元%d) 繋ぎ目%d ガイド無し区間%d 空のガイド%d 置く%d 掘る%d 縁%d 割増抜き%.0f %ds %s",
                        box, start.toShortString(), goal.toShortString(), ProgressiveWalk.horizontal(start, goal),
                        !steps.isEmpty(), cost, cost / lower, steps.size(), worstRetreat(steps, goal),
                        ProgressiveWalk.selfOverlaps(steps), trace.redraws(), trace.nearRedraws(), trace.joints().size(),
                        ProgressiveWalk.UNGUIDED_LEGS.get(), unguided[0],
                        steps.stream().filter(s -> s.placedBlockPos() != null).count(),
                        steps.stream().filter(PathStep::digging).count(), edge, cost - edge * ActionCosts.EDGE_HAZARD_PENALTY_TICKS,
                        secs, trace.stopped()));
            }
        }
    }

    private static void log(Path out, String line) throws IOException {
        System.out.println(line);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            w.println(line);
        }
    }

    /** 本番の{@code PathfindingState#goalGuide}と同じ窓の外の推定で、8ブロック動くごとに組み直す。 */
    private static Function<BlockPos, CostToGo> guide(FakeCells cells, Dim dim, BlockPos start, BlockPos goal,
                                                      CoarseMap sampled) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        CoarseMap map = UNKNOWN_MAP && sampled != null ? new CoarseMapBuilder(sampled.minChunkX(), sampled.minChunkZ(),
                sampled.chunksX(), sampled.chunksZ()).build() : sampled;
        FarField fixedFar = switch (dim) {
            case OVERWORLD -> layer1Far(CoarseRouter.farEstimate(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE));
            case END -> FarField.straightLineTo(goal, CoarseRouter.unknownMultiplier(map));
            case NETHER -> null;
        };
        CostToGo[] voxel = {null};
        BlockPos[] voxelAt = {null};
        BlockPos[] last = {null};
        CostToGo[] cached = {null};
        return player -> {
            if (last[0] != null && Math.max(Math.abs(player.getX() - last[0].getX()),
                    Math.abs(player.getZ() - last[0].getZ())) < 8) {
                return cached[0];
            }
            FarField far = fixedFar;
            if (dim == Dim.NETHER) {
                if (voxelAt[0] == null || Math.hypot(player.getX() - voxelAt[0].getX(), player.getZ() - voxelAt[0].getZ()) >= 128) {
                    voxel[0] = XaeroMapModel.guide(cells, player, goal, 0, 127, 1.0, 0L);
                    voxelAt[0] = player;
                }
                CostToGo current = voxel[0];
                far = FarField.of((x, y, z) -> 1.3 * current.estimate(x, y, z));
            } else if (dim == Dim.OVERWORLD) {
                graph.floorBelow(player.getY());
            }
            if (dim == Dim.END) {
                far = FarField.forwardOf(far, player.getX(), player.getY(), player.getZ());
            }
            WindowedCells window = new WindowedCells(cells, player, WINDOW);
            WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), WINDOW,
                    LoadedArea.square(player.getX(), player.getZ(), WINDOW), far, ForkJoinPool.commonPool(),
                    Runtime.getRuntime().availableProcessors(), () -> false).field();
            cached[0] = field;
            last[0] = player;
            return field;
        };
    }

    /** 箱の中央±{@link #SPREAD}に始点・目的地を置く。周り48ブロックに書き出されていない列（未生成のチャンク）がある点は使わない。 */
    private static List<BlockPos[]> routes(FakeCells cells, Dim dim, long seed) {
        SearchBounds b = cells.bounds();
        int cx = (b.minX() + b.maxX()) / 2;
        int cz = (b.minZ() + b.maxZ()) / 2;
        Random random = new Random(seed);
        List<BlockPos[]> routes = new ArrayList<>();
        for (int attempt = 0; attempt < 20000 && routes.size() < ROUTES; attempt++) {
            BlockPos start = pick(cells, dim, random, cx, cz, routes.size() % 3 == 2);
            BlockPos goal = pick(cells, dim, random, cx, cz, false);
            if (start == null || goal == null) {
                continue;
            }
            double d = ProgressiveWalk.horizontal(start, goal);
            if (d >= MIN_BLOCKS && d <= MAX_BLOCKS) {
                routes.add(new BlockPos[] {start, goal});
            }
        }
        return routes;
    }

    private static BlockPos pick(FakeCells cells, Dim dim, Random random, int cx, int cz, boolean cave) {
        int x = cx - SPREAD + random.nextInt(2 * SPREAD + 1);
        int z = cz - SPREAD + random.nextInt(2 * SPREAD + 1);
        if (dim != Dim.END && !generatedAround(cells, x, z)) {
            return null;
        }
        List<Integer> floors = new ArrayList<>();
        int top = dim == Dim.NETHER ? 120 : cells.bounds().maxY() - 2;
        for (int y = top; y > cells.bounds().minY() + 1; y--) {
            if (CellData.standable(cells.cell(x, y - 1, z)) && CellData.passableEmpty(cells.cell(x, y, z))
                    && CellData.passableEmpty(cells.cell(x, y + 1, z))) {
                floors.add(y);
            }
        }
        if (floors.isEmpty()) {
            return null;
        }
        // 現世・エンドは地表（いちばん上の床）、現世の3本に1本は洞窟の始点。ネザーは床をランダムに選ぶ
        int y = switch (dim) {
            case NETHER -> floors.get(random.nextInt(floors.size()));
            case END -> floors.get(0);
            case OVERWORLD -> cave ? (floors.size() > 1 && floors.get(floors.size() - 1) < floors.get(0) - 8
                    ? floors.get(1 + random.nextInt(floors.size() - 1)) : -9999) : floors.get(0);
        };
        if (y == -9999 || dim == Dim.OVERWORLD && cave && y > floors.get(0) - 8) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private static boolean generatedAround(FakeCells cells, int x, int z) {
        int bottom = cells.bounds().minY();
        for (int dx = -48; dx <= 48; dx += 8) {
            for (int dz = -48; dz <= 48; dz += 8) {
                boolean any = false;
                for (int y = bottom; y < bottom + 16 && !any; y++) {
                    any = !CellData.passableEmpty(cells.cell(x + dx, y, z + dz));
                }
                if (!any) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 横が溶岩・奈落・致死落差のマスへ歩いて着いた手の数（{@code AStarPathfinder#edgeHazardPenalty}と同じ判定）。
     * 落下・跳躍・橋は除くため、水平1マス以内・上下1マス以内の手だけを数える。
     */
    private static int edgeSteps(FakeCells cells, BlockPos start, List<PathStep> steps) {
        int count = 0;
        BlockPos prev = start;
        for (PathStep step : steps) {
            BlockPos pos = step.pos();
            boolean walk = switch (step.movement()) {
                case TRAVERSE, ASCEND, DESCEND -> true;
                default -> false;
            };
            if (walk && !step.bridging() && step.placedBlockPos() == null
                    && Math.abs(pos.getX() - prev.getX()) <= 1 && Math.abs(pos.getZ() - prev.getZ()) <= 1
                    && Math.abs(pos.getY() - prev.getY()) <= 1 && deadlyBeside(cells, pos)) {
                count++;
            }
            prev = pos;
        }
        return count;
    }

    private static boolean deadlyBeside(FakeCells cells, BlockPos pos) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int x = pos.getX() + d[0];
            int y = pos.getY();
            int z = pos.getZ() + d[1];
            long feet = cells.cell(x, y, z);
            if (CellData.lava(feet) || CellData.lava(cells.cell(x, y + 1, z))) {
                return true;
            }
            if (!CellData.passableEmpty(feet)) {
                continue;
            }
            int below = y - 1;
            while (below >= y - ColumnScans.SCAN_DEPTH && CellData.passableEmpty(cells.cell(x, below, z))) {
                below--;
            }
            long hit = cells.cell(x, below, z);
            if (!CellData.present(hit)) {
                if (!cells.isInBounds(x, below, z)) {
                    return true;
                }
                continue;
            }
            if (CellData.lava(hit) || (!CellData.water(hit) && y - below - 1 >= cells.fatalFallBlocks())) {
                return true;
            }
        }
        return false;
    }

    private static double worstRetreat(List<PathStep> steps, BlockPos goal) {
        double closest = Double.POSITIVE_INFINITY;
        double worst = 0;
        for (PathStep step : steps) {
            double left = ProgressiveWalk.horizontal(step.pos(), goal);
            closest = Math.min(closest, left);
            worst = Math.max(worst, left - closest);
        }
        return worst;
    }

    /** 実機の既定（経路の再現用ログの設定）に揃える。ネザーは列の最下ブロックより下を石で埋める。 */
    public static FakeCells load(Path file, Dim dim) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String[] h = reader.readLine().trim().split(" ");
            FakeCells cells = FakeCells.empty(new SearchBounds(Integer.parseInt(h[0]), Integer.parseInt(h[1]),
                            Integer.parseInt(h[2]), Integer.parseInt(h[3]), Integer.parseInt(h[4]), Integer.parseInt(h[5])))
                    .canPlaceBlocks(true).placedBlockBudget(0).maxFallDamagePoints(0).fatalFallBlocks(23)
                    .maxBridgeRunBlocks(96).lavaBridgingEnabled(true).maxLavaBridgeRunBlocks(30)
                    .maxVoidBridgeRunBlocks(96).jumpGapEnabled(true).maxSubmergedTicks(250).canMlgWaterBucket(false)
                    .boatAvailable(true);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(" ");
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[1]);
                short[] runs = new short[(parts.length - 2 + 1) * 3];
                int n = 0;
                for (int i = 2; i < parts.length; i++) {
                    String run = parts[i];
                    char kind = FakeCells.STONE;
                    if (!Character.isDigit(run.charAt(0)) && run.charAt(0) != '-') {
                        kind = run.charAt(0);
                        run = run.substring(1);
                    }
                    int comma = run.indexOf(',');
                    int from = Integer.parseInt(run.substring(0, comma));
                    int to = Integer.parseInt(run.substring(comma + 1));
                    if (n == 0 && dim == Dim.NETHER && from > cells.bounds().minY()) {
                        runs[n++] = (short) cells.bounds().minY();
                        runs[n++] = (short) (from - 1);
                        runs[n++] = (short) FakeCells.STONE;
                    }
                    runs[n++] = (short) from;
                    runs[n++] = (short) to;
                    runs[n++] = (short) kind;
                }
                cells.setColumn(x, z, java.util.Arrays.copyOf(runs, n));
            }
            return cells;
        }
    }

    private static FarField layer1Far(CostToGo estimate) {
        return FarField.byGoal(FarField.of(estimate), FarField.UNKNOWN);
    }
}
