package net.prason.xaeronav.client;

import java.util.List;
import java.util.concurrent.CancellationException;
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
    // 目的地のYと自分のYの差。同じ場所の上下階で「到着」したときに、どちらへ何マスかを伝える
    private volatile int arrivalVerticalOffset;

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
        this.arrivalVerticalOffset = 0;
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

    /** 到着地点から見た目的地の高さの差（正なら目的地の方が上）。 */
    public int arrivalVerticalOffset() {
        return arrivalVerticalOffset;
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
            double radius = XaeroNavConfig.INSTANCE.arrivalRadiusBlocks();
            if (arrivalVerticalOffset != 0
                    && horizontalDistanceSq(mc.player, currentGoal) > 4.0 * radius * radius) {
                // 高さ違いの到着は、目的地の真上・真下を通りかかっただけのこともある。
                // そのまま離れていくなら案内へ戻す
                arrived = false;
                recalculate();
                return;
            }
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
     * 目的地に着いたかどうか。水平距離だけで判断し、高さの違いは「どれだけ上／下か」として伝える。
     *
     * <p>目的地のYがずれているだけの座標（地図クリックやウェイポイント）は珍しくない。そこへ
     * 立てる経路が無いことと、目的地へ着いていないことは別なので、真上・真下まで来ているのに
     * 「経路が見つかりません」と出し続けるのはやめる。
     */
    private boolean checkArrival(Player player, BlockPos currentGoal) {
        double radius = XaeroNavConfig.INSTANCE.arrivalRadiusBlocks();
        if (near(player, currentGoal, radius)) {
            arrive(0);
            return true;
        }
        int verticalOffset = currentGoal.getY() - player.blockPosition().getY();
        List<PathStep> steps = currentResult != null ? currentResult.steps() : List.of();
        if (currentResult != null && currentResult.complete() && !steps.isEmpty()) {
            // 辿れる経路の終わりまで来た。目的地のYが立てない高さだった場合、ここが実際の到着地点になる
            if (near(player, steps.get(steps.size() - 1).pos(), radius)) {
                arrive(verticalOffset);
                return true;
            }
            // 上下階へ回り込む経路がまだ在るなら案内を続ける
            return false;
        }
        if (computing || horizontalDistanceSq(player, currentGoal) > radius * radius) {
            return false;
        }
        arrive(verticalOffset);
        return true;
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

    private void arrive(int verticalOffset) {
        // 走っている探索の結果で経路が復活しないように世代を進める
        generation.incrementAndGet();
        computing = false;
        currentResult = null;
        arrivalVerticalOffset = verticalOffset;
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

        // 探索範囲は描画距離で切る。読み込み済みチャンクの外は読めないので、そこまで広げても
        // 未ロード扱いのセルを舐めるだけになる。同時に、描画距離を下げているマシンでは
        // 探索の負荷も自動的に下がる。
        SearchBounds bounds = SearchBounds.around(level, start, currentGoal,
                XaeroNavConfig.INSTANCE.searchHorizontalMargin(), XaeroNavConfig.INSTANCE.searchVerticalMargin(),
                mc.options.getEffectiveRenderDistance() * 16);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.diggingEnabled(),
                XaeroNavConfig.INSTANCE.bridgingEnabled());

        long myGeneration = generation.incrementAndGet();
        computing = true;
        executor.submit(view, start, currentGoal, XaeroNavConfig.INSTANCE.maxExpandedNodes())
                .whenComplete((result, error) -> {
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
