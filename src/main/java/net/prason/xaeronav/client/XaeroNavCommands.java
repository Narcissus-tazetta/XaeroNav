package net.prason.xaeronav.client;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;

/**
 * {@code /xaeronav goto <pos>}（徒歩・掘削） / {@code /xaeronav flyto <pos>}（エリトラ、design doc §5-3の
 * 明示的モード選択） / {@code /xaeronav clear}。
 * Xaeroの右クリックメニュー等からの目的地設定はPhase 2後半（Xaeroアダプタ層）で追加する想定の暫定UI。
 */
public final class XaeroNavCommands {

    /** 既定の確認範囲（チャンク）。既定の描画距離より十分広く、読み取りが一瞬で終わる程度。 */
    private static final int DEFAULT_MAPDATA_RADIUS_CHUNKS = 64;

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("xaeronav")
                .then(Commands.literal("goto")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    PathfindingState.INSTANCE.setGoal(pos);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable("commands.xaeronav.goal_walk",
                                                    pos.toShortString()), false);
                                    return 1;
                                })))
                .then(Commands.literal("flyto")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    ElytraNavState.INSTANCE.requestPath(pos);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable("commands.xaeronav.goal_elytra",
                                                    pos.toShortString()), false);
                                    warnMissingFlightGear(ctx.getSource());
                                    return 1;
                                })))
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            PathfindingState.INSTANCE.clear();
                            ElytraNavState.INSTANCE.clear();
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("commands.xaeronav.cleared"), false);
                            return 1;
                        }))
                .then(Commands.literal("mapdata")
                        .executes(ctx -> reportMapData(ctx.getSource(), DEFAULT_MAPDATA_RADIUS_CHUNKS))
                        .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> reportMapData(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radiusChunks")))))
                .then(Commands.literal("route")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> reportRoute(ctx.getSource(),
                                        BlockPosArgument.getBlockPos(ctx, "pos"))))));
    }

    /** {@link #reportRoute}が読む範囲を、始点と終点の周りにどれだけ広げるか（チャンク）。 */
    private static final int ROUTE_PADDING_CHUNKS = 32;

    /** 一辺がこれを超える範囲は読まない。粗い地図とはいえ、無制限だと配列確保だけで固まる。 */
    private static final int ROUTE_MAX_SPAN_CHUNKS = 1024;

    /**
     * 段階Aの目視確認用。実際の案内は開始せず、{@link CoarseRouter}が引いた中間目標をその場で
     * チャットに列挙するだけ。実データの海や山で意図通り曲がるかは、これで見るしかない。
     */
    private static int reportRoute(CommandSourceStack source, BlockPos goal) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        if (!XaeroPresence.mapPresent()) {
            source.sendFailure(Component.translatable("commands.xaeronav.mapdata_unavailable"));
            return 0;
        }

        BlockPos start = player.blockPosition();
        int minChunkX = (Math.min(start.getX(), goal.getX()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkX = (Math.max(start.getX(), goal.getX()) >> 4) + ROUTE_PADDING_CHUNKS;
        int minChunkZ = (Math.min(start.getZ(), goal.getZ()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkZ = (Math.max(start.getZ(), goal.getZ()) >> 4) + ROUTE_PADDING_CHUNKS;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        if (chunksX > ROUTE_MAX_SPAN_CHUNKS || chunksZ > ROUTE_MAX_SPAN_CHUNKS) {
            source.sendFailure(Component.translatable("commands.xaeronav.route_too_far"));
            return 0;
        }

        long startNanos = System.nanoTime();
        CoarseMap map = XaeroMapReader.readSurface(minChunkX, minChunkZ, chunksX, chunksZ);
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        if (route.isEmpty()) {
            if (route.reachedGoal()) {
                source.sendSuccess(() -> Component.translatable("commands.xaeronav.route_same_chunk"), false);
                return 1;
            }
            source.sendFailure(Component.translatable("commands.xaeronav.route_none", elapsedMillis));
            return 0;
        }

        List<BlockPos> waypoints = route.waypoints();
        source.sendSuccess(() -> Component.translatable(
                route.reachedGoal() ? "commands.xaeronav.route_summary_reached"
                        : "commands.xaeronav.route_summary_partial",
                waypoints.size(), elapsedMillis), false);
        for (int i = 0; i < waypoints.size(); i++) {
            int number = i + 1;
            BlockPos waypoint = waypoints.get(i);
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.route_waypoint",
                    number, waypoints.size(), waypoint.toShortString()), false);
        }
        return 1;
    }

    /**
     * Xaeroの地図からどれだけ地形が読めているかをその場で確かめるためのもの。長距離ルートは
     * このデータの上に組み立てるので、まず「どこまで読めているか」が見えないと何も判断できない。
     */
    private static int reportMapData(CommandSourceStack source, int radiusChunks) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        if (!XaeroPresence.mapPresent()) {
            source.sendFailure(Component.translatable("commands.xaeronav.mapdata_unavailable"));
            return 0;
        }

        int centerChunkX = player.blockPosition().getX() >> 4;
        int centerChunkZ = player.blockPosition().getZ() >> 4;
        int side = radiusChunks * 2 + 1;
        long startNanos = System.nanoTime();
        CoarseMap map = XaeroMapReader.readSurface(
                centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        int known = map.knownCells();
        int total = map.totalCells();
        int percent = total == 0 ? 0 : known * 100 / total;
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_summary",
                side * 16, known, total, percent, elapsedMillis), false);

        XaeroMapReader.RegionStats regions = XaeroMapReader.surveyRegions(
                centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side);
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_regions",
                regions.loaded(), regions.pendingLoad(), regions.inRange()), false);

        if (regions.pendingLoad() > 0) {
            int requested = XaeroMapReader.requestLoad(
                    centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side);
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_requested",
                    requested), false);
        }

        byte hereKind = map.kindAtChunk(centerChunkX, centerChunkZ);
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_here",
                describeKind(hereKind), map.heightAtChunk(centerChunkX, centerChunkZ)), false);
        return 1;
    }

    private static Component describeKind(byte kind) {
        return Component.translatable(switch (kind) {
            case CoarseMap.LAND -> "commands.xaeronav.mapdata_land";
            case CoarseMap.WATER -> "commands.xaeronav.mapdata_water";
            case CoarseMap.LAVA -> "commands.xaeronav.mapdata_lava";
            default -> "commands.xaeronav.mapdata_none";
        });
    }

    /**
     * エリトラ経路は「地形の上まで高度を上げて越える」前提で引く。ところがエリトラは
     * ロケット花火が無ければ上昇できず、滑空で下るぶんしか進めない。線だけ引いても
     * 辿れないので、実行できない前提が欠けていることはその場で伝える。
     */
    private static void warnMissingFlightGear(CommandSourceStack source) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !ElytraItem.isFlyEnabled(chest)) {
            source.sendFailure(Component.translatable("commands.xaeronav.no_elytra"));
        }
        if (!player.getInventory().contains(stack -> stack.is(Items.FIREWORK_ROCKET))) {
            source.sendFailure(Component.translatable("commands.xaeronav.no_fireworks"));
        }
    }
}
