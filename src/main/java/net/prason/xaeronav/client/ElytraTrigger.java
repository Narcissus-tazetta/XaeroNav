package net.prason.xaeronav.client;

/**
 * エリトラの滑空を「飛行モード」とみなす・みなさないの判定。境界での往復を防ぐヒステリシスだけを
 * 受け持ち、{@code Minecraft}に触れないので単体で検証できる。
 *
 * <p>ヒステリシスは2種類ある。
 *
 * <h4>時間: 一瞬の滑空判定では飛行モードへ入らない</h4>
 *
 * <p>エリトラを着けたまま連続でジャンプすると、バニラは数tickだけ{@code isFallFlying}を立てる。
 * 飛行モードへの切り替えは{@code generation}を進めて走っている探索ごと捨て、着地時には表示中の
 * 経路を消して引き直すので、跳ねるたびに経路が丸ごと作り直されていた。<b>入りに
 * {@link #SUSTAIN_TICKS}の継続を要求する</b>ことで、跳ねただけの滑空判定は飛行モードに
 * 到達しなくなる。本物の滑空は0.5秒どころではないので取りこぼさない。
 *
 * <h4>高さ: 抜けるときの閾値を下げる</h4>
 *
 * <p>入りと抜けで同じ高さを見ると、境界の上を滑空している間ずっと飛行と歩行を往復する。
 */
final class ElytraTrigger {

    /**
     * 滑空判定がこれだけ続いて初めて飛行モードへ入る（tick）。0.5秒——跳ねたときに立つ滑空判定は
     * これよりずっと短い。
     */
    static final int SUSTAIN_TICKS = 10;

    private boolean gliding;
    private int fallFlyingTicks;

    boolean gliding() {
        return gliding;
    }

    /**
     * このtickの状態から飛行モードが有効かを更新して返す。
     *
     * @param fallFlying        エリトラで滑空中か（{@code Player#isFallFlying}）
     * @param groundClearance   足元から真下の地面までの高さ（ブロック）
     * @param requiredClearance 入るのに要る高さ。0以下なら高さを問わない
     */
    boolean update(boolean fallFlying, int groundClearance, int requiredClearance) {
        if (!fallFlying) {
            gliding = false;
            fallFlyingTicks = 0;
            return false;
        }
        fallFlyingTicks++;
        if (requiredClearance <= 0) {
            gliding = fallFlyingTicks >= SUSTAIN_TICKS;
            return gliding;
        }
        if (gliding) {
            gliding = groundClearance >= Math.max(1, requiredClearance / 2);
            return gliding;
        }
        gliding = fallFlyingTicks >= SUSTAIN_TICKS && groundClearance >= requiredClearance;
        return gliding;
    }

    void reset() {
        gliding = false;
        fallFlyingTicks = 0;
    }
}
