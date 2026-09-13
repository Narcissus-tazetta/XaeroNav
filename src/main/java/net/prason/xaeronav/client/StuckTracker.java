package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.pathfinding.astar.PathResult;

/**
 * 「目的地へ行けない」の判定。{@code PathfindingState}から詰み判定だけを切り出したもの
 * （ARCH-01の最初の抽出——最も独立していて他の状態遷移と絡みが薄い部分から始める）。
 *
 * <p>詰みは「<b>狙った先へ届きもせず、目的地へ近づきもしなかった</b>探索」が
 * {@link #SEARCH_STREAK}回続いたこと、と定義する。
 *
 * <p>経路が引けたかどうかでは判定できない。予算切れの探索は行き止まりへ向かう部分経路を毎回
 * 返すので、実機ログではステップ数55→23→5→18→93→0…が5分間続く間ずっと同じ溶岩の海の縁に
 * 居た。逆に「近づいたか」だけで見ると、溶岩の海を大きく迂回する区間（目的地から遠ざかりながら
 * 正しく進んでいる）を詰みと誤判定する——そこでは探索は狙った中間目標へ<b>届いている</b>ので、
 * 2つを併せて初めて正しく切り分けられる。
 *
 * <p>近さの測り方にプレイヤー自身の位置も入れる。部分経路を辿って歩いて前進するのも正常な
 * 進み方なので、その間に投げた探索が何回失敗していようと詰みではない。
 *
 * <p><b>連続として数えるのは、ほぼ同じ場所から投げた探索だけ</b>（{@link #RETRY_MOVE_BLOCKS}）。
 * 詰みの根拠は「同じ実験を繰り返しても結果が変わらない」ことなので、始点が動いていれば
 * 別の実験——読み込み済みチャンクも層1の地図も変わり、実際に結果が変わりうる。実機
 * （ジ・エンドの崖ぎわ、06:36）では、プレイヤーが崖に沿って26ブロック行き来する間の失敗が
 * 連続として数えられ「行けません」が出たが、その16秒後に橋49本で渡り切っている。
 */
final class StuckTracker {

    private static final int SEARCH_STREAK = 4;
    private static final double PROGRESS_BLOCKS = 8.0;
    private static final double RETRY_MOVE_BLOCKS = 16.0;

    private volatile double bestApproachBlocks = Double.MAX_VALUE;
    /** {@link #bestApproachBlocks}を縮められないまま終わった探索の連続回数。 */
    private volatile int stalledSearches;
    /** 直近で「前進しなかった」と数えた探索の始点。 */
    private volatile BlockPos lastStalledAt;
    private volatile PathfindingState.StuckReason reason;
    private volatile PathfindingState.StuckReason pendingNotice;

    /** 目的地ごとの全リセット（{@code PathfindingState#clear()}用）。 */
    void reset() {
        bestApproachBlocks = Double.MAX_VALUE;
        stalledSearches = 0;
        lastStalledAt = null;
        reason = null;
        pendingNotice = null;
    }

    /**
     * 詰みの判定だけを取り下げる（到着時用）。連続カウントや最接近距離までは戻さない
     * ——到着表示が終わるまでは同じ目的地が続く可能性があり、そこは{@link #reset()}の仕事にする。
     */
    void clearReason() {
        reason = null;
        pendingNotice = null;
    }

    /** 詰みと判断済みならその理由、まだなら{@code null}。 */
    PathfindingState.StuckReason reason() {
        return reason;
    }

    /** 詰まりかけている（連続失敗が1回以上ある）か。まだ確定はしていない。 */
    boolean stranded() {
        return stalledSearches > 0;
    }

    /** チャットへまだ知らせていない詰み通知があれば取り出して消費する。無ければ{@code null}。 */
    PathfindingState.StuckReason takePendingNotice() {
        PathfindingState.StuckReason notice = pendingNotice;
        pendingNotice = null;
        return notice;
    }

    /** 詰みと判断したあとで、もう一度探索を投げてよい頃合いか。 */
    boolean retryDue(BlockPos lastStart, BlockPos playerPos, boolean recalcIntervalElapsed) {
        return lastStart == null
                || lastStart.distSqr(playerPos) >= RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS
                || recalcIntervalElapsed;
    }

    /**
     * この探索の結果を詰みの判定へ反映する。
     *
     * @param hasCompleteGroundRoute 完走した地上経路が今も表示中か（中継区間{@code TO_SURFACE}は
     *         含めない）。trueなら詰みではないとみなし、状態を戻す——実機（22:42）では、110ステップ・
     *         橋47本の経路を表示したまま「目的地へ行けません」が出ていた
     * @param routeUnmapped 層1（Xaeroの地図、橋を架ける前提の梯子の最終段）が今の目的地まで
     *         届いていないか。詰みの理由を確度の高い順に決めるのに使う——ここが最も情報量が多く、
     *         それでも届かないなら詳細探索をいくら回しても届かない
     */
    void noteOutcome(BlockPos start, BlockPos planEnd, BlockPos currentGoal, boolean hasCompleteGroundRoute,
                      PathResult result, boolean routeUnmapped, NetherVoxelGuide voxelGuide) {
        if (hasCompleteGroundRoute) {
            stalledSearches = 0;
            reason = null;
            return;
        }
        double approach = Math.min(horizontalDistance(start, currentGoal), horizontalDistance(planEnd, currentGoal));
        // 高水位がPROGRESS_BLOCKSを切ったら、そこから更にその幅ぶん近づいた探索は
        // 原理的に出せない（距離は0未満にならない）。一度でも目的地のそばまで届いた目的地では
        // 以後どんな探索も前進と認められず、未到達がSEARCH_STREAK回続くだけで「行けません」になる
        // ——改善しえない値を歯止めに使うと永久に外れない
        boolean improvable = bestApproachBlocks >= PROGRESS_BLOCKS;
        boolean progressed = result.complete() || !improvable || approach <= bestApproachBlocks - PROGRESS_BLOCKS;
        bestApproachBlocks = Math.min(bestApproachBlocks, approach);
        if (progressed) {
            stalledSearches = 0;
            reason = null;
            return;
        }
        // 薄い地図で組んだ3D粗層が、詰まったまま更新されずに残るのを防ぐ。ここを通るのは
        // 「狙った先へ届きも目的地へ近づきもしなかった」探索だけなので、組み直しの引き金として
        // ちょうどよい（実際に組み直すかはNetherVoxelGuide側が間引く）
        voxelGuide.noteStalled();
        BlockPos previouslyStalledAt = lastStalledAt;
        boolean sameSpot = previouslyStalledAt != null
                && previouslyStalledAt.distSqr(start) < RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS;
        stalledSearches = sameSpot ? stalledSearches + 1 : 1;
        lastStalledAt = start;
        if (stalledSearches < SEARCH_STREAK || reason != null) {
            return;
        }
        reason = classify(result.termination(), routeUnmapped);
        pendingNotice = reason;
        XaeroNav.LOGGER.info("XaeroNav: 目的地へ行けないと判断しました (理由={}, 最接近={}ブロック, 目的地={})",
                reason, Math.round(bestApproachBlocks), currentGoal.toShortString());
    }

    /**
     * 詰みの理由を、確度の高い順に見て決める。次に確かなのが{@code EXHAUSTED}（探索範囲の中に
     * 到達手段が無いことの証明）で、残りは資源不足。
     */
    private static PathfindingState.StuckReason classify(PathResult.Termination termination, boolean routeUnmapped) {
        if (routeUnmapped) {
            return PathfindingState.StuckReason.UNMAPPED;
        }
        return termination == PathResult.Termination.EXHAUSTED
                ? PathfindingState.StuckReason.NO_WAY_THROUGH
                : PathfindingState.StuckReason.SEARCH_TOO_HARD;
    }

    /**
     * {@code PathfindingState#horizontalDistance}と同じ式を独立に持つ。詰み判定はyを見ない
     * （地図上の距離だけで「近づいたか」を測る）という意味的な決定を、この式自体に閉じ込めるため。
     */
    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
