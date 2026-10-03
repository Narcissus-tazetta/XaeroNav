package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

/**
 * @param steps         始点を含まない、ゴールまで（または打ち切り時点でゴールに最も近づけた地点まで）の経路
 * @param termination   探索が終わった理由。{@link #complete()}だけでは「資源を使い切った」と
 *                      「範囲内に道が無い」を区別できず、前者にしか意味の無い再挑戦（範囲拡大・
 *                      粗い経由地チェーン）を後者にも仕掛けてしまう
 * @param distinctNodes 探索が触れた異なるセルの数。{@code expandedNodes}がこれを大きく上回るときは、
 *                      同じセルを何度も展開し直している（重み付きヒューリスティックで確定済みノードが
 *                      openへ戻る）。両者を並べないと、この空回りと純粋な探索範囲の広さを区別できない
 * @param limitsHeld    設定の上限（橋の長さ・潜水・落下ダメージ・危険な跳躍・持ち物の数）が手を捨て、
 *                      {@code strictLimits}のため緩めずに終えたか。届かなかった理由を「道が無い」と
 *                      「上限の内側に道が無い」とで言い分けるのに使う
 */
public record PathResult(List<PathStep> steps, Termination termination, int expandedNodes, int distinctNodes,
                         boolean limitsHeld) {

    public PathResult {
        steps = List.copyOf(steps);
    }

    public PathResult(List<PathStep> steps, Termination termination, int expandedNodes, int distinctNodes) {
        this(steps, termination, expandedNodes, distinctNodes, false);
    }

    public PathResult withLimitsHeld() {
        return new PathResult(steps, termination, expandedNodes, distinctNodes, true);
    }

    /** 探索の打ち切り理由。 */
    public enum Termination {
        /** ゴールに到達した。 */
        REACHED_GOAL,
        /** 展開ノード数の上限に当たった。 */
        NODE_BUDGET,
        /** 時間上限に当たった。 */
        TIME_LIMIT,
        /** 新しい探索に追い出された。 */
        CANCELLED,
        /**
         * オープンセットが尽きた＝探索範囲の中に到達手段が無い。予算を増やしても範囲を広げても
         * 同じ結果になるので、これは本物の「詰み」であって再挑戦の対象ではない。
         */
        EXHAUSTED
    }

    public boolean complete() {
        return termination == Termination.REACHED_GOAL;
    }

    /**
     * 探索資源（ノード数・時間）を使い切って打ち切ったか。範囲を広げる・区間に割るといった再挑戦が
     * 意味を持つのはこのときだけ。
     */
    public boolean budgetExhausted() {
        return termination == Termination.NODE_BUDGET || termination == Termination.TIME_LIMIT;
    }
}
