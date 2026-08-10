package net.prason.xaeronav.client;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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

    // 直近の探索に使った入力。以下はクライアントスレッドからのみ触る。
    private BlockPos lastStart;
    private BlockPos lastGoal;
    private int ticksSinceRecalc;
    private int ticksSinceValidation;

    private PathfindingState() {
    }

    public void setGoal(BlockPos goal) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
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
        this.lastGoal = null;
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

    public void onClientTick() {
        BlockPos currentGoal = goal;
        if (currentGoal == null || computing) {
            // 計算中は逸脱検知・定期実行のトリガーを一旦止める。さもないと非同期結果が
            // 返ってくるまでの数tickの間、毎tick探索を投げ直してしまう。
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
        // Xaeroの世界地図やインベントリを開いている間、プレイヤーは動けない。ここで止めないと
        // 地図を眺めているだけの間ずっと同じ入力に対する探索が走り続ける。
        if (mc.screen != null) {
            return;
        }
        ticksSinceRecalc++;
        ticksSinceValidation++;

        PathResult result = currentResult;
        if (result != null && !mc.player.onGround() && !mc.player.isInWater()) {
            // ジャンプ・落下中は見た目の座標が経路から一時的にずれるだけで実際には逸脱していないことが多い。
            // 空中にいる間は再計算をせず、着地するまで今の経路を維持する。
            // 泳いでいる間もonGroundは常にfalseなので、水中は例外にしないと経路が更新されなくなる。
            return;
        }

        BlockPos start = mc.player.blockPosition();
        if (start.equals(lastStart) && currentGoal.equals(lastGoal)) {
            // 始点も終点も同じなら探索し直しても同じ結果になる。ただしワールドの方が変わっていれば
            // 経路は無効になりうるので、経路上のセルだけを定期的に確認する。
            if (result != null && ticksSinceValidation >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
                ticksSinceValidation = 0;
                if (!PathValidator.stillValid(mc.level, result)) {
                    recalculate();
                }
            }
            return;
        }
        if (result != null && hasDeviated(start, result)) {
            recalculate();
            return;
        }
        if (ticksSinceRecalc >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
            recalculate();
        }
    }

    private boolean hasDeviated(BlockPos playerPos, PathResult result) {
        if (result.steps().isEmpty()) {
            return false;
        }
        double deviation = XaeroNavConfig.INSTANCE.deviationThresholdBlocks();
        double threshold = deviation * deviation;
        for (PathStep step : result.steps()) {
            if (step.pos().distSqr(playerPos) <= threshold) {
                return false;
            }
        }
        return true;
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
        lastGoal = currentGoal;

        // 探索範囲は描画距離で切る。読み込み済みチャンクの外は読めないので、そこまで広げても
        // 未ロード扱いのセルを舐めるだけになる。同時に、描画距離を下げているマシンでは
        // 探索の負荷も自動的に下がる。
        SearchBounds bounds = SearchBounds.around(level, start, currentGoal,
                XaeroNavConfig.INSTANCE.searchHorizontalMargin(), XaeroNavConfig.INSTANCE.searchVerticalMargin(),
                mc.options.getEffectiveRenderDistance() * 16);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.diggingEnabled());

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
