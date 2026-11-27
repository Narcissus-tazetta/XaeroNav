package net.prason.xaeronav.client;

/**
 * Create Aeronautics（Sable）の乗り物が「飛んでいる」かの判定。構造物の外接箱の下端と地面の隙間で見て、
 * 境界での往復を防ぐヒステリシスだけを受け持つ。{@code Minecraft}に触れないので単体で検証できる。
 *
 * <p>実機（Offroadの通常のタイヤ）では、車輪で接地している車の隙間は0.97だった。タイヤは外接箱の外に出るので、
 * 接地中でも隙間は0にならない。一番大きいタイヤ（半径2.0）とサスペンションの伸び（最大0.65）を足すと
 * 計算上は約2.2まで開くので、入りの閾値はそれより上に置く。
 *
 * <p>入りには{@link #SUSTAIN_TICKS}の継続を要求する。段差を跳ねた車が一瞬浮くたびに空中の案内へ切り替わると、
 * 地上の経路が丸ごと引き直しになる（{@link ElytraTrigger}と同じ理由）。
 */
final class VehicleAirTrigger {

    /** 隙間がこれを超えたら飛んでいる候補（ブロック）。 */
    static final double ENTER_CLEARANCE = 3.5;
    /** 飛んでいるとみなした後、これ以下まで下がったら地上へ戻す（ブロック）。 */
    static final double EXIT_CLEARANCE = 3.0;
    /** 隙間が入りの閾値を超えたまま、これだけ続いて初めて飛んでいるとみなす（tick）。 */
    static final int SUSTAIN_TICKS = 10;

    private boolean flying;
    private int highTicks;

    /** このtickの隙間から、飛んでいるかを更新して返す。 */
    boolean update(double clearance) {
        if (flying) {
            flying = clearance > EXIT_CLEARANCE;
            highTicks = flying ? highTicks : 0;
            return flying;
        }
        highTicks = clearance > ENTER_CLEARANCE ? highTicks + 1 : 0;
        flying = highTicks >= SUSTAIN_TICKS;
        return flying;
    }

    void reset() {
        flying = false;
        highTicks = 0;
    }
}
