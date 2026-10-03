package net.prason.xaeronav.pathfinding.flight;

import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.util.MonotonicTime;

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
    static final double GOAL_RADIUS_CELLS = 1.5;

    /**
     * 目的地が閉じた小部屋の中にあるときに、目的地とみなす水平半径（ブロック）。先は歩行が空から
     * 引き継ぐ距離（{@code PathfindingState.LANDING_APPROACH_ENTER_BLOCKS}）、後は歩行が1回の探索で
     * 解ける距離（{@code detailHorizonBlocks}の既定）。
     */
    private static final double[] ENCLOSED_APPROACH_RADII = {48.0, 96.0};

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
                                     FlightTuning tuning, BooleanSupplier cancelled) {
        return route(view, start, goal, rockets, tuning, FlightHorizon.NONE, FlightGuide.NONE, cancelled);
    }

    /**
     * 粗い地図の残りコストの場で出口を選ぶ版。出口の見積もりは読める範囲の内側を通らない回り道で測り直す
     * （{@link HorizonGuide}参照）。{@code field}が{@code null}なら直線の見積もりだけで選ぶ。
     */
    public static FlightRoute route(CellSource view, Vec3 start, Vec3 goal, boolean rockets,
                                     FlightTuning tuning, FlightHorizon horizon, @Nullable CoarseFlightField field,
                                     BooleanSupplier cancelled) {
        if (field == null) {
            return route(view, start, goal, rockets, tuning, horizon, FlightGuide.NONE, cancelled);
        }
        // 塗り広げで判定したセルは、続く探索がほぼ同じ所を触るので、同じ格子を渡してmemoを使い回す
        AirGrid grid = new AirGrid(view, tuning.cellBlocks());
        HorizonGuide.Plan plan = HorizonGuide.plan(grid, start, goal, horizon, field, rockets);
        if (plan.enclosed()) {
            return approach(grid, start, goal, rockets, tuning, plan.guide(), cancelled);
        }
        return route(view, grid, start, goal, rockets, tuning, plan.horizon(), plan.guide(), cancelled);
    }

    /**
     * 目的地が閉じた小部屋の中にあるとき（{@link HorizonGuide}）、空から寄れる所までの経路。目的地そのものは
     * 狙わず、{@link #ENCLOSED_APPROACH_RADII}の近い方から順に、その半径に入る空中のセルを目的地とみなす。
     *
     * <p>目的地を狙ったまま最も寄れた所で打ち切る形だと、限られた予算で塗った範囲の中で選ぶので、
     * 小部屋の反対側から回れば寄れる場合でも手前の壁の前で止まる。半径の中を目的地にすれば、届く所が
     * あればA*がそこまで引き切る。
     */
    private static FlightRoute approach(AirGrid grid, Vec3 start, Vec3 goal, boolean rockets, FlightTuning tuning,
                                        FlightGuide guide, BooleanSupplier cancelled) {
        SearchLimits limits = new SearchLimits(
                Math.min(tuning.limits().maxExpandedNodes(), HorizonGuide.ENCLOSED_MAX_EXPANDED_NODES),
                tuning.limits().timeLimitMillis(), tuning.limits().heuristicWeight());
        FlightRoute best = FlightRoute.NONE;
        for (double radius : ENCLOSED_APPROACH_RADII) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            FlightRoute route = new FlightPathfinder(grid, rockets, limits, tuning.clearancePenaltyTicks())
                    .search(start, goal, radius, FlightHorizon.NONE, guide, cancelled);
            if (route.complete()) {
                return route;
            }
            if (best.isEmpty()) {
                best = route;
            }
        }
        return best;
    }

    /** {@code horizon}の外へ出たところで打ち切ってよい版（{@link FlightHorizon}参照）。 */
    public static FlightRoute route(CellSource view, Vec3 start, Vec3 goal, boolean rockets,
                                     FlightTuning tuning, FlightHorizon horizon, FlightGuide guide,
                                     BooleanSupplier cancelled) {
        return route(view, null, start, goal, rockets, tuning, horizon, guide, cancelled);
    }

    /** {@code firstGrid}は最初の粒度で使う格子（{@code null}なら作る）。 */
    private static FlightRoute route(CellSource view, @Nullable AirGrid firstGrid, Vec3 start, Vec3 goal,
                                     boolean rockets, FlightTuning tuning, FlightHorizon horizon, FlightGuide guide,
                                     BooleanSupplier cancelled) {
        FlightRoute best = FlightRoute.NONE;
        long deadline = MonotonicTime.millis() + tuning.limits().timeLimitMillis();
        for (int cells = tuning.cellBlocks(); cells >= MIN_CELL_BLOCKS; cells /= 2) {
            if (cancelled.getAsBoolean()) {
                return best;
            }
            long remaining = deadline - MonotonicTime.millis();
            if (best != FlightRoute.NONE && remaining < MIN_RETRY_BUDGET_MILLIS) {
                // 既に何か出せていて時間も無い。ここで粘るより今ある線を返す
                break;
            }
            SearchLimits limits = new SearchLimits(tuning.limits().maxExpandedNodes(),
                    Math.max(MIN_RETRY_BUDGET_MILLIS, remaining), tuning.limits().heuristicWeight());
            AirGrid grid = firstGrid != null && firstGrid.cellBlocks() == cells ? firstGrid : new AirGrid(view, cells);
            FlightRoute route = new FlightPathfinder(grid, rockets, limits,
                    tuning.clearancePenaltyTicks()).search(start, goal, cells * GOAL_RADIUS_CELLS, horizon,
                    guide, cancelled);
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
