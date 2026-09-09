package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.WindowedCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * ネザーの長距離を、読み込み窓・持ち越し・継ぎ足し・繋ぎ目の解き直し込みで測る。
 * 3D粗層（{@code VoxelCostToGo}）を掛けた場合と、掛けない場合を比較する。
 *
 * <p>3D粗層へ渡す床は{@link XaeroMapModel}が作る——<b>Xaeroが持つのと同じ「柱ごと・
 * レイヤーごとの床Y」だけ</b>で、天井や岩の形は渡さない。完全な3次元地形から作った理想の
 * ガイド（{@code measuresTheIdealFloorOnlyGuide}）とは別物で、そちらは伸びしろの上限。
 *
 * <p>窓の中でも保存地形を完全に知るオフライン検証であり、実機のtick・描画は再現しない。
 * 通常の区間探索は重み1.5で、実機の初回並列探索（通常重み1.2）とは異なる。
 * 初回APIは別メソッドで直接検査する。落下許容6の比較設定を使用し、
 * ユーザーの2026-09-08保存時の落下許容0とは異なる。
 * ProgressiveWalkの継ぎ足し予算には本体との違いが残る。
 */
@Tag("slow")
class NetherLiveWalkTest {

    private static final int WINDOW_RADIUS = 160;

    /** ネザーの実際の高さ。フィクスチャの書き出し範囲は天井の上まで含むので、そのままは使えない。 */
    static final int NETHER_MIN_Y = 0;
    static final int NETHER_MAX_Y = 127;

    private static List<BlockPos[]> routes() {
        return List.of(
                new BlockPos[] {new BlockPos(-447, 74, 525), new BlockPos(-259, 65, 379)},
                new BlockPos[] {new BlockPos(-505, 71, 836), new BlockPos(-538, 67, 496)},
                new BlockPos[] {new BlockPos(-317, 44, 567), new BlockPos(-523, 66, 465)},
                new BlockPos[] {new BlockPos(-474, 69, 629), new BlockPos(-271, 73, 482)});
    }

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load("/nether_wide.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static String ratio(ProgressiveWalk.Trace trace, double best) {
        if (trace.steps().isEmpty()) {
            return "未到達: " + trace.stopped();
        }
        double cost = ProgressiveWalk.cost(trace.steps());
        return String.format(Locale.ROOT, "%6.0f(%.3f倍) 繋ぎ目%d 解き直し%d/%d 描き変わり%d(足元%d)",
                cost, cost / best, trace.joints().size(), trace.repairsTaken(),
                trace.repairAttempts(), trace.redraws(), trace.nearRedraws());
    }

    @Test
    void walksTheNetherTheWayTheGameDoes() throws Exception {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        for (BlockPos[] route : routes()) {
            long began = System.currentTimeMillis();
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            long mapBegan = System.currentTimeMillis();
            CostToGo guide = XaeroMapModel.guide(cells, route[0], route[1],
                    NETHER_MIN_Y, NETHER_MAX_Y, 1.0, 0L);
            System.out.printf(Locale.ROOT, "  3D粗層を組むのに%dms%n",
                    System.currentTimeMillis() - mapBegan);
            ProgressiveWalk.Trace voxel = ProgressiveWalk.trace(cells, route[0], route[1],
                    WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide);
            ProgressiveWalk.Trace plain = ProgressiveWalk.trace(cells, route[0], route[1],
                    WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL);
            report.add(String.format(Locale.ROOT,
                    "%s→%s 基準%6.0f%n  3D粗層 %s%n  ガイド無し %s%n  (%.0f秒)",
                    route[0].toShortString(), route[1].toShortString(), best,
                    ratio(voxel, best), ratio(plain, best),
                    (System.currentTimeMillis() - began) / 1000.0));
            System.out.println(report.get(report.size() - 1));
            assertTrue(Double.isFinite(best), "基準の探索が完走していない");
            assertTrue(!voxel.steps().isEmpty(), "3D粗層で到達できなくなった: " + report.getLast());
        }
        assertTrue(!report.isEmpty(), "1本も測れていない");
    }

    /** 初回検索の実機APIを直接通す。継ぎ足しを測るtraceとは重み・呼び出しが異なる。 */
    @Test
    void usesFullInitialBudgetOnRecordedNetherTerrain() throws Exception {
        FakeCells cells = terrain();
        SearchLimits normal = new SearchLimits(100_000, 30_000, 1.2);
        SearchLimits deep = new SearchLimits(800_000, 30_000, 1.5);
        for (BlockPos[] route : routes()) {
            var box = ProgressiveWalk.searchBox(cells, route[0], route[1], WINDOW_RADIUS);
            WindowedCells view = new WindowedCells(cells, route[0], WINDOW_RADIUS, box);
            assertTrue(!box.contains(route[1].getX(), route[1].getY(), route[1].getZ()));
            // Before the fix, the returned normal pass was limited to 40%. The
            // subsequent retries could not finish and their partial paths were discarded.
            PathResult oldPass = new AStarPathfinder(view, new SearchLimits(40_000, 30_000, 1.2))
                    .search(route[0], route[1], () -> false);
            PathResult fullPass = new AStarPathfinder(view, normal)
                    .search(route[0], route[1], () -> false);
            long began = System.currentTimeMillis();
            PathResult actual = new PathfindingExecutor().submitWithDeepFallback(view,
                    new WindowedCells(cells, route[0], WINDOW_RADIUS, box),
                    route[0], route[1], normal, deep, true, 0).get(40, TimeUnit.SECONDS);
            assertEquals(fullPass.termination(), actual.termination());
            assertEquals(fullPass.steps().stream().map(PathStep::pos).toList(),
                    actual.steps().stream().map(PathStep::pos).toList());
            System.out.printf(Locale.ROOT,
                    "初回 %s→%s: 旧40%%=%d steps / %d nodes, 修正後=%d steps / %d nodes (%dms)%n",
                    route[0].toShortString(), route[1].toShortString(),
                    oldPass.steps().size(), oldPass.expandedNodes(),
                    actual.steps().size(), actual.expandedNodes(), System.currentTimeMillis() - began);
        }
    }

    /**
     * 3D粗層の<b>伸びしろの上限</b>。地形を完全に知り、床の位置だけを渡した理想のガイド。
     * Xaeroの実データはこれより疎なので、この値を本番の保証値として読まないこと。
     */
    @Test
    void measuresTheIdealFloorOnlyGuide() throws Exception {
        FakeCells cells = terrain();
        for (BlockPos[] route : routes()) {
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            assertTrue(Double.isFinite(best), "The reference route must finish");
            CostToGo guide = WideVoxelGuide.build(cells, cells.bounds(), route[1], true);
            ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, route[0], route[1],
                    WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide);
            System.out.printf(Locale.ROOT, "床の位置だけ %s→%s: %s%n",
                    route[0].toShortString(), route[1].toShortString(), ratio(trace, best));
        }
    }
}
