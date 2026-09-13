package net.prason.xaeronav.util;

/** 壁時計の補正に影響されない、deadline・retry・所要時間用の時刻。 */
public final class MonotonicTime {

    private MonotonicTime() {
    }

    /** {@link System#nanoTime()}と同じ基準をミリ秒単位で返す。絶対日時として使ってはならない。 */
    public static long millis() {
        return System.nanoTime() / 1_000_000L;
    }
}
