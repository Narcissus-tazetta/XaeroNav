package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link DiagnosticJobRunner}——単一worker・世代によるキャンセル・完了時のメインスレッド戻し。
 *
 * <p>{@code onMainThread}はテストでは{@code Runnable::run}（呼び出し元のバックグラウンドスレッドで
 * 即実行）を渡す。実際の呼び出し側は{@code Minecraft.getInstance()::execute}を渡す想定だが、
 * ここで検証したいのは世代照合そのものなので、メインスレッドへの実際のスレッド切り替えは検証対象外。
 */
class DiagnosticJobRunnerTest {

    private static final long AWAIT_SECONDS = 5;

    @Test
    void newerGenerationDiscardsTheOlderOnesResultAndSignalsItsCancelFlag() throws InterruptedException {
        AtomicBoolean firstOnCompleteCalled = new AtomicBoolean();
        AtomicBoolean firstSawCancelled = new AtomicBoolean();
        AtomicReference<String> secondResult = new AtomicReference<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        DiagnosticJobRunner runner = new DiagnosticJobRunner(Runnable::run);

        long firstGeneration = runner.begin();
        runner.submit(firstGeneration, cancelled -> {
            firstStarted.countDown();
            awaitOrFail(releaseFirst);
            // 解放された時点でもう次の世代が始まっているはず——協調cancelの合図が届いていることを見る
            firstSawCancelled.set(cancelled.getAsBoolean());
            return "first";
        }, (result, error) -> firstOnCompleteCalled.set(true));

        assertTrue(firstStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "1本目が開始しない");

        long secondGeneration = runner.begin();
        runner.submit(secondGeneration, cancelled -> "second",
                (result, error) -> secondResult.set(result));

        releaseFirst.countDown();

        awaitCondition(() -> secondResult.get() != null);

        assertTrue(firstSawCancelled.get(), "世代を追い越されたのに協調cancelの合図が届いていない");
        assertFalse(firstOnCompleteCalled.get(), "追い越された1本目の結果が呼び出し側へ届いてしまっている");
        assertEquals("second", secondResult.get());
    }

    @Test
    void exceptionFromWorkIsDeliveredAsTheErrorArgument() {
        RuntimeException thrown = new IllegalStateException("boom");
        AtomicReference<String> result = new AtomicReference<>("untouched");
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        DiagnosticJobRunner runner = new DiagnosticJobRunner(Runnable::run);
        long generation = runner.begin();
        runner.<String>submit(generation, cancelled -> {
            throw thrown;
        }, (r, e) -> {
            result.set(r);
            error.set(e);
            done.countDown();
        });

        assertTrue(awaitOrFail(done), "完了が呼ばれない");
        assertNull(result.get(), "例外時はresultがnullで渡るべき");
        assertNotNull(error.get());
        assertEquals(thrown, error.get());
    }

    private static boolean awaitOrFail(CountDownLatch latch) {
        try {
            return latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("条件が時間内に満たされなかった");
            }
            Thread.onSpinWait();
        }
    }
}
