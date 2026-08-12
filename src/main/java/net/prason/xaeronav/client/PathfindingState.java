package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;

/**
 * クライアント側の経路探索状態（design doc §4-5/§4-6の配線）。
 *
 * <p>{@link #setGoal}/{@link #onClientTick}はクライアントスレッド（メインスレッド）から呼ぶこと。
 * メインスレッドで行うのは{@link ChunkView}の構築（読み込み済みチャンクへの参照集め）だけで、
 * ブロックの読み取りとA*探索はどちらも{@link PathfindingExecutor}のワーカースレッドで行う。
 */
public final class PathfindingState {

    public static final PathfindingState INSTANCE = new PathfindingState();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 到着表示を出しておく長さ（tick）。過ぎたら目的地ごと片付ける。 */
    private static final int ARRIVAL_DISPLAY_TICKS = 100;

    /** 経路から外れたときの再計算の下限間隔（tick）。外れている間ずっと探索を投げ続けないための頭打ち。 */
    private static final int MIN_RECALC_INTERVAL_TICKS = 10;

    /** 打ち切られた経路の末端がこの距離まで近づいたら、その先を計算し直す（ブロック）。 */
    private static final double EXTEND_DISTANCE_BLOCKS = 32.0;

    /** 経路が出せなかったあと、再挑戦するまでに動く距離（ブロック）。 */
    private static final double RETRY_MOVE_BLOCKS = 4.0;

    /** 経路が出せず、その場から動いてもいない場合の再挑戦間隔（tick）。 */
    private static final int NO_ROUTE_RETRY_TICKS = 200;

    /**
     * 地上優先ナビ（{@link #shouldClimbToSurface}）に入る深さの下限（ブロック）。
     * 地上のすぐ下は、洞窟の入口も崖もたいてい目と鼻の先にあるので、中継区間を挟むより
     * そのまま目的地を目指した方が短い。数マスのために案内を2段階にする価値はない。
     */
    private static final int MIN_UNDERGROUND_DEPTH = 5;

    /** 地上へ出る経路が見つからなかった地点から、もう一度試すまでに動く距離（ブロック）。 */
    private static final double SURFACE_RETRY_MOVE_BLOCKS = 16.0;

    /**
     * 地上へ出る中継区間で、水平方向の探索マージンに掛ける倍率。洞窟の出口は目的地の方角にあるとは
     * 限らないので、通常の範囲のままでは出口ごと範囲の外に落ちる。
     */
    private static final int SURFACE_SEARCH_MARGIN_FACTOR = 2;

    /** 長距離ルートの中間目標を読み込む際、始点・終点それぞれの周りに広げる範囲（チャンク）。 */
    private static final int COARSE_ROUTE_PADDING_CHUNKS = 32;

    /** 長距離ルートの読み取り範囲の上限（チャンク四方）。無制限だと配列確保だけで固まる。 */
    private static final int COARSE_ROUTE_MAX_SPAN_CHUNKS = 1024;

    private final PathfindingExecutor executor = new PathfindingExecutor();
    // clear()・新規setGoal()のたびに増分する。非同期結果を適用する直前にこれと照合し、
    // 一致しなければ「もう古くなったリクエストの結果」として捨てる(clear後に古い結果が
    // currentResultを復活させてしまう競合を防ぐ)。
    private final AtomicLong generation = new AtomicLong();

    private volatile BlockPos goal;
    // 目的地を設定した次元。座標だけを覚えていると、ネザーへ移動したあとも同じ座標を目指してしまう
    private volatile ResourceKey<Level> goalDimension;
    private volatile DisplayedPath displayed;
    private volatile boolean computing;
    private volatile boolean arrived;
    // 地上へ出る経路が出せなかった地点。掘削を切っている・密閉された場所では中継区間そのものが
    // 成立しないので、その付近では地上優先ナビを諦めて本来の目的地へ直接向かう
    private volatile BlockPos surfaceLegFailedAt;
    // 長距離ルートの中間目標。地形は不変なので、目的地が変わらない限り引き直さない
    private volatile CoarseRoute coarseRoute;

    // 直近の探索に使った入力。以下はクライアントスレッドからのみ触る。
    private BlockPos lastStart;
    private int ticksSinceRecalc;
    private int ticksSinceValidation;
    private int arrivedTicks;

    private PathfindingState() {
    }

    public void setGoal(BlockPos goal) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        clear();
        // 徒歩とエリトラは別々の目的地を持てるが、案内としては一度に1つだけ意味を成す。
        // 消さずに切り替えると、行き先の違う2本の線が同時に描かれる
        ElytraNavState.INSTANCE.clear();
        this.goal = goal;
        this.goalDimension = level.dimension();
        recalculate();
    }

    public void clear() {
        // 世代を進めた時点で実行中の探索の結果は捨てられる。その結果待ちを表すcomputingもここで下ろす
        generation.incrementAndGet();
        this.computing = false;
        this.goal = null;
        this.goalDimension = null;
        this.displayed = null;
        this.lastStart = null;
        this.arrived = false;
        this.surfaceLegFailedAt = null;
        this.coarseRoute = null;
        this.arrivedTicks = 0;
    }

    public BlockPos goal() {
        return goal;
    }

    public PathResult currentResult() {
        DisplayedPath shown = displayed;
        return shown == null ? null : shown.result();
    }

    /** 探索がまだ走っているか。まだ経路が無いのが計算中だからなのかを案内表示が区別するために使う。 */
    public boolean computing() {
        return computing;
    }

    /** 目的地に着いたか。着いた瞬間から{@link #ARRIVAL_DISPLAY_TICKS}の間だけtrueになる。 */
    public boolean arrived() {
        return arrived;
    }

    /** 表示中の経路が、本来の目的地ではなく「まず地上へ出るまで」の中継経路か。 */
    public boolean climbingToSurface() {
        DisplayedPath shown = displayed;
        return shown != null && shown.mode() == PathMode.TO_SURFACE;
    }

    /** 長距離ルート中、現在向かっている中間目標の番号（1始まり）。長距離ルート中でなければ0。 */
    public int coarseRouteWaypointNumber() {
        DisplayedPath shown = displayed;
        return shown != null && shown.mode() == PathMode.WAYPOINT && shown.waypointIndex() >= 0
                ? shown.waypointIndex() + 1
                : 0;
    }

    /** 長距離ルートの中間目標の総数。長距離ルート中でなければ0。 */
    public int coarseRouteWaypointCount() {
        DisplayedPath shown = displayed;
        if (shown == null || shown.mode() != PathMode.WAYPOINT) {
            return 0;
        }
        return currentRouteWaypoints().size();
    }

    /**
     * Xaero地図へ点線で描くべき中間目標列＝<b>まだ通っていない分だけ</b>。無ければ空リスト。
     *
     * <p>通過済みを含む全体を返すと、粗いルートは目的地が変わらない限り引き直さない設計のため、
     * 点線がいつまでも「ルートを計算した当時の位置」から伸びたままになる。プレイヤーが経路から
     * 大きく外れるほど現在地と点線が食い違い、古いルートが残っているように見える。
     */
    public List<BlockPos> coarseRouteWaypoints() {
        List<BlockPos> all = currentRouteWaypoints();
        DisplayedPath shown = displayed;
        if (all.isEmpty() || shown == null || shown.mode() != PathMode.WAYPOINT) {
            return all;
        }
        int current = shown.waypointIndex();
        return current <= 0 || current >= all.size() ? all : all.subList(current, all.size());
    }

    /**
     * {@link #coarseRoute}を今の目的地に照らして読む。目的地が変わった経緯（直行圏内に戻った・
     * 新しい目的地に切り替わった等）を1つずつ潰すのではなく、読み出し側で常に「今の目的地に対する
     * ルートか」を照合する形にしておくことで、キャッシュの破棄漏れが今後増えても地図に古い点線が
     * 残らない（実際のナビゲーション先はすでに新しい目的地を向いているので、ここが古い値を返しても
     * 実害は「表示だけ」だが、それ自体が過去に踏んだ罠なので構造で防ぐ）。
     */
    private List<BlockPos> currentRouteWaypoints() {
        CoarseRoute route = coarseRoute;
        return route == null || !route.goal().equals(goal) ? List.of() : route.waypoints();
    }

    public void onClientTick() {
        BlockPos currentGoal = goal;
        if (currentGoal == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.level.dimension() != goalDimension) {
            // 別の次元へ移った。同じ座標を目指し続けても意味がないので目的地ごと捨てる
            clear();
            return;
        }
        if (arrived) {
            arrivedTicks++;
            if (arrivedTicks >= ARRIVAL_DISPLAY_TICKS) {
                clear();
            }
            return;
        }
        // Xaeroの世界地図やインベントリを開いている間、プレイヤーは動けない。ここで止めないと
        // 地図を眺めているだけの間ずっと同じ入力に対する探索が走り続ける。
        if (mc.screen != null) {
            return;
        }
        // 経路とモードは一組で差し替わるので、1tickの判断は同じスナップショットの上で行う
        DisplayedPath shown = displayed;
        if (shown != null && shown.mode() == PathMode.TO_SURFACE && !computing
                && surfaceLegDone(mc.level, mc.player, currentGoal, shown)) {
            // 地上に出た。ここから先は本来の目的地に向けて経路を引き直す
            // （新しい経路が届くまでは中継経路のまま表示し続ける。先にモードだけ戻すと、
            // 中継経路の終端＝いまの足元が「経路の終わり」と見なされて誤って到着になる）
            recalculate();
            return;
        }
        if (shown != null && shown.mode() == PathMode.WAYPOINT && !computing
                && reachedPathEnd(mc.player, shown)) {
            // 中間目標に着いた。届く範囲で最も遠い未通過点へ引き直す
            recalculate();
            return;
        }

        PathResult result = shown == null ? null : shown.result();
        PathProgress.INSTANCE.update(result, mc.player.position());
        if (checkArrival(mc.player, currentGoal, shown)) {
            return;
        }
        if (computing) {
            // 計算中は再計算のトリガーを一旦止める。さもないと非同期結果が返ってくるまでの
            // 数tickの間、毎tick探索を投げ直してしまう。
            return;
        }
        ticksSinceRecalc++;
        ticksSinceValidation++;

        if (result == null || result.steps().isEmpty()) {
            retryWithoutRoute(mc.player.blockPosition());
            return;
        }
        // 経路の帯からはみ出したときだけ引き直す。1〜2マス横にずれた程度で作り直すと、
        // そのたびに違う経路が出てきて線が落ち着かない（歩いているだけで案内が変わる）
        if (PathProgress.INSTANCE.distance() > XaeroNavConfig.INSTANCE.deviationThresholdBlocks()) {
            if (ticksSinceRecalc >= MIN_RECALC_INTERVAL_TICKS) {
                recalculate();
            }
            return;
        }
        if (ticksSinceRecalc >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()
                && !result.complete() && nearPathEnd(mc.player.position(), result)) {
            // 打ち切られた末端に近づいた。ここから先は新しく読み込まれたチャンクを使って伸ばせる
            recalculate();
            return;
        }
        if (ticksSinceValidation >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
            // プレイヤーが動かなくてもワールドは変わりうる。経路上のセルだけを定期的に見る
            ticksSinceValidation = 0;
            if (!PathValidator.stillValid(mc.level, result)) {
                recalculate();
            }
        }
    }

    /**
     * 中継区間（地上へ出るまで）を終えて、本来の目的地へ引き直してよいか。
     *
     * <p>高さだけで判断すると、天井の下にある洞窟の坑道でも「地上に出た」ことになり、そこから
     * 目的地へ直行する経路＝避けたかった一直線の掘り進みに戻ってしまう。中継が要らなくなったか
     * （空の下に出た、あるいはこの付近では中継を諦めた）で判断する。
     *
     * <p>あわせて中継経路の終端に立ったかも見る。地上かどうかの判定は、探索側がハイトマップを、
     * ここが{@code canSeeSky}を使っており、ガラス屋根のように両者が食い違う場所がありうる。
     * 終端を見ておかないと、そこに立ったまま次の区間へ進めなくなる。
     */
    private boolean surfaceLegDone(Level level, Player player, BlockPos currentGoal, DisplayedPath shown) {
        if (!shouldClimbToSurface(level, player.blockPosition(), currentGoal,
                XaeroNavConfig.INSTANCE.groundLevelY())) {
            return true;
        }
        return reachedPathEnd(player, shown);
    }

    /**
     * 表示中の経路の終端に着いたか。中間目標はチャンク中心の代表点で、地形によっては
     * 真上に立てないことがあるので、目的地そのものではなく経路の終端で判定する。
     */
    private boolean reachedPathEnd(Player player, DisplayedPath shown) {
        List<PathStep> steps = shown.result().steps();
        return !steps.isEmpty()
                && near(player, steps.get(steps.size() - 1).pos(), XaeroNavConfig.INSTANCE.arrivalRadiusBlocks());
    }

    /**
     * 目的地に着いたかどうか。水平・垂直とも{@code arrivalRadiusBlocks}以内に来たら到着とする。
     *
     * <p>掘っても辿り着けない座標が目的地のこともある（{@link net.prason.xaeronav.pathfinding.world.StanceFinder}
     * が寄せた地点までしか経路が伸びない）。その場合は実際に辿れる経路の終端を基準に到着を判定する。
     * 地上へ出るまでの中継経路・長距離ルートの中間目標（{@link DisplayedPath#mode}が{@code GOAL}でない）は
     * この対象に含めない — 本来の目的地ではないので、着いてもここでは「到着」にしない
     * （{@link #onClientTick}側で次の区間へ引き継ぐ）。
     */
    private boolean checkArrival(Player player, BlockPos currentGoal, DisplayedPath shown) {
        double radius = XaeroNavConfig.INSTANCE.arrivalRadiusBlocks();
        if (near(player, currentGoal, radius)) {
            arrive();
            return true;
        }
        if (shown == null || shown.mode() != PathMode.GOAL) {
            return false;
        }
        PathResult result = shown.result();
        List<PathStep> steps = result.steps();
        if (result.complete() && !steps.isEmpty()
                && near(player, steps.get(steps.size() - 1).pos(), radius)) {
            arrive();
            return true;
        }
        return false;
    }

    private static boolean near(Player player, BlockPos pos, double radius) {
        return horizontalDistanceSq(player, pos) <= radius * radius
                && Math.abs(pos.getY() - player.blockPosition().getY()) <= radius;
    }

    private static double horizontalDistanceSq(Player player, BlockPos pos) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private void arrive() {
        // 走っている探索の結果で経路が復活しないように世代を進める
        generation.incrementAndGet();
        computing = false;
        displayed = null;
        arrivedTicks = 0;
        arrived = true;
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.4f, 1.5f);
        }
    }

    /**
     * 経路が出せなかったときの再挑戦。届かない目的地（海の向こう・未読み込み）では毎回上限まで
     * 探索して失敗するので、間隔を空けないと同じ計算を数秒おきに繰り返すだけになる。
     */
    private void retryWithoutRoute(BlockPos start) {
        if (ticksSinceRecalc < XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
            return;
        }
        boolean moved = lastStart == null
                || lastStart.distSqr(start) >= RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS;
        if (moved || ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS) {
            recalculate();
        }
    }

    private boolean nearPathEnd(Vec3 position, PathResult result) {
        PathStep last = result.steps().get(result.steps().size() - 1);
        double dx = last.pos().getX() + 0.5 - position.x;
        double dy = last.pos().getY() - position.y;
        double dz = last.pos().getZ() + 0.5 - position.z;
        return dx * dx + dy * dy + dz * dz <= EXTEND_DISTANCE_BLOCKS * EXTEND_DISTANCE_BLOCKS;
    }

    private void recalculate() {
        ticksSinceRecalc = 0;
        ticksSinceValidation = 0;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        BlockPos currentGoal = this.goal;
        if (level == null || player == null || currentGoal == null) {
            return;
        }

        BlockPos start = player.blockPosition();
        lastStart = start;
        boolean boatAvailable = player.getInventory().contains(stack -> stack.getItem() instanceof BoatItem);

        int groundLevel = XaeroNavConfig.INSTANCE.groundLevelY();
        boolean climbing = shouldClimbToSurface(level, start, currentGoal, groundLevel);
        int renderRadius = mc.options.getEffectiveRenderDistance() * 16;

        // 地上優先ナビが最優先（逆にすると地中で長距離の中間目標へ掘り進んでしまう）。
        // それ以外は、目的地が描画距離の外にあるときだけ長距離ルートの中間目標を挟む
        PathMode mode;
        BlockPos target;
        int waypointIndex;
        if (climbing) {
            mode = PathMode.TO_SURFACE;
            // 地上優先ナビ中は、遠い本来の目的地の箱に広げても意味が無い（ゴールが1点ではなく
            // 「空の下ならどこでも」なので）。垂直方向はgroundLevelまで確実に届くよう、同じ列で
            // groundLevelにある仮想ゴールとして範囲を組み立てる
            target = new BlockPos(start.getX(), groundLevel, start.getZ());
            waypointIndex = -1;
        } else {
            DetailTarget detail = selectDetailTarget(start, currentGoal, renderRadius, boatAvailable);
            target = detail.target();
            mode = target.equals(currentGoal) ? PathMode.GOAL : PathMode.WAYPOINT;
            waypointIndex = detail.waypointIndex();
        }

        // 探索範囲は描画距離で切る。読み込み済みチャンクの外は読めないので、そこまで広げても
        // 未ロード扱いのセルを舐めるだけになる。同時に、描画距離を下げているマシンでは
        // 探索の負荷も自動的に下がる。
        // そのぶん自分の周囲は広めに取る。洞窟の出口が目的地の方角にあるとは限らず、通常の
        // マージンでは出口ごと範囲の外に落ちる。この区間は掘削を切って探すので通れるセルが
        // 空洞だけに絞られ、範囲を広げても展開数はほとんど増えない
        int horizontalMargin = XaeroNavConfig.INSTANCE.searchHorizontalMargin()
                * (climbing ? SURFACE_SEARCH_MARGIN_FACTOR : 1);
        SearchBounds bounds = SearchBounds.around(level, start, target,
                horizontalMargin, XaeroNavConfig.INSTANCE.searchVerticalMargin(),
                renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.diggingEnabled(),
                XaeroNavConfig.INSTANCE.bridgingEnabled());

        SearchLimits limits = new SearchLimits(XaeroNavConfig.INSTANCE.maxExpandedNodes(),
                AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS, XaeroNavConfig.INSTANCE.heuristicWeight());
        long myGeneration = generation.incrementAndGet();
        computing = true;
        PathMode finalMode = mode;
        BlockPos finalTarget = target;
        int finalWaypointIndex = waypointIndex;
        CompletableFuture<PathResult> future = climbing
                ? executor.submitToSurface(view.withoutDigging(), view, start, groundLevel, limits)
                : executor.submit(view, start, finalTarget, limits);
        future.whenComplete((result, error) -> {
            if (generation.get() != myGeneration) {
                // 追い越された古いリクエスト。computingは今走っているリクエストのものなので触らない
                return;
            }
            computing = false;
            if (error != null) {
                // キャンセルは正常な終わり方（新しいリクエストに置き換わった）
                if (!(error instanceof CancellationException)) {
                    LOGGER.error("XaeroNav: 経路探索に失敗しました", error);
                }
                return;
            }
            if (!result.complete() && LOGGER.isDebugEnabled()) {
                // 経路が目的地まで届かなかった理由は、探索の打ち切りか本当に道が無いかのどちらか。
                // 展開ノード数を出しておかないと、maxExpandedNodesを上げ下げした効果を確かめる
                // 手段がなく、「なぜ線が途中で切れるのか」に答えられない
                LOGGER.debug("XaeroNav: 経路が未到達のまま終了しました (展開ノード数={}, 上限={}, ステップ数={})",
                        result.expandedNodes(), XaeroNavConfig.INSTANCE.maxExpandedNodes(), result.steps().size());
            }
            if (finalMode == PathMode.TO_SURFACE && (!result.complete() || result.steps().isEmpty())) {
                // 地上まで届かなかった中継経路は表示しない。辿っても地上には出られないので、
                // 途中まで案内したところで、その先でまた同じ未到達な経路が引かれるだけになる。
                // 1歩も進まない中継（＝探索から見ればもう地上）も同じ扱いにする。どちらも
                // この付近では中継を諦め、本来の目的地へ直接向かう（次tickで引き直される）
                surfaceLegFailedAt = start;
                displayed = new DisplayedPath(new PathResult(List.of(), false, result.expandedNodes()), PathMode.TO_SURFACE, -1);
                return;
            }
            displayed = new DisplayedPath(result, finalMode, finalWaypointIndex);
        });
    }

    /**
     * 詳細探索のゴールを決める。目的地が描画距離の外にあり、Xaeroの地図データがあるときだけ
     * 長距離ルートの中間目標（届く範囲で最も遠い未通過点）を挟む。地図が無い・範囲外・
     * 引き直してもなお中間目標が一つも届く範囲に無い、のいずれでも本来の目的地へ直接向かう
     * 従来動作にフォールバックする（発動条件が一つでも欠けたら長距離ルートには入らない）。
     */
    private DetailTarget selectDetailTarget(BlockPos start, BlockPos currentGoal, int renderRadius,
                                             boolean boatAvailable) {
        if (horizontalDistance(start, currentGoal) <= renderRadius || !XaeroPresence.mapPresent()) {
            return new DetailTarget(currentGoal, -1);
        }
        DetailTarget target = reachableWaypointTarget(start, currentGoal,
                cachedOrFreshRoute(start, currentGoal, boatAvailable), renderRadius);
        if (target != null) {
            return target;
        }
        // キャッシュ済みのwaypointが1つも描画距離内に届かない＝大きく迂回して経路から外れた。
        // 目的地は変わっていないのでキャッシュは効くはずだが、地形は不変でも自分の位置は変わるので、
        // 今の位置を始点に引き直す（地形が変わらない限り引き直さない、という原則の唯一の例外）
        target = reachableWaypointTarget(start, currentGoal, freshRoute(start, currentGoal, boatAvailable),
                renderRadius);
        return target != null ? target : new DetailTarget(currentGoal, -1);
    }

    private List<BlockPos> cachedOrFreshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable) {
        CoarseRoute cached = coarseRoute;
        return cached != null && cached.goal().equals(currentGoal)
                ? cached.waypoints() : freshRoute(start, currentGoal, boatAvailable);
    }

    private List<BlockPos> freshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable) {
        CoarseRouter.Route route = computeCoarseRoute(start, currentGoal, boatAvailable);
        List<BlockPos> waypoints = route.waypoints();
        if (!waypoints.isEmpty() && route.reachedGoal()) {
            // 粗い終点はチャンク中心±8ブロックで高さも代表値なので、そのままでは到着できない。
            // 最後だけ本来の目的地に差し替える
            waypoints = replaceLast(waypoints, currentGoal);
        }
        coarseRoute = new CoarseRoute(currentGoal, waypoints);
        return waypoints;
    }

    /**
     * 中間目標は順番に1つずつではなく、詳細探索が届く範囲で最も遠い未通過点を選ぶ。
     * waypoint間隔は詳細探索が一度に届く距離より十分短いので、1つずつ渡すと探索能力を捨てる。
     * 1つも届かなければ{@code null}（呼び出し側が引き直すかどうかを判断する）。
     */
    private DetailTarget reachableWaypointTarget(BlockPos start, BlockPos currentGoal,
                                                  List<BlockPos> waypoints, int renderRadius) {
        BlockPos farthestReachable = null;
        int farthestIndex = -1;
        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos waypoint = waypoints.get(i);
            if (horizontalDistance(start, waypoint) <= renderRadius) {
                farthestReachable = waypoint;
                farthestIndex = i;
            }
        }
        if (farthestReachable == null) {
            return null;
        }
        // 本来の目的地（replaceLastで置き換わった最終waypoint）はユーザー指定座標そのものなので
        // 層2で寄せる対象ではない。それ以外の中間waypointだけ実際に立てる座標へ解決する
        BlockPos resolvedTarget = farthestReachable.equals(currentGoal)
                ? farthestReachable
                : resolveWaypointOnSurface(farthestReachable);
        return new DetailTarget(resolvedTarget, farthestIndex);
    }

    /**
     * 層1のwaypoint（チャンク中心+代表高さ）を、層2のブロック解像度データで実際に立てる座標へ
     * 寄せる。X,Zは変えずYだけ調整する（2D地図の点線・進捗表示はwaypointの生座標のままなので、
     * Yしか変えなければ無改修でも整合する）。データが無ければ元のwaypointをそのまま返す
     * （長距離ルートの他の発動条件と同じく、層2が使えない場合は素の層1へフォールバックする）。
     */
    private static BlockPos resolveWaypointOnSurface(BlockPos waypoint) {
        int chunkX = waypoint.getX() >> 4;
        int chunkZ = waypoint.getZ() >> 4;
        XaeroMapReader.RegionStats stats = XaeroMapReader.surveyRegions(chunkX, chunkZ, 1, 1);
        if (stats.pendingLoad() > 0) {
            XaeroMapReader.requestLoad(chunkX, chunkZ, 1, 1);
        }
        SurfaceGrid grid = XaeroMapReader.readSurfaceDetailed(chunkX * 16, chunkZ * 16, 16, 16);
        BlockPos resolved = grid.resolveStandable(waypoint.getX(), waypoint.getZ());
        return resolved != null ? resolved : waypoint;
    }

    /**
     * 始点と終点を含むバウンディングボックス+{@link #COARSE_ROUTE_PADDING_CHUNKS}の範囲でXaeroの
     * 地図データを読み、{@link CoarseRouter}で中間目標列を引く。
     */
    private static CoarseRouter.Route computeCoarseRoute(BlockPos start, BlockPos goal, boolean boatAvailable) {
        int minChunkX = (Math.min(start.getX(), goal.getX()) >> 4) - COARSE_ROUTE_PADDING_CHUNKS;
        int maxChunkX = (Math.max(start.getX(), goal.getX()) >> 4) + COARSE_ROUTE_PADDING_CHUNKS;
        int minChunkZ = (Math.min(start.getZ(), goal.getZ()) >> 4) - COARSE_ROUTE_PADDING_CHUNKS;
        int maxChunkZ = (Math.max(start.getZ(), goal.getZ()) >> 4) + COARSE_ROUTE_PADDING_CHUNKS;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        if (chunksX > COARSE_ROUTE_MAX_SPAN_CHUNKS || chunksZ > COARSE_ROUTE_MAX_SPAN_CHUNKS) {
            return new CoarseRouter.Route(List.of(), false);
        }
        CoarseMap map = XaeroMapReader.readSurface(minChunkX, minChunkZ, chunksX, chunksZ);
        return CoarseRouter.findRoute(map, start, goal, boatAvailable);
    }

    private static List<BlockPos> replaceLast(List<BlockPos> waypoints, BlockPos replacement) {
        List<BlockPos> copy = new ArrayList<>(waypoints);
        copy.set(copy.size() - 1, replacement);
        return List.copyOf(copy);
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 「まず地上へ出る」区間を挟むべきか（design doc外・地上優先ナビ）。
     *
     * <p>目的地が地上にあるとき、目的地の1点だけを狙う探索は最短距離ゆえに真下からの垂直の穴掘りを
     * 選びやすい。地下からの出発に限り、先に「y &gt;= groundLevelの空の下ならどこでもゴール」の探索を
     * 挟むことで、近くの洞窟や崖があればそちらを、無ければ掘削を、状況に応じて選ばせる
     * （探索そのものは{@link net.prason.xaeronav.pathfinding.async.PathfindingExecutor#submitToSurface}が
     * 掘らない道を先に、見つからなければ掘る道を、の順に試す）。
     *
     * <p>判断にYだけを使わないのは、Yが低いことと地下にいることが別だから。川底・谷底・海岸は
     * 既定の{@code groundLevelY}(60)より下にいくらでもあり、そこを歩くたびに中継区間が挟まると、
     * 案内が目的地と関係ない方向へ振れる。空が見えているならそこはもう地上として扱う。
     */
    private boolean shouldClimbToSurface(Level level, BlockPos start, BlockPos goal, int groundLevel) {
        if (goal.getY() < groundLevel || start.getY() > groundLevel - MIN_UNDERGROUND_DEPTH) {
            return false;
        }
        // 空の無い次元・天井のある次元（ジ・エンド／ネザー）では、そもそも「地上」が存在しない。
        // ネザーで地上優先ナビに入ると、岩盤天井へ向かって掘り進む案内になってしまう
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) {
            return false;
        }
        // 頭の上に空が見えているなら地上。屋根の下・洞窟の中にいるときだけ中継区間を挟む
        if (level.canSeeSky(start.above())) {
            return false;
        }
        BlockPos failedAt = surfaceLegFailedAt;
        return failedAt == null
                || failedAt.distSqr(start) > SURFACE_RETRY_MOVE_BLOCKS * SURFACE_RETRY_MOVE_BLOCKS;
    }

    /** 表示中の経路が向かう先の種類。 */
    private enum PathMode {
        /** 本来の目的地。 */
        GOAL,
        /** まず地上へ出るまでの中継区間。 */
        TO_SURFACE,
        /** 長距離ルートの中間目標。 */
        WAYPOINT
    }

    /**
     * 表示中の経路と、それが向かう先の種類（{@link PathMode}）。
     *
     * <p>2つを別々のフィールドに置くと、経路を差し替える瞬間に片方だけが新しい状態になる。
     * 実際、モードを探索の開始時に、経路を完了時に更新していたときは、地上に出た直後の1tickだけ
     * 「中継経路 + 目的地モード」になり、中継経路の終端（＝いまの足元）が目的地の代わりとして
     * 到着判定に掛かって、着いていないのに「到着！」で案内が終了していた。
     *
     * @param waypointIndex {@code mode}が{@link PathMode#WAYPOINT}のとき、{@link CoarseRoute#waypoints()}中の
     *         何番目を指しているか（0始まり）。それ以外のモードでは{@code -1}
     */
    private record DisplayedPath(PathResult result, PathMode mode, int waypointIndex) {
    }

    /**
     * 長距離ルートの中間目標のキャッシュ。地形は不変なので、目的地が変わらない限り引き直さない。
     */
    private record CoarseRoute(BlockPos goal, List<BlockPos> waypoints) {
    }

    /** {@link #selectDetailTarget}の戻り値。詳細探索のゴールと、それが粗いルート中の何番目かの組。 */
    private record DetailTarget(BlockPos target, int waypointIndex) {
    }
}
