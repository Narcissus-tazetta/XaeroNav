package net.prason.xaeronav.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.world.BlockRegistryCompat;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * {@code -Pxaeronav.escalatorCheck=<名前> -Pwith_mtr=true}で、MTRのエスカレーターを空中に組み、次を測ってクライアントを閉じる。
 * <ul>
 * <li>XaeroNavが段・手すりのセルをどう判定するか（立てるか・体を置けるか）</li>
 * <li>XaeroNavが下から上へ引いた経路がエスカレーターを通るか</li>
 * <li>上り・下り・停止中・普通の階段の4レーンで、疾走したとき・立ち止まったときの速さ</li>
 * </ul>
 * 結果はログの「escalator check:」の行と、毎tickの位置を書いた{@code run/escalator-<名前>.csv}。ワールドを書き換えるので複製して使うこと。
 *
 * <p>レーンは+X向き（facing=east）。x=0..3が下の平ら、x=4..11が上り坂（1マスで1段）、x=12..15が上の平ら。前後の床は段と同じ高さに置く。
 */
final class EscalatorCheck {

    private static final int BASE_Y = 200;
    private static final int RISE = 8;
    private static final int LANE_GAP = 4;
    private static final int RUN_TICKS = 140;
    private static final int IDLE_TICKS = 100;
    private static final int SETTLE_TICKS = 15;
    private static final int PATH_WAIT_TICKS = 200;

    private enum Lane {
        UP("up (push +x)"), DOWN("down (push -x)"), STOPPED("stopped"), STAIRS("vanilla stairs");

        final String label;

        Lane(String label) {
            this.label = label;
        }
    }

    private record Run(Lane lane, String kind, double startX, double startY, float yaw, boolean sprint, int ticks) {
    }

    private final String name;
    private final List<Run> runs = new ArrayList<>();
    private final List<String> csv = new ArrayList<>();
    private int stage;
    private int ticks;
    private int runIndex;
    private BlockPos base;
    private Block step;
    private Block side;
    private BlockPos pathGoal;

    private EscalatorCheck(String name) {
        this.name = name;
        csv.add("lane,kind,tick,x,y,z,onGround");
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.escalatorCheck");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new EscalatorCheck(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) {
            return;
        }
        ticks++;
        switch (stage) {
            case 0 -> {
                if (ticks < 100) {
                    return;
                }
                mc.options.pauseOnLostFocus = false;
                step = BlockRegistryCompat.byId(ResourceLocation.fromNamespaceAndPath("mtr", "escalator_step"));
                side = BlockRegistryCompat.byId(ResourceLocation.fromNamespaceAndPath("mtr", "escalator_side"));
                if (step == null || side == null) {
                    finish(mc, "MTR is not loaded (run with -Pwith_mtr=true)");
                    return;
                }
                base = new BlockPos(mc.player.getBlockX(), BASE_Y, mc.player.getBlockZ());
                server.execute(() -> build(server));
                next();
            }
            case 1 -> {
                if (ticks < 40) {
                    return;
                }
                reportCells(mc);
                if (name.startsWith("route")) {
                    planRoutes();
                    stage = 10;
                    ticks = 0;
                    return;
                }
                // 下の床から上の床へ。間を歩いて上がれるのはエスカレーターか、レーンの外を柱で上がるかだけ
                teleport(server, Lane.UP, -5.5, BASE_Y + 1, -90f);
                next();
            }
            case 2 -> {
                if (ticks < 40) {
                    return;
                }
                pathGoal = PathfindingState.INSTANCE.setGoal(lanePos(Lane.UP, 18, BASE_Y + RISE + 1, 0));
                log("path goal {}", pathGoal == null ? "none" : pathGoal.toShortString());
                next();
            }
            case 3 -> {
                PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
                PathResult result = view.currentResult();
                if ((result == null || result.steps().isEmpty() || view.computing()) && ticks < PATH_WAIT_TICKS) {
                    return;
                }
                reportPath(result);
                PathfindingState.INSTANCE.clear();
                planRuns();
                next();
            }
            case 4 -> runStep(mc, server);
            case 10 -> routeStep(mc, server);
            default -> {
            }
        }
    }

    private void next() {
        stage++;
        ticks = 0;
    }

    private void build(IntegratedServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setInvulnerable(true);
        player.getFoodData().setFoodLevel(20);
        int laneWidth = 2;
        int zMax = (Lane.values().length - 1) * LANE_GAP + laneWidth;
        for (int x = -10; x <= 22; x++) {
            for (int y = BASE_Y - 1; y <= BASE_Y + RISE + 6; y++) {
                for (int z = -2; z <= zMax + 1; z++) {
                    level.setBlock(base.offset(x, y - BASE_Y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        // 乗り口と降り口の床はレーンの間もつなぐ。経路がレーンを選べるようにするため
        for (int x = -8; x <= 20; x++) {
            if (x >= 0 && x <= 15) {
                continue;
            }
            for (int z = -1; z <= zMax; z++) {
                level.setBlock(base.offset(x, x < 0 ? 0 : RISE, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        List<BlockPos> placed = new ArrayList<>();
        for (Lane lane : Lane.values()) {
            for (int x = 0; x <= 15; x++) {
                int y = BASE_Y + Math.max(0, Math.min(RISE, x - 3));
                for (int dz = 0; dz < laneWidth; dz++) {
                    BlockPos pos = lanePos(lane, x, y, dz);
                    if (lane == Lane.STAIRS) {
                        boolean slope = x >= 4 && x <= 3 + RISE;
                        level.setBlock(pos, slope
                                ? Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, net.minecraft.core.Direction.EAST)
                                : Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    } else {
                        BlockState stepState = with(with(with(with(step.defaultBlockState(),
                                "facing", "east"), "side", dz == 0 ? "left" : "right"),
                                "status", lane == Lane.STOPPED ? "false" : "true"),
                                "direction", lane == Lane.DOWN ? "false" : "true");
                        BlockState sideState = with(with(side.defaultBlockState(), "facing", "east"),
                                "side", dz == 0 ? "left" : "right");
                        level.setBlock(pos, stepState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        level.setBlock(pos.above(), sideState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        placed.add(pos);
                        placed.add(pos.above());
                    }
                }
            }
        }
        // 置いた順に向き（orientation）が決まると、後から置いた隣を見ていない。全部置いてから組み直す
        for (BlockPos pos : placed) {
            BlockState state = level.getBlockState(pos);
            level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        log("built at {}", base.toShortString());
    }

    private BlockPos lanePos(Lane lane, int x, int y, int dz) {
        return new BlockPos(base.getX() + x, y, base.getZ() + lane.ordinal() * LANE_GAP + dz);
    }

    private void reportCells(Minecraft mc) {
        int[][] samples = {{1, 0}, {3, 0}, {4, 1}, {7, 4}, {11, 8}, {12, 8}, {14, 8}};
        for (Lane lane : new Lane[] {Lane.UP, Lane.STAIRS}) {
            for (int[] s : samples) {
                BlockPos pos = lanePos(lane, s[0], BASE_Y + s[1], 0);
                BlockState floor = mc.level.getBlockState(pos);
                BlockState body = mc.level.getBlockState(pos.above());
                long floorCell = CellData.flagsOf(floor);
                long bodyCell = CellData.flagsOf(body);
                log("cell {} x={} floor={} standable={} unresolved={} | body={} occupiable={} unresolved={}",
                        lane.label, s[0], floor, CellData.standable(floorCell), CellData.unresolvedShape(floorCell),
                        body, CellData.occupiableWithoutDigging(bodyCell), CellData.unresolvedShape(bodyCell));
            }
        }
    }

    private void reportPath(PathResult result) {
        if (result == null || result.steps().isEmpty()) {
            log("path: none");
            return;
        }
        int onEscalator = 0;
        double cost = 0;
        StringBuilder cells = new StringBuilder();
        for (PathStep stepInPath : result.steps()) {
            BlockPos pos = stepInPath.pos();
            cost += stepInPath.cost();
            int x = pos.getX() - base.getX();
            int z = pos.getZ() - base.getZ() - Lane.UP.ordinal() * LANE_GAP;
            if (x >= 0 && x <= 15 && z >= 0 && z <= 1) {
                onEscalator++;
            }
            cells.append(' ').append(x).append(',').append(pos.getY() - BASE_Y).append(',').append(z)
                    .append(':').append(stepInPath.movement());
        }
        log("path ({}): {} steps, {} on the escalator lane, cost {}; cells (dx,dy,dz):{}", result.termination(),
                result.steps().size(), onEscalator, String.format(Locale.ROOT, "%.1f", cost), cells);
    }

    private void planRuns() {
        for (Lane lane : Lane.values()) {
            runs.add(new Run(lane, "sprint-east", -5.5, BASE_Y + 1, -90f, true, RUN_TICKS));
            runs.add(new Run(lane, "sprint-west", 19.5, BASE_Y + RISE + 1, 90f, true, RUN_TICKS));
            runs.add(new Run(lane, "walk-east", -5.5, BASE_Y + 1, -90f, false, RUN_TICKS * 2));
            runs.add(new Run(lane, "idle-flat", 1.5, BASE_Y + 1, -90f, false, IDLE_TICKS));
            runs.add(new Run(lane, "idle-slope", 7.5, BASE_Y + 5.2, -90f, false, IDLE_TICKS));
        }
    }

    private void runStep(Minecraft mc, IntegratedServer server) {
        if (runIndex >= runs.size()) {
            release(mc);
            writeCsv(mc);
            finish(mc, "done");
            return;
        }
        Run run = runs.get(runIndex);
        if (ticks == 1) {
            release(mc);
            teleport(server, run.lane(), run.startX(), run.startY(), run.yaw());
            return;
        }
        if (ticks <= SETTLE_TICKS) {
            return;
        }
        int t = ticks - SETTLE_TICKS;
        boolean move = !run.kind().startsWith("idle");
        mc.options.keyUp.setDown(move);
        mc.options.keySprint.setDown(move && run.sprint());
        mc.player.setYRot(run.yaw());
        double x = mc.player.getX() - base.getX();
        csv.add(String.format(Locale.ROOT, "%s,%s,%d,%.4f,%.4f,%.4f,%b", run.lane().name(), run.kind(), t, x,
                mc.player.getY() - BASE_Y, mc.player.getZ() - base.getZ(), mc.player.onGround()));
        boolean pastEnd = run.yaw() < 0 ? x > 19 : x < -4;
        if (t >= run.ticks() || move && pastEnd) {
            log("run {} {}: {} ticks, end at x={} y={}", run.lane().label, run.kind(), t,
                    String.format(Locale.ROOT, "%.2f", x),
                    String.format(Locale.ROOT, "%.2f", mc.player.getY() - BASE_Y));
            runIndex++;
            ticks = 0;
        }
    }

    /** 名前が{@code route}で始まるとき、速さの計測の代わりにXaeroNavの経路を引いて歩き通す。 */
    private record Route(String label, Lane startLane, double startX, double startY, Lane goalLane, int goalX, int goalY) {
    }

    private final List<Route> routes = new ArrayList<>();
    private int routeIndex;
    private int routeStage;
    private double routeCost;
    private int walkTicks;
    private int stuckTicks;
    private double lastX;
    private double lastY;

    private void planRoutes() {
        int top = BASE_Y + RISE + 1;
        int bottom = BASE_Y + 1;
        routes.add(new Route("bottom->top from the up lane", Lane.UP, -5.5, bottom, Lane.UP, 18, top));
        routes.add(new Route("top->bottom from the down lane", Lane.DOWN, 18.5, top, Lane.DOWN, -5, bottom));
        routes.add(new Route("bottom->top starting at the down lane", Lane.DOWN, -5.5, bottom, Lane.DOWN, 18, top));
        routes.add(new Route("top->bottom starting at the up lane", Lane.UP, 18.5, top, Lane.UP, -5, bottom));
    }

    private void routeStep(Minecraft mc, IntegratedServer server) {
        if (routeIndex >= routes.size()) {
            release(mc);
            finish(mc, "done");
            return;
        }
        Route route = routes.get(routeIndex);
        PathfindingState state = PathfindingState.INSTANCE;
        PathfindingState.NavigationView view = state.navigationView();
        switch (routeStage) {
            case 0 -> {
                release(mc);
                state.clear();
                // 人が乗り口に立つ位置（列の中心）から始める。レーンの中央から始めると、手すりに掛かる線でも通れてしまう
                teleport(server, route.startLane(), route.startX(), route.startY(), route.startX() < 0 ? -90f : 90f, 1.5);
                routeStage = 1;
                ticks = 0;
            }
            case 1 -> {
                if (ticks < 30) {
                    return;
                }
                BlockPos goal = state.setGoal(lanePos(route.goalLane(), route.goalX(), route.goalY(), 1));
                log("route {}: goal {}", route.label(), goal == null ? "none" : goal.toShortString());
                routeStage = 2;
                ticks = 0;
            }
            case 2 -> {
                PathResult result = view.currentResult();
                if ((result == null || result.steps().isEmpty() || view.computing()) && ticks < PATH_WAIT_TICKS) {
                    return;
                }
                routeCost = describeRoute(route, result);
                walkTicks = 0;
                stuckTicks = 0;
                routeStage = 3;
                ticks = 0;
            }
            case 3 -> walk(mc, view, route);
            default -> {
            }
        }
    }

    /** 経路がどのレーンのエスカレーター（x=0..15）を何手使ったかと、見積もった値段（tick）を出す。 */
    private double describeRoute(Route route, PathResult result) {
        if (result == null || result.steps().isEmpty()) {
            log("route {}: no path", route.label());
            return 0;
        }
        int[] perLane = new int[Lane.values().length];
        double cost = 0;
        StringBuilder cells = new StringBuilder();
        for (PathStep s : result.steps()) {
            cost += s.cost();
            int x = s.pos().getX() - base.getX();
            int z = s.pos().getZ() - base.getZ();
            if (x >= 0 && x <= 15 && z >= 0 && z % LANE_GAP < 2 && z / LANE_GAP < perLane.length) {
                perLane[z / LANE_GAP]++;
            }
            cells.append(' ').append(x).append(',').append(s.pos().getY() - BASE_Y).append(',').append(z);
        }
        StringBuilder lanes = new StringBuilder();
        for (Lane lane : Lane.values()) {
            lanes.append(lane.label).append('=').append(perLane[lane.ordinal()]).append(' ');
        }
        log("route {}: {} ({} steps, cost {} ticks) lanes used: {}| cells:{}", route.label(), result.termination(),
                result.steps().size(), String.format(Locale.ROOT, "%.1f", cost), lanes, cells);
        return cost;
    }

    /** 経路の一番近い手の次の手へ向かって疾走する。組み直しで経路が変わっても追える。 */
    private void walk(Minecraft mc, PathfindingState.NavigationView view, Route route) {
        walkTicks++;
        if (view.arrived() || view.goal() == null) {
            log("route {}: arrived in {} ticks (estimated {})", route.label(), walkTicks,
                    String.format(Locale.ROOT, "%.1f", routeCost));
            nextRoute();
            return;
        }
        double moved = Math.abs(mc.player.getX() - lastX) + Math.abs(mc.player.getY() - lastY);
        lastX = mc.player.getX();
        lastY = mc.player.getY();
        stuckTicks = moved < 0.01 ? stuckTicks + 1 : 0;
        if (stuckTicks > 60 || walkTicks > 800) {
            log("route {}: gave up after {} ticks at x={} y={} z={}", route.label(), walkTicks,
                    String.format(Locale.ROOT, "%.2f", mc.player.getX() - base.getX()),
                    String.format(Locale.ROOT, "%.2f", mc.player.getY() - BASE_Y),
                    String.format(Locale.ROOT, "%.2f", mc.player.getZ() - base.getZ()));
            nextRoute();
            return;
        }
        PathResult result = view.currentResult();
        if (result == null || result.steps().isEmpty()) {
            release(mc);
            return;
        }
        // 人が描かれた線をなぞる場合を見るため、経路のセルではなく本番のPathGeometryの折れ線を追う
        double[][] line = drawnLine(mc, result);
        double[] aim = pursue(line[0], line[1], mc.player.getX(), mc.player.getZ(), LOOKAHEAD_BLOCKS);
        if (walkTicks == 1) {
            logLine(route, line);
        }
        double dx = aim[0] - mc.player.getX();
        double dz = aim[1] - mc.player.getZ();
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(true);
    }

    /** 先読みの距離。小さいほど角で線から外れない（体が線の上にいる）。 */
    private static final double LOOKAHEAD_BLOCKS = 0.6;

    private static java.lang.reflect.Method geometryBuild;
    private static java.lang.reflect.Field geometryX;
    private static java.lang.reflect.Field geometryZ;

    /** 本番の描画と同じ{@code PathGeometry.build}で作った線のX・Z。PathGeometryはclientパッケージの内部なのでリフレクションで呼ぶ。 */
    static double[][] drawnLine(Minecraft mc, PathResult result) {
        try {
            if (geometryBuild == null) {
                Class<?> type = Class.forName("net.prason.xaeronav.client.PathGeometry");
                geometryBuild = type.getDeclaredMethod("build", net.minecraft.world.level.Level.class, PathResult.class,
                        BlockPos.class);
                geometryBuild.setAccessible(true);
                geometryX = type.getDeclaredField("pointX");
                geometryX.setAccessible(true);
                geometryZ = type.getDeclaredField("pointZ");
                geometryZ.setAccessible(true);
            }
            Object geometry = geometryBuild.invoke(null, mc.level, result, result.steps().get(0).pos());
            return new double[][] {(double[]) geometryX.get(geometry), (double[]) geometryZ.get(geometry)};
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build PathGeometry", e);
        }
    }

    /** 折れ線上で現在地に一番近い点から{@code ahead}だけ先の点。 */
    static double[] pursue(double[] xs, double[] zs, double px, double pz, double ahead) {
        if (xs.length == 1) {
            return new double[] {xs[0], zs[0]};
        }
        int bestSegment = 0;
        double bestT = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < xs.length; i++) {
            double sx = xs[i + 1] - xs[i];
            double sz = zs[i + 1] - zs[i];
            double len2 = sx * sx + sz * sz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - xs[i]) * sx + (pz - zs[i]) * sz) / len2));
            double qx = xs[i] + sx * t - px;
            double qz = zs[i] + sz * t - pz;
            double d = qx * qx + qz * qz;
            if (d < best) {
                best = d;
                bestSegment = i;
                bestT = t;
            }
        }
        double left = ahead;
        double cx = xs[bestSegment] + (xs[bestSegment + 1] - xs[bestSegment]) * bestT;
        double cz = zs[bestSegment] + (zs[bestSegment + 1] - zs[bestSegment]) * bestT;
        for (int i = bestSegment; i + 1 < xs.length; i++) {
            double ex = xs[i + 1] - cx;
            double ez = zs[i + 1] - cz;
            double len = Math.hypot(ex, ez);
            if (len >= left) {
                return new double[] {cx + ex / len * left, cz + ez / len * left};
            }
            left -= len;
            cx = xs[i + 1];
            cz = zs[i + 1];
        }
        return new double[] {xs[xs.length - 1], zs[zs.length - 1]};
    }

    /** 線の点を基準からの相対座標で出す（エスカレーターの上では列の中心からずれているか見る）。 */
    private void logLine(Route route, double[][] line) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < line[0].length; i++) {
            points.append(String.format(Locale.ROOT, " %.2f,%.2f", line[0][i] - base.getX(), line[1][i] - base.getZ()));
        }
        log("route {}: drawn line (x,z):{}", route.label(), points);
    }

    private void nextRoute() {
        release(mc());
        PathfindingState.INSTANCE.clear();
        routeIndex++;
        routeStage = 0;
        ticks = 0;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private void teleport(IntegratedServer server, Lane lane, double x, double y, float yaw) {
        teleport(server, lane, x, y, yaw, 1.0);
    }

    private void teleport(IntegratedServer server, Lane lane, double x, double y, float yaw, double dz) {
        double z = base.getZ() + lane.ordinal() * LANE_GAP + dz;
        double worldX = base.getX() + x;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            player.teleportTo(player.serverLevel(), worldX, y, z, yaw, 0f);
        });
    }

    private static void release(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private void writeCsv(Minecraft mc) {
        Path out = mc.gameDirectory.toPath().resolve("escalator-" + name + ".csv");
        try {
            Files.write(out, csv);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log("wrote {}", out);
    }

    private static BlockState with(BlockState state, String property, String value) {
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals(property)) {
                return withValue(state, p, value);
            }
        }
        throw new IllegalStateException("no property " + property + " on " + state);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value)
                .orElseThrow(() -> new IllegalStateException(property.getName() + "=" + value + " on " + state)));
    }

    private static void log(String format, Object... args) {
        XaeroNav.LOGGER.info("escalator check: " + format, args);
    }

    private void finish(Minecraft mc, String why) {
        log("finished: {}", why);
        stage = 99;
        mc.stop();
    }
}
