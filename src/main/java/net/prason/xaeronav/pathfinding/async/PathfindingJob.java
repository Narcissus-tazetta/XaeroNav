package net.prason.xaeronav.pathfinding.async;

import java.util.concurrent.atomic.AtomicBoolean;

/** キャンセル可能な1回の探索リクエスト（design doc §4-6）。 */
final class PathfindingJob {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    void cancel() {
        cancelled.set(true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }
}
