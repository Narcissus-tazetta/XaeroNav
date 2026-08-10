package net.prason.xaeronav.pathfinding.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.world.WorldSnapshot;

/**
 * design doc §4-5/§4-6。ワーカースレッドでA*を実行する。新しいリクエストが来たら
 * 実行中(または未着手)の古いジョブをキャンセルし、常に最新のリクエストだけが結果を返す。
 *
 * <p>{@link WorldSnapshot}の構築（メインスレッドでのブロック読み取り）は呼び出し側の責務。
 * このクラスはスナップショット構築後のA*実行と、そのキャンセル制御のみを担当する。
 */
public final class PathfindingExecutor {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-pathfinding");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicReference<PathfindingJob> currentJob = new AtomicReference<>();

    public CompletableFuture<PathResult> submit(WorldSnapshot snapshot, BlockPos start, BlockPos goal) {
        PathfindingJob job = new PathfindingJob();
        PathfindingJob previous = currentJob.getAndSet(job);
        if (previous != null) {
            previous.cancel();
        }

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                AStarPathfinder pathfinder = new AStarPathfinder(snapshot);
                PathResult result = pathfinder.search(start, goal, job::isCancelled);
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
