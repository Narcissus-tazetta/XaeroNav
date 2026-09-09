package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.VoxelCostToGo;
import net.prason.xaeronav.pathfinding.coarse.VoxelTerrain;
import net.prason.xaeronav.pathfinding.coarse.XaeroMapModel;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>実機のXaeroが実際に持っている薄さの地図</b>で歩き通せることの番人。
 *
 * <p>他のネザーの回帰（{@code NetherVoxelReachTest}・{@code NetherLiveWalkTest}）は
 * {@link XaeroMapModel#fill(VoxelTerrain, net.prason.xaeronav.pathfinding.world.CellSource,
 * double, long)}が<b>柱ごとに最大8枚の床</b>を流す。実際のXaeroの保存はもっと薄い——洞窟レイヤーは
 * <b>プレイヤーが実際にいた高さ帯にしか書かれない</b>ので、ネザーを一定の高さで歩けば1枚しか
 * 埋まらない。実機ログ（2026-09-09）のレイヤー別内訳が{@code L4=3408}・他は全部0だった。
 *
 * <p>薄い地図では格子の「床以外」の割合が上がり、見積もりが直線距離の5.84倍まで膨らむ。
 * <b>それ自体は正常</b>（この経路の実コストは直線距離の約5.7倍）。膨らみを抑えようと表を
 * 縮めると<b>逆に歩けなくなる</b>ので、ここは「膨らみの大きさ」ではなく<b>歩き通せること</b>で守る。
 */
@Tag("slow")
class NetherThinMapGuideTest {

    private static final BlockPos START = new BlockPos(-328, 64, 696);
    private static final BlockPos GOAL = new BlockPos(-259, 64, 379);

    /** 実機の保存が持っていた唯一の洞窟レイヤー。 */
    private static final int[] LAYERS = {4};

    /** 実機ログの「既知セル=4410/6486」＝訪問済み68%。 */
    private static final double VISITED = 0.68;

    /** {@code PathfindingState}の既定の描画距離相当。 */
    private static final int WINDOW = 240;

    private static FakeCells terrain() throws Exception {
        return TerrainFixture.load("/nether_wide.txt.gz", b -> FakeCells.empty(b)
                .canPlaceBlocks(true).maxFallDamagePoints(0).fatalFallBlocks(23)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .avoidRiskyJumps(true).boatAvailable(true)
                .minDescentTicksPerBlock(ActionCosts.descentBoundForMaxDrop(3)));
    }

    private void walk(String name, VoxelTerrain grid, FakeCells cells) {
        assertNotNull(grid, name + ": 格子を組めなかった");
        VoxelCostToGo guide = VoxelCostToGo.build(grid, GOAL, () -> false);
        assertNotNull(guide, name + ": ガイドを組めなかった");
        ProgressiveWalk.Trace trace = ProgressiveWalk.trace(cells, START, GOAL, WINDOW,
                ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL, guide);
        System.out.printf(Locale.ROOT, "%-22s セル=%d 辺=%d %s -> %s 手=%d%n", name,
                grid.cellCount(), grid.cellBlocks(), grid.breakdown(),
                trace.stopped().isEmpty() ? "到達" : "未到達: " + trace.stopped(),
                trace.steps().size());
        assertTrue(!trace.steps().isEmpty(), name + "で歩き通せなくなった: " + trace.stopped());
    }

    @Test
    void walksTheStallRouteOnAMapAsThinAsTheRealSave() throws Exception {
        FakeCells cells = terrain();
        VoxelTerrain grid = VoxelTerrain.of(XaeroMapModel.guideBox(START, GOAL,
                NetherLiveWalkTest.NETHER_MIN_Y, NetherLiveWalkTest.NETHER_MAX_Y), true);
        XaeroMapModel.fill(grid, cells, LAYERS, VISITED, 1L);
        walk("洞窟レイヤー1枚・訪問68%", grid, cells);
    }

    /**
     * <b>次元の高さが歩ける高さより広くても歩けること。</b>実機ログのセル数
     * （276318と229405）の共通の約数は43しかなく、辺=6と併せると箱のYの幅は<b>256</b>——
     * ネザーの歩ける高さの2倍だった。岩盤天井より上の空きが格子の半分を占めると、
     * ガイドが「天井の上を橋で走る」道を描き、探索がそちらへ引きずられる。
     *
     * <p>実測（箱のYを次元の全高に取っていた頃）: 0..255の箱では歩き通せず、探索が
     * y=95・経路から100ブロック西で止まった——実機ログの「繋ぎ目の大回り(x=-416)」と同じ形。
     * {@code VoxelTerrain#boxFor}が床のある範囲へ絞るようになって直っている。
     */
    @Test
    void walksWhenTheDimensionIsTallerThanTheGroundItHas() throws Exception {
        FakeCells cells = terrain();
        for (int[] layers : new int[][] {null, LAYERS}) {
            walk(layers == null ? "全高256・8枚" : "全高256・レイヤー1枚",
                    XaeroMapModel.grid(cells, START, GOAL, XaeroMapModel.height(0, 255),
                            layers, VISITED, 1L),
                    cells);
        }
    }
}
