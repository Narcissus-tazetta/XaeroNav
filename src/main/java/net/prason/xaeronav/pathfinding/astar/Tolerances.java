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
 * @param allowRiskyJumps 底の無い空虚の上・外したら死ぬ落差の上の跳躍を許すか。既定では避け、
 *                        <b>経路が一本も引けなかったときだけ</b>緩める側が開ける——ユーザーの意図は
 *                        「回り込めるならそちらを通れ」であって「絶対に跳ぶな」ではない
 *                        （C字の島の両端を跳ぶより外周を歩く方が安全、島と島の間なら跳ぶしかない）。
 *                        {@code fallDamageToleranceEnabled}が詰み回避でも開かないのとは<b>意図的に違う</b>：
 *                        あちらは「痛い思いをしたくない」という好みで、断られた以上は代案が要らない。
 *                        こちらの代案は「経路が出ない」しかなく、跳ぶ区間には
 *                        {@code PathRisk.VOID_BELOW}で必ず警告色が付く
 * @param placedBlockBudget 経路全体で置いてよい足場の総数。0なら無制限。<b>{@link RunCaps}へ入れずに
 *                        ここへ置くのは、あちらが「何マス続けてよいか」＝連続長の器だから</b>——
 *                        累積の予算を{@code RUN_CAP_LOOSEN_MULTIPLIERS}の倍率で緩めても意味が無い
 *                        （持ち物の枚数は地形の都合で増えない）。緩めるなら外す一択なので、
 *                        梯子の最後の段でだけ0にする
 * @param placeWithoutBlocks 足場に使えるブロックを1つも持っていなくても設置の移動を作ってよいか。
 *                        <b>詰み回避の最後の手段</b>——ジ・エンドの島渡りのように橋以外に道が無い
 *                        地形では、持っていないというだけで経路が<b>原理的に</b>出なくなる。案内に
 *                        何も出ないので「島渡りだけできない」としか見えない。出せば「ここに橋が要る」
 *                        と分かり、掘って集めるなり引き返すなり判断できる。{@code maxSubmergedTicks}を
 *                        外して息の続かない潜水を見せるのと同じ扱いで、HUDが必要な枚数を伝える
 */
public record Tolerances(RunCaps caps, int maxFallDamagePoints, boolean allowRiskyJumps,
                          int placedBlockBudget, boolean placeWithoutBlocks) {

    public static Tolerances of(CellSource view) {
        return new Tolerances(RunCaps.of(view), view.maxFallDamagePoints(), !view.avoidRiskyJumps(),
                view.placedBlockBudget(), false);
    }
}
