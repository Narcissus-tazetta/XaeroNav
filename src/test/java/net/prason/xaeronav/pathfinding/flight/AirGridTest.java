package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class AirGridTest {

    private static final SearchBounds BOUNDS = new SearchBounds(-64, 0, -64, 64, 128, 64);
    private static final int CELL = 4;

    private static AirGrid grid(FakeCells cells) {
        return new AirGrid(cells, CELL);
    }

    @Test
    void anAllAirCellIsFlyable() {
        assertTrue(grid(FakeCells.empty(BOUNDS)).flyable(0, 10, 0));
    }

    @Test
    void oneBlockInTheCellIsEnoughToBlockIt() {
        // 粗さそのものがクリアランス。1ブロックの出っ張りでもセルごと諦める
        FakeCells cells = FakeCells.empty(BOUNDS);
        cells.set(2, 42, 1, FakeCells.STONE);

        assertFalse(grid(cells).flyable(0, 10, 0), "セル(0,10,0)は 8..11 のYを含むので石が入っている");
    }

    @Test
    void lavaIsNotFlyable() {
        FakeCells cells = FakeCells.empty(BOUNDS);
        cells.set(1, 41, 1, FakeCells.LAVA);

        assertFalse(grid(cells).flyable(0, 10, 0));
    }

    @Test
    void unloadedChunksAreBlockedRatherThanTransparent() {
        // 未ロードを素通りさせるのはFlightLineRouter（方角を示すだけの線）の作法で、
        // 実際に辿らせる経路では逆にしなければならない
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 0, 0, 15, 15, 15));

        assertFalse(grid(cells).flyable(-1, 0, 0), "範囲外のセルが飛行可になっている");
    }

    @Test
    void clearLineSeesAWallThatSitsBetweenTwoOpenCells() {
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int y = 0; y <= 128; y++) {
            for (int z = -64; z <= 64; z++) {
                cells.set(0, y, z, FakeCells.STONE);
            }
        }
        AirGrid grid = grid(cells);

        assertFalse(grid.clearLine(new Vec3(-32.0, 40.0, 0.0), new Vec3(32.0, 40.0, 0.0)),
                "壁を挟んだ2点が「見通せる」ことになっている");
        assertTrue(grid.clearLine(new Vec3(-32.0, 40.0, 0.0), new Vec3(-8.0, 40.0, 0.0)));
    }

    @Test
    void nearestFlyableSnapsOffTheGroundToTheAirAbove() {
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int x = -64; x <= 64; x++) {
            for (int z = -64; z <= 64; z++) {
                for (int y = 0; y <= 40; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        AirGrid grid = grid(cells);

        // 目的地は「着地する地面」であることの方が多い。そのままではセルが飛行不可
        long snapped = grid.nearestFlyable(new Vec3(0.5, 40.0, 0.5), 3);

        assertTrue(snapped != AirGrid.NONE, "地面の上の空間へ寄せられていない");
        assertTrue(grid.flyable(net.minecraft.core.BlockPos.getX(snapped),
                net.minecraft.core.BlockPos.getY(snapped),
                net.minecraft.core.BlockPos.getZ(snapped)));
    }

    @Test
    void nearestFlyableGivesUpInsideSolidRock() {
        FakeCells cells = FakeCells.empty(BOUNDS).fillWith(FakeCells.STONE);

        assertTrue(grid(cells).nearestFlyable(new Vec3(0.0, 40.0, 0.0), 3) == AirGrid.NONE);
    }
}
