package net.prason.xaeronav.pathfinding.cost;

import net.prason.xaeronav.pathfinding.cost.ElytraPhysics.Velocity;

/**
 * 空中経路のコストモデル（単位: tick、他のコストと揃えてある）。
 *
 * <p>すべての定数は{@link ElytraPhysics}がバニラの漸化式を回して求める。ここに直接書かれた数値は
 * <b>掃引の範囲と刻みだけ</b>で、速度・沈下率・滑空比・上昇率は1つも書き写していない。
 *
 * <h2>要点: 水平飛行はすでに「登り」である</h2>
 *
 * エリトラはどのピッチでも定常状態で高度を失う（{@link ElytraPhysics#zoomClimb}のコメント参照）。
 * つまり高度を保って飛ぶこと自体に能動的な入力＝ロケットが要る。この非対称性をコストに入れないと、
 * 「まっすぐ水平に飛ぶ」が最安に見えてしまい、実際には滑空だけで届く緩い下り勾配が選ばれなくなる。
 *
 * <p>そこで区間のコストは、自然な滑空で稼げる降下（水平距離÷滑空比）を先に差し引いてから、
 * 残った分だけを「能動的に稼ぐ高度」として課金する:
 *
 * <pre>
 *   requiredClimb = dv + dh / GLIDE_RATIO
 *   cost = dh * HORIZONTAL + max(0, -dv) * DESCENT + max(0, requiredClimb) * ASCENT
 * </pre>
 *
 * <p>副次的に、滑空比より急な降下も割に合わなくなる（{@code -dv}に課金され、requiredClimbは0で
 * 頭打ちなので得にならない）。使い切った高度は登り直すしかない、という実際の損得がそのまま出る。
 *
 * <h2>ヒューリスティックが滑空分を差し引かない理由</h2>
 *
 * 見積もりから{@code dh / GLIDE_RATIO}を引くと、遠回りするほど滑空で稼げる降下が増えて
 * {@code requiredClimb}が減るため、直線距離で測った見積もりが実際のコストを<b>上回りうる</b>
 * ＝非許容になる。滑空の割引は区間コスト側にだけ置き、見積もりは割り引かない。
 * こうすると常に{@code 区間コスト >= 見積もりの減少分}が成り立ち、A*の最適性が保たれる。
 */
public final class FlightCosts {

    /** 掃引の刻み（度）。0.5度でポーラの頂点は十分に捉えられる（頂点付近は平坦）。 */
    private static final double PITCH_STEP_DEGREES = 0.5;

    /** 最も遠くまで滑空できる姿勢。ここから水平コストと滑空比が決まる。 */
    private static final Velocity BEST_GLIDE =
            ElytraPhysics.bestSteadyState(-30.0, 80.0, PITCH_STEP_DEGREES, false, Velocity::glideRatio);

    /** ロケットを焚き続けたときに最も速く登れる姿勢。 */
    private static final Velocity BEST_ROCKET_CLIMB =
            ElytraPhysics.bestSteadyState(-90.0, 0.0, PITCH_STEP_DEGREES, true, Velocity::vertical);

    /** ロケット無しの上昇は、巡航で溜めた速度を一度きり高度へ替えるしかない。 */
    private static final ElytraPhysics.ZoomClimb BEST_ZOOM_CLIMB =
            ElytraPhysics.bestZoomClimb(BEST_GLIDE, -5.0, -90.0, PITCH_STEP_DEGREES);

    /** 水平に1ブロック進むtick数。最良滑空姿勢の巡航速度から。 */
    public static final double HORIZONTAL_TICKS_PER_BLOCK = 1.0 / BEST_GLIDE.horizontal();

    /** 水平に何ブロック進む間に1ブロック沈むか。無料で使える降下の勾配。 */
    public static final double GLIDE_RATIO = BEST_GLIDE.glideRatio();

    /**
     * 1ブロック降りるtick数。真下へ向けたときの終端速度から。
     *
     * <p><b>0にしてはいけない</b>。登った高度を取り返す代償が無料に見えると、重み付きA*は
     * 上りの枝を系統的に選んで{@code closed}で確定させる（歩行側で実際に踏んだ振動と同じ形）。
     */
    public static final double DESCENT_TICKS_PER_BLOCK =
            1.0 / -ElytraPhysics.steadyState(90.0, false).vertical();

    /** ロケットを持っているときに1ブロック登るtick数。 */
    public static final double ROCKET_ASCENT_TICKS_PER_BLOCK = 1.0 / BEST_ROCKET_CLIMB.vertical();

    /** ロケットが無いときに1ブロック登るtick数。速度と高度の交換なので桁違いに高い。 */
    public static final double GLIDING_ASCENT_TICKS_PER_BLOCK = BEST_ZOOM_CLIMB.ticksPerBlock();

    private FlightCosts() {
    }

    /** 1ブロック登るtick数。ロケットの所持で切り替わる（ボートの有無で水のコストが変わるのと同じ形）。 */
    public static double ascentTicksPerBlock(boolean rockets) {
        return rockets ? ROCKET_ASCENT_TICKS_PER_BLOCK : GLIDING_ASCENT_TICKS_PER_BLOCK;
    }

    /**
     * 区間を飛ぶtick数。{@code verticalBlocks}は上が正。
     * 自然な滑空で賄える降下は差し引いてから登りに課金する（クラスのコメント参照）。
     */
    public static double segmentTicks(double horizontalBlocks, double verticalBlocks, boolean rockets) {
        double requiredClimb = verticalBlocks + horizontalBlocks / GLIDE_RATIO;
        return horizontalBlocks * HORIZONTAL_TICKS_PER_BLOCK
                + Math.max(0.0, -verticalBlocks) * DESCENT_TICKS_PER_BLOCK
                + Math.max(0.0, requiredClimb) * ascentTicksPerBlock(rockets);
    }

    /**
     * ゴールまでの見積もり。滑空の割引を入れないぶん{@link #segmentTicks}を必ず下回るので、
     * 重みを掛けない限りA*の最適性が保たれる。
     */
    public static double heuristicTicks(double horizontalBlocks, double verticalBlocks, boolean rockets) {
        return horizontalBlocks * HORIZONTAL_TICKS_PER_BLOCK
                + Math.max(0.0, -verticalBlocks) * DESCENT_TICKS_PER_BLOCK
                + Math.max(0.0, verticalBlocks) * ascentTicksPerBlock(rockets);
    }
}
