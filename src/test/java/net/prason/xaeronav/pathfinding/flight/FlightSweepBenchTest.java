package net.prason.xaeronav.pathfinding.flight;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.RandomSweepBenchTest;
import net.prason.xaeronav.pathfinding.astar.RandomSweepBenchTest.Dim;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * 保存ワールドから書き出した箱の中で、ランダムな空中の始点から地上の目的地まで、本番の空中経路の段取り
 * （{@code FlightNavState}の引き直し・継ぎ足し）を真似て飛び通す。判定のない計測。
 *
 * <p>プレイヤーは探索にかかった実時間ぶん線の上を進む（最良滑空の巡航速度）。末端に追いついてしまった
 * 時間を「待ち」として数える——ユーザーが「経路探索が遅い」と感じるのはこの待ち。
 */
@Tag("bench")
class FlightSweepBenchTest {

    private static final Path DIR = Path.of(System.getProperty("xaeronav.sweepDir", "."));
    private static final int ROUTES = Integer.getInteger("xaeronav.routes", 8);
    private static final int MIN_BLOCKS = Integer.getInteger("xaeronav.sweepMin", 300);
    private static final int MAX_BLOCKS = Integer.getInteger("xaeronav.sweepMax", 700);
    private static final int SPREAD = Integer.getInteger("xaeronav.sweepSpread", 360);
    private static final long SEED = Long.parseLong(System.getProperty("xaeronav.sweepSeed", "0"));
    private static final String TAG = System.getProperty("xaeronav.sweepTag", "");
    /** 実機の描画距離15チャンク。 */
    private static final int RENDER_RADIUS = Integer.getInteger("xaeronav.window", 240);
    private static final int CELL_BLOCKS = Integer.getInteger("xaeronav.flightCell", 4);
    private static final boolean SKIP_OPTIMAL = Boolean.getBoolean("xaeronav.skipClosure");
    /** {@code horizon}なら目的地を本物のまま狙い、読める範囲の縁を出口にする。 */
    private static final double WEIGHT = Double.parseDouble(System.getProperty("xaeronav.flightWeight", "1.5"));
    private static final boolean TRACE = Boolean.getBoolean("xaeronav.walkTrace");

    // FlightNavStateの定数と同じ値
    private static final int DETAIL_HORIZON_BLOCKS = 256;
    private static final int EXTEND_LEAD_BLOCKS = 160;
    private static final int MIN_EXTENSION_BLOCKS = 64;
    private static final double EXTEND_RETRY_MOVE_BLOCKS = 48.0;
    private static final double LOADED_MARGIN = 0.9;
    private static final int HANDOFF_BLOCKS = 96;
    private static final int VERTICAL_MARGIN = FlightLineRouter.VERTICAL_MARGIN_BLOCKS;
    private static final double SPEED_BLOCKS_PER_TICK = 1.0 / FlightCosts.HORIZONTAL_TICKS_PER_BLOCK;

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
        sweep(Dim.END, boxes("en1,en2,en3"));
    }

    private static List<String> boxes(String defaults) {
        return List.of(System.getProperty("xaeronav.sweepBoxes", defaults).split(","));
    }

    private static FlightTuning tuning(int nodes) {
        return new FlightTuning(CELL_BLOCKS, 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK,
                new SearchLimits(nodes, 2_000, WEIGHT));
    }

    private static void sweep(Dim dim, List<String> boxes) throws IOException {
        Path out = Path.of(System.getProperty("xaeronav.profileOut", "."), "flight-" + dim + TAG + ".txt");
        Files.deleteIfExists(out);
        boolean warmed = false;
        double totalSearchMs = 0;
        double totalWaitTicks = 0;
        int totalRoutes = 0;
        int arrived = 0;
        for (String box : boxes) {
            FakeCells cells = RandomSweepBenchTest.load(DIR.resolve(box + ".txt.gz"), dim);
            SearchBounds b = cells.bounds();
            int top = switch (dim) {
                case OVERWORLD -> 319;
                case END -> 255;
                case NETHER -> b.maxY();
            };
            cells.bounds(new SearchBounds(b.minX(), b.minY(), b.minZ(), b.maxX(), top, b.maxZ()));
            List<Vec3[]> routes = routes(cells, dim, box.hashCode() + SEED);
            CoarseMap map = dim == Dim.NETHER
                    ? LiveCoarseSampler.sample(cells, new SearchBounds(b.minX(), b.minY(), b.minZ(), b.maxX(), 120,
                            b.maxZ()), 64, () -> false)
                    : null;
            log(out, String.format(Locale.ROOT, "# 箱%s ルート%d本 種%d 格子%d 窓%d 重み%.2f", box,
                    routes.size(), SEED, CELL_BLOCKS, RENDER_RADIUS, WEIGHT));
            if (!warmed) {
                Vec3[] first = routes.get(0);
                fly(cells, dim, map, first[0], first[1]);
                warmed = true;
            }
            for (Vec3[] route : routes) {
                Flight flight = fly(cells, dim, map, route[0], route[1]);
                double optimal = SKIP_OPTIMAL ? Double.NaN : optimal(cells, route[0], flight.end());
                double cost = cost(cells, flight.points());
                totalRoutes++;
                arrived += flight.arrived() ? 1 : 0;
                totalSearchMs += flight.searchMs();
                totalWaitTicks += flight.waitTicks();
                log(out, String.format(Locale.ROOT,
                        "%s %s→%s 直線%.0f 到達=%s 探索%d回 計%.0fms 最大%.0fms 予算切れ%d 展開計%d 待ち%.1f秒 値段%.0f 最適比%.3f %s",
                        box, shortVec(route[0]), shortVec(route[1]), horizontal(route[0], route[1]), flight.arrived(),
                        flight.searches(), flight.searchMs(), flight.maxMs(), flight.budgetOuts(), flight.nodes(),
                        flight.waitTicks() / 20.0, cost, cost / optimal, flight.note()));
            }
        }
        log(out, String.format(Locale.ROOT, "# 合計 %d本中%d本到達 探索計%.0fms 待ち計%.1f秒", totalRoutes, arrived,
                totalSearchMs, totalWaitTicks / 20.0));
    }

    /** 実機の段取りの記録。{@code end}は空中経路が最後に届いた点（最適と比べる相手）。 */
    private record Flight(boolean arrived, int searches, double searchMs, double maxMs, int budgetOuts, long nodes,
                          double waitTicks, List<Vec3> points, Vec3 end, String note) {
    }

    /**
     * {@code FlightNavState}の段取り。最初に目的地（地図があれば届く範囲で最も遠い中間目標）を狙って引き、
     * 以降は末端まで160ブロックを切るたびに継ぎ足す。
     */
    private static Flight fly(FakeCells cells, Dim dim, CoarseMap map, Vec3 start, Vec3 goal) {
        var coarse = map == null ? null : CoarseFlightRouter.findRoute(
                CoarseAirMap.from(map, cells.bounds().minY() + 10, 117), BlockPos.containing(start),
                BlockPos.containing(goal), false);
        List<BlockPos> waypoints = coarse == null ? List.of() : coarse.waypoints();
        if (TRACE) {
            System.out.println("  粗い経路 " + (coarse == null ? "なし" : coarse.waypoints().size() + "点 到達="
                    + coarse.reachedGoal() + " " + coarse.waypoints()));
        }
        Vec3 player = start;
        double searchMs = 0;
        double maxMs = 0;
        int searches = 0;
        int budgetOuts = 0;
        long nodes = 0;
        double waitTicks = 0;
        List<Vec3> flown = new ArrayList<>();
        flown.add(start);

        Vec3 aim = detailTarget(start, goal, waypoints);
        FlightHorizon firstHorizon = new FlightHorizon(player.x, player.z, RENDER_RADIUS * LOADED_MARGIN);
        long began = System.nanoTime();
        FlightRoute route = FlightRouter.route(view(cells, player, aim), player, aim, false, tuning(150_000),
                firstHorizon, () -> false);
        double ms = (System.nanoTime() - began) / 1e6;
        searches++;
        searchMs += ms;
        maxMs = Math.max(maxMs, ms);
        nodes += route.expandedNodes();
        budgetOuts += route.budgetExhausted() ? 1 : 0;
        if (route.isEmpty()) {
            return new Flight(false, searches, searchMs, maxMs, budgetOuts, nodes, waitTicks, flown, start, "初回空");
        }
        // 探索中もプレイヤーは飛んでいるが、最初の1本は線が出るまで待つしかない
        waitTicks += ms / 50.0;
        List<Vec3> line = new ArrayList<>(route.points());
        double along = 0;
        Vec3 blockedAt = null;
        Vec3 blockedFrom = null;
        for (int guard = 0; guard < 400; guard++) {
            player = pointAt(line, along);
            if (horizontal(player, goal) <= HANDOFF_BLOCKS) {
                return new Flight(true, searches, searchMs, maxMs, budgetOuts, nodes, waitTicks, line, line.get(
                        line.size() - 1), "");
            }
            Vec3 tail = line.get(line.size() - 1);
            double toTail = length(line) - along;
            if (toTail > EXTEND_LEAD_BLOCKS) {
                along = length(line) - EXTEND_LEAD_BLOCKS;
                continue;
            }
            if (tail.equals(blockedAt) && blockedFrom.distanceTo(player) < EXTEND_RETRY_MOVE_BLOCKS) {
                if (toTail <= 1e-6) {
                    return new Flight(false, searches, searchMs, maxMs, budgetOuts, nodes, waitTicks, line, tail,
                            "末端で行き止まり");
                }
                along = Math.min(length(line), along + EXTEND_RETRY_MOVE_BLOCKS);
                continue;
            }
            double lead = Math.min(DETAIL_HORIZON_BLOCKS, RENDER_RADIUS * LOADED_MARGIN - player.distanceTo(tail));
            Vec3 target = extensionTarget(tail, goal, waypoints, lead);
            if (lead < MIN_EXTENSION_BLOCKS || tail.distanceTo(target) < MIN_EXTENSION_BLOCKS) {
                blockedAt = tail;
                blockedFrom = player;
                continue;
            }
            began = System.nanoTime();
            FlightHorizon horizon = new FlightHorizon(player.x, player.z, RENDER_RADIUS * LOADED_MARGIN);
            FlightRoute extension = FlightRouter.route(view(cells, player, target), tail, target, false,
                    tuning(60_000), horizon, () -> false);
            ms = (System.nanoTime() - began) / 1e6;
            searches++;
            searchMs += ms;
            maxMs = Math.max(maxMs, ms);
            nodes += extension.expandedNodes();
            budgetOuts += extension.budgetExhausted() ? 1 : 0;
            double flownDuring = ms / 50.0 * SPEED_BLOCKS_PER_TICK;
            double room = length(line) - along;
            if (flownDuring > room) {
                waitTicks += (flownDuring - room) / SPEED_BLOCKS_PER_TICK;
            }
            along = Math.min(length(line), along + flownDuring);
            Vec3 grown = extension.tail();
            if (extension.isEmpty()) {
                blockedAt = tail;
                blockedFrom = player;
                continue;
            }
            if (extension.budgetExhausted() && tail.distanceTo(grown) < MIN_EXTENSION_BLOCKS) {
                blockedAt = grown;
                blockedFrom = player;
            } else {
                blockedAt = null;
                blockedFrom = null;
            }
            if (TRACE) {
                System.out.println(String.format(Locale.ROOT, "  継ぎ足し %s→狙い%s 末端%s %s 展開%d", shortVec(tail),
                        shortVec(target), shortVec(grown), extension.termination(), extension.expandedNodes()));
            }
            line.addAll(extension.points().subList(1, extension.points().size()));
        }
        return new Flight(false, searches, searchMs, maxMs, budgetOuts, nodes, waitTicks, line,
                line.get(line.size() - 1), "打ち切り");
    }

    /** 本番の{@code ChunkView.capture}と同じく、プレイヤー中心の描画半径とプレイヤー・狙いの箱だけを見せる。 */
    private static CellSource view(FakeCells cells, Vec3 player, Vec3 target) {
        BlockPos p = BlockPos.containing(player);
        int minY = Math.max(cells.bounds().minY(), (int) Math.min(player.y, target.y) - VERTICAL_MARGIN);
        int maxY = Math.min(cells.bounds().maxY(), (int) Math.max(player.y, target.y) + VERTICAL_MARGIN);
        SearchBounds box = new SearchBounds(p.getX() - RENDER_RADIUS, minY, p.getZ() - RENDER_RADIUS,
                p.getX() + RENDER_RADIUS, maxY, p.getZ() + RENDER_RADIUS);
        return new WindowedCells(cells, p, RENDER_RADIUS, box);
    }

    private static Vec3 detailTarget(Vec3 start, Vec3 goal, List<BlockPos> waypoints) {
        if (waypoints.isEmpty() || start.distanceTo(goal) <= DETAIL_HORIZON_BLOCKS) {
            return goal;
        }
        Vec3 target = null;
        for (int i = nearest(waypoints, start) + 1; i < waypoints.size(); i++) {
            if (Vec3.atCenterOf(waypoints.get(i)).distanceTo(start) > DETAIL_HORIZON_BLOCKS) {
                break;
            }
            target = Vec3.atCenterOf(waypoints.get(i));
        }
        return target == null ? goal : target;
    }

    private static Vec3 extensionTarget(Vec3 tail, Vec3 goal, List<BlockPos> waypoints, double lead) {
        if (tail.distanceTo(goal) <= lead) {
            return goal;
        }
        Vec3 target = null;
        for (int i = nearest(waypoints, tail) + 1; i < waypoints.size(); i++) {
            if (Vec3.atCenterOf(waypoints.get(i)).distanceTo(tail) > lead) {
                break;
            }
            target = Vec3.atCenterOf(waypoints.get(i));
        }
        return target != null ? target : goal;
    }

    private static int nearest(List<BlockPos> waypoints, Vec3 position) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            double d = Vec3.atCenterOf(waypoints.get(i)).distanceToSqr(position);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** 全視界・重み1で、同じ始点から空中経路が最後に届いた点までの最適。 */
    private static double optimal(FakeCells cells, Vec3 start, Vec3 end) {
        FlightRoute best = new FlightPathfinder(new AirGrid(cells, CELL_BLOCKS), false,
                new SearchLimits(4_000_000, 120_000, 1.0), 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK)
                .search(start, end, CELL_BLOCKS * 1.5);
        return best.complete() ? cost(cells, best.points()) : Double.POSITIVE_INFINITY;
    }

    private static double cost(FakeCells cells, List<Vec3> points) {
        AirGrid grid = new AirGrid(cells, CELL_BLOCKS);
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 c = points.get(i);
            total += FlightCosts.segmentTicks(Math.hypot(c.x - a.x, c.z - a.z), c.y - a.y, false)
                    + Clearance.alongLine(grid, a, c, 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK);
        }
        return total;
    }

    private static double length(List<Vec3> line) {
        double total = 0;
        for (int i = 1; i < line.size(); i++) {
            total += line.get(i - 1).distanceTo(line.get(i));
        }
        return total;
    }

    private static Vec3 pointAt(List<Vec3> line, double along) {
        double left = along;
        for (int i = 1; i < line.size(); i++) {
            double segment = line.get(i - 1).distanceTo(line.get(i));
            if (left <= segment) {
                return segment < 1e-9 ? line.get(i) : line.get(i - 1).lerp(line.get(i), left / segment);
            }
            left -= segment;
        }
        return line.get(line.size() - 1);
    }

    /**
     * 始点は空中（現世・エンドは地表の30ブロック上、ネザーは床の上の4×4×4が空いている所）、
     * 目的地は地表の床。
     */
    private static List<Vec3[]> routes(FakeCells cells, Dim dim, long seed) {
        SearchBounds b = cells.bounds();
        int cx = (b.minX() + b.maxX()) / 2;
        int cz = (b.minZ() + b.maxZ()) / 2;
        Random random = new Random(seed);
        List<Vec3[]> routes = new ArrayList<>();
        for (int attempt = 0; attempt < 50000 && routes.size() < ROUTES; attempt++) {
            Vec3 start = pick(cells, dim, random, cx, cz, true);
            Vec3 goal = pick(cells, dim, random, cx, cz, false);
            if (start == null || goal == null) {
                continue;
            }
            double d = horizontal(start, goal);
            if (d >= MIN_BLOCKS && d <= MAX_BLOCKS) {
                routes.add(new Vec3[] {start, goal});
            }
        }
        return routes;
    }

    private static Vec3 pick(FakeCells cells, Dim dim, Random random, int cx, int cz, boolean air) {
        int x = cx - SPREAD + random.nextInt(2 * SPREAD + 1);
        int z = cz - SPREAD + random.nextInt(2 * SPREAD + 1);
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
        int floor = dim == Dim.NETHER ? floors.get(random.nextInt(floors.size())) : floors.get(0);
        if (!air) {
            return new Vec3(x + 0.5, floor, z + 0.5);
        }
        int y = dim == Dim.NETHER ? floor + 6 : floor + 30;
        if (y > cells.bounds().maxY() - 12) {
            return null;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!CellData.passableEmpty(cells.cell(x + dx, y + dy, z + dz))) {
                        return null;
                    }
                }
            }
        }
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static String shortVec(Vec3 v) {
        return Mth.floor(v.x) + "," + Mth.floor(v.y) + "," + Mth.floor(v.z);
    }

    private static void log(Path out, String line) throws IOException {
        System.out.println(line);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            w.println(line);
        }
    }
}
