package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class VoxelTerrainTest {

    private static final SearchBounds BOX = new SearchBounds(0, 0, 0, 63, 63, 63);

    @Test
    void unknownCellsStayPassable() {
        VoxelTerrain terrain = VoxelTerrain.of(BOX, 4, false);
        assertEquals(VoxelTerrain.OPEN, terrain.kindAt(terrain.indexOfBlock(32, 32, 32)),
                "地図に無い場所を壁にすると、そこにある迂回路ごと消える");
    }

    @Test
    void marksTheCellAboveTheFloor() {
        VoxelTerrain terrain = VoxelTerrain.of(BOX, 4, false);
        terrain.markFloor(8, 8, 19, false);
        assertEquals(VoxelTerrain.STANDABLE, terrain.kindAt(terrain.indexOfBlock(8, 20, 8)));
        assertEquals(1, terrain.floorMarks());
    }

    /**
     * 溶岩は橋の設定に関わらず記録する。渡ってよい設定でも、溶岩の海と「地図が無いだけの場所」を
     * 同じ値段にすると迂回すべき向きが消える。
     */
    @Test
    void lavaIsRecordedWhicheverWayBridgingIsSet() {
        for (boolean bridging : new boolean[] {false, true}) {
            VoxelTerrain terrain = VoxelTerrain.of(BOX, 4, bridging);
            terrain.markFloor(8, 8, 19, true);
            assertEquals(VoxelTerrain.LAVA, terrain.kindAt(terrain.indexOfBlock(8, 20, 8)),
                    "溶岩橋=" + bridging);
            assertEquals(1, terrain.floorMarks(), "溶岩橋=" + bridging);
        }
    }

    /** 同じセルに床と溶岩が混じったら床が勝つ。書いた順に依らないこと。 */
    @Test
    void floorWinsOverLavaInTheSameCell() {
        VoxelTerrain lavaFirst = VoxelTerrain.of(BOX, 4, false);
        lavaFirst.markFloor(8, 8, 19, true);
        lavaFirst.markFloor(10, 10, 19, false);
        VoxelTerrain floorFirst = VoxelTerrain.of(BOX, 4, false);
        floorFirst.markFloor(10, 10, 19, false);
        floorFirst.markFloor(8, 8, 19, true);
        assertEquals(VoxelTerrain.STANDABLE, lavaFirst.kindAt(lavaFirst.indexOfBlock(8, 20, 8)));
        assertEquals(VoxelTerrain.STANDABLE, floorFirst.kindAt(floorFirst.indexOfBlock(8, 20, 8)));
    }

    @Test
    void ignoresFloorsOutsideTheBox() {
        VoxelTerrain terrain = VoxelTerrain.of(BOX, 4, false);
        terrain.markFloor(8, 8, 200, false);
        terrain.markFloor(-40, 8, 19, false);
        assertEquals(0, terrain.floorMarks());
    }

    /**
     * 箱のYは<b>床のある範囲</b>で決まり、次元の全高では決まらないこと。
     *
     * <p>次元がその中身より高いと（実機のネザーは高さ256だった）、岩盤天井より上の空きが
     * 格子の半分を占め、ガイドが「天井の上を橋で走る」道を描いて歩けなくなる。
     */
    @Test
    void theBoxFollowsTheMappedFloorsNotTheDimensionHeight() {
        LevelHeightAccessor tall = LevelHeightAccessor.create(0, 256);
        SearchBounds box = VoxelTerrain.boxFor(tall, new BlockPos(0, 64, 0),
                new BlockPos(100, 64, 100), 30, 70);
        assertEquals(30 - VoxelTerrain.VERTICAL_MARGIN_BLOCKS, box.minY());
        assertEquals(70 + VoxelTerrain.VERTICAL_MARGIN_BLOCKS, box.maxY());
    }

    /** 次元の高さは上限として効く。床の周りの余白がそれを超えて広がってはいけない。 */
    @Test
    void theBoxNeverLeavesTheDimension() {
        LevelHeightAccessor nether = LevelHeightAccessor.create(0, 128);
        SearchBounds box = VoxelTerrain.boxFor(nether, new BlockPos(0, 10, 0),
                new BlockPos(100, 120, 100), 4, 124);
        assertEquals(0, box.minY());
        assertEquals(127, box.maxY());
    }

    /** 始点と目的地は必ず箱の中。目的地が外だとガイドの起点が決まらず、表が丸ごと空になる。 */
    @Test
    void theBoxAlwaysHoldsTheStartAndTheGoal() {
        LevelHeightAccessor tall = LevelHeightAccessor.create(0, 256);
        SearchBounds box = VoxelTerrain.boxFor(tall, new BlockPos(0, 200, 0),
                new BlockPos(100, 12, 100), 40, 60);
        assertTrue(box.contains(0, 200, 0), "始点が箱の外: " + box);
        assertTrue(box.contains(100, 12, 100), "目的地が箱の外: " + box);
    }

    /** 遠い目的地で確保量が爆発しないこと。セルを粗くして吸収する。 */
    @Test
    void coarsensTheGridForFarGoals() {
        SearchBounds wide = new SearchBounds(0, 0, 0, 4000, 127, 4000);
        int cell = VoxelTerrain.cellBlocksFor(wide);
        assertTrue(cell > VoxelTerrain.DEFAULT_CELL_BLOCKS, "粗くなっていない: " + cell);
        VoxelTerrain terrain = VoxelTerrain.of(wide, true);
        assertNotNull(terrain);
        assertTrue(terrain.cellCount() <= 500_000, "セル数=" + terrain.cellCount());
    }
}
