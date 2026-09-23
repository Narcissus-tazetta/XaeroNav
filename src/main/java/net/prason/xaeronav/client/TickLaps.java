package net.prason.xaeronav.client;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * 1tickの中で、名前を付けた処理にかかった時間を積み上げる。{@code tick処理が遅い}のログに内訳を添えるためのもの。
 *
 * <p>入れ子で測った処理は親にも子にも数える（{@code 再計算}の中の{@code 航法グラフの起動}など）ので、足し合わせると
 * tick全体を超えることがある。{@link #begin}を呼んだスレッド以外からの記録は捨てる——同じ処理が
 * {@code onMainThread}のタスクやワーカーから呼ばれることがあり、それはtickの時間ではない。
 */
final class TickLaps {

    /** 内訳に出す下限。これ未満の処理は並べても原因の手がかりにならない。 */
    private static final long SHOWN_NANOS = 1_000_000L;

    private static final int CAPACITY = 32;
    private static final String[] NAMES = new String[CAPACITY];
    private static final long[] NANOS = new long[CAPACITY];
    private static final int[] CALLS = new int[CAPACITY];
    private static int size;
    private static Thread owner;
    private static boolean inTick;
    private static long gcAtBegin;

    private TickLaps() {
    }

    static void begin() {
        owner = Thread.currentThread();
        size = 0;
        inTick = true;
        gcAtBegin = gcPauseMillis();
    }

    static void end() {
        inTick = false;
    }

    static long start() {
        return System.nanoTime();
    }

    static void add(String name, long startNanos) {
        if (Thread.currentThread() != owner) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        for (int i = 0; i < size; i++) {
            // 名前は定数なので同一性で引ける
            if (NAMES[i] == name) {
                NANOS[i] += elapsed;
                CALLS[i]++;
                return;
            }
        }
        if (size < CAPACITY) {
            NAMES[size] = name;
            NANOS[size] = elapsed;
            CALLS[size] = 1;
            size++;
        }
    }

    /**
     * 探索の結果をメインスレッドで受け取るタスクを測る。tickの中で直に走れば（{@code Minecraft#execute}は描画スレッドから
     * 呼ばれるとその場で実行する）tickの内訳に数える。tickの外で走った分は{@code tick処理が遅い}に出ないが同じ描画スレッドを
     * 止めるので、{@link #SLOW_TASK_MILLIS}を超えればその中の内訳を付けて別に知らせる。
     */
    static Runnable timed(Runnable task) {
        return () -> {
            boolean nested = inTick && Thread.currentThread() == owner;
            if (!nested) {
                begin();
            }
            long lap = start();
            try {
                task.run();
            } finally {
                add("結果の受け取り", lap);
                long millis = (System.nanoTime() - lap) / 1_000_000L;
                long now = MonotonicTime.millis();
                if (!nested && millis > SLOW_TASK_MILLIS
                        && slowTaskGate.changed(true, now, SLOW_TASK_LOG_INTERVAL_MILLIS)) {
                    XaeroNav.LOGGER.warn("XaeroNav: tickの外の結果の受け取りが遅い ({}ms, 内訳={})", millis, summary());
                }
                if (!nested) {
                    end();
                }
            }
        };
    }

    /** 受け取りの中身に名前を付けて測る。どの受け取りが重いかは、ラムダのクラス名からは分からない。 */
    static <T> BiConsumer<T, Throwable> timed(String name, BiConsumer<T, Throwable> action) {
        return (result, error) -> {
            long lap = start();
            try {
                action.accept(result, error);
            } finally {
                add(name, lap);
            }
        };
    }

    private static final long SLOW_TASK_MILLIS = 50L;
    private static final long SLOW_TASK_LOG_INTERVAL_MILLIS = 5_000L;
    private static final ChangeGate<Boolean> slowTaskGate = new ChangeGate<>();

    /** 下限以上の処理を、かかった順に。無ければ{@code "内訳なし"}。 */
    static String summary() {
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (NANOS[i] >= SHOWN_NANOS) {
                shown.add(i);
            }
        }
        // 止まっていた間のGC。処理そのものではなく、その最中に入ったGCの停止で遅く見えることがある
        long gc = gcPauseMillis() - gcAtBegin;
        if (shown.isEmpty()) {
            return gc > 0 ? "内訳なし, GC=" + gc + "ms" : "内訳なし";
        }
        shown.sort((a, b) -> Long.compare(NANOS[b], NANOS[a]));
        StringBuilder text = new StringBuilder();
        for (int i : shown) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(NAMES[i]).append('=').append(String.format(Locale.ROOT, "%.1f", NANOS[i] / 1e6)).append("ms");
            if (CALLS[i] > 1) {
                text.append('×').append(CALLS[i]);
            }
        }
        if (gc > 0) {
            text.append(", GC=").append(gc).append("ms");
        }
        return text.toString();
    }

    /** 起動からのGCでスレッドが止まった時間の合計。 */
    static long gcPauseMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            // G1の"G1 Concurrent GC"はアプリのスレッドを止めない並行処理の時間なので、止まった時間に数えない
            if (!bean.getName().contains("Concurrent")) {
                total += Math.max(0L, bean.getCollectionTime());
            }
        }
        return total;
    }
}
