package net.prason.xaeronav.client;

/**
 * 潜水中の追尾ナビ（{@link SwimNavState}）に入る・抜けるの判定。境界での往復を防ぐヒステリシスだけを
 * 受け持ち、{@code Minecraft}に触れないので単体で検証できる。
 *
 * <p>入りは「目が水中」かつ{@link #ENTER_DEPTH_BLOCKS}以上潜っていること。抜けは2通り——水から
 * 完全に出たら即座に、浅くなった（頭が水面から出た、または水面から{@link #STAY_DEPTH_BLOCKS}より
 * 浅い）状態が{@link #HEAD_OUT_GRACE_TICKS}続いたら。後者の猶予が無いと、水面下を泳いでいる間に
 * 頭が1tickだけ出るたびにモードが往復し、そのたびに経路が作り直される
 * （{@code PathfindingState#landingApproach}と同じ形）。
 *
 * <p><b>入りと抜けで深さの閾値を分けるのが要点。</b>1本の閾値で切っていた頃は、泳ぎの上下動が
 * そのまま境界の往復になり、目も体も水中のまま1〜4秒おきに入り直して経路を引き直していた
 * （実機ログで確認）。深さの幅と時間の猶予は<b>両方要る</b>——幅だけでは境界に張り付いたときに
 * 往復し、猶予だけでは1本の閾値を跨ぐ振動を止められない。
 */
final class SwimTrigger {

    /**
     * 頭が水面から出たまま（体はまだ水中）これだけ続いたら追尾ナビを抜ける（tick）。
     * 波や視点の揺れで一瞬だけ目が出るのは無視したいが、意図して浮上したなら歩行ナビへ戻したい。
     */
    static final int HEAD_OUT_GRACE_TICKS = 10;

    /**
     * 追尾ナビへ入るのに要る深さ（視点から水面まで、ブロック）。
     *
     * <p>水面のすぐ下では追尾線が視界に張り付くだけで、水面も目的地の方角も自分の目で見えている
     * ——ユーザー報告「見えてるのに追尾の線が邪魔になる」。
     */
    static final double ENTER_DEPTH_BLOCKS = 2.0;

    /** 入った後、これより浅くなったら抜ける側へ数え始める（ブロック）。入りとの差がそのまま幅になる。 */
    static final double STAY_DEPTH_BLOCKS = 1.0;

    private boolean active;
    private int headOutTicks;

    boolean active() {
        return active;
    }

    /**
     * このtickの状態から追尾ナビが有効かを更新して返す。
     *
     * @param enabled      設定で水中ナビが有効か。falseなら常に抜ける
     * @param isUnderWater 目（視点）が水中にあるか（{@code Player#isUnderWater}）
     * @param isInWater    体が水に触れているか（{@code Player#isInWater}）
     * @param depth        視点から水面までの深さ（ブロック）。深い側は頭打ちでよい
     */
    boolean update(boolean enabled, boolean isUnderWater, boolean isInWater, double depth) {
        if (!enabled) {
            active = false;
            headOutTicks = 0;
            return false;
        }
        boolean deep = isUnderWater && depth >= (active ? STAY_DEPTH_BLOCKS : ENTER_DEPTH_BLOCKS);
        if (!active) {
            active = deep;
            headOutTicks = 0;
            return active;
        }
        if (!isInWater) {
            active = false;
            headOutTicks = 0;
            return false;
        }
        if (deep) {
            headOutTicks = 0;
            return true;
        }
        headOutTicks++;
        if (headOutTicks >= HEAD_OUT_GRACE_TICKS) {
            active = false;
            headOutTicks = 0;
            return false;
        }
        return true;
    }

    void reset() {
        active = false;
        headOutTicks = 0;
    }
}
