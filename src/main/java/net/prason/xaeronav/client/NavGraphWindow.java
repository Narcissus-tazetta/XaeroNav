package net.prason.xaeronav.client;

/**
 * 航法グラフの窓の半径。ヒープの上限で決まり、起動中は変わらない。
 */
public final class NavGraphWindow {

    /**
     * ヒープに余裕があるときの窓の半径（ブロック）。描画距離がこれより広くてもここで切る。
     *
     * <p>経路の見直し（{@link net.prason.xaeronav.pathfinding.navgraph.RouteReview}）は目的地が窓に入ってから走るので、
     * 窓が狭いと遠回りに気づくのが遅れる（実機のネザーで目的地まで約130ブロックで初めて気づき、余計に653tick）。
     * 歩き通しの模型で160・192・224・240を測った。
     * <ul>
     * <li>224は、ネザーが平均1.044→1.001・最悪1.147→1.003倍、現世が1.018→1.009倍。実機の保存地形の罠では6207→4177tick（真値3941）</li>
     * <li>192は、ネザーの最悪が1.229倍で、160より悪い。広げるほど単調に良くなるわけではない</li>
     * <li>240は、エンド外側の島の1本で経路が出なくなる</li>
     * </ul>
     * 辺の数は面積に比例して増える。224で辺は最大7,000万本、グラフとガイドは合わせて最大約270MB（160では約150MB。ネザーの罠の地形で実測）。
     * ガイド1回の最大は1.3→2.1秒になる。
     *
     * <p>ヒープが{@link #WIDE_MIN_HEAP_BYTES}未満なら{@link #NARROW_BLOCKS}に落とす。
     */
    static final int WIDE_BLOCKS = 224;

    /** ヒープが小さいときの窓。間の192はネザーの最悪が160より悪いので選ばない。 */
    static final int NARROW_BLOCKS = 160;

    /**
     * 窓224を使うのに要るヒープ。公式ランチャーの既定の2GBでは、本体の分と窓224の最大約270MBが重なると余裕が無い。
     *
     * <p>{@code -Xmx3G}を指定した人は224にしたいが、SerialGC・ParallelGCの{@link Runtime#maxMemory}は生存領域1つ分を
     * 引いて返す（実測: {@code -Xmx3G}で2,969MB・2,731MB、{@code -Xmx2G}で1,979MB・1,820MB）ので、間の2.5GBで切る。
     */
    static final long WIDE_MIN_HEAP_BYTES = 2560L << 20;

    static final int BLOCKS = Runtime.getRuntime().maxMemory() >= WIDE_MIN_HEAP_BYTES ? WIDE_BLOCKS : NARROW_BLOCKS;

    private NavGraphWindow() {
    }

    /** ヒープが足りず窓を狭めているか。設定画面で知らせる。 */
    public static boolean narrowed() {
        return BLOCKS < WIDE_BLOCKS;
    }
}
