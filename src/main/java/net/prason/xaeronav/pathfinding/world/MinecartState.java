package net.prason.xaeronav.pathfinding.world;

/**
 * 探索から見たトロッコの状態。
 *
 * @param available トロッコで走る移動を提示してよいか（持っているか乗っていて、旧来の挙動のワールド）
 * @param riding    いま乗っているか。乗っているなら{@code railX/Y/Z}のレールから始まる
 * @param speed     乗っているトロッコの水平の速さ（ブロック/tick、溜めた惰性を含む）
 * @param dirX      進んでいる向き（乗っていて動いているときだけ非0）
 * @param dirZ      同上
 */
public record MinecartState(boolean available, boolean riding, int railX, int railY, int railZ, double speed,
                            double dirX, double dirZ) {

    public static final MinecartState UNAVAILABLE = new MinecartState(false, false, 0, 0, 0, 0.0, 0.0, 0.0);

    public static MinecartState carrying() {
        return new MinecartState(true, false, 0, 0, 0, 0.0, 0.0, 0.0);
    }
}
