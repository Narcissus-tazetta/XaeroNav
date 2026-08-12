package net.prason.xaeronav.pathfinding.cost;

/**
 * 移動コストの基準値（単位: tick）。design doc §3-1/§4-1参照。
 * 徒歩・スプリント・ジャンプ・落下の数値はBaritone(ActionCosts.java, LGPL)で使われている実測値と同一だが、
 * ここではアイデア・数値のみを参考にし、コード自体は独自実装している。
 * 昇降・水中採掘はMinecraft本体の実装から導出し、設置・ドア開閉のオーバーヘッドは本MOD独自の見積もり。
 */
public final class ActionCosts {

    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;
    public static final double WALK_ONE_IN_WATER = 20.0 / 2.2;

    /**
     * ボートで直進し続けたときの定常速度（1ブロックあたりのtick数）。
     * {@code Boat#floatBoat()}（水上時 invFriction=0.9F）と{@code Boat#controlBoat()}
     * （前進キー押下時、friction適用後に f=0.04F を加算）から、速度の漸化式
     * v_(n+1) = 0.9 * v_n + 0.04 の収束先 v* = 0.04/(1-0.9) = 0.4 blocks/tick = 8.0 blocks/秒を
     * 導出。時定数 1/(1-0.9)=10 tick(0.5秒) なので静水上ではほぼ瞬時に収束する。
     */
    public static final double PADDLE_ONE_BLOCK = 20.0 / 8.0;

    /**
     * 蜘蛛の巣の中を1マス進む。{@code WebBlock#entityInside}が移動量そのものに0.25を掛けるため
     * （{@code Entity#move}）、走っていても素の1/4しか進まない。
     */
    public static final double SPRINT_ONE_IN_COBWEB = SPRINT_ONE_BLOCK / 0.25;

    private static final double WALK_OFF_BLOCK = WALK_ONE_BLOCK * 0.8;
    private static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_BLOCK;

    /**
     * ジャンプで1マス登るのに要するtick数。放物線の対称性から
     * 「1.25マス分落下する時間」と「最後の0.25マス分落下する時間」の差として導出する
     * （踏み切ってから頂点=1.25マスに達するまでの上昇時間 = 対称な下降時間という関係を使う）。
     */
    public static final double JUMP_ONE_BLOCK = FallPhysics.ticksToFall(1.25) - FallPhysics.ticksToFall(0.25);

    public static final double ASCEND_ONE_BLOCK = Math.max(JUMP_ONE_BLOCK, WALK_ONE_BLOCK);

    /**
     * 1マスの隙間を飛び越えるのに要するtick数。踏み切ってから着地するまでは滞空時間そのもので、
     * 頂点1.25マスの上昇と下降が対称なので、その往復として求める（約12.5tick）。
     *
     * <p>走って2マス進む（約7tick）より高くつくため、平地では選ばれない。迂回が4マス以上に
     * なるときだけ跳ぶ、という現実に近い判断になる。
     */
    public static final double JUMP_ACROSS_GAP = 2.0 * FallPhysics.ticksToFall(1.25);

    public static final double DESCEND_ONE_BLOCK = fallCost(1);

    /**
     * 落下ダメージを受けずに降りられる高さ。バニラは{@code ceil((落下距離 - SAFE_FALL_DISTANCE) × 倍率)}を
     * ダメージとし、{@code SAFE_FALL_DISTANCE}属性の既定値は3。
     */
    public static final int SAFE_FALL_BLOCKS = 3;

    /**
     * 梯子・ツタを1マス登る。バニラの登坂速度2.35ブロック/秒より。
     * 降りる方は{@code LivingEntity#handleOnClimbable}が下向き速度を0.15ブロック/tickで頭打ちにするため、
     * ちょうど3ブロック/秒になる。
     */
    public static final double LADDER_UP_ONE_BLOCK = 20.0 / 2.35;

    public static final double LADDER_DOWN_ONE_BLOCK = 20.0 / 3.0;

    /** ドア・フェンスゲートを開けて通る追加コスト。立ち止まって向き直り、開けるまで。ドアは上下2マスぶん掛かる。 */
    public static final double OPEN_DOOR_OVERHEAD_TICKS = 5.0;

    /** 斜め1マス（水平√2マス分）の移動距離倍率。Diagonal移動のコスト・ヒューリスティック双方で使う。 */
    public static final double DIAGONAL_DISTANCE = Math.sqrt(2.0);

    /**
     * 大きく落下する場合、tick/マスはterminal velocity(3.92 blocks/tick)に漸近しこれを下回らない。
     * A*ヒューリスティックの下降成分に使う安全な下限値（design doc §4-2参照）。
     */
    public static final double FALL_ASYMPTOTIC_MIN_PER_BLOCK = 1.0 / 3.92;

    public static final double DIG_OVERHEAD_TICKS = 2.0;

    /**
     * 水中での採掘の遅さ。バニラは頭が水に浸かっている間、採掘速度に{@code SUBMERGED_MINING_SPEED}
     * （水中採掘のエンチャントが無ければ0.2）を掛ける。
     */
    public static final double SUBMERGED_DIG_PENALTY = 5.0;

    /**
     * ブロックを設置して空洞を渡る際の照準・設置オーバーヘッド（design doc §4-1 Pillar水平版）。
     *
     * <p>橋を架けながらの前進は「一度止まって足元の縁へ向き直り、狙って置く」の繰り返しなので、
     * 走るのに比べて1/3程度の速さしか出ない。ここを数tickに見積もると1マスあたり徒歩(4.63)より
     * 安くなり、数マス迂回すれば済む場所でも常に設置が選ばれてしまう。
     */
    public static final double PLACE_BLOCK_OVERHEAD_TICKS = 8.0;

    public static final double INFEASIBLE = Double.POSITIVE_INFINITY;

    private ActionCosts() {
    }

    /** 縁から踏み出して{@code blocks}マス落ち、着地してマスの中心に戻るまで。 */
    public static double fallCost(int blocks) {
        return WALK_OFF_BLOCK + Math.max(FallPhysics.ticksToFall(blocks), CENTER_AFTER_FALL);
    }
}
