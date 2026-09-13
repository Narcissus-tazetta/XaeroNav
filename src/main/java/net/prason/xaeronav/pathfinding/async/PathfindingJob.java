package net.prason.xaeronav.pathfinding.async;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

final class PathfindingJob {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CompletableFuture<?> future;

    PathfindingJob(CompletableFuture<?> future) {
        this.future = future;
    }

    void cancel() {
        cancelled.set(true);
        future.cancel(false);
    }

    boolean isCancelled() {
        return cancelled.get();
    }
}
