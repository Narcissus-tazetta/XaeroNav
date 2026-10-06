package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.util.BlockDistance;
import org.jspecify.annotations.Nullable;

/**
 * 探索が「範囲が足りなかった」「予算が足りなかった」と分かったときに立てる、一度きりの再挑戦の予約。
 *
 * <p>他の再計算トリガー（逸脱・末端への到達・定期検証）と違って、これらはプレイヤーが動くことを前提に
 * していない。立てた側は次の一手を持っていないので、次tickで{@link #take}して投げ直さないと予約は永久に
 * 消化されない。目的地に着いた直後に次の目的地を指定して、その場に立ったまま結果を見るような使い方でも
 * 発動させるためのもの。
 *
 * <p>メインスレッド専用（結果は{@code GenerationGate}がメインスレッドへ戻してから渡す）。
 */
final class PendingEscalations {

    /**
     * 予約を「同じゴール」とみなす距離（ブロック）。
     *
     * <p>座標の完全一致で照合してはいけない。detail-targetは<b>プレイヤー位置からルート上へ
     * 補間し直す</b>ので、歩いている限り毎回1〜3ブロックずれる。一致を求めると、予約を立てた
     * 次のtickにはもう別の座標になっていて再挑戦が発動しない——実機ログでは予算切れが
     * 0.5〜0.7秒間隔で20〜30回続く間、粗い経由地チェーンが1回しか走っていなかった。
     *
     * <p>予約が意味しているのは「この辺りの地形では通常の探索が届かない」という<b>局所的な事実</b>
     * なので、点ではなくその近傍で照合する。幅はwaypointの間引き間隔に合わせてある。
     */
    private static final double TARGET_TOLERANCE_BLOCKS = 24.0;

    /**
     * 通常探索が予算切れした地点から、これだけ離れるまでは通常探索を省いて深い予算から始める
     * （ブロック）。{@link #plainSearchHopeless}参照。
     */
    private static final double PLAIN_RETRY_MOVE_BLOCKS = 32.0;

    /**
     * 通常マージンでは届かなかった探索ゴール。本来の目的地と長距離ルートの中間目標を区別しないのは、
     * どちらも「描画距離の内側にある詳細探索のゴール」で、迂回路が範囲の外に落ちる事情が同じだから。
     *
     * <p>booleanではなくゴールそのものを覚えるのは、同じ場所を指定し直したときに通常マージンから
     * やり直しにならないため（届かないから指定し直す、が一番ありがちな操作）。そのため{@link #reset}でも
     * 消さない——別のゴールには一致しないので勝手に無効化される。
     */
    private @Nullable BlockPos wideNeededTarget;
    private boolean widePending;

    /**
     * 深い予算でも展開ノード数の上限に当たって届かなかった探索ゴール。範囲を広げても同じ上限に当たるだけ
     * なので、代わりに粗い経由地チェーンで区間を分割する。
     */
    private @Nullable BlockPos coarseGuideNeededTarget;
    private boolean coarseGuidePending;

    private boolean deepPending;

    /** 直前の通常探索（粗い経由地チェーンではない側）が展開ノード数の上限に当たった地点。 */
    private @Nullable BlockPos plainBudgetExhaustedAt;

    /** 立っている予約を1つ取り出して消す。広げる→予算を積む→区間分割、の順。 */
    Escalation take() {
        if (widePending) {
            // 広い範囲での探索は目的地ごとに一度だけで、失敗しても二度目の予約は立たない
            widePending = false;
            return Escalation.WIDE;
        }
        if (deepPending) {
            deepPending = false;
            return Escalation.DEEP;
        }
        if (coarseGuidePending) {
            coarseGuidePending = false;
            return Escalation.COARSE_GUIDED;
        }
        return Escalation.NONE;
    }

    boolean coarseGuidePending() {
        return coarseGuidePending;
    }

    boolean any() {
        return widePending || deepPending || coarseGuidePending;
    }

    boolean wideReserved(BlockPos target) {
        return near(target, wideNeededTarget);
    }

    boolean coarseGuideReserved(BlockPos target) {
        return near(target, coarseGuideNeededTarget);
    }

    /**
     * この地点からの通常探索は予算切れが確定しているか。確定しているなら、通常探索を省いて
     * 最初から深い予算で解く。
     *
     * <p>実機（エンドの島渡り）では通常探索が<b>1回も成功せず</b>、毎周期「30万ノードを焼いて
     * 失敗する通常探索」を繰り返していた。捨てると分かっている探索に1〜1.5秒を払う間、案内は古いままで、
     * その間にプレイヤーは経路から離れていく。歩けば失効する——地形が変われば通常探索で解けるようになる。
     */
    boolean plainSearchHopeless(BlockPos start) {
        BlockPos exhausted = plainBudgetExhaustedAt;
        return exhausted != null
                && exhausted.distSqr(start) < PLAIN_RETRY_MOVE_BLOCKS * PLAIN_RETRY_MOVE_BLOCKS;
    }

    /**
     * 地上の詳細探索（中継区間ではない側）の結果から、次の予約を立て直す。直前の予約は探索が1つ終わった
     * 時点で用済みなので、ここでいったん全部下ろしてから必要なものだけ立てる。
     *
     * @param retryTargetInBox 探索ゴールが描画距離の内側か。どちらの再挑戦もそれが前提で（広げ先が
     *         描画距離、区間分割は読み込み済みチャンクからしか粗い地図を作れない）、前提が通らないのに
     *         予約すると、次tickで同じ探索をやり直しては同じ予約を立て直す無限ループになる
     */
    void noteResult(PathResult result, BlockPos start, BlockPos target, SearchAttempt attempt,
            boolean retryTargetInBox) {
        clearPending();
        // 未到達の理由が展開ノード数の上限なら、箱を広げても同じ上限に同じように当たるだけ（実機で確認済み:
        // 通常マージンと拡大後で展開ノード数が一致し、どちらも上限ちょうどで打ち切られていた）。
        //
        // 粗い経由地チェーンの回からはそれ以上エスカレーションしない。複数区間の合算は単一探索の上限と
        // 比較できないうえ、ここで再び予約すると同じチェーンを試みては同じ理由で失敗する無限往復になる
        boolean budgetExhausted = !attempt.coarseGuided() && result.budgetExhausted();
        if (!attempt.coarseGuided()) {
            // 粗い経由地チェーンの成否は通常探索の見込みについて何も言っていないので、そちらでは書き換えない
            plainBudgetExhaustedAt = budgetExhausted ? start : null;
        }
        boolean unreached = !result.complete() && !attempt.coarseGuided() && retryTargetInBox;
        boolean needsWide = unreached && !budgetExhausted;
        // 予算不足に対する最初の答えは「予算を積む」。深い予算を挟まず区間分割へ来ると、区間分割も
        // 同じ予算不足で失敗する
        boolean needsDeep = unreached && budgetExhausted && !attempt.deepBudget();
        // 航法グラフのガイドで探しているときは区間分割へ逃がさない。区間分割はそのガイドを受け取らないので、
        // ガイド付きの深い予算より悪い案内しか作れない——実機のネザーで9回中8回が未到達、1回10〜13秒だった
        boolean needsCoarseGuide = unreached && budgetExhausted && attempt.deepBudget() && !attempt.navGraphGuided();
        wideNeededTarget = needsWide ? target : null;
        widePending = needsWide && !attempt.wide();
        coarseGuideNeededTarget = needsCoarseGuide ? target : null;
        coarseGuidePending = needsCoarseGuide;
        deepPending = needsDeep;
    }

    void clearPending() {
        widePending = false;
        coarseGuidePending = false;
        deepPending = false;
    }

    /** 目的地を変えた・消したとき。ゴールの記憶（{@link #wideNeededTarget}）は残す。 */
    void reset() {
        clearPending();
        plainBudgetExhaustedAt = null;
    }

    private static boolean near(BlockPos target, @Nullable BlockPos reserved) {
        return reserved != null && BlockDistance.horizontal(target, reserved) <= TARGET_TOLERANCE_BLOCKS;
    }

    /**
     * 1回の詳細探索の作り方。
     *
     * @param deepBudget 深い予算まで試したか。並列フォールバックの回も、結果が届かなければ深い予算まで
     *         試し終えたのと同じ意味になる
     */
    record SearchAttempt(boolean wide, boolean coarseGuided, boolean deepBudget, boolean navGraphGuided) {
    }
}
