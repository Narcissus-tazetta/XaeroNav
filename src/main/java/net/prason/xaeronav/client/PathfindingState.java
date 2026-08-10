package net.prason.xaeronav.client;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathSafetyChecker;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.WorldSnapshot;

/**
 * クライアント側の経路探索状態（design doc §4-5/§4-6の配線）。シングルプレイのみ対象。
 *
 * <p>{@link #setGoal}/{@link #onClientTick}はクライアントスレッド（メインスレッド）から呼ぶこと。
 * スナップショット構築はここでメインスレッド上で行い、実際のA*探索だけを{@link PathfindingExecutor}の
 * ワーカースレッドに投げる。
 */
public final class PathfindingState {

    public static final PathfindingState INSTANCE = new PathfindingState();

    private final PathfindingExecutor executor = new PathfindingExecutor();
    // clear()・新規setGoal()のたびに増分する。非同期結果を適用する直前にこれと照合し、
    // 一致しなければ「もう古くなったリクエストの結果」として捨てる(clear後に古い結果が
    // currentResultを復活させてしまう競合を防ぐ)。
    private final AtomicLong generation = new AtomicLong();

    private volatile BlockPos goal;
    private volatile PathResult currentResult;
    private volatile boolean computing;
    private int ticksSinceRecalc;

    private PathfindingState() {
    }

    public void setGoal(BlockPos goal) {
        this.goal = goal;
        recalculate();
    }

    public void clear() {
        generation.incrementAndGet();
        this.goal = null;
        this.currentResult = null;
    }

    public BlockPos goal() {
        return goal;
    }

    public PathResult currentResult() {
        return currentResult;
    }

    public void onClientTick() {
        if (goal == null || computing) {
            // 計算中は逸脱検知・定期実行のトリガーを一旦止める。さもないと非同期結果が
            // 返ってくるまでの数tickの間、毎tick全域スナップショットを取り直してしまう。
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        ticksSinceRecalc++;

        PathResult result = currentResult;
        if (result != null && hasDeviated(mc.player.blockPosition(), result)) {
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
        double nearestDistSq = Double.MAX_VALUE;
        for (PathStep step : result.steps()) {
            double d = step.pos().distSqr(playerPos);
            if (d < nearestDistSq) {
                nearestDistSq = d;
            }
            if (nearestDistSq <= threshold) {
                return false;
            }
        }
        return nearestDistSq > threshold;
    }

    private void recalculate() {
        ticksSinceRecalc = 0;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        BlockPos currentGoal = this.goal;
        if (level == null || player == null || currentGoal == null) {
            return;
        }

        BlockPos start = player.blockPosition();
        // 探索範囲全ブロックを毎回メインスレッドでスナップショットする(design doc §4-3)。
        // 数万ブロック規模の同期読み取りになるため、頻繁な再計算はカクつきの原因になりうる。
        // Phase 3で増分更新・キャッシュ化を検討する。
        SearchBounds bounds = SearchBounds.around(start, currentGoal,
                XaeroNavConfig.INSTANCE.searchHorizontalMargin(), XaeroNavConfig.INSTANCE.searchVerticalMargin());
        WorldSnapshot snapshot = WorldSnapshot.capture(level, player, bounds, XaeroNavConfig.INSTANCE.diggingEnabled());

        long myGeneration = generation.incrementAndGet();
        computing = true;
        executor.submit(snapshot, start, currentGoal)
                .whenComplete((result, error) -> {
                    computing = false;
                    if (error == null && generation.get() == myGeneration) {
                        currentResult = PathSafetyChecker.annotate(snapshot, result);
                    }
                });
    }
}
