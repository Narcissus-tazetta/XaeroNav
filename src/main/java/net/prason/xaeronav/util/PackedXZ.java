package net.prason.xaeronav.util;

/**
 * 水平座標の組（チャンク座標・リージョン座標など）を1つの{@code long}に詰めるキー。上位32ビットがx、
 * 下位32ビットがz。
 *
 * <p>{@link GameCompat#chunkKey}とは別物——あちらはゲームの{@code ChunkPos}に合わせるのでバージョンで
 * ビットの並びが変わる。こちらは並びを固定しているので、{@code rail/}の保存形式や
 * {@code pathfinding/}のようにゲームのバージョンに依らない場所で使える。
 */
public final class PackedXZ {

    private PackedXZ() {
    }

    public static long pack(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }
}
