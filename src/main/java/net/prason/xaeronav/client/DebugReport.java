package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MinecartState;
import net.prason.xaeronav.platform.ModPresence;
import net.prason.xaeronav.util.BlockDistance;
import net.prason.xaeronav.util.GameCompat;
import net.prason.xaeronav.xaero.XaeroHookHealth;
import net.prason.xaeronav.xaero.XaeroHooks;
import org.jspecify.annotations.Nullable;

/**
 * {@code /xaeronav debug}の中身。不具合報告に貼ってもらう前提なので、チャット・ログ・クリップボードへ
 * 同じ英語の行を出す（チャットの翻訳を通すと、報告を受け取る側が読めない言語になりうる）。
 *
 * <p>経路を引き直したりはしない。いま持っている状態を写すだけなので、何度打っても案内は変わらない。
 */
final class DebugReport {

    private DebugReport() {
    }

    static void send(NavCommandSink out) {
        List<String> lines = lines(Minecraft.getInstance());
        for (String line : lines) {
            out.success(TextCompat.literal(line));
        }
        String text = String.join("\n", lines);
        XaeroNav.LOGGER.info("XaeroNav debug report:\n{}", text);
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        out.success(TextCompat.translatable("commands.xaeronav.debug_copied"));
    }

    private static List<String> lines(Minecraft mc) {
        List<String> lines = new ArrayList<>();
        lines.add("== XaeroNav debug ==");
        lines.add("versions: xaeronav " + ModPresence.version(XaeroNav.MOD_ID)
                + ", minecraft " + ModPresence.version("minecraft") + ", " + loader()
                + ", minimap " + versionOrMissing("xaerominimap") + ", worldmap " + versionOrMissing("xaeroworldmap"));
        Runtime runtime = Runtime.getRuntime();
        lines.add("runtime: java " + Runtime.version() + ", " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", heap " + ((runtime.totalMemory() - runtime.freeMemory()) >> 20)
                + "/" + (runtime.maxMemory() >> 20) + "MB, cpus " + runtime.availableProcessors());

        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            lines.add("world: not in a world");
            return lines;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        lines.add("world: " + (server == null ? "multiplayer" : "singleplayer") + ", dimension "
                + GameCompat.dimensionId(level.dimension()) + ", seed "
                + (server == null ? "unknown (multiplayer)" : String.valueOf(server.overworld().getSeed()))
                + ", render distance " + ClientCompat.renderDistance(mc.options) + " chunks");
        BlockPos at = player.blockPosition();
        lines.add("player: " + at.toShortString());

        PathfindingState state = PathfindingState.INSTANCE;
        PathfindingState.DebugState debug = state.debugState();
        BlockPos goal = state.goal();
        if (goal == null) {
            lines.add("goal: none");
        } else {
            lines.add("goal: " + goal.toShortString() + " in " + GameCompat.dimensionId(debug.goalDimension())
                    + (debug.unresolvedGoal() == null ? "" : " (requested " + debug.unresolvedGoal().toShortString()
                            + ", column not loaded yet)")
                    + ", horizontal " + Math.round(BlockDistance.horizontal(at, goal))
                    + " blocks, dy " + (goal.getY() - at.getY()));
        }
        lines.add("state: computing=" + state.computing() + ", arrived=" + state.arrived()
                + ", flying=" + state.flying() + ", stuck=" + (state.stuckReason() == null ? "none" : state.stuckReason())
                + ", awaiting nav graph=" + debug.awaitingNavGraph());
        lines.add(pathLine(state.currentResult(), debug));
        if (state.flying()) {
            FlightRoute flight = state.flightRoute();
            lines.add("flight route: points=" + flight.points().size() + ", termination=" + flight.termination()
                    + ", expanded=" + flight.expandedNodes());
        }
        lines.add("long route: waypoints=" + debug.coarseWaypoints() + ", reaches goal=" + debug.coarseReachedGoal()
                + ", pending regions=" + debug.coarsePendingRegions() + ", refined=" + debug.refinedRouteInUse()
                + ", passed waypoints=" + debug.passedWaypoints());
        lines.add("nav graph: ready=" + debug.navGraphReady() + ", failed recently=" + debug.navGraphFailedRecently()
                + ", stalled searches=" + debug.stalledSearches());
        lines.add("last refusals: splice=" + orNone(debug.spliceRefusal())
                + ", seam repair=" + orNone(debug.seamRepairRefusal())
                + ", unstandable target=" + (debug.unstandableTarget() == null ? "none"
                        : debug.unstandableTarget().toShortString()));
        lines.add(RailMemory.INSTANCE.debugLine());
        lines.add(minecartLine(level, player));
        lines.add(hooksLine());
        lines.add(configLine());
        return lines;
    }

    private static String pathLine(@Nullable PathResult result, PathfindingState.DebugState debug) {
        if (result == null) {
            return "path: none";
        }
        List<PathStep> steps = result.steps();
        int bridges = 0;
        int digs = 0;
        for (PathStep step : steps) {
            if (step.bridging()) {
                bridges++;
            }
            if (!step.digCells().isEmpty()) {
                digs++;
            }
        }
        return "path: mode=" + debug.mode()
                + (debug.waypointIndex() < 0 ? "" : " #" + debug.waypointIndex())
                + ", steps=" + steps.size() + ", progress=" + PathProgress.INSTANCE.indexFor(result)
                + ", complete=" + result.complete() + ", termination=" + result.termination()
                + ", expanded=" + result.expandedNodes() + ", placements=" + bridges + ", digs=" + digs
                + (steps.isEmpty() ? "" : ", end=" + steps.get(steps.size() - 1).pos().toShortString());
    }

    /** トロッコで走る経路が出るか。出ないときは理由を言う（実験的トロッコでは黙って止めているので）。 */
    private static String minecartLine(Level level, Player player) {
        if (ChunkView.experimentalMinecarts(level)) {
            return "minecart rides: disabled (experimental minecarts are on in this world)";
        }
        MinecartState cart = ChunkView.minecart(level, player);
        if (cart.riding()) {
            return String.format(Locale.ROOT, "minecart rides: riding on %d, %d, %d at %.3f blocks/tick",
                    cart.railX(), cart.railY(), cart.railZ(), cart.speed());
        }
        return cart.available() ? "minecart rides: on (carrying a minecart)" : "minecart rides: off (no minecart)";
    }

    private static String hooksLine() {
        StringBuilder text = new StringBuilder("xaero hooks:");
        for (XaeroHooks.Hook hook : XaeroHooks.Hook.values()) {
            String status = !ModPresence.isLoaded(hook.modId()) ? "mod missing"
                    : XaeroHooks.applied(hook) ? "ok" : "not applied";
            text.append(' ').append(hook.name().toLowerCase(Locale.ROOT)).append('=').append(status);
        }
        if (XaeroHookHealth.worldMapRenderBroken()) {
            text.append(", world map render never reached");
        }
        return text.toString();
    }

    private static String configLine() {
        XaeroNavConfig config = XaeroNavConfig.INSTANCE;
        return "config: digging=" + config.diggingEnabled() + ", bridging=" + config.bridgingEnabled()
                + ", lavaBridging=" + config.movementOptions().lavaBridgingEnabled()
                + ", strictLimits=" + config.strictLimits() + ", maxExpandedNodes=" + config.maxExpandedNodes()
                + ", searchMargin=" + config.searchHorizontalMargin() + ", flightRouting=" + config.flightRoutingEnabled();
    }

    private static String loader() {
        if (ModPresence.isLoaded("neoforge")) {
            return "neoforge " + ModPresence.version("neoforge");
        }
        if (ModPresence.isLoaded("forge")) {
            return "forge " + ModPresence.version("forge");
        }
        return "fabric " + ModPresence.version("fabricloader") + " + fabric-api " + versionOrMissing("fabric-api");
    }

    private static String versionOrMissing(String modId) {
        return ModPresence.isLoaded(modId) ? ModPresence.version(modId) : "missing";
    }

    private static String orNone(@Nullable String value) {
        return value == null ? "none" : value;
    }
}
