package net.prason.xaeronav.util;

/**
 * {@link Math#clamp}相当。JDK21で追加されたオーバーロードなので、Java 17ノード（1.20.1）向けに
 * 自前で持つ。ローダー/バージョンを問わず同じ結果になるので、{@code pathfinding/}からでも
 * ゲート無しで呼べる。
 */
public final class MathSupport {

    private MathSupport() {
    }

    public static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    public static long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }

    public static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
