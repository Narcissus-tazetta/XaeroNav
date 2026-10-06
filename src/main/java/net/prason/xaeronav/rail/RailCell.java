package net.prason.xaeronav.rail;

/**
 * レール1本を{@code int}に詰めた表現。チャンクの中の位置で持つ。
 *
 * <p>ビット: x(4) | z(4) | 向き(4) | 種類(3) | 通電(1) | y(16、符号付き)。
 * 保存データにこの値をそのまま書くので、並びを変えるときは{@link RailStore}の版を上げること。
 */
public final class RailCell {

    private RailCell() {
    }

    public static int pack(int localX, int y, int localZ, TrackShape shape, RailKind kind, boolean powered) {
        return (localX & 15)
                | (localZ & 15) << 4
                | shape.ordinal() << 8
                | kind.ordinal() << 12
                | (powered ? 1 : 0) << 15
                | (y & 0xFFFF) << 16;
    }

    public static int localX(int cell) {
        return cell & 15;
    }

    public static int localZ(int cell) {
        return cell >>> 4 & 15;
    }

    public static int y(int cell) {
        return cell >> 16;
    }

    public static TrackShape shape(int cell) {
        return TrackShape.values()[cell >>> 8 & 15];
    }

    public static RailKind kind(int cell) {
        return RailKind.values()[cell >>> 12 & 7];
    }

    public static boolean powered(int cell) {
        return (cell >>> 15 & 1) != 0;
    }

    /** 保存データから読んだ値が、今の列挙で解ける範囲か。 */
    static boolean valid(int cell) {
        return (cell >>> 8 & 15) < TrackShape.values().length && (cell >>> 12 & 7) < RailKind.values().length;
    }
}
