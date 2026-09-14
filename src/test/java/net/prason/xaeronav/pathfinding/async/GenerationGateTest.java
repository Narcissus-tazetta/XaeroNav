package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link GenerationGate}——世代を追い越された結果が静かに捨てられること、最新の結果は
 * 届くこと。ライブナビ（{@code PathfindingState}）が5箇所で共有する世代カウンタと同じ形を、
 * 実クライアント無しで検証する（TEST-01）。
 *
 * <p>{@code onMainThread}はテストでは{@code Runnable::run}を渡す
 * （{@link DiagnosticJobRunnerTest}と同じ理由——検証したいのは世代照合そのもの）。
 */
class GenerationGateTest {

    private static final long AWAIT_SECONDS = 5;

    @Test
    void aStaleGenerationsResultIsDiscardedEvenAfterItCompletes() throws InterruptedException {
        AtomicLong generation = new AtomicLong(1);
        GenerationGate gate = new GenerationGate(generation, Runnable::run);
        AtomicReference<String> delivered = new AtomicReference<>();
        CountDownLatch actionCalled = new CountDownLatch(1);

        CompletableFuture<String> staleFuture = new CompletableFuture<>();
        long staleGeneration = generation.get();
        gate.whenStillCurrent(staleFuture, staleGeneration, (result, error) -> {
            delivered.set(result);
            actionCalled.countDown();
        });

        // 完了前に世代が進む（新しいリクエストへ置き換わった）
        generation.incrementAndGet();
        staleFuture.complete("stale");

        // actionは呼ばれないはず。時間制限つきで待って「呼ばれないこと」を確認する
        assertEquals(false, actionCalled.await(300, TimeUnit.MILLISECONDS),
                "世代を追い越された結果のactionが呼ばれてしまっている");
        assertNull(delivered.get());
    }

    @Test
    void theCurrentGenerationsResultIsDeliveredToTheAction() throws InterruptedException {
        AtomicLong generation = new AtomicLong(1);
        GenerationGate gate = new GenerationGate(generation, Runnable::run);
        AtomicReference<String> delivered = new AtomicReference<>();
        CountDownLatch actionCalled = new CountDownLatch(1);

        CompletableFuture<String> future = new CompletableFuture<>();
        gate.whenStillCurrent(future, generation.get(), (result, error) -> {
            delivered.set(result);
            actionCalled.countDown();
        });

        future.complete("fresh");

        assertEquals(true, actionCalled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "最新世代の結果が届かない");
        assertEquals("fresh", delivered.get());
    }

    @Test
    void theActionRunsThroughTheInjectedOnMainThreadConsumer() throws InterruptedException {
        AtomicLong generation = new AtomicLong(1);
        AtomicReference<Thread> ranOnThread = new AtomicReference<>();
        CountDownLatch mainThreadInvoked = new CountDownLatch(1);
        // onMainThreadが実際に経由されていることを見る——別スレッドから呼んでも
        // ここに登録したConsumerを必ず通ることを確認する
        GenerationGate gate = new GenerationGate(generation, runnable -> {
            mainThreadInvoked.countDown();
            runnable.run();
        });
        CompletableFuture<String> future = new CompletableFuture<>();
        gate.whenStillCurrent(future, generation.get(), (result, error) -> ranOnThread.set(Thread.currentThread()));

        future.complete("ok");

        assertEquals(true, mainThreadInvoked.await(AWAIT_SECONDS, TimeUnit.SECONDS), "onMainThreadが呼ばれていない");
        assertEquals(Thread.currentThread(), ranOnThread.get());
    }
}
