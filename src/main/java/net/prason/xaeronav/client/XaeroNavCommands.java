package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.corridor.CorridorLegSolver;
import net.prason.xaeronav.pathfinding.flight.FlightLineRouter;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.flight.FlightRouter;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;

/**
 * {@code /xaeronav} のクライアントコマンド。
 *
 * <p>案内そのものに使うのは{@code goto} / {@code clear} / {@code version}の3つだけ。残りは経路を
 * 引かずに数値をチャットへ出す計測用なので{@code debug}の下へ入れてある——同じ高さに並べると、
 * 目的地を設定したいだけの人のタブ補完が計測用の名前で埋まる。
 */
public final class XaeroNavCommands {

    /** 既定の確認範囲（チャンク）。既定の描画距離より十分広く、読み取りが一瞬で終わる程度。 */
    private static final int DEFAULT_MAPDATA_RADIUS_CHUNKS = 64;

    /**
     * {@code probe}の上限なし計測で使う展開ノード数。時間上限（ライブナビと同じ）の方が先に効くよう、
     * 到達し得ない大きさにしてある。実質の打ち切りは時間側なので、この計測は
     * 「ライブナビと同じ時間予算で何ノードまで展開でき、届くのか」を測ることになる。
     */
    private static final int PROBE_UNBOUNDED_MAX_EXPANDED_NODES = 100_000_000;

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("xaeronav")
                .then(Commands.literal("goto")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    PathfindingState.INSTANCE.setGoal(BlockPosArgument.getBlockPos(ctx, "pos"));
                                    // 指定座標ではなく解決後の目的地を出す。Yはその列で実際に立てる高さへ
                                    // 寄せられるので、指定したままを表示すると案内先と食い違って見える
                                    BlockPos resolved = PathfindingState.INSTANCE.goal();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.translatable("commands.xaeronav.goal_walk",
                                                    resolved.toShortString()), false);
                                    return 1;
                                })))
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            PathfindingState.INSTANCE.clear();
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("commands.xaeronav.cleared"), false);
                            return 1;
                        }))
                .then(Commands.literal("version")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("commands.xaeronav.version", modVersion()), false);
                            return 1;
                        }))
                .then(Commands.literal("debug")
                        .then(Commands.literal("mapdata")
                                .executes(ctx -> reportMapData(ctx.getSource(), DEFAULT_MAPDATA_RADIUS_CHUNKS))
                                .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 512))
                                        .executes(ctx -> reportMapData(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radiusChunks")))))
                        .then(Commands.literal("route")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportRoute(ctx.getSource(),
                                                BlockPosArgument.getBlockPos(ctx, "pos")))))
                        .then(Commands.literal("corridor")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportCorridor(ctx.getSource(),
                                                BlockPosArgument.getBlockPos(ctx, "pos")))))
                        .then(Commands.literal("probe")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportProbe(ctx.getSource(),
                                                BlockPosArgument.getBlockPos(ctx, "pos")))))
                        .then(Commands.literal("flight")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportFlight(ctx.getSource(),
                                                BlockPosArgument.getBlockPos(ctx, "pos")))))));
    }

    /** 実機デバッグ用: 今読み込まれているビルドがどのgitコミットかを確認する（ビルド時にmod_versionへ埋め込み済み）。 */
    private static String modVersion() {
        return ModList.get().getModContainerById(XaeroNav.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
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
        return withCoarseRoute(source, goal, (start, waypoints) -> {
            for (int i = 0; i < waypoints.size(); i++) {
                int number = i + 1;
                BlockPos waypoint = waypoints.get(i);
                source.sendSuccess(() -> Component.translatable("commands.xaeronav.route_waypoint",
                        number, waypoints.size(), waypoint.toShortString()), false);
            }
        });
    }

    /** {@link #withCoarseRoute}が層1の要約を出した後に呼ぶ、コマンドごとの続き。 */
    @FunctionalInterface
    private interface RouteDetail {
        void report(BlockPos start, List<BlockPos> waypoints);
    }

    /**
     * 2つの診断コマンドが共有する前半——プレイヤーと地図データの確認、層1の実行、経路が
     * 引けなかった場合の報告、waypoint数と所要時間の要約まで。要約まで出せたときだけ
     * {@code detail}を呼ぶ。
     */
    private static int withCoarseRoute(CommandSourceStack source, BlockPos goal, RouteDetail detail) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        if (!XaeroPresence.mapPresent()) {
            source.sendFailure(Component.translatable("commands.xaeronav.mapdata_unavailable"));
            return 0;
        }

        BlockPos start = player.blockPosition();
        long startNanos = System.nanoTime();
        CoarseRouter.Route route =
                computeRouteOrFail(source, start, goal, ChunkView.boatAvailable(player));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        if (route == null) {
            return 0;
        }

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
        detail.report(start, waypoints);
        return 1;
    }

    /**
     * {@link #reportRoute}と{@link #reportCorridor}が共有する層1の計算。範囲が
     * {@link #ROUTE_MAX_SPAN_CHUNKS}を超える場合は失敗を送って{@code null}を返す。
     */
    private static CoarseRouter.Route computeRouteOrFail(CommandSourceStack source, BlockPos start, BlockPos goal,
                                                          boolean boatAvailable) {
        int minChunkX = (Math.min(start.getX(), goal.getX()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkX = (Math.max(start.getX(), goal.getX()) >> 4) + ROUTE_PADDING_CHUNKS;
        int minChunkZ = (Math.min(start.getZ(), goal.getZ()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkZ = (Math.max(start.getZ(), goal.getZ()) >> 4) + ROUTE_PADDING_CHUNKS;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        if (chunksX > ROUTE_MAX_SPAN_CHUNKS || chunksZ > ROUTE_MAX_SPAN_CHUNKS) {
            source.sendFailure(Component.translatable("commands.xaeronav.route_too_far"));
            return null;
        }
        CoarseMap map = XaeroMapReader.readSurface(minChunkX, minChunkZ, chunksX, chunksZ,
                (start.getY() + goal.getY()) / 2);
        // 診断コマンドは既定の重み付けをそのまま見せる（溶岩の梯子はPathfindingState側の話）
        return CoarseRouter.findRoute(map, start, goal, boatAvailable, CoarseRouter.LavaPolicy.ALLOW);
    }

    /**
     * 長距離ルート層2（ブロック解像度の地表グラフ）の目視確認用。層1のwaypoint列を隣接ペアで結び、
     * 線分ごとに{@link CorridorLegSolver}で廊下を切り出して既存の{@link AStarPathfinder}を走らせる。
     * {@code goto}（ライブナビ）も同じ{@link CorridorLegSolver}を非同期に使ってwaypointを精緻化するが、
     * こちらはその場でチャットに結果を出す同期実行の確認用コマンドとして独立に残す。
     */
    private static int reportCorridor(CommandSourceStack source, BlockPos goal) {
        return withCoarseRoute(source, goal, (start, waypoints) -> {
            List<BlockPos> legs = new ArrayList<>();
            legs.add(start);
            legs.addAll(waypoints);
            int legCount = legs.size() - 1;
            for (int i = 0; i < legCount; i++) {
                reportCorridorLeg(source, i + 1, legCount, legs.get(i), legs.get(i + 1));
            }
        });
    }

    private static void reportCorridorLeg(CommandSourceStack source, int index, int total, BlockPos from, BlockPos to) {
        long startNanos = System.nanoTime();
        CorridorLegSolver.PreparedLeg prepared = CorridorLegSolver.prepare(from, to);
        if (prepared.view() == null) {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            source.sendFailure(Component.translatable(
                    "commands.xaeronav.corridor_no_data", index, total, elapsedMillis, prepared.pendingRegions()));
            return;
        }
        PathResult result = new AStarPathfinder(prepared.view(), CorridorLegSolver.SEARCH_LIMITS)
                .search(prepared.from(), prepared.to(), () -> false);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        source.sendSuccess(() -> Component.translatable(
                result.complete() ? "commands.xaeronav.corridor_leg_reached" : "commands.xaeronav.corridor_leg_partial",
                index, total, result.steps().size(), elapsedMillis, prepared.pendingRegions()), false);
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
        int referenceY = player.blockPosition().getY();
        int side = radiusChunks * 2 + 1;
        long startNanos = System.nanoTime();
        CoarseMap map = XaeroMapReader.readSurface(
                centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side, referenceY);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        int known = map.knownCells();
        int total = map.totalCells();
        int percent = total == 0 ? 0 : known * 100 / total;
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_summary",
                side * 16, known, total, percent, elapsedMillis), false);

        XaeroMapReader.RegionStats regions = XaeroMapReader.surveyRegions(
                centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side, referenceY);
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_regions",
                regions.loaded(), regions.pendingLoad(), regions.inRange()), false);

        if (regions.pendingLoad() > 0) {
            int requested = XaeroMapReader.requestLoad(
                    centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side, referenceY);
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_requested",
                    requested), false);
        }

        reportKindHistogram(source, map, centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side);
        reportMapLayers(source, centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side);

        // 実際に立っているYに最も近い床を報告する。粗い地図の高さは洞窟レイヤーのcaveStartから
        // 下向きに走査した結果なので、足元と食い違っていないかはこの2つを比べないと分からない。
        // このセルが複数の床を持つ（＝上下に独立した通路が重なっている）ことがある旨も添える
        int hereFloorCount = map.floorCount(centerChunkX, centerChunkZ);
        int hereFloor = map.nearestFloor(centerChunkX, centerChunkZ, referenceY);
        byte hereKind = hereFloor < 0 ? CoarseMap.NO_DATA : map.kindAtFloor(centerChunkX, centerChunkZ, hereFloor);
        int hereHeight = hereFloor < 0 ? 0 : map.heightAtFloor(centerChunkX, centerChunkZ, hereFloor);
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_here",
                describeKind(hereKind), hereHeight, referenceY, hereFloorCount), false);
        return 1;
    }

    /**
     * 粗い地図の地形種別の内訳。{@link CoarseRouter}で溶岩だけが通行不能（他は未知でも通れる）なので、
     * 長距離ルートが途中で打ち切られたとき、溶岩がどれだけ通行可能領域を削っているかがここで分かる。
     *
     * <p>セルではなく<b>床</b>単位で数える——1セルが複数の床を持ちうる（天井のある次元で
     * 上下に独立した通路が重なる）ので、セル単位だと実際に読めているデータ量を過小に見せる。
     */
    private static void reportKindHistogram(CommandSourceStack source, CoarseMap map,
                                             int minChunkX, int minChunkZ, int side) {
        int land = 0;
        int water = 0;
        int lava = 0;
        int lavaMixed = 0;
        int noData = 0;
        for (int chunkX = minChunkX; chunkX < minChunkX + side; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ < minChunkZ + side; chunkZ++) {
                int floorCount = map.floorCount(chunkX, chunkZ);
                if (floorCount == 0) {
                    noData++;
                    continue;
                }
                for (int floor = 0; floor < floorCount; floor++) {
                    switch (map.kindAtFloor(chunkX, chunkZ, floor)) {
                        case CoarseMap.LAND -> land++;
                        case CoarseMap.WATER -> water++;
                        case CoarseMap.LAVA -> lava++;
                        case CoarseMap.LAVA_MIXED -> lavaMixed++;
                        default -> noData++;
                    }
                }
            }
        }
        // 割合は既知セルに対して出す。全体に対してだと未探索で薄まって、
        // 通行可能領域がどれだけ削られているかが見えない
        int known = land + water + lava + lavaMixed;
        int lavaPercent = known == 0 ? 0 : lava * 100 / known;
        final int landCount = land;
        final int waterCount = water;
        final int lavaCount = lava;
        final int lavaMixedCount = lavaMixed;
        final int noDataCount = noData;
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_kinds",
                landCount, waterCount, lavaCount, lavaMixedCount, noDataCount, lavaPercent), false);
    }

    /**
     * Xaeroがこの範囲のデータをどのレイヤーに持っているかを並べる。ネザーのように空の無い次元では
     * 地表レイヤーが空になり、データが{@code caveStart >> 4}のY帯ごとに分かれる——長距離ルートが
     * 効かないときに、地形が読めていないのか読む場所を間違えているのかを切り分けるためのもの。
     */
    private static void reportMapLayers(CommandSourceStack source, int minChunkX, int minChunkZ, int side) {
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_cave_mode",
                XaeroMapReader.caveModeType()), false);

        List<XaeroMapReader.LayerProbe> probes = XaeroMapReader.probeLayers(minChunkX, minChunkZ, side, side);
        if (probes.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_layers_none"), false);
            return;
        }
        for (XaeroMapReader.LayerProbe probe : probes) {
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.mapdata_layer",
                    probe.isSurface()
                            ? Component.translatable("commands.xaeronav.mapdata_layer_surface")
                            : Component.literal(String.valueOf(probe.caveLayer())),
                    probe.knownCells(), probe.minHeight(), probe.maxHeight()), false);
        }
    }

    private static Component describeKind(byte kind) {
        return Component.translatable(switch (kind) {
            case CoarseMap.LAND -> "commands.xaeronav.mapdata_land";
            case CoarseMap.WATER -> "commands.xaeronav.mapdata_water";
            case CoarseMap.LAVA -> "commands.xaeronav.mapdata_lava";
            case CoarseMap.LAVA_MIXED -> "commands.xaeronav.mapdata_lava_mixed";
            default -> "commands.xaeronav.mapdata_none";
        });
    }

    /**
     * 徒歩の詳細A*を{@code goto}と同じ設定・範囲で同期実行し、到達可否・展開ノード数・移動種類の
     * 内訳（斜め昇降が実際に選ばれているか）をその場で確認する診断コマンド。「多分できてる」で
     * 終わらせず数値で裏取りするためのもの（近距離レパートリー拡充・Phase 3）。
     *
     * <p>1回目は通常のマージンで探索する。続けて同じ箱のまま掘削だけを切って探索し、展開ノード数を
     * 並べて報告する（掘削が分岐数に効いている量を測るため）。展開ノード数の上限に達して届かなかった
     * 場合は、上限を外して時間だけで打ち切る計測も行う（必要な展開ノード数そのものを知るため）。
     * 範囲内なのに届かなかった場合は、{@link PathfindingState}のPhase 2（探索範囲を読み込み済み
     * チャンクいっぱいまで広げる再挑戦）と同じ条件・同じ広さでもう一度探索し、その結果も併せて報告する。
     */
    /**
     * 空中経路を1回だけ解いて中身を出す。飛んでいる必要は無い——地上から投げて格子の粒度や
     * 展開数を確かめられる方が、飛びながら画面を読むより遥かに測りやすい。
     */
    private static int reportFlight(CommandSourceStack source, BlockPos goal) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return 0;
        }

        int renderRadius = mc.options.getEffectiveRenderDistance() * 16;
        SearchBounds bounds = SearchBounds.around(level, player.blockPosition(), goal,
                renderRadius, FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        boolean rockets = player.getInventory().contains(stack -> stack.getItem() instanceof FireworkRocketItem);

        long startedAt = System.nanoTime();
        FlightRoute route = FlightRouter.route(view, player.position(), Vec3.atCenterOf(goal), rockets,
                PathfindingState.flightTuning());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        Vec3 tail = route.tail();
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.flight_result",
                route.points().size(), route.termination().name(), route.expandedNodes(), elapsedMillis,
                route.cellBlocks(), rockets ? 1 : 0), false);
        if (tail != null) {
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.flight_tail",
                    Mth.floor(tail.x), Mth.floor(tail.y), Mth.floor(tail.z),
                    Mth.floor(Math.sqrt(tail.distanceToSqr(Vec3.atCenterOf(goal))))), false);
        }
        if (level.dimensionType().hasCeiling()) {
            // 描画距離の外は粗い層（Xaeroの地図由来）が担当する。中間目標が0本なら、
            // その方向のデータが地図に無い＝未訪問ということ。
            // 範囲もマージンも本番と同じ道を通す——別々に組むと測った数字が案内と食い違う
            CoarseRouter.Route coarse = PathfindingState.solveFlightCoarseRoute(
                    level, player.blockPosition(), goal, rockets);
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.flight_coarse",
                    coarse.waypoints().size(), coarse.reachedGoal() ? 1 : 0), false);
        }
        // これは測るだけのコマンドで、目的地は設定しない。線を出すには goto が要る
        source.sendSuccess(() -> Component.translatable("commands.xaeronav.flight_diagnostic_only"), false);
        return 1;
    }

    private static int reportProbe(CommandSourceStack source, BlockPos goal) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return 0;
        }

        BlockPos start = player.blockPosition();
        int renderRadius = mc.options.getEffectiveRenderDistance() * 16;
        int verticalMargin = PathfindingState.verticalSearchMargin(level, false);
        int normalMargin = XaeroNavConfig.INSTANCE.searchHorizontalMargin();

        SearchBounds normalBounds = SearchBounds.around(level, start, goal, normalMargin, verticalMargin,
                renderRadius);
        ChunkView normalView =
                ChunkView.capture(level, player, normalBounds, XaeroNavConfig.INSTANCE.movementOptions());
        reportGoalCell(source, normalView, normalBounds, start, goal, renderRadius);

        ProbeRun normal = runProbe(normalView, normalBounds, start, goal);
        reportProbeRun(source, "commands.xaeronav.probe_normal", normal);

        // 掘削が有効だと、固体セルがすべて「有限コストで進入可能」になる（ChunkView#computeState）。
        // 探索空間が地表という面から山という体積に変わるので、同じ箱・同じ上限のまま掘削だけを切って
        // 走らせた展開ノード数との差が、掘削が分岐数に効いている量そのものになる。
        // 箱の広さを変えずに比べるため、チャンク参照を共有する派生ビューを使う（同スレッドで逐次実行）
        if (XaeroNavConfig.INSTANCE.diggingEnabled()) {
            ProbeRun noDigging = runProbe(normalView.withoutDigging(), normalBounds, start, goal);
            reportProbeRun(source, "commands.xaeronav.probe_no_digging", noDigging);
        } else {
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.probe_no_digging_skipped"), false);
        }

        // 予算切れ（ノード数上限・時間上限）での未到達は、箱を広げても同じ上限に同じように当たるだけで
        // 結果は変わらない（実機で確認済み: 通常マージンと拡大後で展開ノード数が完全一致していた）。
        // ここで弾かないと、無駄なA*をもう1回投げたうえ「箱が原因」と誤読させる出力になる。
        // 時間上限で切れた回もここに含める——展開数だけを見ると「範囲が狭い」と誤読して
        // widenTriggeredに倒れてしまう
        int maxExpandedNodes = XaeroNavConfig.INSTANCE.maxExpandedNodes();
        boolean budgetExhausted = normal.result().budgetExhausted();
        boolean widenTriggered = !normal.result().complete() && !budgetExhausted
                && horizontalDistance(start, goal) <= renderRadius && normalMargin < renderRadius;
        if (!normal.result().complete() && budgetExhausted) {
            source.sendSuccess(() -> Component.translatable(
                    "commands.xaeronav.probe_widen_skipped_budget", maxExpandedNodes), false);
            // 上限に張り付いた回どうしを比べても展開ノード数は必ず一致するので、そこからは何も分からない。
            // 打ち切りを時間だけに任せて「この地形で目的地まで実際に何ノード要るのか」を測り、
            // 設定値が足りないだけなのか、時間予算でも届かない＝探索側の問題なのかを切り分ける
            ProbeRun unbounded = runProbe(normalView, normalBounds, start, goal,
                    new SearchLimits(PROBE_UNBOUNDED_MAX_EXPANDED_NODES,
                            AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS,
                            XaeroNavConfig.INSTANCE.heuristicWeight()));
            reportProbeRun(source, "commands.xaeronav.probe_unbounded", unbounded);
        } else {
            source.sendSuccess(() -> Component.translatable(widenTriggered
                    ? "commands.xaeronav.probe_widen_triggered" : "commands.xaeronav.probe_widen_skipped"), false);
        }
        if (widenTriggered) {
            ProbeRun widened = runProbe(level, player, start, goal, renderRadius,
                    PathfindingState.verticalSearchMargin(level, true), renderRadius);
            reportProbeRun(source, "commands.xaeronav.probe_widened", widened);
        }
        return 1;
    }

    /**
     * ゴールのセルそのものが探索の終了条件を満たしうるかを報告する。到達判定は座標の完全一致
     * （{@code AStarPathfinder#reachedGoal}）なので、ゴールが箱の外にある・足元に立てる地面が無い・
     * 体の2セルに入れないのいずれでも、予算をいくら積んでも到達しない。展開ノード数だけを見ていると
     * この「そもそも終われない探索」を予算不足と読み違える。
     *
     * <p>体の2セルは掘って入れるなら通れるので、掘れないセル（溶岩・危険セル・掘削禁止設定）だけを
     * 到達不能として扱う。素の空きかどうかで判定すると、掘れば普通に到達する目的地まで不能と報告する。
     */
    private static void reportGoalCell(CommandSourceStack source, ChunkView view, SearchBounds bounds,
                                        BlockPos start, BlockPos goal, int renderRadius) {
        int x = goal.getX();
        int y = goal.getY();
        int z = goal.getZ();
        if (!bounds.contains(x, y, z)) {
            // 箱はゴール方向へrenderRadiusで切られる。長距離ナビの目的地をそのまま渡すと必ずここへ
            // 落ちるので、どこまでなら測れるのかを併せて出さないと同じ指定を繰り返すことになる
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.probe_goal_outside_bounds",
                    Math.round(horizontalDistance(start, goal)), renderRadius), false);
            return;
        }
        long feetCell = view.cell(x, y, z);
        long headCell = view.cell(x, y + 1, z);
        boolean groundBelow = CellData.standable(view.cell(x, y - 1, z));
        Component feet = describeGoalCell(feetCell);
        Component head = describeGoalCell(headCell);
        if (groundBelow && enterable(feetCell) && enterable(headCell)) {
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.probe_goal_ok", feet, head), false);
        } else {
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.probe_goal_blocked",
                    Component.translatable(groundBelow ? "commands.xaeronav.probe_goal_cell_ok"
                            : "commands.xaeronav.probe_goal_cell_blocked"), feet, head), false);
        }
    }

    /** 掘って入れるセルも通れる。掘れないセル（溶岩・危険セル・掘削禁止設定）だけが進入不可。 */
    private static boolean enterable(long cell) {
        return CellData.occupiableWithoutDigging(cell) || !Double.isInfinite(CellData.digTicks(cell));
    }

    private static Component describeGoalCell(long cell) {
        if (CellData.occupiableWithoutDigging(cell)) {
            return Component.translatable("commands.xaeronav.probe_goal_cell_ok");
        }
        return Component.translatable(Double.isInfinite(CellData.digTicks(cell))
                ? "commands.xaeronav.probe_goal_cell_blocked" : "commands.xaeronav.probe_goal_cell_dig");
    }

    private static ProbeRun runProbe(Level level, Player player, BlockPos start, BlockPos goal,
                                      int horizontalMargin, int verticalMargin, int renderRadius) {
        SearchBounds bounds = SearchBounds.around(level, start, goal, horizontalMargin, verticalMargin, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.movementOptions());
        return runProbe(view, bounds, start, goal);
    }

    private static ProbeRun runProbe(ChunkView view, SearchBounds bounds, BlockPos start, BlockPos goal) {
        return runProbe(view, bounds, start, goal, XaeroNavConfig.INSTANCE.searchLimits());
    }

    private static ProbeRun runProbe(ChunkView view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                      SearchLimits limits) {
        long startNanos = System.nanoTime();
        PathResult result = new AStarPathfinder(view, limits).search(start, goal, () -> false);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        return new ProbeRun(start, result, bounds, elapsedMillis, view.loadedChunksInBounds(), view.totalChunksInBounds());
    }

    private static void reportProbeRun(CommandSourceStack source, String labelKey, ProbeRun run) {
        PathResult result = run.result();
        SearchBounds bounds = run.bounds();
        int spanX = bounds.maxX() - bounds.minX() + 1;
        int spanZ = bounds.maxZ() - bounds.minZ() + 1;
        Component label = Component.translatable(labelKey);
        source.sendSuccess(() -> Component.translatable(
                result.complete() ? "commands.xaeronav.probe_summary_reached"
                        : "commands.xaeronav.probe_summary_partial",
                label, result.steps().size(), result.expandedNodes(), run.elapsedMillis(), spanX, spanZ,
                result.distinctNodes()), false);
        if (run.loadedChunks() < run.totalChunks()) {
            // 未読み込みチャンクは進入不可セルとして扱われる（design doc外・ChunkView#capture）。
            // 探索範囲の縁がまだ届いていないだけで、少し待てば同じ座標でも結果が変わりうる
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.probe_chunks_missing",
                    run.loadedChunks(), run.totalChunks()), false);
        }
        if (!result.steps().isEmpty()) {
            String breakdown = describeMovements(result.steps(), run.start());
            source.sendSuccess(() -> Component.translatable("commands.xaeronav.probe_movements", breakdown), false);
        }
    }

    /**
     * ステップ数を{@link MovementType}ごとに集計する。ASCEND/DESCENDは、直前の地点からXZ両方に
     * ずれているものを「斜め」として別集計する（{@code MoveKind.DIAGONAL_ASCEND/DESCEND}は
     * astarパッケージ内部の型で公開APIには出てこないが、カーディナルのAscend/Descendは定義上
     * どちらか一方の軸にしか動かないので、両軸が動いていれば斜めだと判定できる）。
     */
    private static String describeMovements(List<PathStep> steps, BlockPos start) {
        Map<MovementType, Integer> counts = new EnumMap<>(MovementType.class);
        Map<MovementType, Integer> diagonalCounts = new EnumMap<>(MovementType.class);
        BlockPos previous = start;
        for (PathStep step : steps) {
            MovementType type = step.movement();
            counts.merge(type, 1, Integer::sum);
            if ((type == MovementType.ASCEND || type == MovementType.DESCEND)
                    && step.pos().getX() != previous.getX() && step.pos().getZ() != previous.getZ()) {
                diagonalCounts.merge(type, 1, Integer::sum);
            }
            previous = step.pos();
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<MovementType, Integer> entry : counts.entrySet()) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(entry.getKey()).append(' ').append(entry.getValue());
            Integer diagonal = diagonalCounts.get(entry.getKey());
            if (diagonal != null) {
                text.append(" diag=").append(diagonal);
            }
        }
        return text.toString();
    }

    /** {@link #runProbe}1回分の結果。{@link #reportProbeRun}が探索範囲のサイズを求めるのに始点も要る。 */
    private record ProbeRun(BlockPos start, PathResult result, SearchBounds bounds, long elapsedMillis,
                             int loadedChunks, int totalChunks) {
    }

    /**
     * {@link PathfindingState}のPhase 2発動条件と同じ水平距離の測り方（{@code y}は見ない）。
     * ここでも同じ判定を再現する必要があるため、同じ式を独立に持つ。
     */
    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
