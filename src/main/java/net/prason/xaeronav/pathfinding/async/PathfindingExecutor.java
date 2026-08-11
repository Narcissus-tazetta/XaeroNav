package net.prason.xaeronav.pathfinding.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.StanceFinder;

/**
 * design doc §4-5/§4-6。ワーカースレッドでA*を実行する。新しいリクエストが来たら
 * 実行中(または未着手)の古いジョブをキャンセルし、常に最新のリクエストだけが結果を返す。
 *
 * <p>{@link ChunkView}の構築（メインスレッドでのチャンク参照集め）は呼び出し側の責務。
 * このクラスはA*の実行と、そのキャンセル制御のみを担当する。
 */
public final class PathfindingExecutor {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-pathfinding");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicReference<PathfindingJob> currentJob = new AtomicReference<>();

    public CompletableFuture<PathResult> submit(ChunkView view, BlockPos start, BlockPos goal, int maxExpandedNodes) {
        PathfindingJob job = new PathfindingJob();
        PathfindingJob previous = currentJob.getAndSet(job);
        if (previous != null) {
            previous.cancel();
        }

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                AStarPathfinder pathfinder = new AStarPathfinder(view, maxExpandedNodes,
                        AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS);
                // 立てない座標のまま探索すると経路が1本も伸びない。ブロックを読める場所での
                // 寄せ直しなので、メインスレッドへ戻さずここで行う
                PathResult result = pathfinder.search(
                        StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal),
                        job::isCancelled);
                if (job.isCancelled()) {
                    future.cancel(false);
                } else {
                    future.complete(result);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public void shutdown() {
        PathfindingJob job = currentJob.get();
        if (job != null) {
            job.cancel();
        }
        executor.shutdownNow();
    }
}
