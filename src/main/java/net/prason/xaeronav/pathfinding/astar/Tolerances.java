package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 詰んだときに段階的に緩める「危険の許容量」一式。{@link RunCaps}（何マス／何tick続けてよいか）と
 * 落下ダメージの許容点数をまとめたもの。
 *
 * <p>1つの器にまとめてあるのは、緩める側（{@code PathfindingExecutor}）が段階を1本の梯子として
 * 持つため。片方だけ緩めても、もう片方で詰んでいれば同じ探索をもう一度払うだけになる。
 *
 * <p><b>{@link RunCaps}へ{@code maxFallDamagePoints}を直接足さないこと。</b>あちらは
 * <b>0が無制限</b>を表すのに対し、落下ダメージの0は「一切許さない」で意味が正反対になる
 * （{@link RunCaps#NONE}が落下ダメージだけ最も厳しい側へ倒れる）。
 *
 * @param maxFallDamagePoints 落下ダメージを何点(0.5ハート単位)まで許容してよいか。0なら安全高さを
 *                            超える落下を一切提示しない。<b>無制限は表現しない</b>——上限を外すと
 *                            即死する落下が案内に出るので、緩める側が体力から上限を決める
 */
public record Tolerances(RunCaps caps, int maxFallDamagePoints) {

    public static Tolerances of(CellSource view) {
        return new Tolerances(RunCaps.of(view), view.maxFallDamagePoints());
    }
}
