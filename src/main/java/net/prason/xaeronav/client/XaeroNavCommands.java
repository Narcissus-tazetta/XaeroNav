package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.platform.ModPresence;
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
import net.prason.xaeronav.xaero.XaeroHookHealth;
import net.prason.xaeronav.xaero.XaeroHooks;
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

    /**
     * ローダーが持つdispatcherへ載せるコマンドツリー。
     *
     * <p>ツリーの中身はローダーに依存しないが、brigadierのsource型は依存する
     * （NeoForgeは{@code CommandSourceStack}、Fabricは{@code FabricClientCommandSource}）。
     * source型を型引数にし、実際にsourceへ触る2つの操作——応答の宛先と座標引数の解決——だけを
     * 呼び出し側から受け取る。
     */
    public static <S> LiteralArgumentBuilder<S> tree(Function<CommandContext<S>, NavCommandSink> sink,
            BlockPosReader<S> blockPos) {
        return XaeroNavCommands.<S>literal("xaeronav")
                .then(XaeroNavCommands.<S>literal("goto")
                        .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    PathfindingState.INSTANCE.setGoal(blockPos.read(ctx, "pos"));
                                    // 指定座標ではなく解決後の目的地を出す。Yはその列で実際に立てる高さへ
                                    // 寄せられるので、指定したままを表示すると案内先と食い違って見える
                                    BlockPos resolved = PathfindingState.INSTANCE.goal();
                                    sink.apply(ctx).success(Component.translatable("commands.xaeronav.goal_walk",
                                            resolved.toShortString()));
                                    return 1;
                                })))
                .then(XaeroNavCommands.<S>literal("clear")
                        .executes(ctx -> {
                            PathfindingState.INSTANCE.clear();
                            sink.apply(ctx).success(Component.translatable("commands.xaeronav.cleared"));
                            return 1;
                        }))
                .then(XaeroNavCommands.<S>literal("version")
                        .executes(ctx -> {
                            sink.apply(ctx).success(
                                    Component.translatable("commands.xaeronav.version", modVersion()));
                            return 1;
                        }))
                .then(XaeroNavCommands.<S>literal("debug")
                        .then(XaeroNavCommands.<S>literal("mapdata")
                                .executes(ctx -> reportMapData(sink.apply(ctx), DEFAULT_MAPDATA_RADIUS_CHUNKS))
                                .then(XaeroNavCommands.<S, Integer>argument("radiusChunks",
                                        IntegerArgumentType.integer(1, 512))
                                        .executes(ctx -> reportMapData(sink.apply(ctx),
                                                IntegerArgumentType.getInteger(ctx, "radiusChunks")))))
                        .then(XaeroNavCommands.<S>literal("route")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportRoute(sink.apply(ctx),
                                                blockPos.read(ctx, "pos")))))
                        .then(XaeroNavCommands.<S>literal("corridor")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportCorridor(sink.apply(ctx),
                                                blockPos.read(ctx, "pos")))))
                        .then(XaeroNavCommands.<S>literal("probe")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportProbe(sink.apply(ctx),
                                                blockPos.read(ctx, "pos")))))
                        .then(XaeroNavCommands.<S>literal("hooks")
                                .executes(ctx -> reportHooks(sink.apply(ctx))))
                        .then(XaeroNavCommands.<S>literal("flight")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportFlight(sink.apply(ctx),
                                                blockPos.read(ctx, "pos"))))));
    }

    /**
     * Xaero連携が今どうなっているかを1行ずつ出す。「地図に線が出ない」の切り分けは、
     * 連携先のMODが入っていない / mixinが当たっていない / 当たっているが描かれていない、の
     * どれなのかが分からないと進まない。
     */
    private static int reportHooks(NavCommandSink out) {
        for (XaeroHooks.Hook hook : XaeroHooks.Hook.values()) {
            Component name = Component.translatable(hook.nameKey());
            if (!ModPresence.isLoaded(hook.modId())) {
                out.success(Component.translatable("commands.xaeronav.hooks_mod_missing", name, hook.modId()));
            } else if (!XaeroHooks.applied(hook)) {
                out.success(Component.translatable("commands.xaeronav.hooks_not_applied", name));
            } else {
                out.success(Component.translatable("commands.xaeronav.hooks_ok", name));
            }
        }
        if (XaeroHookHealth.worldMapRenderBroken()) {
            out.failure(Component.translatable("commands.xaeronav.hooks_render_broken"));
        }
        return 1;
    }

    /**
     * {@code pos}引数からブロック座標を取り出す。
     *
     * <p>引数型（{@link BlockPosArgument}）自体はsource型を問わないが、`~`相対座標の解決には
     * {@code CommandSourceStack}が要るので、そこだけローダー側に任せる。
     */
    @FunctionalInterface
    public interface BlockPosReader<S> {
        BlockPos read(CommandContext<S> ctx, String name);
    }

    private static <S> LiteralArgumentBuilder<S> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <S, T> RequiredArgumentBuilder<S, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    /** 実機デバッグ用: 今読み込まれているビルドがどのgitコミットかを確認する（ビルド時にmod_versionへ埋め込み済み）。 */
    private static String modVersion() {
        return ModPresence.version(XaeroNav.MOD_ID);
    }

    /** {@link #reportRoute}が読む範囲を、始点と終点の周りにどれだけ広げるか（チャンク）。 */
    private static final int ROUTE_PADDING_CHUNKS = 32;

    /** 一辺がこれを超える範囲は読まない。粗い地図とはいえ、無制限だと配列確保だけで固まる。 */
    private static final int ROUTE_MAX_SPAN_CHUNKS = 1024;

    /**
     * 段階Aの目視確認用。実際の案内は開始せず、{@link CoarseRouter}が引いた中間目標をその場で
     * チャットに列挙するだけ。実データの海や山で意図通り曲がるかは、これで見るしかない。
     */
    private static int reportRoute(NavCommandSink out, BlockPos goal) {
        return withCoarseRoute(out, goal, (start, waypoints) -> {
            for (int i = 0; i < waypoints.size(); i++) {
                int number = i + 1;
                BlockPos waypoint = waypoints.get(i);
                out.success(Component.translatable("commands.xaeronav.route_waypoint",
                        number, waypoints.size(), waypoint.toShortString()));
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
    private static int withCoarseRoute(NavCommandSink out, BlockPos goal, RouteDetail detail) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        if (!XaeroPresence.mapPresent()) {
            out.failure(Component.translatable("commands.xaeronav.mapdata_unavailable"));
            return 0;
        }

        BlockPos start = player.blockPosition();
        long startNanos = System.nanoTime();
        CoarseRouter.Route route =
                computeRouteOrFail(out, start, goal, ChunkView.boatAvailable(player));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        if (route == null) {
            return 0;
        }

        if (route.isEmpty()) {
            if (route.reachedGoal()) {
                out.success(Component.translatable("commands.xaeronav.route_same_chunk"));
                return 1;
            }
            out.failure(Component.translatable("commands.xaeronav.route_none", elapsedMillis));
            return 0;
        }

        List<BlockPos> waypoints = route.waypoints();
        out.success(Component.translatable(
                route.reachedGoal() ? "commands.xaeronav.route_summary_reached"
                        : "commands.xaeronav.route_summary_partial",
                waypoints.size(), elapsedMillis));
        detail.report(start, waypoints);
        return 1;
    }

    /**
     * {@link #reportRoute}と{@link #reportCorridor}が共有する層1の計算。範囲が
     * {@link #ROUTE_MAX_SPAN_CHUNKS}を超える場合は失敗を送って{@code null}を返す。
     */
    private static CoarseRouter.Route computeRouteOrFail(NavCommandSink out, BlockPos start, BlockPos goal,
                                                          boolean boatAvailable) {
        int minChunkX = (Math.min(start.getX(), goal.getX()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkX = (Math.max(start.getX(), goal.getX()) >> 4) + ROUTE_PADDING_CHUNKS;
        int minChunkZ = (Math.min(start.getZ(), goal.getZ()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkZ = (Math.max(start.getZ(), goal.getZ()) >> 4) + ROUTE_PADDING_CHUNKS;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        if (chunksX > ROUTE_MAX_SPAN_CHUNKS || chunksZ > ROUTE_MAX_SPAN_CHUNKS) {
            out.failure(Component.translatable("commands.xaeronav.route_too_far"));
            return null;
        }
        CoarseMap map = XaeroMapReader.readSurface(minChunkX, minChunkZ, chunksX, chunksZ,
                (start.getY() + goal.getY()) / 2);
        // 診断コマンドは既定の重み付けをそのまま見せる（溶岩の梯子はPathfindingState側の話）
        return CoarseRouter.findRoute(map, start, goal, boatAvailable, CoarseRouter.BridgePolicy.ALLOW);
    }

    /**
     * 長距離ルート層2（ブロック解像度の地表グラフ）の目視確認用。層1のwaypoint列を隣接ペアで結び、
     * 線分ごとに{@link CorridorLegSolver}で廊下を切り出して既存の{@link AStarPathfinder}を走らせる。
     * {@code goto}（ライブナビ）も同じ{@link CorridorLegSolver}を非同期に使ってwaypointを精緻化するが、
     * こちらはその場でチャットに結果を出す同期実行の確認用コマンドとして独立に残す。
     */
    private static int reportCorridor(NavCommandSink out, BlockPos goal) {
        return withCoarseRoute(out, goal, (start, waypoints) -> {
            List<BlockPos> legs = new ArrayList<>();
            legs.add(start);
            legs.addAll(waypoints);
            int legCount = legs.size() - 1;
            for (int i = 0; i < legCount; i++) {
                reportCorridorLeg(out, i + 1, legCount, legs.get(i), legs.get(i + 1));
            }
        });
    }

    private static void reportCorridorLeg(NavCommandSink out, int index, int total, BlockPos from, BlockPos to) {
        long startNanos = System.nanoTime();
        CorridorLegSolver.PreparedLeg prepared = CorridorLegSolver.prepare(from, to);
        if (prepared.view() == null) {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            out.failure(Component.translatable(
                    "commands.xaeronav.corridor_no_data", index, total, elapsedMillis, prepared.pendingRegions()));
            return;
        }
        PathResult result = new AStarPathfinder(prepared.view(), CorridorLegSolver.SEARCH_LIMITS)
                .search(prepared.from(), prepared.to(), () -> false);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        out.success(Component.translatable(
                result.complete() ? "commands.xaeronav.corridor_leg_reached" : "commands.xaeronav.corridor_leg_partial",
                index, total, result.steps().size(), elapsedMillis, prepared.pendingRegions()));
    }

    /**
     * Xaeroの地図からどれだけ地形が読めているかをその場で確かめるためのもの。長距離ルートは
     * このデータの上に組み立てるので、まず「どこまで読めているか」が見えないと何も判断できない。
     */
    private static int reportMapData(NavCommandSink out, int radiusChunks) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        if (!XaeroPresence.mapPresent()) {
            out.failure(Component.translatable("commands.xaeronav.mapdata_unavailable"));
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
        out.success(Component.translatable("commands.xaeronav.mapdata_summary",
                side * 16, known, total, percent, elapsedMillis));

        XaeroMapReader.RegionStats regions = XaeroMapReader.surveyRegions(
                centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side, referenceY);
        out.success(Component.translatable("commands.xaeronav.mapdata_regions",
                regions.loaded(), regions.pendingLoad(), regions.inRange()));

        if (regions.pendingLoad() > 0) {
            int requested = XaeroMapReader.requestLoad(
                    centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side, side, referenceY);
            out.success(Component.translatable("commands.xaeronav.mapdata_requested",
                    requested));
        }

        reportKindHistogram(out, map, centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side);
        reportMapLayers(out, centerChunkX - radiusChunks, centerChunkZ - radiusChunks, side);

        // 実際に立っているYに最も近い床を報告する。粗い地図の高さは洞窟レイヤーのcaveStartから
        // 下向きに走査した結果なので、足元と食い違っていないかはこの2つを比べないと分からない。
        // このセルが複数の床を持つ（＝上下に独立した通路が重なっている）ことがある旨も添える
        int hereFloorCount = map.floorCount(centerChunkX, centerChunkZ);
        int hereFloor = map.nearestFloor(centerChunkX, centerChunkZ, referenceY);
        byte hereKind = hereFloor < 0 ? CoarseMap.NO_DATA : map.kindAtFloor(centerChunkX, centerChunkZ, hereFloor);
        int hereHeight = hereFloor < 0 ? 0 : map.heightAtFloor(centerChunkX, centerChunkZ, hereFloor);
        out.success(Component.translatable("commands.xaeronav.mapdata_here",
                describeKind(hereKind), hereHeight, referenceY, hereFloorCount));
        return 1;
    }

    /**
     * 粗い地図の地形種別の内訳。{@link CoarseRouter}で溶岩だけが通行不能（他は未知でも通れる）なので、
     * 長距離ルートが途中で打ち切られたとき、溶岩がどれだけ通行可能領域を削っているかがここで分かる。
     *
     * <p>セルではなく<b>床</b>単位で数える——1セルが複数の床を持ちうる（天井のある次元で
     * 上下に独立した通路が重なる）ので、セル単位だと実際に読めているデータ量を過小に見せる。
     */
    private static void reportKindHistogram(NavCommandSink out, CoarseMap map,
                                             int minChunkX, int minChunkZ, int side) {
        int land = 0;
        int water = 0;
        int lava = 0;
        int lavaMixed = 0;
        int voidCells = 0;
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
                        case CoarseMap.VOID -> voidCells++;
                        default -> noData++;
                    }
                }
            }
        }
        // 割合は既知セルに対して出す。全体に対してだと未探索で薄まって、
        // 通行可能領域がどれだけ削られているかが見えない
        int known = land + water + lava + lavaMixed + voidCells;
        int lavaPercent = known == 0 ? 0 : lava * 100 / known;
        final int landCount = land;
        final int waterCount = water;
        final int lavaCount = lava;
        final int lavaMixedCount = lavaMixed;
        final int voidCount = voidCells;
        final int noDataCount = noData;
        out.success(Component.translatable("commands.xaeronav.mapdata_kinds",
                landCount, waterCount, lavaCount, lavaMixedCount, voidCount, noDataCount, lavaPercent));
    }

    /**
     * Xaeroがこの範囲のデータをどのレイヤーに持っているかを並べる。ネザーのように空の無い次元では
     * 地表レイヤーが空になり、データが{@code caveStart >> 4}のY帯ごとに分かれる——長距離ルートが
     * 効かないときに、地形が読めていないのか読む場所を間違えているのかを切り分けるためのもの。
     */
    private static void reportMapLayers(NavCommandSink out, int minChunkX, int minChunkZ, int side) {
        out.success(Component.translatable("commands.xaeronav.mapdata_cave_mode",
                XaeroMapReader.caveModeType()));

        List<XaeroMapReader.LayerProbe> probes = XaeroMapReader.probeLayers(minChunkX, minChunkZ, side, side);
        if (probes.isEmpty()) {
            out.success(Component.translatable("commands.xaeronav.mapdata_layers_none"));
            return;
        }
        for (XaeroMapReader.LayerProbe probe : probes) {
            out.success(Component.translatable("commands.xaeronav.mapdata_layer",
                    probe.isSurface()
                            ? Component.translatable("commands.xaeronav.mapdata_layer_surface")
                            : Component.literal(String.valueOf(probe.caveLayer())),
                    probe.knownCells(), probe.minHeight(), probe.maxHeight()));
        }
    }

    private static Component describeKind(byte kind) {
        return Component.translatable(switch (kind) {
            case CoarseMap.LAND -> "commands.xaeronav.mapdata_land";
            case CoarseMap.WATER -> "commands.xaeronav.mapdata_water";
            case CoarseMap.LAVA -> "commands.xaeronav.mapdata_lava";
            case CoarseMap.LAVA_MIXED -> "commands.xaeronav.mapdata_lava_mixed";
            case CoarseMap.VOID -> "commands.xaeronav.mapdata_void";
            default -> "commands.xaeronav.mapdata_none";
        });
    }

    /**
     * 徒歩の詳細A*を{@code goto}と同じ設定・範囲で同期実行し、到達可否・展開ノード数・移動種類の
     * 内訳（斜め昇降が実際に選ばれているか）をその場で確認する診断コマンド。「多分できてる」で
     * 終わらせず数値で裏取りするためのもの。
     *
     * <p>1回目は通常のマージンで探索する。続けて同じ箱のまま掘削だけを切って探索し、展開ノード数を
     * 並べて報告する（掘削が分岐数に効いている量を測るため）。展開ノード数の上限に達して届かなかった
     * 場合は、上限を外して時間だけで打ち切る計測も行う（必要な展開ノード数そのものを知るため）。
     * 範囲内なのに届かなかった場合は、{@link PathfindingState}の「探索範囲を読み込み済みチャンクいっぱい
     * まで広げる再挑戦」と同じ条件・同じ広さでもう一度探索し、その結果も併せて報告する。
     */
    /**
     * 空中経路を1回だけ解いて中身を出す。飛んでいる必要は無い——地上から投げて格子の粒度や
     * 展開数を確かめられる方が、飛びながら画面を読むより遥かに測りやすい。
     */
    private static int reportFlight(NavCommandSink out, BlockPos goal) {
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
                FlightNavState.tuning());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        Vec3 tail = route.tail();
        out.success(Component.translatable("commands.xaeronav.flight_result",
                route.points().size(), route.termination().name(), route.expandedNodes(), elapsedMillis,
                route.cellBlocks(), rockets ? 1 : 0));
        if (tail != null) {
            out.success(Component.translatable("commands.xaeronav.flight_tail",
                    Mth.floor(tail.x), Mth.floor(tail.y), Mth.floor(tail.z),
                    Mth.floor(Math.sqrt(tail.distanceToSqr(Vec3.atCenterOf(goal))))));
        }
        if (level.dimensionType().hasCeiling()) {
            // 描画距離の外は粗い層（Xaeroの地図由来）が担当する。中間目標が0本なら、
            // その方向のデータが地図に無い＝未訪問ということ。
            // 範囲もマージンも本番と同じ道を通す——別々に組むと測った数字が案内と食い違う
            CoarseRouter.Route coarse = FlightNavState.solveCoarseRoute(
                    level, player.blockPosition(), goal, rockets);
            out.success(Component.translatable("commands.xaeronav.flight_coarse",
                    coarse.waypoints().size(), coarse.reachedGoal() ? 1 : 0));
        }
        // これは測るだけのコマンドで、目的地は設定しない。線を出すには goto が要る
        out.success(Component.translatable("commands.xaeronav.flight_diagnostic_only"));
        return 1;
    }

    private static int reportProbe(NavCommandSink out, BlockPos goal) {
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
        reportPlacementAvailability(out, normalView);
        reportGoalCell(out, normalView, normalBounds, start, goal, renderRadius);

        ProbeRun normal = runProbe(normalView, normalBounds, start, goal);
        reportProbeRun(out, "commands.xaeronav.probe_normal", normal);

        // 掘削が有効だと、固体セルがすべて「有限コストで進入可能」になる（ChunkView#computeState）。
        // 探索空間が地表という面から山という体積に変わるので、同じ箱・同じ上限のまま掘削だけを切って
        // 走らせた展開ノード数との差が、掘削が分岐数に効いている量そのものになる。
        // 箱の広さを変えずに比べるため、チャンク参照を共有する派生ビューを使う（同スレッドで逐次実行）
        if (XaeroNavConfig.INSTANCE.diggingEnabled()) {
            ProbeRun noDigging = runProbe(normalView.withoutDigging(), normalBounds, start, goal);
            reportProbeRun(out, "commands.xaeronav.probe_no_digging", noDigging);
        } else {
            out.success(Component.translatable("commands.xaeronav.probe_no_digging_skipped"));
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
            out.success(Component.translatable(
                    "commands.xaeronav.probe_widen_skipped_budget", maxExpandedNodes));
            // 上限に張り付いた回どうしを比べても展開ノード数は必ず一致するので、そこからは何も分からない。
            // 打ち切りを時間だけに任せて「この地形で目的地まで実際に何ノード要るのか」を測り、
            // 設定値が足りないだけなのか、時間予算でも届かない＝探索側の問題なのかを切り分ける
            ProbeRun unbounded = runProbe(normalView, normalBounds, start, goal,
                    new SearchLimits(PROBE_UNBOUNDED_MAX_EXPANDED_NODES,
                            AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS,
                            XaeroNavConfig.INSTANCE.heuristicWeight()));
            reportProbeRun(out, "commands.xaeronav.probe_unbounded", unbounded);
        } else {
            out.success(Component.translatable(widenTriggered
                    ? "commands.xaeronav.probe_widen_triggered" : "commands.xaeronav.probe_widen_skipped"));
        }
        if (widenTriggered) {
            ProbeRun widened = runProbe(level, player, start, goal, renderRadius,
                    PathfindingState.verticalSearchMargin(level, true), renderRadius);
            reportProbeRun(out, "commands.xaeronav.probe_widened", widened);
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
    private static void reportGoalCell(NavCommandSink out, ChunkView view, SearchBounds bounds,
                                        BlockPos start, BlockPos goal, int renderRadius) {
        int x = goal.getX();
        int y = goal.getY();
        int z = goal.getZ();
        if (!bounds.contains(x, y, z)) {
            // 箱はゴール方向へrenderRadiusで切られる。長距離ナビの目的地をそのまま渡すと必ずここへ
            // 落ちるので、どこまでなら測れるのかを併せて出さないと同じ指定を繰り返すことになる
            out.success(Component.translatable("commands.xaeronav.probe_goal_outside_bounds",
                    Math.round(horizontalDistance(start, goal)), renderRadius));
            return;
        }
        long feetCell = view.cell(x, y, z);
        long headCell = view.cell(x, y + 1, z);
        long belowCell = view.cell(x, y - 1, z);
        // 足場が無くても、そこへ置いて立てるなら到達しうる（addBridgeが床を作って着く）。
        // 置ける状態かを見ずに「原理的に到達しない」と言い切ると、橋で届く目的地まで
        // 探索の側の問題として誤読させる
        boolean floorReachable = CellData.standable(belowCell)
                || view.canPlaceBlocks()
                && (CellData.lava(belowCell) || CellData.replaceable(belowCell));
        Component feet = describeGoalCell(feetCell);
        Component head = describeGoalCell(headCell);
        if (floorReachable && enterable(feetCell) && enterable(headCell)) {
            out.success(Component.translatable("commands.xaeronav.probe_goal_ok", feet, head));
        } else {
            out.success(Component.translatable("commands.xaeronav.probe_goal_blocked",
                    Component.translatable(floorReachable ? "commands.xaeronav.probe_goal_cell_ok"
                            : "commands.xaeronav.probe_goal_cell_blocked"), feet, head));
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
        AStarPathfinder pathfinder = new AStarPathfinder(view, limits);
        long startNanos = System.nanoTime();
        PathResult result = pathfinder.search(start, goal, () -> false);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        return new ProbeRun(start, result, bounds, elapsedMillis, view.loadedChunksInBounds(),
                view.totalChunksInBounds(), pathfinder.trimmedPlacements(), pathfinder.bridgeRunCapBlocked());
    }

    private static void reportProbeRun(NavCommandSink out, String labelKey, ProbeRun run) {
        PathResult result = run.result();
        SearchBounds bounds = run.bounds();
        int spanX = bounds.maxX() - bounds.minX() + 1;
        int spanZ = bounds.maxZ() - bounds.minZ() + 1;
        Component label = Component.translatable(labelKey);
        out.success(Component.translatable(
                result.complete() ? "commands.xaeronav.probe_summary_reached"
                        : "commands.xaeronav.probe_summary_partial",
                label, result.steps().size(), result.expandedNodes(), run.elapsedMillis(), spanX, spanZ,
                result.distinctNodes()));
        if (run.loadedChunks() < run.totalChunks()) {
            // 未読み込みチャンクは進入不可セルとして扱われる（ChunkView#capture）。
            // 探索範囲の縁がまだ届いていないだけで、少し待てば同じ座標でも結果が変わりうる
            out.success(Component.translatable("commands.xaeronav.probe_chunks_missing",
                    run.loadedChunks(), run.totalChunks()));
        }
        if (!result.steps().isEmpty()) {
            String breakdown = describeMovements(result.steps(), run.start());
            out.success(Component.translatable("commands.xaeronav.probe_movements", breakdown));
            reportWorkload(out, result.steps(), run.start());
        }
        if (run.trimmedPlacements() > 0) {
            // 切り落とした後の経路を見るだけでは「橋を架けなかった」と「架けたが渡り切れなかった」が
            // 同じ設置0に見える。原因が正反対なので、切った事実の方を出す
            out.success(Component.translatable("commands.xaeronav.probe_trimmed",
                    run.trimmedPlacements()));
        }
        if (run.bridgeRunCapBlocked()) {
            out.success(Component.translatable("commands.xaeronav.probe_bridge_cap_blocked",
                    XaeroNavConfig.INSTANCE.maxBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxLavaBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxVoidBridgeRunBlocks()));
        }
    }

    /**
     * 足場を置く移動を提示できる状態か。設定と持ち物の両方が要る（{@code ChunkView#capture}）。
     *
     * <p>これを出さないと、ホットバーにブロックが1つも無いだけの回と、地形の側で橋が架からない回が
     * 同じ「設置0」に見える。橋の挙動を調べているときに最初に潰すべき前提なので、探索の前に出す。
     */
    private static void reportPlacementAvailability(NavCommandSink out, ChunkView view) {
        if (view.canPlaceBlocks()) {
            // 予算（経路全体で置ける総数）も併記する。上限3つは「1本が何マス続いてよいか」しか
            // 言っておらず、橋が短く切り上げられている理由が持ち物の枚数だった回を、
            // これが無いと地形の側の話と取り違える
            out.success(Component.translatable("commands.xaeronav.probe_placing_on",
                    XaeroNavConfig.INSTANCE.maxBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxLavaBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxVoidBridgeRunBlocks(),
                    view.placedBlockBudget()));
        } else {
            out.success(Component.translatable("commands.xaeronav.probe_placing_off",
                    Component.translatable(XaeroNavConfig.INSTANCE.bridgingEnabled()
                            ? "commands.xaeronav.probe_placing_no_blocks"
                            : "commands.xaeronav.probe_placing_disabled")));
        }
    }

    /**
     * 経路が要求する作業量。{@code MovementType}の内訳だけでは見えないものを出す。
     *
     * <p>橋と柱は{@code MoveKind}の区別で、公開APIの{@link MovementType}には出てこない
     * （どちらもTRAVERSE/ASCENDとして数えられる）ので、設置先の有無から数え直す。
     *
     * <p>累積昇降量を並べるのは、上下動が「地形上どうしようもない量」なのか「経路の選び方が
     * 生んだ量」なのかを、直線距離と比べて判断するため。数字が無いままでは、上下動の多さは
     * 印象でしか語れない。
     */
    private static void reportWorkload(NavCommandSink out, List<PathStep> steps, BlockPos start) {
        int placements = 0;
        int digCells = 0;
        int climbed = 0;
        int descended = 0;
        BlockPos previous = start;
        for (PathStep step : steps) {
            if (step.bridging()) {
                placements++;
            }
            digCells += step.digCells().size();
            int dy = step.pos().getY() - previous.getY();
            if (dy > 0) {
                climbed += dy;
            } else {
                descended -= dy;
            }
            previous = step.pos();
        }
        Component line = Component.translatable("commands.xaeronav.probe_workload",
                placements, digCells, climbed, descended,
                steps.get(steps.size() - 1).pos().getY() - start.getY());
        out.success(line);
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

    /**
     * {@link #runProbe}1回分の結果。{@link #reportProbeRun}が探索範囲のサイズを求めるのに始点も要る。
     *
     * @param trimmedPlacements 提示できないとして末尾から落とした設置ステップ数。0でない＝橋は架かったが
     *                          渡り切れなかった、という「設置0」とは正反対の結論になる
     */
    private record ProbeRun(BlockPos start, PathResult result, SearchBounds bounds, long elapsedMillis,
                             int loadedChunks, int totalChunks, int trimmedPlacements,
                             boolean bridgeRunCapBlocked) {
    }

    /**
     * {@link PathfindingState}が範囲を広げた再挑戦を発動する条件と同じ水平距離の測り方（{@code y}は見ない）。
     * ここでも同じ判定を再現する必要があるため、同じ式を独立に持つ。
     */
    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
