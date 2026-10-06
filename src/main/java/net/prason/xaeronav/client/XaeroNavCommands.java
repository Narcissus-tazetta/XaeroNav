package net.prason.xaeronav.client;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.platform.ModPresence;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.DiagnosticJobRunner;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.flight.FlightLineRouter;
import net.prason.xaeronav.pathfinding.flight.FlightGuide;
import net.prason.xaeronav.pathfinding.flight.FlightRouter;
import net.prason.xaeronav.pathfinding.world.BlockRegistryCompat;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;
import net.prason.xaeronav.util.GameCompat;
import net.prason.xaeronav.util.BlockDistance;

/**
 * {@code /xaeronav} のクライアントコマンド。
 *
 * <p>案内そのものに使うのは{@code goto} / {@code clear} / {@code version}の3つだけ。{@code debug}は
 * 不具合報告用の状態の一覧（{@link DebugReport}）、{@code debug probe}はいまの目的地に向けて探索を
 * 測り直す計測用。どちらも座標を取らない——報告する人に「どの座標で打てばいいか」を考えさせない。
 */
public final class XaeroNavCommands {

    /** 地図データを確かめる範囲（チャンク）。既定の描画距離より十分広く、読み取りが一瞬で終わる程度。 */
    private static final int MAPDATA_RADIUS_CHUNKS = 64;

    /**
     * {@code probe}の上限なし計測で使う展開ノード数。時間上限（ライブナビと同じ）の方が先に効くよう、
     * 到達し得ない大きさにしてある。実質の打ち切りは時間側なので、この計測は
     * 「ライブナビと同じ時間予算で何ノードまで展開でき、届くのか」を測ることになる。
     */
    private static final int PROBE_UNBOUNDED_MAX_EXPANDED_NODES = 100_000_000;

    /**
     * {@code probe}が使う専用の非同期実行基盤。ライブナビの
     * {@code PathfindingExecutor}とは別インスタンス・別スレッド——共有すると診断コマンドを
     * 打っただけで進行中の本番探索がキャンセルされてしまう。Xaeroの地図を読む層1部分
     * （{@link CoarseRouter}・{@link XaeroMapReader}）はメインスレッド専用のため対象外
     * （{@code FlightNavState}のクラスJavadoc参照）で、この基盤へ
     * 委ねるのは実際に重いA*探索/空中経路計算だけ。
     */
    private static final DiagnosticJobRunner DIAGNOSTIC =
            new DiagnosticJobRunner(runnable -> Minecraft.getInstance().execute(runnable));

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
                                    BlockPos resolved = PathfindingState.INSTANCE.setGoal(blockPos.read(ctx, "pos"));
                                    // 指定座標ではなく解決後の目的地を出す。Yはその列で実際に立てる高さへ
                                    // 寄せられるので、指定したままを表示すると案内先と食い違って見える
                                    if (resolved != null) {
                                        sink.apply(ctx).success(TextCompat.translatable("commands.xaeronav.goal_walk",
                                                resolved.toShortString()));
                                    }
                                    return 1;
                                })))
                .then(XaeroNavCommands.<S>literal("clear")
                        .executes(ctx -> {
                            PathfindingState.INSTANCE.clear();
                            sink.apply(ctx).success(TextCompat.translatable("commands.xaeronav.cleared"));
                            return 1;
                        }))
                .then(XaeroNavCommands.<S>literal("version")
                        .executes(ctx -> {
                            sink.apply(ctx).success(
                                    TextCompat.translatable("commands.xaeronav.version", modVersion()));
                            return 1;
                        }))
                .then(XaeroNavCommands.<S>literal("debug")
                        .executes(ctx -> {
                            DebugReport.send(sink.apply(ctx));
                            return 1;
                        })
                        .then(XaeroNavCommands.<S>literal("probe")
                                .executes(ctx -> reportProbe(sink.apply(ctx)))));
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

    /** {@link #reportCoarseRoute}が読む範囲を、始点と終点の周りにどれだけ広げるか（チャンク）。 */
    private static final int ROUTE_PADDING_CHUNKS = 32;

    /** 一辺がこれを超える範囲は読まない。粗い地図とはいえ、無制限だと配列確保だけで固まる。 */
    private static final int ROUTE_MAX_SPAN_CHUNKS = 1024;

    /**
     * いまの目的地まで層1（Xaeroの地図の上の長距離ルート）が引けるかを要約する。
     *
     * <p>地図の読み取りはXaero API契約によりメインスレッドで同期実行するが、その後の
     * {@link CoarseRouter#findRoute}はMinecraft/Xaero状態を読まない純粋な計算なので{@link #DIAGNOSTIC}の
     * ワーカーへ逃がす。
     */
    private static void reportCoarseRoute(NavCommandSink out, long generation, Player player, BlockPos goal) {
        BlockPos start = player.blockPosition();
        boolean boatAvailable = ChunkView.boatAvailable(player);
        CoarseMap map = readCoarseMapOrFail(out, start, goal);
        if (map == null) {
            return;
        }
        long startNanos = System.nanoTime();
        DIAGNOSTIC.submit(generation,
                // 診断は既定の重み付けをそのまま見せる（溶岩の梯子はPathfindingState側の話）
                cancelled -> CoarseRouter.findRoute(map, start, goal, boatAvailable, CoarseRouter.BridgePolicy.ALLOW),
                (route, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: diagnostic layer 1 search failed", error);
                        return;
                    }
                    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
                    if (route.isEmpty()) {
                        if (route.reachedGoal()) {
                            out.success(TextCompat.translatable("commands.xaeronav.route_same_chunk"));
                            return;
                        }
                        out.failure(TextCompat.translatable("commands.xaeronav.route_none", elapsedMillis));
                        return;
                    }
                    out.success(TextCompat.translatable(
                            route.reachedGoal() ? "commands.xaeronav.route_summary_reached"
                                    : "commands.xaeronav.route_summary_partial",
                            route.waypoints().size(), elapsedMillis));
                });
    }

    /**
     * 層1の地図読み取り。範囲が
     * {@link #ROUTE_MAX_SPAN_CHUNKS}を超える場合は失敗を送って{@code null}を返す。
     */
    private static CoarseMap readCoarseMapOrFail(NavCommandSink out, BlockPos start, BlockPos goal) {
        int minChunkX = (Math.min(start.getX(), goal.getX()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkX = (Math.max(start.getX(), goal.getX()) >> 4) + ROUTE_PADDING_CHUNKS;
        int minChunkZ = (Math.min(start.getZ(), goal.getZ()) >> 4) - ROUTE_PADDING_CHUNKS;
        int maxChunkZ = (Math.max(start.getZ(), goal.getZ()) >> 4) + ROUTE_PADDING_CHUNKS;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        if (chunksX > ROUTE_MAX_SPAN_CHUNKS || chunksZ > ROUTE_MAX_SPAN_CHUNKS) {
            out.failure(TextCompat.translatable("commands.xaeronav.route_too_far"));
            return null;
        }
        return XaeroMapReader.readSurface(minChunkX, minChunkZ, chunksX, chunksZ, (start.getY() + goal.getY()) / 2);
    }

    /**
     * Xaeroの地図からどれだけ地形が読めているかをその場で確かめるためのもの。長距離ルートは
     * このデータの上に組み立てるので、まず「どこまで読めているか」が見えないと何も判断できない。
     */
    private static void reportMapData(NavCommandSink out, Player player) {
        int centerChunkX = player.blockPosition().getX() >> 4;
        int centerChunkZ = player.blockPosition().getZ() >> 4;
        int referenceY = player.blockPosition().getY();
        int side = MAPDATA_RADIUS_CHUNKS * 2 + 1;
        int minChunkX = centerChunkX - MAPDATA_RADIUS_CHUNKS;
        int minChunkZ = centerChunkZ - MAPDATA_RADIUS_CHUNKS;
        long startNanos = System.nanoTime();
        CoarseMap map = XaeroMapReader.readSurface(minChunkX, minChunkZ, side, side, referenceY);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        int known = map.knownCells();
        int total = map.totalCells();
        int percent = total == 0 ? 0 : known * 100 / total;
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_summary",
                side * 16, known, total, percent, elapsedMillis));

        XaeroMapReader.RegionStats regions = XaeroMapReader.surveyRegions(minChunkX, minChunkZ, side, side, referenceY);
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_regions",
                regions.loaded(), regions.pendingLoad(), regions.inRange()));

        if (regions.pendingLoad() > 0) {
            int requested = XaeroMapReader.requestLoad(minChunkX, minChunkZ, side, side, referenceY);
            out.success(TextCompat.translatable("commands.xaeronav.mapdata_requested", requested));
        }

        reportKindHistogram(out, map, minChunkX, minChunkZ, side);
        reportMapLayers(out, minChunkX, minChunkZ, side);

        // 実際に立っているYに最も近い床を報告する。粗い地図の高さは洞窟レイヤーのcaveStartから
        // 下向きに走査した結果なので、足元と食い違っていないかはこの2つを比べないと分からない。
        // このセルが複数の床を持つ（＝上下に独立した通路が重なっている）ことがある旨も添える
        int hereFloorCount = map.floorCount(centerChunkX, centerChunkZ);
        int hereFloor = map.nearestFloor(centerChunkX, centerChunkZ, referenceY);
        byte hereKind = hereFloor < 0 ? CoarseMap.NO_DATA : map.kindAtFloor(centerChunkX, centerChunkZ, hereFloor);
        int hereHeight = hereFloor < 0 ? 0 : map.heightAtFloor(centerChunkX, centerChunkZ, hereFloor);
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_here",
                describeKind(hereKind), hereHeight, referenceY, hereFloorCount));
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
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_kinds",
                landCount, waterCount, lavaCount, lavaMixedCount, voidCount, noDataCount, lavaPercent));
    }

    /**
     * Xaeroがこの範囲のデータをどのレイヤーに持っているかを並べる。ネザーのように空の無い次元では
     * 地表レイヤーが空になり、データが{@code caveStart >> 4}のY帯ごとに分かれる——長距離ルートが
     * 効かないときに、地形が読めていないのか読む場所を間違えているのかを切り分けるためのもの。
     */
    private static void reportMapLayers(NavCommandSink out, int minChunkX, int minChunkZ, int side) {
        out.success(TextCompat.translatable("commands.xaeronav.mapdata_cave_mode",
                XaeroMapReader.caveModeType()));

        List<XaeroMapReader.LayerProbe> probes = XaeroMapReader.probeLayers(minChunkX, minChunkZ, side, side);
        if (probes.isEmpty()) {
            out.success(TextCompat.translatable("commands.xaeronav.mapdata_layers_none"));
            return;
        }
        for (XaeroMapReader.LayerProbe probe : probes) {
            out.success(TextCompat.translatable("commands.xaeronav.mapdata_layer",
                    probe.isSurface()
                            ? TextCompat.translatable("commands.xaeronav.mapdata_layer_surface")
                            : TextCompat.literal(String.valueOf(probe.caveLayer())),
                    probe.knownCells(), probe.minHeight(), probe.maxHeight()));
        }
    }

    private static Component describeKind(byte kind) {
        return TextCompat.translatable(switch (kind) {
            case CoarseMap.LAND -> "commands.xaeronav.mapdata_land";
            case CoarseMap.WATER -> "commands.xaeronav.mapdata_water";
            case CoarseMap.LAVA -> "commands.xaeronav.mapdata_lava";
            case CoarseMap.LAVA_MIXED -> "commands.xaeronav.mapdata_lava_mixed";
            case CoarseMap.VOID -> "commands.xaeronav.mapdata_void";
            default -> "commands.xaeronav.mapdata_none";
        });
    }

    /**
     * いまの目的地に向けて計測を走らせる。周りの地図データと層1の要約を出したうえで、滑空中なら空中経路を、
     * そうでなければ徒歩の詳細A*を測る。
     */
    private static int reportProbe(NavCommandSink out) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return 0;
        }
        BlockPos goal = PathfindingState.INSTANCE.goal();
        if (goal == null) {
            out.failure(TextCompat.translatable("commands.xaeronav.probe_no_goal"));
            return 0;
        }
        long generation = DIAGNOSTIC.begin();
        if (XaeroPresence.mapPresent()) {
            reportMapData(out, player);
            reportCoarseRoute(out, generation, player, goal);
        } else {
            out.success(TextCompat.translatable("commands.xaeronav.mapdata_unavailable"));
        }
        if (PathfindingState.INSTANCE.flying()) {
            reportFlight(out, generation, level, player, goal);
        } else {
            reportGroundProbe(out, generation, level, player, goal);
        }
        return 1;
    }

    /**
     * 空中経路を1回だけ解いて中身を出す。表示中の空中経路とは別に解き直すので、格子の粒度や展開数を
     * 本番の状態に左右されずに確かめられる。
     */
    private static void reportFlight(NavCommandSink out, long generation, Level level, Player player,
                                     BlockPos goal) {
        int renderRadius = ClientCompat.renderDistance(Minecraft.getInstance().options) * 16;
        BlockPos playerPos = player.blockPosition();
        SearchBounds bounds = SearchBounds.around(level, playerPos, goal,
                renderRadius, FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        boolean rockets = ChunkView.hasItem(GameCompat.inventory(player),
                stack -> stack.getItem() instanceof FireworkRocketItem);
        Vec3 start = player.position();
        Vec3 target = Vec3.atCenterOf(goal);

        out.success(TextCompat.translatable("commands.xaeronav.debug_running"));
        long startedAt = System.nanoTime();
        DIAGNOSTIC.submit(generation,
                cancelled -> FlightRouter.route(view, start, target, rockets, FlightNavState.tuning(),
                        FlightNavState.loadedHorizon(start, renderRadius), FlightGuide.NONE, cancelled),
                (route, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: flight probe failed", error);
                        return;
                    }
                    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                    Vec3 tail = route.tail();
                    out.success(TextCompat.translatable("commands.xaeronav.flight_result",
                            route.points().size(), route.termination().name(), route.expandedNodes(), elapsedMillis,
                            route.cellBlocks(), rockets ? 1 : 0));
                    if (tail != null) {
                        out.success(TextCompat.translatable("commands.xaeronav.flight_tail",
                                Mth.floor(tail.x), Mth.floor(tail.y), Mth.floor(tail.z),
                                Mth.floor(Math.sqrt(tail.distanceToSqr(target)))));
                    }
                    if (level.dimensionType().hasCeiling()) {
                        // 描画距離の外は粗い層（Xaeroの地図由来）が担当する。中間目標が0本なら、
                        // その方向のデータが地図に無い＝未訪問ということ。Xaeroを読むためメインスレッド
                        // 専用（FlightNavStateのクラスJavadoc参照）——ここは既にメインスレッドへ戻った後
                        CoarseRouter.Route coarse = FlightNavState.solveCoarseRoute(level, playerPos, goal, rockets);
                        out.success(TextCompat.translatable("commands.xaeronav.flight_coarse",
                                coarse.waypoints().size(), coarse.reachedGoal() ? 1 : 0));
                    }
                });
    }

    /**
     * 徒歩の詳細A*を{@code goto}と同じ設定・範囲で実行し、到達可否・展開ノード数・移動種類の内訳を出す。
     *
     * <p>1回目は通常のマージンで探索する。続けて同じ箱のまま掘削だけを切って探索し、展開ノード数を
     * 並べて報告する（掘削が分岐数に効いている量を測るため）。展開ノード数の上限に達して届かなかった
     * 場合は、上限を外して時間だけで打ち切る計測も行う（必要な展開ノード数そのものを知るため）。
     * 範囲内なのに届かなかった場合は、{@link PathfindingState}の「探索範囲を読み込み済みチャンクいっぱい
     * まで広げる再挑戦」と同じ条件・同じ広さでもう一度探索する。
     */
    private static void reportGroundProbe(NavCommandSink out, long generation, Level level, Player player,
                                          BlockPos destination) {
        BlockPos start = player.blockPosition();
        int renderRadius = ClientCompat.renderDistance(Minecraft.getInstance().options) * 16;
        BlockPos goal = probeTarget(out, start, destination, renderRadius);
        int verticalMargin = PathfindingState.verticalSearchMargin(level, false);
        int normalMargin = XaeroNavConfig.INSTANCE.searchHorizontalMargin();

        CapturedView normal = captureProbeView(level, player, start, goal, normalMargin, verticalMargin,
                renderRadius);
        reportPlacementAvailability(out, normal.view());
        reportGoalCell(out, level, normal.view(), normal.bounds(), start, goal, renderRadius);
        out.success(TextCompat.translatable("commands.xaeronav.debug_running"));

        DIAGNOSTIC.submit(generation,
                cancelled -> runProbe(normal.view(), normal.bounds(), start, goal, cancelled),
                (normalRun, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: probe failed (normal budget)", error);
                        return;
                    }
                    reportProbeRun(out, "commands.xaeronav.probe_normal", normalRun);
                    continueProbeAfterNormal(out, generation, level, player, start, goal, renderRadius,
                            normalMargin, normal, normalRun);
                });
    }

    /**
     * 目的地が描画距離の外なら、表示中の経路の末端を測る。詳細探索は描画距離の内側しか読めないので、
     * 遠い目的地をそのまま渡すと必ず「箱の外」で終わり、何も測れない。
     */
    private static BlockPos probeTarget(NavCommandSink out, BlockPos start, BlockPos destination,
                                        int renderRadius) {
        PathResult shown = PathfindingState.INSTANCE.currentResult();
        if (BlockDistance.horizontal(start, destination) > renderRadius && shown != null
                && !shown.steps().isEmpty()) {
            BlockPos end = shown.steps().get(shown.steps().size() - 1).pos();
            out.success(TextCompat.translatable("commands.xaeronav.probe_target_path_end", end.toShortString()));
            return end;
        }
        out.success(TextCompat.translatable("commands.xaeronav.probe_target_goal", destination.toShortString()));
        return destination;
    }

    /**
     * 掘削が有効だと、固体セルがすべて「有限コストで進入可能」になる（ChunkView#computeState）。
     * 探索空間が地表という面から山という体積に変わるので、同じ箱・同じ上限のまま掘削だけを切って
     * 走らせた展開ノード数との差が、掘削が分岐数に効いている量そのものになる。
     * 箱の広さを変えずに比べるため、チャンク参照を共有する派生ビューを使う
     * （{@link #DIAGNOSTIC}は単一スレッドなので、通常予算の完了後に逐次実行される）。
     */
    private static void continueProbeAfterNormal(NavCommandSink out, long generation, Level level, Player player,
                                                  BlockPos start, BlockPos goal, int renderRadius, int normalMargin,
                                                  CapturedView normal, ProbeRun normalRun) {
        if (!XaeroNavConfig.INSTANCE.diggingEnabled()) {
            out.success(TextCompat.translatable("commands.xaeronav.probe_no_digging_skipped"));
            continueProbeAfterDigging(out, generation, level, player, start, goal, renderRadius, normalMargin,
                    normal, normalRun);
            return;
        }
        DIAGNOSTIC.submit(generation,
                cancelled -> runProbe(normal.view().withoutDigging(), normal.bounds(), start, goal, cancelled),
                (noDiggingRun, error) -> {
                    if (error != null) {
                        XaeroNav.LOGGER.error("XaeroNav: probe failed (digging off)", error);
                        return;
                    }
                    reportProbeRun(out, "commands.xaeronav.probe_no_digging", noDiggingRun);
                    continueProbeAfterDigging(out, generation, level, player, start, goal, renderRadius,
                            normalMargin, normal, normalRun);
                });
    }

    /**
     * 予算切れ（ノード数上限・時間上限）での未到達は、箱を広げても同じ上限に同じように当たるだけで
     * 結果は変わらない（実機で確認済み: 通常マージンと拡大後で展開ノード数が完全一致していた）。
     * ここで弾かないと、無駄なA*をもう1回投げたうえ「箱が原因」と誤読させる出力になる。
     * 時間上限で切れた回もここに含める——展開数だけを見ると「範囲が狭い」と誤読して
     * widenTriggeredに倒れてしまう。
     */
    private static void continueProbeAfterDigging(NavCommandSink out, long generation, Level level, Player player,
                                                   BlockPos start, BlockPos goal, int renderRadius, int normalMargin,
                                                   CapturedView normal, ProbeRun normalRun) {
        int maxExpandedNodes = XaeroNavConfig.INSTANCE.maxExpandedNodes();
        boolean budgetExhausted = normalRun.result().budgetExhausted();
        boolean widenTriggered = !normalRun.result().complete() && !budgetExhausted
                && BlockDistance.horizontal(start, goal) <= renderRadius && normalMargin < renderRadius;
        if (!normalRun.result().complete() && budgetExhausted) {
            out.success(TextCompat.translatable(
                    "commands.xaeronav.probe_widen_skipped_budget", maxExpandedNodes));
            // 上限に張り付いた回どうしを比べても展開ノード数は必ず一致するので、そこからは何も分からない。
            // 打ち切りを時間だけに任せて「この地形で目的地まで実際に何ノード要るのか」を測り、
            // 設定値が足りないだけなのか、時間予算でも届かない＝探索側の問題なのかを切り分ける
            SearchLimits unboundedLimits = new SearchLimits(PROBE_UNBOUNDED_MAX_EXPANDED_NODES,
                    AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS, XaeroNavConfig.INSTANCE.heuristicWeight());
            DIAGNOSTIC.submit(generation,
                    cancelled -> runProbe(normal.view(), normal.bounds(), start, goal, unboundedLimits, cancelled),
                    (unboundedRun, error) -> {
                        if (error != null) {
                            XaeroNav.LOGGER.error("XaeroNav: probe failed (unbounded)", error);
                            return;
                        }
                        reportProbeRun(out, "commands.xaeronav.probe_unbounded", unboundedRun);
                    });
        } else {
            out.success(TextCompat.translatable(widenTriggered
                    ? "commands.xaeronav.probe_widen_triggered" : "commands.xaeronav.probe_widen_skipped"));
        }
        if (widenTriggered) {
            CapturedView widened = captureProbeView(level, player, start, goal, renderRadius,
                    PathfindingState.verticalSearchMargin(level, true), renderRadius);
            DIAGNOSTIC.submit(generation,
                    cancelled -> runProbe(widened.view(), widened.bounds(), start, goal, cancelled),
                    (widenedRun, error) -> {
                        if (error != null) {
                            XaeroNav.LOGGER.error("XaeroNav: probe failed (widened)", error);
                            return;
                        }
                        reportProbeRun(out, "commands.xaeronav.probe_widened", widenedRun);
                    });
        }
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
    private static void reportGoalCell(NavCommandSink out, Level level, ChunkView view, SearchBounds bounds,
                                        BlockPos start, BlockPos goal, int renderRadius) {
        int x = goal.getX();
        int y = goal.getY();
        int z = goal.getZ();
        if (!bounds.contains(x, y, z)) {
            // 箱はゴール方向へrenderRadiusで切られる。長距離ナビの目的地をそのまま渡すと必ずここへ
            // 落ちるので、どこまでなら測れるのかを併せて出さないと同じ指定を繰り返すことになる
            out.success(TextCompat.translatable("commands.xaeronav.probe_goal_outside_bounds",
                    Math.round(BlockDistance.horizontal(start, goal)), renderRadius));
            return;
        }
        BlockPos feetPos = new BlockPos(x, y, z);
        BlockPos headPos = new BlockPos(x, y + 1, z);
        long feetCell = view.cell(x, y, z);
        long headCell = view.cell(x, y + 1, z);
        long belowCell = view.cell(x, y - 1, z);
        // 足場が無くても、そこへ置いて立てるなら到達しうる（addBridgeが床を作って着く）。
        // 置ける状態かを見ずに「原理的に到達しない」と言い切ると、橋で届く目的地まで
        // 探索の側の問題として誤読させる
        boolean floorReachable = CellData.standable(belowCell)
                || view.canPlaceBlocks()
                && (CellData.lava(belowCell) || CellData.replaceable(belowCell));
        Component feet = describeGoalCell(level, feetPos, feetCell);
        Component head = describeGoalCell(level, headPos, headCell);
        if (floorReachable && enterable(feetCell) && enterable(headCell)) {
            out.success(TextCompat.translatable("commands.xaeronav.probe_goal_ok", feet, head));
        } else {
            out.success(TextCompat.translatable("commands.xaeronav.probe_goal_blocked",
                    TextCompat.translatable(floorReachable ? "commands.xaeronav.probe_goal_cell_ok"
                            : "commands.xaeronav.probe_goal_cell_blocked"), feet, head));
        }
    }

    /** 掘って入れるセルも通れる。掘れないセル（溶岩・危険セル・掘削禁止設定）だけが進入不可。 */
    private static boolean enterable(long cell) {
        return CellData.occupiableWithoutDigging(cell) || !Double.isInfinite(CellData.digTicks(cell));
    }

    private static ResourceLocation blockId(Block block) {
        return BlockRegistryCompat.keyOf(block);
    }

    /**
     * {@code UNRESOLVED_SHAPE}（{@code hasDynamicShape()}なブロック、CellData参照）はmodブロックの
     * ことが多く、対象を名指ししないと「なぜここだけ通れないのか」が地形からは分からない。
     */
    private static Component describeGoalCell(Level level, BlockPos pos, long cell) {
        if (CellData.unresolvedShape(cell)) {
            ResourceLocation id = blockId(level.getBlockState(pos).getBlock());
            return TextCompat.translatable("commands.xaeronav.probe_goal_cell_unresolved_shape",
                    id == null ? "?" : id.toString());
        }
        if (CellData.occupiableWithoutDigging(cell)) {
            return TextCompat.translatable("commands.xaeronav.probe_goal_cell_ok");
        }
        return TextCompat.translatable(Double.isInfinite(CellData.digTicks(cell))
                ? "commands.xaeronav.probe_goal_cell_blocked" : "commands.xaeronav.probe_goal_cell_dig");
    }

    /**
     * {@link ChunkView#capture}はメインスレッド専用。ここで作った{@link CapturedView}をバックグラウンドの
     * {@link #DIAGNOSTIC}へ渡し、実際のA*探索はワーカースレッドで行う。
     */
    private static CapturedView captureProbeView(Level level, Player player, BlockPos start, BlockPos goal,
                                                  int horizontalMargin, int verticalMargin, int renderRadius) {
        SearchBounds bounds = SearchBounds.around(level, start, goal, horizontalMargin, verticalMargin, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.movementOptions());
        return new CapturedView(view, bounds);
    }

    private record CapturedView(ChunkView view, SearchBounds bounds) {
    }

    private static ProbeRun runProbe(ChunkView view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                      BooleanSupplier cancelled) {
        return runProbe(view, bounds, start, goal, XaeroNavConfig.INSTANCE.searchLimits(), cancelled);
    }

    private static ProbeRun runProbe(ChunkView view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                      SearchLimits limits, BooleanSupplier cancelled) {
        AStarPathfinder pathfinder = new AStarPathfinder(view, limits);
        long startNanos = System.nanoTime();
        PathResult result = pathfinder.search(start, goal, cancelled);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        return new ProbeRun(start, result, bounds, elapsedMillis, view.loadedChunksInBounds(),
                view.totalChunksInBounds(), pathfinder.trimmedPlacements(), pathfinder.bridgeRunCapBlocked());
    }

    private static void reportProbeRun(NavCommandSink out, String labelKey, ProbeRun run) {
        PathResult result = run.result();
        SearchBounds bounds = run.bounds();
        int spanX = bounds.maxX() - bounds.minX() + 1;
        int spanZ = bounds.maxZ() - bounds.minZ() + 1;
        Component label = TextCompat.translatable(labelKey);
        out.success(TextCompat.translatable(
                result.complete() ? "commands.xaeronav.probe_summary_reached"
                        : "commands.xaeronav.probe_summary_partial",
                label, result.steps().size(), result.expandedNodes(), run.elapsedMillis(), spanX, spanZ,
                result.distinctNodes()));
        if (run.loadedChunks() < run.totalChunks()) {
            // 未読み込みチャンクは進入不可セルとして扱われる（ChunkView#capture）。
            // 探索範囲の縁がまだ届いていないだけで、少し待てば同じ座標でも結果が変わりうる
            out.success(TextCompat.translatable("commands.xaeronav.probe_chunks_missing",
                    run.loadedChunks(), run.totalChunks()));
        }
        if (!result.steps().isEmpty()) {
            String breakdown = describeMovements(result.steps(), run.start());
            out.success(TextCompat.translatable("commands.xaeronav.probe_movements", breakdown));
            reportWorkload(out, result.steps(), run.start());
        }
        if (run.trimmedPlacements() > 0) {
            // 切り落とした後の経路を見るだけでは「橋を架けなかった」と「架けたが渡り切れなかった」が
            // 同じ設置0に見える。原因が正反対なので、切った事実の方を出す
            out.success(TextCompat.translatable("commands.xaeronav.probe_trimmed",
                    run.trimmedPlacements()));
        }
        if (run.bridgeRunCapBlocked()) {
            out.success(TextCompat.translatable("commands.xaeronav.probe_bridge_cap_blocked",
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
            out.success(TextCompat.translatable("commands.xaeronav.probe_placing_on",
                    XaeroNavConfig.INSTANCE.maxBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxLavaBridgeRunBlocks(),
                    XaeroNavConfig.INSTANCE.maxVoidBridgeRunBlocks(),
                    view.placedBlockBudget()));
        } else {
            out.success(TextCompat.translatable("commands.xaeronav.probe_placing_off",
                    TextCompat.translatable(XaeroNavConfig.INSTANCE.bridgingEnabled()
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
        Component line = TextCompat.translatable("commands.xaeronav.probe_workload",
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

}
