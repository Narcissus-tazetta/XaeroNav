package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;

/**
 * ネザーで「ガイドを良くすると歩き通しが悪くなる」現象の原因を切り分ける計測。
 *
 * <p>{@code AStarPathfinder#selectFallback}は{@code argmin(h + g/c)}で部分経路の終点を選ぶ。
 * 無駄{@code w = g - (h0 - h)}を置くと、ガイドが真値の{@code k}倍のとき、これは
 * {@code maximize g - λw}（{@code λ = k / (k - 1/c)}）と等価になる——つまり
 * <b>ガイドのスケールが、そのまま終点選びの遠回り許容度を決めてしまう</b>。
 * ここで測るのはその{@code k}。
 */
@Tag("bench")
class NetherFallbackBenchTest {

    private static final BooleanSupplier NEVER = () -> false;

    /** 基準の1回解きに渡す予算（{@code ProgressiveWalk#fullVisibilityBest}と同じ）。 */
    private static final SearchLimits REFERENCE = new SearchLimits(3_000_000, 120_000, 1.0);

    /**
     * 残りコストがこれ未満の区間は比を取らない。ゴール直前は真値が0へ落ちるので、
     * どんなガイドでも比が発散して平均を壊す。
     */
    private static final double MIN_REMAINING_TICKS = 200.0;

    /** 測るガイドと、その名前。 */
    private record Guide(String name, CostToGo guide) {
    }

    private static List<Guide> guides(FakeCells cells, BlockPos start, BlockPos goal) {
        return List.of(
                new Guide("3D粗層(本番)", XaeroMapModel.guide(cells, start, goal,
                        NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L)),
                new Guide("床だけ(理想)", WideVoxelGuide.build(cells, cells.bounds(), goal, true)),
                new Guide("全地形(理想)", WideVoxelGuide.build(cells, cells.bounds(), goal, false)));
    }

    /**
     * 行列で使うガイド。<b>最後の2本は「値の大きさだけ真値に寄せた」代用品</b>——
     * 完璧ガイドの{@code k=1}を、閉包を組み直さずに作るための定数倍。形（どちらへ迂回すべきか）は
     * 元のガイドのままなので完璧ガイドそのものではないが、<b>終点選びがkにどう反応するか</b>を
     * 見るには、kだけを動かせるこちらの方が素直に効く。
     */
    private static List<Guide> matrixGuides(FakeCells cells, BlockPos start, BlockPos goal) {
        CostToGo ideal = WideVoxelGuide.build(cells, cells.bounds(), goal, true);
        return List.of(
                new Guide("3D粗層(本番)", XaeroMapModel.guide(cells, start, goal,
                        NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y, 1.0, 0L)),
                new Guide("床だけ k≈0.8", ideal),
                new Guide("床だけ×1.25 k≈1", scaled(ideal, 1.25)),
                new Guide("床だけ×1.6 k≈1.3", scaled(ideal, 1.6)));
    }

    private static CostToGo scaled(CostToGo guide, double factor) {
        return (x, y, z) -> guide.estimate(x, y, z) * factor;
    }

    /**
     * 基準経路の各点で{@code h / 真の残りコスト}を測る。最適経路の部分経路は最適なので、
     * 真の残りコストは「全体 − そこまでの累積」で厳密に出る。
     */
    @Test
    void measuresHowMuchEachGuideInflates() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain();
        double descent = cells.minDescentTicksPerBlock(6);
        for (BlockPos[] route : NetherLiveWalkTest.routes()) {
            PathResult reference = new AStarPathfinder(cells, REFERENCE).search(route[0], route[1], NEVER);
            if (!reference.complete()) {
                System.out.printf(Locale.ROOT, "%s→%s 基準が完走しない（%s）%n",
                        route[0].toShortString(), route[1].toShortString(), reference.termination());
                continue;
            }
            List<PathStep> steps = reference.steps();
            double total = ProgressiveWalk.cost(steps);
            System.out.printf(Locale.ROOT, "%n%s→%s 基準%.0ftick %d手%n",
                    route[0].toShortString(), route[1].toShortString(), total, steps.size());

            List<BlockPos> at = new ArrayList<>();
            List<Double> remaining = new ArrayList<>();
            double walked = 0.0;
            at.add(route[0]);
            remaining.add(total);
            for (PathStep step : steps) {
                walked += step.cost();
                at.add(step.pos());
                remaining.add(total - walked);
            }

            report("幾何のみ", at, remaining, (x, y, z) -> Heuristic.estimate(x, y, z,
                    route[1].getX(), route[1].getY(), route[1].getZ(), descent,
                    ActionCosts.SPRINT_ONE_BLOCK));
            for (Guide guide : guides(cells, route[0], route[1])) {
                report(guide.name(), at, remaining, (x, y, z) -> guide.guide().estimate(x, y, z));
                report(guide.name() + "+幾何max", at, remaining, (x, y, z) -> Math.max(
                        guide.guide().estimate(x, y, z),
                        Heuristic.estimate(x, y, z, route[1].getX(), route[1].getY(), route[1].getZ(),
                                descent, ActionCosts.SPRINT_ONE_BLOCK)));
            }
        }
    }

    /** 比べる終点選び。{@code budget}がfalseなら上限を効かせない＝この変更の前の挙動。 */
    private record Rule(String name, boolean budget) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("上限なし(旧)", false),
            new Rule("上限あり(現行)", true));

    /**
     * ガイドの質 × 終点選びの行列。<b>知りたいのは絶対値ではなく向き</b>——
     * ガイドが良くなるほど倍率が下がる規則があるか。
     */
    @Test
    void comparesFallbackRulesAcrossGuideQuality() throws IOException {
        FakeCells cells = NetherLiveWalkTest.terrain();
        for (BlockPos[] route : NetherLiveWalkTest.routes()) {
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            System.out.printf(Locale.ROOT, "%n%s→%s 基準%.0f%n",
                    route[0].toShortString(), route[1].toShortString(), best);
            for (Guide guide : matrixGuides(cells, route[0], route[1])) {
                for (Rule rule : RULES) {
                    System.out.printf(Locale.ROOT, "  %-14s %-14s %s%n", guide.name(), rule.name(),
                            walk(cells, route, guide.guide(), rule, best));
                }
            }
        }
    }

    private static String walk(FakeCells cells, BlockPos[] route, CostToGo guide, Rule rule, double best) {
        AStarPathfinder.fallbackBudgetEnabled = rule.budget();
        try {
            long began = System.currentTimeMillis();
            ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, route[0], route[1],
                    NetherLiveWalkTest.WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR,
                    ProgressiveWalk.Aim.GOAL, guide);
            long took = (System.currentTimeMillis() - began) / 1000;
            if (trace.steps().isEmpty()) {
                return "未到達: " + trace.stopped() + String.format(Locale.ROOT, " (%ds)", took);
            }
            double cost = ProgressiveWalk.cost(trace.steps());
            return String.format(Locale.ROOT, "%6.0f(%.3f倍) 繋ぎ目%2d 解き直し%d/%d (%ds)",
                    cost, cost / best, trace.joints().size(), trace.repairsTaken(),
                    trace.repairAttempts(), took);
        } finally {
            AStarPathfinder.fallbackBudgetEnabled = true;
        }
    }

    private interface Estimate {
        double at(int x, int y, int z);
    }

    /** 経路上の{@code h / 真値}の平均・中央値・最大と、そこから決まる終点選びの無駄許容度λ。 */
    private static void report(String name, List<BlockPos> at, List<Double> remaining, Estimate h) {
        List<Double> ratios = new ArrayList<>();
        for (int i = 0; i < at.size(); i++) {
            double truth = remaining.get(i);
            if (truth < MIN_REMAINING_TICKS) {
                continue;
            }
            BlockPos pos = at.get(i);
            ratios.add(h.at(pos.getX(), pos.getY(), pos.getZ()) / truth);
        }
        if (ratios.isEmpty()) {
            System.out.printf(Locale.ROOT, "  %-16s 測る区間が無い%n", name);
            return;
        }
        List<Double> sorted = new ArrayList<>(ratios);
        sorted.sort(Double::compareTo);
        double mean = ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = sorted.get(sorted.size() / 2);
        System.out.printf(Locale.ROOT, "  %-16s k平均%.2f 中央%.2f 最小%.2f 最大%.2f → λ(c=1.5)=%s%n",
                name, mean, median, sorted.get(0), sorted.get(sorted.size() - 1), lambda(median));
    }

    /** {@code argmin(h + g/c)}が実際に最大化している{@code g - λw}のλ。 */
    private static String lambda(double k) {
        double denominator = k - 1.0 / 1.5;
        if (denominator <= 0) {
            return "発散(前へ出るほど良い)";
        }
        return String.format(Locale.ROOT, "%.2f", k / denominator);
    }
}
