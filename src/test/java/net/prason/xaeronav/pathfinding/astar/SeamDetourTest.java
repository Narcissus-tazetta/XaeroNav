package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * 遠回りが<b>経路のどこに溜まっているか</b>を測り、繋ぎ目の直し方3通りを並べる。
 *
 * <p>経路全体の倍率（{@code ProgressiveDiscoveryTest}）では「繋ぎ目だけが悪い」のか
 * 「どこも同じくらい悪い」のかが分からない。ここは窓（{@link #WINDOW_BLOCKS}ブロックぶんの区間）を
 * ずらしながら、その区間だけを全視界で解き直して比べ、<b>繋ぎ目を含む窓とそうでない窓に分けて</b>出す。
 * 継ぎ足したままの経路では、繋ぎ目を含む窓だけが平均1.02〜1.21倍・最悪1.795倍で、含まない窓
 * （1.00〜1.05倍）とはっきり分かれる。
 *
 * <p>並べる3通りは{@link ProgressiveWalk.Mode}。<b>この番人が守っているのは
 * 「繋ぎ目だけ直す」を選んだ判断そのもの</b>——全部引き直す方が安くなったら、
 * {@code PathfindingState#repairSeam}ごと考え直す価値が出たということ。
 * 質だけでなく<b>線の描き変わり</b>も見る。「歩いているだけで案内が変わる」は一度直した症状で、
 * 質のためにそこへ戻ってはいけない。
 */
@Tag("slow")
class SeamDetourTest {

    /** 局所の遠回りを測る窓の長さ（経路に沿った距離・ブロック）。 */
    private static final double WINDOW_BLOCKS = 64.0;

    /** 窓をずらす間隔（ブロック）。 */
    private static final double STRIDE_BLOCKS = 16.0;

    private static final int RADIUS = 96;

    /**
     * 繋ぎ目を直した経路が、直さない経路よりこの割合を超えて高くなったら落とす。
     * 実測は0.88〜1.00倍（5地形すべてで安くなる）。
     */
    private static final double REPAIR_VERSUS_EXTEND_LIMIT = 1.02;

    /**
     * <b>全部引き直す方がはっきり安くなったら落とす。</b>ユーザー要望の「引き直すときは全部
     * 引き直す」を採らずに繋ぎ目だけ直すと決めた根拠がこれ——実測では引き直しても繋ぎ目の
     * 遠回りは半分しか消えず（引き直した先にも繋ぎ目ができる）、足元の線が4〜12回描き変わった。
     * 実測は0.94〜1.00倍。
     */
    private static final double REPAIR_VERSUS_REPLAN_LIMIT = 1.05;

    /** 繋ぎ目を直した経路に残ってよい局所の遠回り。実測は最悪1.093倍。 */
    private static final double REPAIRED_SEAM_WORST_LIMIT = 1.20;

    /** 足元（32ブロック以内）で線が描き変わってよい回数。実測は0〜1回、全部引き直しは4〜12回。 */
    private static final int REPAIRED_NEAR_REDRAW_LIMIT = 2;

    private record Route(String name, String resource, BlockPos start, BlockPos goal) {
    }

    private static List<Route> routes() {
        return List.of(
                new Route("地上", "/overworld_terrain_columns.txt.gz",
                        new BlockPos(30, 0, 30), new BlockPos(230, 0, 220)),
                new Route("地上2", "/overworld_terrain_columns.txt.gz",
                        new BlockPos(230, 0, 30), new BlockPos(40, 0, 210)),
                new Route("ネザー", "/nether_terrain_columns.txt.gz",
                        new BlockPos(-180, 0, -180), new BlockPos(-20, 0, -20)),
                new Route("ネザー2", "/nether_terrain_columns.txt.gz",
                        new BlockPos(-20, 0, -180), new BlockPos(-180, 0, -30)),
                new Route("エンド", "/end_terrain_columns.txt.gz",
                        new BlockPos(1160, 0, 1240), new BlockPos(1260, 0, 1160)));
    }

    private static FakeCells terrain(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record Window(int from, int to, double ratio, boolean seam) {
    }

    private static List<Window> windows(FakeCells all, ProgressiveWalk.Trace trace) {
        List<PathStep> steps = trace.steps();
        double[] along = new double[steps.size()];
        double[] cost = new double[steps.size()];
        for (int i = 1; i < steps.size(); i++) {
            along[i] = along[i - 1] + distance(steps.get(i - 1).pos(), steps.get(i).pos());
            cost[i] = cost[i - 1] + steps.get(i).cost();
        }
        List<Window> result = new ArrayList<>();
        double nextStart = 0.0;
        for (int i = 0; i < steps.size(); i++) {
            if (along[i] < nextStart) {
                continue;
            }
            int to = -1;
            for (int j = i + 1; j < steps.size(); j++) {
                if (along[j] - along[i] >= WINDOW_BLOCKS) {
                    to = j;
                    break;
                }
            }
            if (to < 0) {
                break;
            }
            nextStart = along[i] + STRIDE_BLOCKS;
            double best = ProgressiveWalk.fullVisibilityBest(all, steps.get(i).pos(), steps.get(to).pos());
            if (!Double.isFinite(best) || best <= 0) {
                continue;
            }
            boolean seam = false;
            for (int joint : trace.joints()) {
                if (joint > i && joint < to) {
                    seam = true;
                    break;
                }
            }
            result.add(new Window(i, to, (cost[to] - cost[i]) / best, seam));
        }
        return result;
    }

    private static String summarize(String label, List<Window> windows, List<PathStep> steps) {
        if (windows.isEmpty()) {
            return label + " 窓なし";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%s 窓%d本", label, windows.size()));
        for (boolean seam : new boolean[] {true, false}) {
            List<Window> subset = windows.stream().filter(w -> w.seam() == seam).toList();
            String kind = seam ? "繋ぎ目あり" : "繋ぎ目なし";
            if (subset.isEmpty()) {
                out.append(String.format(Locale.ROOT, " | %s 0本", kind));
                continue;
            }
            double mean = subset.stream().mapToDouble(Window::ratio).average().orElse(0);
            Window worst = subset.stream().max((a, b) -> Double.compare(a.ratio(), b.ratio())).orElseThrow();
            out.append(String.format(Locale.ROOT, " | %s %d本 平均%.3f 最悪%.3f@%s",
                    kind, subset.size(), mean, worst.ratio(),
                    steps.get(worst.from()).pos().toShortString()));
        }
        return out.toString();
    }

    private static String label(ProgressiveWalk.Mode mode) {
        return switch (mode) {
            case EXTEND -> "継ぎ足し";
            case REPLAN -> "全部引き直し";
            case REPAIR -> "繋ぎ目だけ直す";
        };
    }

    @Test
    void repairingSeamsBeatsBothExtendingAndReplanning() throws IOException {
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Route route : routes()) {
            FakeCells all = terrain(route.resource());
            SearchBounds bounds = all.bounds();
            BlockPos start = TerrainFixture.onGround(all, bounds, route.start());
            BlockPos goal = TerrainFixture.onGround(all, bounds, route.goal());
            Map<ProgressiveWalk.Mode, ProgressiveWalk.Trace> traces = new EnumMap<>(ProgressiveWalk.Mode.class);
            for (ProgressiveWalk.Mode mode : ProgressiveWalk.Mode.values()) {
                ProgressiveWalk.Trace trace = ProgressiveWalk.trace(all, start, goal, RADIUS, mode);
                if (trace.steps().isEmpty()) {
                    failures.add(route.name() + " " + label(mode) + " が目的地まで届かなかった");
                    continue;
                }
                traces.put(mode, trace);
                report.add(summarize(String.format(Locale.ROOT,
                                "%s %s 全体%.0f 繋ぎ目%d箇所 描き変わり%d回(近く%d回, 計%.0fブロック)"
                                        + " 修復%d/%d回 展開%dノード",
                                route.name(), label(mode), ProgressiveWalk.cost(trace.steps()),
                                trace.joints().size(), trace.redraws(), trace.nearRedraws(),
                                trace.redrawnBlocks(), trace.repairsTaken(), trace.repairAttempts(),
                                trace.repairNodes()),
                        windows(all, trace), trace.steps()));
            }
            ProgressiveWalk.Trace repaired = traces.get(ProgressiveWalk.Mode.REPAIR);
            if (repaired == null) {
                continue;
            }
            double cost = ProgressiveWalk.cost(repaired.steps());
            for (ProgressiveWalk.Mode other : List.of(ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Mode.REPLAN)) {
                ProgressiveWalk.Trace trace = traces.get(other);
                double limit = other == ProgressiveWalk.Mode.EXTEND
                        ? REPAIR_VERSUS_EXTEND_LIMIT : REPAIR_VERSUS_REPLAN_LIMIT;
                if (trace != null && cost > ProgressiveWalk.cost(trace.steps()) * limit) {
                    failures.add(String.format(Locale.ROOT, "%s: 繋ぎ目だけ直すと %.0f で、%sの %.0f より高い",
                            route.name(), cost, label(other), ProgressiveWalk.cost(trace.steps())));
                }
            }
            double worst = windows(all, repaired).stream().filter(Window::seam)
                    .mapToDouble(Window::ratio).max().orElse(1.0);
            if (worst > REPAIRED_SEAM_WORST_LIMIT) {
                failures.add(String.format(Locale.ROOT, "%s: 直したあとも繋ぎ目に %.3f倍が残っている",
                        route.name(), worst));
            }
            if (repaired.nearRedraws() > REPAIRED_NEAR_REDRAW_LIMIT) {
                failures.add(String.format(Locale.ROOT, "%s: 足元で線が%d回描き変わっている（上限%d回）",
                        route.name(), repaired.nearRedraws(), REPAIRED_NEAR_REDRAW_LIMIT));
            }
        }
        System.out.println(String.join("\n", report));
        assertTrue(failures.isEmpty(), String.join("\n", failures) + "\n" + String.join("\n", report));
    }
}
