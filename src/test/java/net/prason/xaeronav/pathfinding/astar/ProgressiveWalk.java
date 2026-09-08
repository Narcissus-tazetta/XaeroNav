package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * <b>実機と同じ組み立て方で経路を作る</b>——層1のcost-to-goガイド・{@link #DETAIL_HORIZON}
 * ごとの区間分割・末端からの継ぎ足し。オフラインで「実運用の経路」を再現する唯一の手段で、
 * 1回のA*で全区間を解いた経路とは別物になる。
 *
 * <p>{@code ProgressiveDiscoveryTest}（窓を動かして歩く）と{@code LongRouteOptimalityTest}
 * （全視界と窓の取り分を分ける）の両方が使う。定数は{@code PathfindingState}の既定値と揃える。
 */
final class ProgressiveWalk {

    private static final BooleanSupplier NEVER = () -> false;

    /** {@code PathfindingState#detailHorizonBlocks}の既定。 */
    static final int DETAIL_HORIZON = 96;

    /** {@code PathfindingState#MIN_DETAIL_REACH_BLOCKS}。これだけ先へ窓が届いていれば継ぎ足す。 */
    static final int MIN_DETAIL_REACH = 24;

    /** {@code PathfindingState#MIN_EXTEND_PROGRESS_BLOCKS}。これ未満しか前へ出ない尻尾は繋がない。 */
    static final double MIN_EXTEND_PROGRESS = 12.0;

    /**
     * 1回の歩き通しで解いてよい区間の総数。<b>これが無いと測定そのものが暴走する</b>——
     * 400tickのループは「1tickでも前へ出れば続く」ので、探索がほとんど進まない条件に入ると
     * 400tick×数秒＝数時間走り続ける（実際に9時間と1時間の実行を2回作った）。
     * 経路1本は実測で10〜20区間なので、その3倍を天井に置く。
     */
    private static final int MAX_LEGS = 60;

    /**
     * 1回の歩き通しに使ってよい壁時計（ミリ秒）。区間数の天井だけでは足りない——
     * 1区間が深い予算(80万ノード)まで積むと数秒かかるので、天井いっぱいまで解くと数十分になる。
     * <b>結果の値ではなく暴走の歯止め</b>なので、CIの速度で答えが変わる心配はしなくてよい
     * （ここへ当たった時点でその測定は「届かなかった」として捨てる）。
     */
    private static final long TRACE_BUDGET_MILLIS = 120_000;

    /** {@code PathfindingState#INTERPOLATED_GOAL_RADIUS_BLOCKS}。補間した中間目標は領域で狙う。 */
    static final int INTERPOLATED_GOAL_RADIUS = 16;

    /** 1回の計画のあいだにプレイヤーが歩く距離（ブロック）。 */
    static final int WALK_PER_TICK = 16;

    /**
     * 窓を掛けない＝世界が丸ごと見えている状態を表す半径。どのフィクスチャの箱よりも大きいので、
     * {@link WindowedCells}が何も隠さず、継ぎ足しも一度に目的地まで届く。
     */
    static final int NO_WINDOW = 4096;

    /** 1区間の探索に渡す予算（{@code PathfindingState}の既定）。 */
    private static final int LEG_NODE_BUDGET = 100_000;

    /** 最初の探索の取り分（{@code PathfindingExecutor#FIRST_PASS_PERCENT}）。 */
    private static final int FIRST_PASS_NODE_BUDGET = LEG_NODE_BUDGET * 40 / 100;

    /** 届かなかったときに順に試す重み（{@code PathfindingExecutor#GREEDY_RETRY_WEIGHTS}）。 */
    private static final double[] GREEDY_RETRY_WEIGHTS = {2.5, 3.0};

    /** 基準の探索に渡す予算。 */
    private static final int UNLIMITED_NODE_BUDGET = 3_000_000;

    private ProgressiveWalk() {
    }

    static double cost(List<PathStep> steps) {
        return steps.stream().mapToDouble(PathStep::cost).sum();
    }

    static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 目的地そのもの、または遠すぎるならその方向へ{@code reach}だけ進んだ点（実装と同じ）。 */
    private static BlockPos aimToward(BlockPos from, BlockPos goal, int reach) {
        double distance = horizontal(from, goal);
        if (distance <= reach) {
            return goal;
        }
        double t = reach / distance;
        return new BlockPos(from.getX() + (int) Math.round((goal.getX() - from.getX()) * t),
                from.getY() + (int) Math.round((goal.getY() - from.getY()) * t),
                from.getZ() + (int) Math.round((goal.getZ() - from.getZ()) * t));
    }

    /**
     * 1区間を解く。届かなければ重みを上げて引き直すのは{@code PathfindingExecutor#retryGreedier}と
     * 同じで、<b>これが無いとジ・エンドの島渡りは1本も返らない</b>（実測6本中5本）。
     */
    private static PathResult leg(CellSource view, BlockPos from, BlockPos goal) {
        BlockPos aim = aimToward(from, goal, DETAIL_HORIZON);
        int radius = aim.equals(goal) ? 0 : INTERPOLATED_GOAL_RADIUS;
        CoarseMap map = LiveCoarseSampler.sample(view, view.bounds(), from.getY(), NEVER);
        CostToGo guide = CoarseRouter.costToGo(map, aim, false, CoarseRouter.BridgePolicy.BRIDGE);
        PathResult first = search(view, guide, from, aim, radius,
                AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, FIRST_PASS_NODE_BUDGET);
        if (first.complete()) {
            return first;
        }
        for (double weight : GREEDY_RETRY_WEIGHTS) {
            PathResult attempt = search(view, guide, from, aim, radius, weight, LEG_NODE_BUDGET);
            if (attempt.complete()) {
                return attempt;
            }
        }
        return first;
    }

    private static PathResult search(CellSource view, CostToGo guide, BlockPos from, BlockPos aim,
                                     int radius, double weight, int budget) {
        return new AStarPathfinder(view, new SearchLimits(budget, 30_000, weight), guide)
                .search(from, aim, NEVER, Carryover.NONE, radius);
    }

    /** 詳細探索が何を狙うか。実装の{@code PathfindingState#selectDetailTarget}の2つの姿。 */
    enum Aim {
        /** 遠い目的地は{@link #DETAIL_HORIZON}ぶん手前で切って狙う（{@code goalOrPointToward}）。 */
        HORIZON,
        /**
         * 常に最終目的地を狙い、箱で切られた部分経路を継ぎ足す
         * （{@code PathfindingState#COARSE_ROUTE_DISTRUST_RATIO}を超えた地形での動き）。
         */
        GOAL,
        /** {@link #GOAL}と同じだが、経路全体を覆う3D地図から作ったガイドを掛ける（実験）。 */
        WIDE_VOXEL
    }

    /** {@code XaeroNavConfig#searchHorizontalMargin}の既定。 */
    private static final int SEARCH_HORIZONTAL_MARGIN = 64;

    /** 1区間に渡す予算（{@code XaeroNavConfig#searchLimits}の既定）。 */
    private static final SearchLimits LIVE_LIMITS =
            new SearchLimits(LEG_NODE_BUDGET, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * 1手も返らなかったときに積む予算（{@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}=8）。
     * <b>これを入れないと実機より厳しいモデルになる</b>——実機は通常予算の探索が空で返った回を
     * 詰みとは扱わず、深い予算で解き直す。
     */
    private static final SearchLimits DEEP_LIVE_LIMITS =
            new SearchLimits(LEG_NODE_BUDGET * 8, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /**
     * 実機の探索の箱（{@code SearchBounds#around}と同じ切り方）。<b>これを渡さないと測れない</b>——
     * 目的地が箱の外にあることで層1ガイドが無効になるのが、目的地を狙う設計の実際の姿だから。
     */
    private static SearchBounds searchBox(FakeCells all, BlockPos from, BlockPos to, int radius) {
        SearchBounds world = all.bounds();
        return new SearchBounds(
                Math.max(from.getX() - radius, Math.min(from.getX(), to.getX()) - SEARCH_HORIZONTAL_MARGIN),
                world.minY(),
                Math.max(from.getZ() - radius, Math.min(from.getZ(), to.getZ()) - SEARCH_HORIZONTAL_MARGIN),
                Math.min(from.getX() + radius, Math.max(from.getX(), to.getX()) + SEARCH_HORIZONTAL_MARGIN),
                world.maxY(),
                Math.min(from.getZ() + radius, Math.max(from.getZ(), to.getZ()) + SEARCH_HORIZONTAL_MARGIN));
    }

    /** 実機と同じ箱・同じ緩和の梯子で1区間を解く。窓はプレイヤー周り＝読み込み済みチャンク。 */
    private static CellSource boxedView(FakeCells all, BlockPos player, int radius, BlockPos from,
                                        BlockPos to) {
        return new WindowedCells(all, player, radius, searchBox(all, from, to, radius));
    }

    /**
     * <b>最終目的地を狙う区間。</b>実機と同じく{@link PathfindingExecutor#submit}へ通す——
     * 最初の探索は予算の{@code FIRST_PASS_PERCENT}しか使わず、届かなければ重みを上げて
     * 引き直す（その部分経路は捨てられる）という梯子まで含めて再現するため。
     */
    private static PathResult legToGoal(PathfindingExecutor executor, FakeCells all, BlockPos player,
                                        int radius, BlockPos from, BlockPos goal, List<PathStep> planned,
                                        CostToGo wide) {
        CellSource view = boxedView(all, player, radius, from, goal);
        Carryover carried = Carryover.after(planned);
        try {
            PathResult result =
                    executor.submit(view, from, goal, LIVE_LIMITS, true, 0, carried, wide).get();
            if (!result.steps().isEmpty()) {
                return result;
            }
            return executor.submit(view, from, goal, DEEP_LIVE_LIMITS, true, 0, carried, wide).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #walk}のコストだけを見る版。届かなければ{@link Double#POSITIVE_INFINITY}。 */
    static double walkToGoal(FakeCells all, BlockPos start, BlockPos goal, int radius,
                             boolean extending) {
        List<PathStep> walked = walk(all, start, goal, radius, extending);
        return walked.isEmpty() ? Double.POSITIVE_INFINITY : cost(walked);
    }

    /**
     * 同じ位置を2回通っているステップの数。<b>1回の探索では起きえない</b>（{@code AStarPathfinder}は
     * 同じセルを二度閉じない）ので、これが0でなければ継ぎ足しの繋ぎ目で生まれたもの。
     */
    static int selfOverlaps(List<PathStep> steps) {
        Set<BlockPos> seen = new HashSet<>();
        int overlaps = 0;
        for (PathStep step : steps) {
            if (!seen.add(step.pos())) {
                overlaps++;
            }
        }
        return overlaps;
    }

    /** 経路の組み立て方。{@link #trace}が測り分ける3通り。 */
    enum Mode {
        /** 実装どおり。末端から継ぎ足し、手前は二度と見直さない。 */
        EXTEND,
        /** 計画のたびに手前を捨ててプレイヤーから引き直す（ユーザー要望の「全部引き直す」）。 */
        REPLAN,
        /** 継ぎ足したあと、繋ぎ目をまたぐ区間だけを解き直して安ければ差し替える。 */
        REPAIR
    }

    /** {@code PathfindingState#SEAM_REPAIR_SPAN_BLOCKS}。繋ぎ目の手前・先をそれぞれ何ブロック見るか。 */
    private static final double REPAIR_SPAN_BLOCKS = 48.0;

    /** {@code PathfindingState#SEAM_REPAIR_KEEP_BLOCKS}。プレイヤーの前方これだけは描き変えない。 */
    private static final double REPAIR_KEEP_BLOCKS = 16.0;

    /** {@code PathfindingState#SEAM_REPAIR_MIN_GAIN}。これより安くならないなら線を描き変えない。 */
    private static final double REPAIR_MIN_GAIN = 0.98;

    /**
     * 修復に渡す予算。{@code PathfindingState}が1区間に渡すのと同じ（既定の{@code maxExpandedNodes}）。
     *
     * <p><b>6万で頭打ちにすると足りない。</b>実測でネザー2の修復が3回中1回落ち、
     * 繋ぎ目の局所の遠回りが最悪1.059倍→1.927倍に戻った（実機ログにも
     * 「解き直しが繋ぎ目の先へ届かなかった (NODE_BUDGET)」が出ていた）。
     */
    private static final int REPAIR_NODE_BUDGET = 100_000;

    /** 「プレイヤーの近くで線が描き変わった」とみなす距離（ブロック）。 */
    private static final double NEAR_PLAYER_BLOCKS = 32.0;

    /**
     * 歩いた経路と、その上で<b>区間が切り替わった位置</b>（＝繋ぎ目）、そして
     * <b>線がどれだけ描き変わったか</b>。
     *
     * @param joints         {@code steps}の添字。そのステップから新しい区間が始まっている
     * @param redraws        すでに引いてある線が描き変わった回数
     * @param redrawnBlocks  描き変わった区間の長さの合計（ブロック）
     * @param nearRedraws    そのうち、描き変わりの起点がプレイヤーから
     *                       {@link #NEAR_PLAYER_BLOCKS}以内だった回数
     */
    record Trace(List<PathStep> steps, List<Integer> joints, int redraws, double redrawnBlocks,
                 int nearRedraws, int repairAttempts, int repairsTaken, long repairNodes,
                 String stopped) {

        /** 目的地まで歩き通せなかった理由（診断用）。歩き通したなら空。 */
        static Trace failed(String reason) {
            return new Trace(List.of(), List.of(), 0, 0, 0, 0, 0, 0, reason);
        }
    }

    /**
     * 窓を動かしながら目的地まで歩き通し、実際に歩いた経路を返す。届かなければ空。
     *
     * @param radius    読み込み済みの窓の半径。{@link #NO_WINDOW}なら全視界
     * @param extending trueなら実装どおり末端から継ぎ足す。falseなら計画のたびに手前を捨てて
     *                  プレイヤーから引き直す。<b>歩き方は両方で同じ</b>にしてある
     */
    static List<PathStep> walk(FakeCells all, BlockPos start, BlockPos goal, int radius,
                               boolean extending) {
        return trace(all, start, goal, radius, extending ? Mode.EXTEND : Mode.REPLAN).steps();
    }

    /** 線が描き変わった最初の添字。同じなら{@code -1}。 */
    private static int firstDifference(List<PathStep> before, List<PathStep> after) {
        int shared = Math.min(before.size(), after.size());
        for (int i = 0; i < shared; i++) {
            if (!before.get(i).pos().equals(after.get(i).pos())) {
                return i;
            }
        }
        // 後ろへ伸びただけ（継ぎ足し）は描き変わりではない
        return before.size() > after.size() ? shared : -1;
    }

    /** {@code steps}の{@code from}から{@code to}までの、経路に沿った長さ（ブロック）。 */
    private static double lengthBetween(List<PathStep> steps, int from, int to) {
        double length = 0;
        for (int i = from + 1; i <= to && i < steps.size(); i++) {
            length += Math.sqrt(steps.get(i).pos().distSqr(steps.get(i - 1).pos()));
        }
        return length;
    }

    /** 差し替えた線と、差し替えた区間（{@code from}以降が{@code length}ステップになった）。 */
    private record Repair(List<PathStep> steps, int from, int replaced, int length) {
    }

    /** 繋ぎ目の修復を1回試したときの値段。{@code repair}がnullなら採らなかった。 */
    private record RepairAttempt(Repair repair, long expandedNodes) {
    }

    /**
     * 繋ぎ目をまたぐ区間だけを解き直す。安くなったなら差し替えた線を、そうでなければ{@code null}。
     */
    private static RepairAttempt repairSeam(CellSource view, BlockPos player, List<PathStep> planned,
                                            int seam) {
        int first = 0;
        while (first < planned.size() && distance(player, planned.get(first).pos()) < REPAIR_KEEP_BLOCKS) {
            first++;
        }
        int from = seam;
        while (from > first && lengthBetween(planned, from - 1, seam) < REPAIR_SPAN_BLOCKS) {
            from--;
        }
        int to = seam;
        while (to < planned.size() - 1 && lengthBetween(planned, seam, to + 1) <= REPAIR_SPAN_BLOCKS) {
            to++;
        }
        if (from < 1 || from >= seam || to <= seam || to - from < 4) {
            return new RepairAttempt(null, 0);
        }
        // fromは差し替える区間の先頭なので、探索の始点はその1つ手前
        BlockPos fromPos = planned.get(from - 1).pos();
        BlockPos toPos = planned.get(to).pos();
        double current = cost(planned.subList(from, to + 1));
        // 層1のガイドは掛けない。<b>この距離では効かないことを実測した</b>——96ブロックの区間では
        // 16ブロック解像度のガイドが幾何Heuristicを下回り、maxで常に負けるので展開ノード数が
        // 1つも変わらなかった（5地形すべてで完全一致）。掛ける手間だけが増える
        PathResult result = new AStarPathfinder(view,
                new SearchLimits(REPAIR_NODE_BUDGET, 30_000, 1.0)).search(fromPos, toPos, NEVER);
        if (!result.complete() || result.steps().isEmpty()
                || cost(result.steps()) >= current * REPAIR_MIN_GAIN) {
            return new RepairAttempt(null, result.expandedNodes());
        }
        List<PathStep> repaired = new ArrayList<>(planned.subList(0, from));
        repaired.addAll(result.steps());
        repaired.addAll(planned.subList(to + 1, planned.size()));
        return new RepairAttempt(new Repair(repaired, from, to + 1 - from, result.steps().size()),
                result.expandedNodes());
    }

    /**
     * 繋ぎ目の解き直しに渡す視界。実機は差し替える区間の両端で箱を切る（{@code repairSeam}）ので、
     * ここも繋ぎ目の周り{@link #REPAIR_SPAN_BLOCKS}＋マージンに絞る。
     */
    private static CellSource repairView(FakeCells all, BlockPos player, int radius,
                                          List<PathStep> planned, int seam) {
        BlockPos at = planned.get(seam).pos();
        int reach = (int) REPAIR_SPAN_BLOCKS + SEARCH_HORIZONTAL_MARGIN;
        return boxedView(all, player, radius, at.offset(-reach, 0, -reach), at.offset(reach, 0, reach));
    }

    /** {@link #walk}と同じものを、繋ぎ目の位置と描き変わりの量つきで返す。 */
    static Trace trace(FakeCells all, BlockPos start, BlockPos goal, int radius, Mode mode) {
        return trace(all, start, goal, radius, mode, Aim.HORIZON);
    }

    /** 狙い方を指定する版。{@link Aim#GOAL}は実機の{@link PathfindingExecutor}をそのまま通す。 */
    static Trace trace(FakeCells all, BlockPos start, BlockPos goal, int radius, Mode mode, Aim aim) {
        PathfindingExecutor executor = new PathfindingExecutor();
        CostToGo wide = aim == Aim.WIDE_VOXEL
                ? WideVoxelGuide.build(all, all.bounds(), goal) : null;
        List<PathStep> walked = new ArrayList<>();
        List<PathStep> planned = new ArrayList<>();
        // plannedの中で新しい区間が始まる位置。歩いた分だけ手前へ詰める
        List<Integer> plannedJoints = new ArrayList<>();
        List<Integer> joints = new ArrayList<>();
        int redraws = 0;
        int nearRedraws = 0;
        double redrawnBlocks = 0;
        int repairAttempts = 0;
        int repairsTaken = 0;
        long repairNodes = 0;
        BlockPos player = start;
        int legs = 0;
        long deadline = System.currentTimeMillis() + TRACE_BUDGET_MILLIS;
        for (int tick = 0; tick < 400; tick++) {
            if (System.currentTimeMillis() > deadline) {
                return Trace.failed(String.format("%d秒を使い切った（%s、目的地まで%.0f、区間%d本）",
                        TRACE_BUDGET_MILLIS / 1000, player.toShortString(),
                        horizontal(player, goal), legs));
            }
            CellSource view = new WindowedCells(all, player, radius);
            List<PathStep> before = planned;
            if (mode == Mode.REPLAN) {
                planned = new ArrayList<>();
                plannedJoints = new ArrayList<>();
                // 引き直しでは、これから足す区間の先頭がそのまま繋ぎ目になる（手前は捨てた）
                plannedJoints.add(0);
            }
            BlockPos end = planned.isEmpty() ? player : planned.get(planned.size() - 1).pos();
            while (horizontal(player, end) <= radius - MIN_DETAIL_REACH && horizontal(end, goal) > 1) {
                if (++legs > MAX_LEGS) {
                    return Trace.failed(String.format("区間%d本を超えた（%s、目的地まで%.0f）",
                            MAX_LEGS, player.toShortString(), horizontal(player, goal)));
                }
                PathResult result = aim == Aim.HORIZON
                        ? leg(view, end, goal)
                        : legToGoal(executor, all, player, radius, end, goal, planned, wide);
                if (result.steps().isEmpty()) {
                    break;
                }
                if (aim != Aim.HORIZON && !planned.isEmpty() && !result.complete()
                        && horizontal(end, result.steps().get(result.steps().size() - 1).pos())
                                < MIN_EXTEND_PROGRESS) {
                    // 実機（PathfindingState#MIN_EXTEND_PROGRESS_BLOCKS）はこの尻尾を繋がずに捨て、
                    // その末端からは伸ばせないと記録する。這うだけの数ブロックを繋ぐと、
                    // 証明済みの経路まで未到達扱いになるため。
                    // <b>歯止めが掛かるのは継ぎ足しだけ</b>——プレイヤーから引き直す側（recalculate）は
                    // どれだけ短くても暫定経路として出す
                    break;
                }
                int seam = planned.size();
                if (!planned.isEmpty() || !plannedJoints.contains(0)) {
                    plannedJoints.add(seam);
                }
                planned.addAll(result.steps());
                BlockPos next = planned.get(planned.size() - 1).pos();
                if (mode == Mode.REPAIR && seam > 0) {
                    RepairAttempt attempt = repairSeam(
                            aim == Aim.HORIZON ? view : repairView(all, player, radius, planned, seam),
                            player, planned, seam);
                    repairAttempts++;
                    repairNodes += attempt.expandedNodes();
                    if (attempt.repair() != null) {
                        repairsTaken++;
                        planned = attempt.repair().steps();
                        plannedJoints = shifted(plannedJoints, attempt.repair());
                    }
                }
                if (next.equals(end)) {
                    break;
                }
                end = next;
            }
            if (planned.isEmpty()) {
                return Trace.failed(String.format("tick%d 経路が1本も出ない（%s、目的地まで%.0f）",
                        tick, player.toShortString(), horizontal(player, goal)));
            }
            int changed = firstDifference(before, planned);
            if (changed >= 0) {
                redraws++;
                redrawnBlocks += lengthBetween(before, changed, before.size() - 1);
                if (changed < before.size()
                        && distance(player, before.get(changed).pos()) <= NEAR_PLAYER_BLOCKS) {
                    nearRedraws++;
                }
            }
            int walkTo = 0;
            while (walkTo < planned.size()
                    && horizontal(player, planned.get(walkTo).pos()) < WALK_PER_TICK) {
                walkTo++;
            }
            walkTo = Math.max(1, Math.min(walkTo, planned.size()));
            int walkedBefore = walked.size();
            for (int offset : plannedJoints) {
                if (offset < walkTo) {
                    joints.add(walkedBefore + offset);
                }
            }
            int consumed = walkTo;
            List<Integer> remaining = new ArrayList<>();
            for (int offset : plannedJoints) {
                if (offset >= consumed) {
                    remaining.add(offset - consumed);
                }
            }
            plannedJoints = remaining;
            walked.addAll(planned.subList(0, walkTo));
            planned = new ArrayList<>(planned.subList(walkTo, planned.size()));
            player = walked.get(walked.size() - 1).pos();
            if (horizontal(player, goal) <= 1) {
                return new Trace(walked, joints, redraws, redrawnBlocks, nearRedraws,
                        repairAttempts, repairsTaken, repairNodes, "");
            }
        }
        return Trace.failed(String.format("400tickで届かず（%s、目的地まで%.0f、歩いた%d手）",
                player.toShortString(), horizontal(player, goal), walked.size()));
    }

    /** 差し替えで動いた繋ぎ目の添字を付け直す。差し替えた区間の中の繋ぎ目はその先頭にまとめる。 */
    private static List<Integer> shifted(List<Integer> joints, Repair repair) {
        int after = repair.from() + repair.replaced();
        int shift = repair.length() - repair.replaced();
        List<Integer> moved = new ArrayList<>();
        boolean inside = false;
        for (int joint : joints) {
            if (joint < repair.from()) {
                moved.add(joint);
            } else if (joint >= after) {
                moved.add(joint + shift);
            } else {
                inside = true;
            }
        }
        if (inside) {
            moved.add(repair.from());
        }
        moved.sort(Integer::compareTo);
        return moved;
    }

    private static double distance(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }

    /** 全視界・重み1.0・ガイド無しの1回の探索。届かなければ{@link Double#POSITIVE_INFINITY}。 */
    static double fullVisibilityBest(FakeCells all, BlockPos start, BlockPos goal) {
        PathResult result = new AStarPathfinder(all,
                new SearchLimits(UNLIMITED_NODE_BUDGET, 120_000, 1.0)).search(start, goal, NEVER);
        return result.complete() ? cost(result.steps()) : Double.POSITIVE_INFINITY;
    }
}
