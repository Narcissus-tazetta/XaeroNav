package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class VoxelCostToGoTest {

    private static SearchBounds box() {
        return new SearchBounds(0, 0, 0, 127, 63, 127);
    }

    /** 1本の廊下だけに床を置き、そこから外れるほど高くなることを見る。 */
    private static VoxelTerrain corridor() {
        VoxelTerrain terrain = VoxelTerrain.of(box(), VoxelTerrain.DEFAULT_CELL_BLOCKS, false);
        for (int x = 0; x <= 127; x++) {
            terrain.markFloor(x, 64, 15, false);
        }
        return terrain;
    }

    @Test
    void followsTheFloorInsteadOfTheStraightLine() {
        VoxelCostToGo guide = VoxelCostToGo.build(corridor(), new BlockPos(120, 16, 64), () -> false);
        assertNotNull(guide);
        // 廊下の上は走るだけ。同じ距離でも、床の無い所は橋を架ける値段になる
        double onFloor = guide.estimate(8, 16, 64);
        double offFloor = guide.estimate(8, 16, 8);
        assertTrue(offFloor > onFloor * 2.0,
                "床から外れた見積もりが安すぎる: 床の上=" + onFloor + " 外=" + offFloor);
    }

    @Test
    void neverReportsMoreThanZeroAtTheGoal() {
        VoxelCostToGo guide = VoxelCostToGo.build(corridor(), new BlockPos(120, 16, 64), () -> false);
        assertEquals(0.0, guide.estimate(120, 16, 64));
    }

    /**
     * 箱の外は縁の値＋そこまでの直線。0を返すと縁が崖になり、A*が「箱の外の方が安い」と
     * 読んで経路から離れる向きへ展開する。
     */
    @Test
    void doesNotDropToZeroOutsideTheBox() {
        VoxelCostToGo guide = VoxelCostToGo.build(corridor(), new BlockPos(120, 16, 64), () -> false);
        double atEdge = guide.estimate(0, 16, 64);
        double outside = guide.estimate(-40, 16, 64);
        assertTrue(outside >= atEdge, "箱の外が縁より安い: 縁=" + atEdge + " 外=" + outside);
    }

    /** 床が1枚も無くても表は作れる。ここで諦めるとガイド無しへ落ちて症状が戻る。 */
    @Test
    void anchorsEvenWhenNoFloorIsKnown() {
        VoxelTerrain empty = VoxelTerrain.of(box(), VoxelTerrain.DEFAULT_CELL_BLOCKS, false);
        VoxelCostToGo guide = VoxelCostToGo.build(empty, new BlockPos(120, 16, 64), () -> false);
        assertNotNull(guide, "床が無いだけでガイドを諦めてはいけない");
        assertTrue(guide.reachableCells() > empty.cellCount() / 2);
        assertTrue(guide.estimate(0, 16, 64) > 0.0);
    }

    /** 目的地のセルが溶岩でも、周りの床を起点にできる。 */
    @Test
    void anchorsNextToTheGoalWhenItsOwnCellIsBlocked() {
        VoxelTerrain terrain = VoxelTerrain.of(box(), VoxelTerrain.DEFAULT_CELL_BLOCKS, false);
        terrain.markFloor(64, 64, 15, true);
        terrain.markFloor(76, 64, 15, false);
        VoxelCostToGo guide = VoxelCostToGo.build(terrain, new BlockPos(64, 16, 64), () -> false);
        assertNotNull(guide);
        assertTrue(guide.estimate(76, 16, 64) < guide.estimate(4, 16, 4),
                "近くの床を起点にできていない");
    }

    @Test
    void refusesAGoalOutsideTheBox() {
        assertNull(VoxelCostToGo.build(corridor(), new BlockPos(400, 16, 64), () -> false));
    }

    @Test
    void stopsWhenCancelled() {
        assertNull(VoxelCostToGo.build(corridor(), new BlockPos(120, 16, 64), () -> true));
    }
}
