package net.prason.xaeronav.pathfinding.flight;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 空中経路を求める入口。粒度を落としながら数回試す段取りだけを持つ。
 *
 * <p>{@code CoarseRouter.BridgePolicy}と同じ形のエスカレーション。既定の粒度で解けなかったのは
 * たいてい「その粗さでは抜けられない隙間しか無い」ケースなので、半分の粒度で一度だけ解き直す。
 * 細かくすると1セルあたりの余白は減るが、通れない経路を出すよりは狭い経路を出す方がまし——
 * 案内が消えるのが一番困る、という既存の優先順に合わせてある。
 */
public final class FlightRouter {

    /**
     * ゴール領域の半径をセル幅の何倍にするか。目的地はたいてい着地する地面そのもの＝飛行不可なので、
     * 「あとは自力で降りられる所まで寄れたか」で判定する。
     */
    private static final double GOAL_RADIUS_CELLS = 1.5;

    /** 粒度を落とす下限（ブロック）。これより細かくしても格子の意味（クリアランス）が無くなる。 */
    private static final int MIN_CELL_BLOCKS = 2;

    /**
     * 2回目の挑戦に踏み切るために残っていてほしい時間（ミリ秒）。
     *
     * <p>段階ごとに期限を取り直すと、呼び出し1回の総時間が段数ぶん膨らむ。飛んでいる相手への案内
     * なので、<b>全体で1回ぶんの時間に収める</b>方が正しい——遅れて出てくる完璧な線より、
     * 今出てくる粗い線の方が役に立つ。
     */
    private static final long MIN_RETRY_BUDGET_MILLIS = 500L;

    private FlightRouter() {
    }

    /**
     * {@code start}から{@code goal}への空中経路。引けなければ{@link FlightRoute#NONE}を返す
     * （呼び出し側は従来どおり目的地への点線へ落とすこと）。
     */
    public static FlightRoute route(CellSource view, Vec3 start, Vec3 goal, boolean rockets,
                                     FlightTuning tuning) {
        FlightRoute best = FlightRoute.NONE;
        long deadline = System.currentTimeMillis() + tuning.limits().timeLimitMillis();
        for (int cells = tuning.cellBlocks(); cells >= MIN_CELL_BLOCKS; cells /= 2) {
            long remaining = deadline - System.currentTimeMillis();
            if (best != FlightRoute.NONE && remaining < MIN_RETRY_BUDGET_MILLIS) {
                // 既に何か出せていて時間も無い。ここで粘るより今ある線を返す
                break;
            }
            SearchLimits limits = new SearchLimits(tuning.limits().maxExpandedNodes(),
                    Math.max(MIN_RETRY_BUDGET_MILLIS, remaining), tuning.limits().heuristicWeight());
            FlightRoute route = new FlightPathfinder(new AirGrid(view, cells), rockets, limits,
                    tuning.clearancePenaltyTicks()).search(start, goal, cells * GOAL_RADIUS_CELLS);
            if (route.complete()) {
                return route;
            }
            if (best.isEmpty() && !route.isEmpty()) {
                // 届かなかった部分経路も案内には使える。粗い側で出た（＝余白の広い）方を残す
                best = route;
            }
            if (route.budgetExhausted()) {
                // 予算を焼き切ったのなら、細かい格子で解き直しても<b>同じ上限に、より早く</b>当たる
                // だけ——同じ体積のセル数が8倍になるので、届く距離はむしろ縮む。細かくして意味が
                // あるのは「その粗さでは抜けられる隙間が無い」と証明された（EXHAUSTED）ときだけ。
                // 実機ログ: ネザーで4ブロック格子が10万ノードを2.1秒焼いた後、2ブロック格子でも
                // 同じだけ焼いて1回の引き直しに4秒かかっていた
                break;
            }
        }
        return best;
    }
}
