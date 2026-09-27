package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.GameCompat;

/**
 * 採った経路が何でできていて、なぜその形になったのかをdebugに残す。
 *
 * <p>1行目（経路の内訳）は採るたびに出す。手の種類ごとの手数と値段・高い手の塊・直線からのずれ・ガイドの見積もりと
 * 実際の差を並べるので、「なぜ遠回りに見えるか」の多くはこの1行で割れる（例: 積む7段を避けて丘の斜面を回った）。
 *
 * <p>2行目（再現用）は、模型（{@code FakeCells}＋{@code tools/dump_terrain_columns.py}）で同じ探索を解き直すのに要る
 * 設定と地形の書き出し範囲。中身が変わったときだけ出す——始点は1行目にあるので、毎回出しても同じ行が並ぶだけになる。
 */
final class RouteExplain {

    private static final Logger LOGGER = LogManager.getLogger();

    /** 高い手の塊を何個まで並べるか。 */
    private static final int COSTLY_RUNS = 3;

    /** 書き出し範囲を始点と目的地の外接箱からどれだけ広げるか。窓の縁の外まで少し入れないと、模型の窓が欠ける。 */
    private static final int DUMP_PAD_BLOCKS = 128;

    /**
     * 書き出し範囲を始点からこれより遠くへ伸ばさない。目的地が数千ブロック先だと書き出しが数百MBになり、
     * 局所の形を調べるのに要るのは窓（最大224）とその少し外だけ。
     */
    private static final int DUMP_MAX_REACH_BLOCKS = 1024;

    /** 書き出し範囲の丸め。少し歩くたびに範囲が変わって再現用の行が出直さないようにする。 */
    private static final int DUMP_GRID_BLOCKS = 256;

    private static final ChangeGate<String> reproGate = new ChangeGate<>();

    private RouteExplain() {
    }

    /**
     * @param kind   何がこの経路を引いたか（再計算のきっかけ・継ぎ足しなど）
     * @param target この探索が狙った点（中間目標・着地点・目的地）
     * @param guide  探索に掛けたガイド。掛けていなければ{@code null}
     * @param window 目的地までの航法グラフのガイド。エンドの着地点を狙う探索は{@code guide}が着地点までの値に引き直されているので、
     *               値の出どころ（窓のどの縁から出るか）はこちらで見る
     */
    static void log(String kind, Level level, BlockPos start, BlockPos target, BlockPos goal, PathResult result,
                    @Nullable CostToGo guide, @Nullable CostToGo window, CellSource view, MovementOptions options,
                    int renderRadius) {
        if (!LOGGER.isDebugEnabled() || result.steps().isEmpty()) {
            return;
        }
        // 次元と高さはワールドから読むのでここで写す。数えるのは航法グラフのガイドを下るぶん重いので、ログ用のスレッドへ回す
        String repro = repro(level, start, target, goal, window, view, options, renderRadius);
        NavGraphGuide.logOffThread(() -> {
            LOGGER.debug("XaeroNav: 経路の内訳 ({})", summary(kind, start, target, goal, result, guide, window));
            if (reproGate.changed(repro)) {
                LOGGER.debug("XaeroNav: 経路の再現用 ({})", repro);
            }
        });
    }

    private static String summary(String kind, BlockPos start, BlockPos target, BlockPos goal, PathResult result,
                                  @Nullable CostToGo guide, @Nullable CostToGo window) {
        List<PathStep> steps = result.steps();
        Map<String, double[]> byKind = new LinkedHashMap<>();
        List<Run> runs = new ArrayList<>();
        Run run = null;
        int up = 0;
        int down = 0;
        int minY = start.getY();
        int maxY = start.getY();
        double walked = 0.0;
        double cost = 0.0;
        double gx = target.getX() - start.getX();
        double gz = target.getZ() - start.getZ();
        double line = Math.hypot(gx, gz);
        double deviation = 0.0;
        BlockPos deviationAt = start;
        BlockPos previous = start;
        for (PathStep step : steps) {
            BlockPos pos = step.pos();
            String action = action(step);
            double[] tally = byKind.computeIfAbsent(action, key -> new double[2]);
            tally[0]++;
            tally[1] += step.cost();
            cost += step.cost();
            if (run == null || !run.action.equals(action)) {
                run = new Run(action, pos);
                runs.add(run);
            }
            run.steps++;
            run.cost += step.cost();
            int dy = pos.getY() - previous.getY();
            if (dy > 0) {
                up += dy;
            } else {
                down -= dy;
            }
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            walked += Math.hypot(pos.getX() - previous.getX(), pos.getZ() - previous.getZ());
            if (line >= 1.0) {
                double off = Math.abs((pos.getX() - start.getX()) * gz - (pos.getZ() - start.getZ()) * gx) / line;
                if (off > deviation) {
                    deviation = off;
                    deviationAt = pos;
                }
            }
            previous = pos;
        }
        BlockPos end = previous;
        double straight = Math.hypot(end.getX() - start.getX(), end.getZ() - start.getZ());

        StringJoiner kinds = new StringJoiner(" ");
        byKind.forEach((action, tally) -> kinds.add("%s%d手/%d".formatted(action, (int) tally[0], Math.round(tally[1]))));
        // 歩きの塊は長いだけで理由にならない。見たいのは「ここで高い手を払った」所
        StringJoiner costly = new StringJoiner(" ");
        runs.stream().filter(r -> !r.action.equals("歩く")).sorted(Comparator.comparingDouble((Run r) -> r.cost).reversed())
                .limit(COSTLY_RUNS)
                .forEach(r -> costly.add("%s%d@%s(%d)".formatted(r.action, r.steps, r.from.toShortString(),
                        Math.round(r.cost))));

        return String.format(Locale.ROOT,
                "%s, 始点=%s, 末端=%s, 狙い=%s, 目的地=%s, %s, 到達=%s, 展開=%d, %dステップ, 値段=%dtick, 内訳=[%s], "
                        + "高い手=[%s], 登り%d 降り%d y%d〜%d, 直線%d→歩く長さ%d(%.2f倍), 狙いへの直線から最大%d@%s, %s",
                kind, start.toShortString(), end.toShortString(), target.toShortString(), goal.toShortString(),
                result.termination(), result.complete(), result.expandedNodes(), steps.size(), Math.round(cost),
                kinds, costly.length() == 0 ? "無し" : costly, up, down, minY, maxY, Math.round(straight),
                Math.round(walked), straight < 1.0 ? 0.0 : walked / straight, Math.round(deviation),
                deviationAt.toShortString(), guideVerdict(guide, window, start, end, cost));
    }

    /**
     * ガイドが始点で言っていた残りと、実際に払った値段＋末端の残りを比べる。大きく食い違えば、探索はガイドに
     * 引っ張られて形が決まっている（窓の外の推定が安すぎて外へ寄る、など）。航法グラフなら値の出どころも添える。
     */
    private static String guideVerdict(@Nullable CostToGo guide, @Nullable CostToGo window, BlockPos start,
                                       BlockPos end, double cost) {
        String measured = guide == null ? "ガイド=無し" : "ガイド%s 始点の残り%d 払った%d+末端の残り%d".formatted(
                guide == window ? "" : "(狙いまで)", Math.round(guide.estimate(start.getX(), start.getY(), start.getZ())),
                Math.round(cost), Math.round(guide.estimate(end.getX(), end.getY(), end.getZ())));
        if (!(window instanceof WindowField field)) {
            return measured;
        }
        return "%s, 窓の中心=%d,%d 半径%d, 始点の値の出どころ=%s, 末端の値の出どころ=%s".formatted(measured,
                field.centerX(), field.centerZ(), field.radius(), NavGraphGuide.origin(field, start),
                NavGraphGuide.origin(field, end));
    }

    private static String action(PathStep step) {
        if (step.bridging()) {
            return step.movement() == MovementType.ASCEND ? "積む" : "橋";
        }
        if (step.digging()) {
            return "掘る";
        }
        return switch (step.movement()) {
            case TRAVERSE -> "歩く";
            case ASCEND -> "登る";
            case DESCEND -> "降りる";
            case JUMP -> "跳ぶ";
            case FALL_DAMAGE -> "落ちる(ダメージ)";
            case FALL_MLG -> "落ちる(水バケツ)";
            case SWIM -> "泳ぐ";
            case BOAT -> "ボート";
            case CLIMB -> "梯子";
        };
    }

    /**
     * 模型で解き直すための1行。設定は{@code FakeCells}の同名メソッドにそのまま渡せる形で並べる。
     * 窓の外の推定は書き出した範囲の地形から組み直すので、範囲を始点の近くに切ったときは遠くの値が実機と違う。
     */
    private static String repro(Level level, BlockPos start, BlockPos target, BlockPos goal, @Nullable CostToGo guide,
                                CellSource view, MovementOptions options, int renderRadius) {
        int window = guide instanceof WindowField field ? field.radius() : NavGraphGuide.window(renderRadius);
        String settings = String.format(Locale.ROOT,
                "canPlaceBlocks(%s).placedBlockBudget(%d).maxFallDamagePoints(%d).fatalFallBlocks(%d)"
                        + ".maxBridgeRunBlocks(%d).lavaBridgingEnabled(%s).maxLavaBridgeRunBlocks(%d)"
                        + ".maxVoidBridgeRunBlocks(%d).jumpGapEnabled(%s).maxSubmergedTicks(%d).canMlgWaterBucket(%s)"
                        + ".boatAvailable(%s)",
                view.canPlaceBlocks(), view.placedBlockBudget(), view.maxFallDamagePoints(), view.fatalFallBlocks(),
                view.maxBridgeRunBlocks(), view.lavaBridgingEnabled(), view.maxLavaBridgeRunBlocks(),
                view.maxVoidBridgeRunBlocks(), view.jumpGapEnabled(), view.maxSubmergedTicks(),
                view.canMlgWaterBucket(), view.boatAvailable());
        int minX = Math.max(Math.min(start.getX(), goal.getX()) - DUMP_PAD_BLOCKS, start.getX() - DUMP_MAX_REACH_BLOCKS);
        int maxX = Math.min(Math.max(start.getX(), goal.getX()) + DUMP_PAD_BLOCKS, start.getX() + DUMP_MAX_REACH_BLOCKS);
        int minZ = Math.max(Math.min(start.getZ(), goal.getZ()) - DUMP_PAD_BLOCKS, start.getZ() - DUMP_MAX_REACH_BLOCKS);
        int maxZ = Math.min(Math.max(start.getZ(), goal.getZ()) + DUMP_PAD_BLOCKS, start.getZ() + DUMP_MAX_REACH_BLOCKS);
        boolean clipped = maxX - minX < Math.abs(goal.getX() - start.getX()) + 2 * DUMP_PAD_BLOCKS
                || maxZ - minZ < Math.abs(goal.getZ() - start.getZ()) + 2 * DUMP_PAD_BLOCKS;
        // ネザーは天井の岩盤より上（y128〜）を書き出しても、屋根の上を歩く経路しか増えない
        int bandTop = level.dimensionType().hasCeiling() ? 127 : GameCompat.maxBuildHeight(level) - 1;
        return String.format(Locale.ROOT,
                "目的地=%s, 狙い=%s, 窓=%d, 掘る=%s, 設定=%s, 書き出し=python3 tools/dump_terrain_columns.py "
                        + "saves/<ワールド>/%s %d %d %d %d --band %d,%d --out <名前>.txt.gz%s",
                goal.toShortString(), target.toShortString(), window, options.diggingEnabled(), settings,
                regionDir(level), Math.floorDiv(minX, DUMP_GRID_BLOCKS) * DUMP_GRID_BLOCKS,
                Math.floorDiv(minZ, DUMP_GRID_BLOCKS) * DUMP_GRID_BLOCKS,
                Math.floorDiv(maxX, DUMP_GRID_BLOCKS) * DUMP_GRID_BLOCKS + DUMP_GRID_BLOCKS - 1,
                Math.floorDiv(maxZ, DUMP_GRID_BLOCKS) * DUMP_GRID_BLOCKS + DUMP_GRID_BLOCKS - 1,
                GameCompat.minBuildHeight(level), bandTop,
                clipped ? " (目的地が遠いので始点の近くだけ。窓の外の推定は実機と違う)" : "");
    }

    private static String regionDir(Level level) {
        if (level.dimension() == Level.NETHER) {
            return "DIM-1/region";
        }
        if (level.dimension() == Level.END) {
            return "DIM1/region";
        }
        if (level.dimension() == Level.OVERWORLD) {
            return "region";
        }
        return "dimensions/<名前空間>/<次元>/region";
    }

    private static final class Run {
        final String action;
        final BlockPos from;
        int steps;
        double cost;

        Run(String action, BlockPos from) {
            this.action = action;
            this.from = from;
        }
    }
}
