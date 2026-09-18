package net.prason.xaeronav.pathfinding.navgraph;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

/**
 * {@code [0, count)}を小分けにして、呼び出し元のスレッドとプールで並べて回す。
 *
 * <p>呼び出し元も手を動かすので、プールが他の仕事で埋まっていても止まらない。
 * <b>プールのスレッドから呼んではいけない</b>——待っている間そのスレッドを塞ぐので、並列度が1つ減る。
 */
record Parallel(@Nullable Executor pool, int workers) {

    static final Parallel INLINE = new Parallel(null, 1);

    /** {@code [from, to)}を処理する。打ち切るなら{@code false}を返す。 */
    @FunctionalInterface
    interface Range {
        boolean run(int from, int to);
    }

    /** @return どこかの小分けが打ち切ったら{@code false} */
    boolean forEach(int count, int grain, BooleanSupplier cancelled, Range body) {
        if (pool == null || workers <= 1 || count <= grain) {
            for (int from = 0; from < count; from += grain) {
                if (cancelled.getAsBoolean() || !body.run(from, Math.min(count, from + grain))) {
                    return false;
                }
            }
            return true;
        }
        AtomicInteger cursor = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable worker = () -> {
            while (!stopped.get()) {
                int from = cursor.getAndAdd(grain);
                if (from >= count) {
                    return;
                }
                if (cancelled.getAsBoolean() || !body.run(from, Math.min(count, from + grain))) {
                    stopped.set(true);
                }
            }
        };
        int helpers = Math.min(workers - 1, (count + grain - 1) / grain - 1);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[helpers];
        for (int i = 0; i < helpers; i++) {
            futures[i] = CompletableFuture.runAsync(worker, pool);
        }
        worker.run();
        CompletableFuture.allOf(futures).join();
        return !stopped.get();
    }
}
