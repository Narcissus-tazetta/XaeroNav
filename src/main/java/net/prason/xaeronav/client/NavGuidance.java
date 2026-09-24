package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.util.MathSupport;

/**
 * 提示中の経路から「残りの道のり」「所要時間」「経路の終点が近いか」を求める。所要時間には、経路の終点から目的地までの
 * 見積もり（{@link GoalEta}）を足せる。
 *
 * <p>各地点までの累積は経路だけで決まるので、経路ごとに1度だけ組み立てて使い回す
 * （{@link Route}）。プレイヤーが進むたびに変わるのは「いま経路のどこにいるか」（{@link PathProgress}）
 * だけで、そこから先の問い合わせは累積の引き算で済む。
 */
final class NavGuidance {

    /** これ以下まで近づいたら経路末端の案内を出す。 */
    private static final int ARRIVAL_BLOCKS = 3;

    /**
     * 実測の速さで所要時間を割り直すときの倍率の範囲。止まる直前の遅さや、乗り物での一瞬の速さを
     * そのまま掛けると桁が変わってしまう。
     */
    private static final double PACE_FACTOR_MIN = 0.5;
    private static final double PACE_FACTOR_MAX = 4.0;

    /**
     * 表示する秒数の刻み。実測の速さは常に揺れているので、1秒刻みで出すと数字が落ち着かず、
     * かえって信用できない表示になる。
     */
    private static final int SECONDS_GRANULARITY = 5;
    /**
     * 見積もりを含む秒数の刻み（1分以上・10分以上）。見積もりは窓が進むたびに数十秒単位で動くので、
     * 5秒刻みでは精度があるように見えてしまう。
     */
    private static final int ESTIMATE_GRANULARITY_MINUTE = 10;
    private static final int ESTIMATE_GRANULARITY_LONG = 30;

    private static final PathCache<Route> ROUTES = new PathCache<>();

    final int remainingBlocks;
    final int remainingSeconds;
    final boolean nearEnd;
    final boolean complete;

    private NavGuidance(int remainingBlocks, int remainingSeconds, boolean nearEnd, boolean complete) {
        this.remainingBlocks = remainingBlocks;
        this.remainingSeconds = remainingSeconds;
        this.nearEnd = nearEnd;
        this.complete = complete;
    }

    /**
     * @param beyondTicks 経路の終点から目的地までの見積もり（tick）。経路が目的地に届いているなら0
     */
    static NavGuidance forPath(PathResult result, BlockPos playerPos, double beyondTicks) {
        return ROUTES.get(result, Route::new).guidanceAt(playerPos, beyondTicks);
    }

    /** 経路がまだ無いときの所要時間（秒）。{@code ticks}は{@link GoalEta}の見積もり。 */
    static int estimateSeconds(double ticks) {
        return roundSeconds(ticks * paceFactor(1.0 / ActionCosts.SPRINT_ONE_BLOCK) / 20.0, true);
    }

    private static NavGuidance build(Route route, double beyondTicks) {
        int from = PathProgress.INSTANCE.indexFor(route.source);
        int last = route.source.steps().size() - 1;

        double blocks = route.blocks[last] - route.blocks[from];
        double seconds = route.remainingTicks(from, beyondTicks) / 20.0;
        // まだ道のりが残っているのに「約0秒」と出さない
        int rounded = Math.max(blocks > 0.0 ? SECONDS_GRANULARITY : 0, roundSeconds(seconds, beyondTicks > 0.0));
        return new NavGuidance((int) Math.round(blocks), rounded,
                blocks <= ARRIVAL_BLOCKS, route.source.complete());
    }

    private static int roundSeconds(double seconds, boolean estimated) {
        int granularity = !estimated || seconds < 60.0 ? SECONDS_GRANULARITY
                : seconds < 600.0 ? ESTIMATE_GRANULARITY_MINUTE : ESTIMATE_GRANULARITY_LONG;
        return (int) Math.round(seconds / granularity) * granularity;
    }

    /**
     * 所要時間を実測の速さで割り直す倍率。
     *
     * @param assumed 見積もりが想定している速さ（ブロック/tick）
     */
    private static double paceFactor(double assumed) {
        double actual = NavPace.INSTANCE.blocksPerTick();
        return actual <= 0.0 ? 1.0 : MathSupport.clamp(assumed / actual, PACE_FACTOR_MIN, PACE_FACTOR_MAX);
    }

    /**
     * 経路ごとの下ごしらえ。各ステップまでの累積（道のり・移動コスト・作業コスト）を持つ。
     */
    private static final class Route {

        private final PathResult source;
        /** 各ステップまでの道のり。 */
        private final double[] blocks;
        /** 移動そのもののコストと、掘る・置く・開ける側のコスト。所要時間の補正で扱いを分ける。 */
        private final double[] movementTicks;
        private final double[] movementBlocks;
        private final double[] actionTicks;

        // プレイヤーが1マス動くまで案内は変わらない。HUDは毎フレーム描かれるので、
        // 同じマスにいる間の問い合わせは作り直さない
        private BlockPos cachedPos;
        private double cachedBeyondTicks;
        private NavGuidance cached;

        private Route(PathResult source) {
            this.source = source;
            List<PathStep> steps = source.steps();
            int size = steps.size();
            this.blocks = new double[size];
            this.movementTicks = new double[size];
            this.movementBlocks = new double[size];
            this.actionTicks = new double[size];

            for (int i = 1; i < size; i++) {
                PathStep step = steps.get(i);
                double distance = Math.sqrt(steps.get(i - 1).pos().distSqr(step.pos()));
                // 掘る・置く・ボートを出す区間のコストは歩く速さとは無関係なので、
                // 実測での割り直しから外す
                boolean action = step.digging() || step.bridging() || step.boating();
                blocks[i] = blocks[i - 1] + distance;
                movementBlocks[i] = movementBlocks[i - 1] + (action ? 0.0 : distance);
                movementTicks[i] = movementTicks[i - 1] + (action ? 0.0 : step.cost());
                actionTicks[i] = actionTicks[i - 1] + (action ? step.cost() : 0.0);
            }
        }

        private NavGuidance guidanceAt(BlockPos playerPos, double beyondTicks) {
            if (cached == null || !playerPos.equals(cachedPos) || beyondTicks != cachedBeyondTicks) {
                cached = build(this, beyondTicks);
                cachedPos = playerPos;
                cachedBeyondTicks = beyondTicks;
            }
            return cached;
        }

        /**
         * {@code from}から先の所要時間（tick）。移動の分と経路の先の見積もりを、プレイヤーの実測の速さで割り直す。
         *
         * <p>基準にする速さは経路が想定している速さ（移動区間の平均）にする。スプリント固定で比べると、
         * 泳ぎや水中歩行のように元々遅い経路で二重に遅く見積もることになる。経路の先の見積もりも同じ移動コストで
         * 積んだ値なので、同じ倍率で割り直す。
         */
        private double remainingTicks(int from, double beyondTicks) {
            int last = source.steps().size() - 1;
            double movement = movementTicks[last] - movementTicks[from];
            double action = actionTicks[last] - actionTicks[from];
            double moved = movementBlocks[last] - movementBlocks[from];
            double assumed = movement > 0.0 && moved > 0.0 ? moved / movement : 1.0 / ActionCosts.SPRINT_ONE_BLOCK;
            return (movement + beyondTicks) * paceFactor(assumed) + action;
        }
    }
}
