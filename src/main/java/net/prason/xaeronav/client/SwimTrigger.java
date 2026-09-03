package net.prason.xaeronav.client;

/**
 * 潜水中の追尾ナビ（{@link SwimNavState}）に入る・抜けるの判定。境界での往復を防ぐヒステリシスだけを
 * 受け持ち、{@code Minecraft}に触れないので単体で検証できる。
 *
 * <p>入りは「目が水中」かつ「{@link #MIN_DEPTH_BLOCKS}以上潜っている」。抜けは2通り——水から
 * 完全に出たら即座に、浅くなった（頭が水面から出た、または水面すれすれ）状態が
 * {@link #HEAD_OUT_GRACE_TICKS}続いたら。後者の猶予が無いと、水面下を泳いでいる間に頭が1tickだけ
 * 出るたびにモードが往復し、そのたびに経路が作り直される
 * （{@code PathfindingState#landingApproach}と同じ形）。
 */
final class SwimTrigger {

    /**
     * 頭が水面から出たまま（体はまだ水中）これだけ続いたら追尾ナビを抜ける（tick）。
     * 波や視点の揺れで一瞬だけ目が出るのは無視したいが、意図して浮上したなら歩行ナビへ戻したい。
     */
    static final int HEAD_OUT_GRACE_TICKS = 10;

    /**
     * 追尾ナビへ入るのに要る水深（足元から上に続く水のブロック数）。
     *
     * <p>水面のすぐ下では追尾線が視界に張り付くだけで、水面も目的地の方角も自分の目で見えている
     * ——ユーザー報告「見えてるのに追尾の線が邪魔になる」。3を境にすると、水面から1〜2マスの
     * 浅いところは従来の歩行ナビの線のままになる。
     */
    static final int MIN_DEPTH_BLOCKS = 3;

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
     * @param depthBlocks  足元から上に続く水のブロック数（{@link #MIN_DEPTH_BLOCKS}で頭打ちでよい）
     */
    boolean update(boolean enabled, boolean isUnderWater, boolean isInWater, int depthBlocks) {
        if (!enabled) {
            active = false;
            headOutTicks = 0;
            return false;
        }
        boolean deep = isUnderWater && depthBlocks >= MIN_DEPTH_BLOCKS;
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
