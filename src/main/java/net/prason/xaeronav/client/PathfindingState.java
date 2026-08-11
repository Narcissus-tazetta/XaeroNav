package net.prason.xaeronav.client;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathSafetyChecker;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

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

    private final PathfindingExecutor executor = new PathfindingExecutor();
    // clear()・新規setGoal()のたびに増分する。非同期結果を適用する直前にこれと照合し、
    // 一致しなければ「もう古くなったリクエストの結果」として捨てる(clear後に古い結果が
    // currentResultを復活させてしまう競合を防ぐ)。
    private final AtomicLong generation = new AtomicLong();

    private volatile BlockPos goal;
    // 目的地を設定した次元。座標だけを覚えていると、ネザーへ移動したあとも同じ座標を目指してしまう
    private volatile ResourceKey<Level> goalDimension;
    private volatile PathResult currentResult;
    private volatile boolean computing;
    private volatile boolean arrived;
    // trueの間は、currentResultは本来の目的地ではなく「地上へ出るまで」の中継経路を表す
    // （design doc外・地上優先ナビ。recalculate/onClientTick参照）。
    private volatile boolean climbingToSurface;

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
        this.currentResult = null;
        this.lastStart = null;
        this.arrived = false;
        this.climbingToSurface = false;
        this.arrivedTicks = 0;
    }

    public BlockPos goal() {
        return goal;
    }

    public PathResult currentResult() {
        return currentResult;
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
        return climbingToSurface;
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
        if (climbingToSurface && !computing
                && mc.player.blockPosition().getY() >= XaeroNavConfig.INSTANCE.groundLevelY()) {
            // 地上に出た。ここから先は本来の目的地に向けて経路を引き直す
            // （recalculate()が現在地を見てclimbingToSurfaceを自然にfalseへ戻す）
            recalculate();
            return;
        }

        PathProgress.INSTANCE.update(currentResult, mc.player.position());
        if (checkArrival(mc.player, currentGoal)) {
            return;
        }
        if (computing) {
            // 計算中は再計算のトリガーを一旦止める。さもないと非同期結果が返ってくるまでの
            // 数tickの間、毎tick探索を投げ直してしまう。
            return;
        }
        ticksSinceRecalc++;
        ticksSinceValidation++;

        PathResult result = currentResult;
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
     * 目的地に着いたかどうか。水平・垂直とも{@code arrivalRadiusBlocks}以内に来たら到着とする。
     *
     * <p>掘っても辿り着けない座標が目的地のこともある（{@link net.prason.xaeronav.pathfinding.world.StanceFinder}
     * が寄せた地点までしか経路が伸びない）。その場合は実際に辿れる経路の終端を基準に到着を判定する。
     * 地上へ出るまでの中継経路（{@link #climbingToSurface}）はこの対象に含めない — 中継地点は
     * 目的地ではないので、着いてもここでは「到着」にしない（{@link #onClientTick}側で次の区間へ引き継ぐ）。
     */
    private boolean checkArrival(Player player, BlockPos currentGoal) {
        double radius = XaeroNavConfig.INSTANCE.arrivalRadiusBlocks();
        if (near(player, currentGoal, radius)) {
            arrive();
            return true;
        }
        if (climbingToSurface) {
            return false;
        }
        PathResult result = currentResult;
        List<PathStep> steps = result != null ? result.steps() : List.of();
        if (result != null && result.complete() && !steps.isEmpty()
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
        currentResult = null;
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

        int groundLevel = XaeroNavConfig.INSTANCE.groundLevelY();
        // 目的地が地上（groundLevel以上）で自分は地下にいるなら、目的地の真下を一直線に掘るのではなく、
        // まず「どこでもいいから地上に出る」経路を探す。近くに使える洞窟・崖があればそちらを、
        // 無ければ通常の掘削を、状況に応じて選ばせるため（design doc外・地上優先ナビ）。
        // 地上に出たあとの次の再計算では自分のYがgroundLevel以上になっているので自然にfalseへ戻る
        climbingToSurface = start.getY() < groundLevel && currentGoal.getY() >= groundLevel;

        // 探索範囲は描画距離で切る。読み込み済みチャンクの外は読めないので、そこまで広げても
        // 未ロード扱いのセルを舐めるだけになる。同時に、描画距離を下げているマシンでは
        // 探索の負荷も自動的に下がる。
        // 地上優先ナビ中は、水平方向は自分の周囲だけに絞る（ゴールが1点ではなく「y>=groundLevelの
        // どこでも」なので、遠い本来の目的地の箱に広げる意味が無い）。垂直方向はgroundLevelまで
        // 確実に届くよう、同じ列でgroundLevelにある仮想ゴールとして範囲を組み立てる
        BlockPos boundsGoal = climbingToSurface
                ? new BlockPos(start.getX(), groundLevel, start.getZ())
                : currentGoal;
        SearchBounds bounds = SearchBounds.around(level, start, boundsGoal,
                XaeroNavConfig.INSTANCE.searchHorizontalMargin(), XaeroNavConfig.INSTANCE.searchVerticalMargin(),
                mc.options.getEffectiveRenderDistance() * 16);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.diggingEnabled(),
                XaeroNavConfig.INSTANCE.bridgingEnabled());

        long myGeneration = generation.incrementAndGet();
        computing = true;
        CompletableFuture<PathResult> future = climbingToSurface
                ? executor.submitToSurface(view, start, groundLevel, XaeroNavConfig.INSTANCE.maxExpandedNodes())
                : executor.submit(view, start, currentGoal, XaeroNavConfig.INSTANCE.maxExpandedNodes());
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
            currentResult = PathSafetyChecker.annotate(view, result);
        });
    }
}
