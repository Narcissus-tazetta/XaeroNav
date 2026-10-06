package net.prason.xaeronav.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * XaeroNavのワーカースレッドはすべてデーモンにする。ゲームの終了をワーカーの後始末で待たせないため。
 */
public final class DaemonThreads {

    private DaemonThreads() {
    }

    public static ThreadFactory named(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 名前に通し番号を付け、優先度を下げる。ゲームのスレッドとCPUを奪い合う並列計算用。 */
    public static ThreadFactory numberedLowPriority(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    /**
     * 1本のスレッドで順に処理する。{@link java.util.concurrent.Executors#newSingleThreadExecutor}と違い、
     * 待ち行列の中身を覗いたり捨てたりできる{@link ThreadPoolExecutor}のまま返す。
     */
    public static ThreadPoolExecutor singleThread(String name) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), named(name));
    }

    public static ExecutorService fixedPool(String name, int threads) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                numberedLowPriority(name));
    }
}
