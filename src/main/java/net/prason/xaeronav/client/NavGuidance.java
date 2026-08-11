package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 提示中の経路から「残りの道のり」「所要時間」「次にどちらへ曲がるか」を求める（カーナビの案内相当）。
 *
 * <p>曲がり角の位置と各地点までの累積は経路だけで決まるので、経路ごとに1度だけ組み立てて使い回す
 * （{@link Route}）。プレイヤーが進むたびに変わるのは「いま経路のどこにいるか」（{@link PathProgress}）
 * だけで、そこから先の問い合わせは累積の引き算で済む。
 */
final class NavGuidance {

    /**
     * 直線とみなす帯の半幅（ブロック）。斜めに進む区間は東→北東→東…と1マスごとに向きが振れるので、
     * 1ステップずつ向きの変化を見ると「右・左・右・左」と案内が暴れる。多少のジグザグを許す帯の中に
     * 収まっている限り同じ直線（レグ）として扱えば、斜めの直進はまとめて1本になる。
     */
    private static final double LEG_CORRIDOR_BLOCKS = 1.5;

    /** レグの向きを決めるのに使う先読みステップ数。1ステップだけでは斜めのジグザグを拾ってしまう。 */
    private static final int LEG_DIRECTION_SPAN = 4;

    /**
     * レグの進行方向へ最も進んだ地点から、これ以上引き返したらレグの終わり（ブロック）。
     * 折り返しは帯の幅だけでは捕まえられない — 1ブロック横にずれて戻ってくる経路は、
     * 帯の中に収まったまま逆走するので、進んだ距離が減っていないかも見る必要がある。
     */
    private static final double BACKTRACK_BLOCKS = 1.0;

    /** 曲がったと判定する角度の下限（この余弦より内積が小さいと「曲がった」）。35度。 */
    private static final double TURN_COS_THRESHOLD = 0.819;

    /** これ以下まで近づいたら曲がり角の案内をやめて「まもなく到着」にする。 */
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

    enum Turn {
        STRAIGHT,
        LEFT,
        RIGHT,
        ARRIVE
    }

    private static Route route;
    private static NavGuidance cached;
    private static BlockPos cachedPos;

    final int remainingBlocks;
    final int remainingSeconds;
    final int turnDistance;
    final Turn turn;
    final boolean complete;

    private NavGuidance(int remainingBlocks, int remainingSeconds, int turnDistance, Turn turn, boolean complete) {
        this.remainingBlocks = remainingBlocks;
        this.remainingSeconds = remainingSeconds;
        this.turnDistance = turnDistance;
        this.turn = turn;
        this.complete = complete;
    }

    static NavGuidance forPath(PathResult result, BlockPos playerPos) {
        if (route == null || route.source != result) {
            route = new Route(result);
            cached = null;
        }
        if (cached != null && playerPos.equals(cachedPos)) {
            return cached;
        }
        cached = build(route, playerPos);
        cachedPos = playerPos;
        return cached;
    }

    private static NavGuidance build(Route route, BlockPos playerPos) {
        int from = PathProgress.INSTANCE.indexFor(route.source);
        int last = route.source.steps().size() - 1;

        double blocks = route.blocks[last] - route.blocks[from];
        double seconds = route.remainingTicks(from) / 20.0;

        Turn turn;
        int turnDistance;
        int slot = route.nextTurnSlot(from);
        if (blocks <= ARRIVAL_BLOCKS && route.source.complete()) {
            turn = Turn.ARRIVE;
            turnDistance = 0;
        } else if (slot < 0) {
            turn = Turn.STRAIGHT;
            turnDistance = 0;
        } else {
            turn = route.turnLeft[slot] ? Turn.LEFT : Turn.RIGHT;
            turnDistance = (int) Math.round(route.blocks[route.turnStep[slot]] - route.blocks[from]);
        }

        // まだ道のりが残っているのに「約0秒」と出さない
        int rounded = Math.max(blocks > 0.0 ? SECONDS_GRANULARITY : 0,
                (int) Math.round(seconds / SECONDS_GRANULARITY) * SECONDS_GRANULARITY);
        return new NavGuidance((int) Math.round(blocks), rounded, turnDistance, turn, route.source.complete());
    }

    /**
     * 経路ごとの下ごしらえ。各ステップまでの累積（道のり・移動コスト・作業コスト）と、
     * 曲がり角の位置を持つ。
     *
     * <p>曲がり角を経路の先頭から通しで求めるのが要点。プレイヤーの現在地から区切り直すと、
     * ちょうど角に立っているときにレグの向きが角をまたいで決まり、その場で曲がっていることに
     * なってしまう（案内がいちばん要る場所でいちばん不安定になる）。
     */
    private static final class Route {

        private final PathResult source;
        /** 各ステップまでの道のり。 */
        private final double[] blocks;
        /** 移動そのもののコストと、掘る・置く・開ける側のコスト。所要時間の補正で扱いを分ける。 */
        private final double[] movementTicks;
        private final double[] movementBlocks;
        private final double[] actionTicks;

        private final int[] turnStep;
        private final boolean[] turnLeft;
        private final int turnCount;

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
                // 掘る・置く区間のコストは歩く速さとは無関係なので、実測での割り直しから外す
                boolean action = step.digging() || step.bridging();
                blocks[i] = blocks[i - 1] + distance;
                movementBlocks[i] = movementBlocks[i - 1] + (action ? 0.0 : distance);
                movementTicks[i] = movementTicks[i - 1] + (action ? 0.0 : step.cost());
                actionTicks[i] = actionTicks[i - 1] + (action ? step.cost() : 0.0);
            }

            List<int[]> turns = findTurns(steps);
            this.turnCount = turns.size();
            this.turnStep = new int[turnCount];
            this.turnLeft = new boolean[turnCount];
            for (int i = 0; i < turnCount; i++) {
                turnStep[i] = turns.get(i)[0];
                turnLeft[i] = turns.get(i)[1] < 0;
            }
        }

        /** {@code from}以降で最初の曲がり角。無ければ-1。 */
        private int nextTurnSlot(int from) {
            for (int i = 0; i < turnCount; i++) {
                if (turnStep[i] > from) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * {@code from}から先の所要時間（tick）。移動の分だけをプレイヤーの実測の速さで割り直す。
         *
         * <p>基準にする速さは経路が想定している速さ（移動区間の平均）にする。スプリント固定で比べると、
         * 泳ぎや水中歩行のように元々遅い経路で二重に遅く見積もることになる。
         */
        private double remainingTicks(int from) {
            int last = source.steps().size() - 1;
            double movement = movementTicks[last] - movementTicks[from];
            double action = actionTicks[last] - actionTicks[from];
            double moved = movementBlocks[last] - movementBlocks[from];
            double actual = NavPace.INSTANCE.blocksPerTick();
            if (movement <= 0.0 || moved <= 0.0 || actual <= 0.0) {
                return movement + action;
            }
            double assumed = moved / movement;
            return movement * Math.clamp(assumed / actual, PACE_FACTOR_MIN, PACE_FACTOR_MAX) + action;
        }
    }

    /**
     * 経路全体の曲がり角。要素は{@code {ステップ添字, 外積の符号}}で、外積が負なら左
     * （+X=東・+Z=南なので、進行方向から見て左を向くと負になる）。
     *
     * <p>レグ同士の向きの差だけを見る。緩い曲がりはレグの境目であっても曲がり角とはせず、
     * 次のレグの向きを基準にして追い続ける（道なりのカーブで案内を出さないため）。
     */
    private static List<int[]> findTurns(List<PathStep> steps) {
        List<int[]> turns = new ArrayList<>();
        double[] previous = null;
        int from = 0;
        while (from < steps.size() - 1) {
            double[] direction = new double[2];
            int end = legEnd(steps, from, direction);
            if (end <= from) {
                // 梯子や掘り下げのように真上・真下だけへ動く区間は水平の向きが決まらない。
                // ここで打ち切ると、その先にある曲がり角を全部見落とす
                from++;
                continue;
            }
            if (previous != null
                    && previous[0] * direction[0] + previous[1] * direction[1] < TURN_COS_THRESHOLD) {
                double side = previous[0] * direction[1] - previous[1] * direction[0];
                turns.add(new int[] {from, side < 0.0 ? -1 : 1});
            }
            previous = direction;
            from = end;
        }
        return turns;
    }

    /**
     * {@code from}から始まる直線区間の終端。区間の向きは最初の数ステップで決め、
     * そこから引いた直線から{@link #LEG_CORRIDOR_BLOCKS}以上離れた点が出たところで区切る。
     * 水平の向きが決められなければ{@code from}をそのまま返す。
     */
    private static int legEnd(List<PathStep> steps, int from, double[] outDirection) {
        BlockPos origin = steps.get(from).pos();
        // 縦移動を挟むと先読み先が真上・真下になって差が0になる。水平に動いている一番遠い点を使う
        int span = Math.min(from + LEG_DIRECTION_SPAN, steps.size() - 1);
        while (span > from && horizontalLength(origin, steps.get(span).pos()) < 1.0e-6) {
            span--;
        }
        if (span == from) {
            return from;
        }
        double dx = steps.get(span).pos().getX() - origin.getX();
        double dz = steps.get(span).pos().getZ() - origin.getZ();
        double length = horizontalLength(origin, steps.get(span).pos());
        outDirection[0] = dx / length;
        outDirection[1] = dz / length;

        int end = from;
        double furthest = 0.0;
        for (int i = from + 1; i < steps.size(); i++) {
            double px = steps.get(i).pos().getX() - origin.getX();
            double pz = steps.get(i).pos().getZ() - origin.getZ();
            double along = px * outDirection[0] + pz * outDirection[1];
            double perpX = px - along * outDirection[0];
            double perpZ = pz - along * outDirection[1];
            if (along < furthest - BACKTRACK_BLOCKS
                    || perpX * perpX + perpZ * perpZ > LEG_CORRIDOR_BLOCKS * LEG_CORRIDOR_BLOCKS) {
                break;
            }
            furthest = Math.max(furthest, along);
            end = i;
        }
        return end;
    }

    private static double horizontalLength(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
