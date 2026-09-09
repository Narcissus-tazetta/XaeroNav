package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/**
 * ユーザーが1週間詰まっていた実ルートの番人。<b>この線が引けないことがネザーの主症状</b>
 * （「線が途切れて同じ場所で止まる」）だったので、ここが落ちたら3D粗層が壊れている。
 *
 * <p>設定はユーザーの2026-09-08の保存に寄せてある（落下許容0・溶岩橋30）。
 * 診断で確かめた現行実装の姿:
 *
 * <pre>
 * 層1の2.5Dガイド        0ステップ（始点から1歩も動けない）
 * ガイド無し・全世界が見える  300万ノードで未到達
 * 3D粗層                 到達
 * </pre>
 *
 * <p>疎な地図（訪問済みチャンクを25〜42%まで落とす）でも到達すること——これが
 * {@code VoxelCostToGo}の目的地アンカー堅牢化が効いているかの唯一の検査。堅牢化前は
 * 起点を床に限っていたため、到達がseed次第のコインフリップになっていた。
 */
@Tag("slow")
class NetherVoxelReachTest {

    private static final BlockPos START = new BlockPos(-328, 64, 696);
    private static final BlockPos GOAL = new BlockPos(-259, 64, 379);

    /** {@code PathfindingState}の既定の描画距離相当。実機ログと同じ窓。 */
    private static final int WINDOW = 240;

    private static FakeCells terrain() throws Exception {
        return TerrainFixture.load("/nether_wide.txt.gz", b -> FakeCells.empty(b)
                .canPlaceBlocks(true).maxFallDamagePoints(0).fatalFallBlocks(23)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .avoidRiskyJumps(true).boatAvailable(true)
                .minDescentTicksPerBlock(ActionCosts.descentBoundForMaxDrop(3)));
    }

    private static VoxelCostToGo guide(FakeCells cells, double keep, long seed) {
        return XaeroMapModel.guide(cells, START, GOAL,
                NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y, keep, seed);
    }

    @Test
    void theStallRouteIsUnreachableWithoutTheVoxelLayer() throws Exception {
        FakeCells cells = terrain();
        SearchBounds box = ProgressiveWalk.searchBox(cells, START, GOAL, WINDOW);
        WindowedCells windowed = new WindowedCells(cells, START, WINDOW, box);
        CoarseMap coarse = LiveCoarseSampler.sample(windowed, windowed.bounds(), START.getY(), () -> false);
        CostToGo flat = CoarseRouter.costToGo(coarse, GOAL, false, CoarseRouter.BridgePolicy.BRIDGE);

        PathResult withFlatGuide = new AStarPathfinder(windowed,
                new SearchLimits(800_000, 60_000, 1.5), flat).search(START, GOAL, () -> false);
        assertTrue(withFlatGuide.steps().isEmpty(),
                "層1の2.5Dガイドが動くようになったなら、この番人の前提を測り直すこと: "
                        + withFlatGuide.termination() + " " + withFlatGuide.steps().size() + "手");
    }

    @Test
    void walksTheStallRouteWithTheVoxelLayer() throws Exception {
        FakeCells cells = terrain();
        record Variant(String name, double keep, long seed) { }
        // 42%・25%は、Xaeroの欠損リージョンの悲観的な近似（実データは歩いた回廊が連続で埋まる）
        Variant[] variants = {
            new Variant("全チャンク訪問済み", 1.0, 0L),
            new Variant("42% seed1", 0.42, 1L),
            new Variant("42% seed2", 0.42, 2L),
            new Variant("25% seed1", 0.25, 1L),
        };
        for (Variant variant : variants) {
            VoxelCostToGo guide = guide(cells, variant.keep(), variant.seed());
            assertNotNull(guide, "3D粗層が組めなかった: " + variant.name());
            long began = System.currentTimeMillis();
            ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, START, GOAL, WINDOW,
                    ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide);
            System.out.printf(Locale.ROOT, "%-18s -> %s cost=%.0f 手=%d (%d秒)%n", variant.name(),
                    trace.stopped().isEmpty() ? "到達" : "未到達: " + trace.stopped(),
                    ProgressiveWalk.cost(trace.steps()), trace.steps().size(),
                    (System.currentTimeMillis() - began) / 1000);
            assertTrue(!trace.steps().isEmpty(),
                    variant.name() + "の地図で歩き通せなくなった: " + trace.stopped());
        }
    }
}
