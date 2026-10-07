package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class GhastFlightTest {

    /** バニラのハッピーガストの{@code FLYING_SPEED}。 */
    private static final double VANILLA_FLYING_SPEED = 0.05;

    private static final GhastFlight GHAST = GhastFlight.of(VANILLA_FLYING_SPEED);

    private static final SearchBounds BOUNDS = new SearchBounds(-160, 0, -160, 160, 128, 160);
    private static final int CELL = 4;

    @Test
    void cruisesAtAboutThreeAndAHalfBlocksPerSecond() {
        assertEquals(0.1806, GHAST.cruise(), 1.0e-3);
        assertEquals(GHAST.cruise() / 2.0, GHAST.lift(), 1.0e-9);
    }

    @Test
    void climbsForFreeWhileJumpKeepsUpWithForwardFlight() {
        double level = GHAST.segmentTicks(100.0, 0.0);
        assertEquals(100.0 / GHAST.cruise(), level, 1.0e-9);
        assertEquals(level, GHAST.segmentTicks(100.0, 45.0), 1.0e-9, "ジャンプで間に合う登りに値段が付いている");
        assertTrue(GHAST.segmentTicks(100.0, 60.0) > level, "ジャンプで間に合わない登りが無料になっている");
        assertEquals(GHAST.segmentTicks(100.0, 0.0), GHAST.segmentTicks(100.0, -0.0), 1.0e-9);
        assertTrue(GHAST.segmentTicks(100.0, -30.0) > level, "下りは視線の向きに進むだけで速くならない");
    }

    @Test
    void straightUpUsesForwardAndJumpTogether() {
        assertEquals(10.0 / (GHAST.cruise() + GHAST.lift()), GHAST.segmentTicks(0.0, 10.0), 1.0e-9);
        assertEquals(10.0 / GHAST.cruise(), GHAST.segmentTicks(0.0, -10.0), 1.0e-9);
    }

    @Test
    void aStraightSegmentIsNeverMoreExpensiveThanABentOne() {
        for (double endY = -60.0; endY <= 60.0; endY += 15.0) {
            for (double viaX = -40.0; viaX <= 140.0; viaX += 20.0) {
                for (double viaY = -80.0; viaY <= 80.0; viaY += 10.0) {
                    double bent = GHAST.segmentTicks(Math.abs(viaX), viaY)
                            + GHAST.segmentTicks(Math.abs(100.0 - viaX), endY - viaY);
                    double bound = GHAST.lowerBoundTicks(100.0, endY, endY);
                    assertTrue(bound <= bent + 1.0e-9,
                            "折れ線が直線より安い: 経由(" + viaX + "," + viaY + ") 終点" + endY + " → " + bent + " < " + bound);
                }
            }
        }
    }

    @Test
    void lowerBoundIsTheCheapestHeightInTheRange() {
        for (double low = -80.0; low <= 80.0; low += 10.0) {
            for (double high = low; high <= low + 40.0; high += 10.0) {
                double bound = GHAST.lowerBoundTicks(60.0, low, high);
                for (double v = low; v <= high; v += 1.0) {
                    assertTrue(bound <= GHAST.segmentTicks(60.0, v) + 1.0e-9,
                            "下限が幅の中の高さ" + v + "より高い: [" + low + "," + high + "]");
                }
            }
        }
    }

    @Test
    void avoidsASlotTooLowForTheGhastButFineForElytra() {
        // X=0の壁に、近くの低い横穴（高さ4）と遠くの高い穴を開ける。エリトラは近い横穴、ガストは遠い穴を通る
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int x = -160; x <= 160; x++) {
            for (int z = -160; z <= 160; z++) {
                for (int y = 0; y <= 32; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
                for (int y = 120; y <= 128; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        for (int y = 33; y < 120; y++) {
            for (int z = -160; z <= 160; z++) {
                boolean lowSlot = z >= 32 && z < 48 && y >= 64 && y < 68;
                boolean tallGap = z >= -120 && z < -96;
                if (!lowSlot && !tallGap) {
                    cells.set(0, y, z, FakeCells.STONE);
                    cells.set(1, y, z, FakeCells.STONE);
                }
            }
        }
        Vec3 start = new Vec3(-60.0, 66.0, 40.0);
        Vec3 goal = new Vec3(60.0, 66.0, 40.0);

        FlightRoute elytra = new FlightPathfinder(new AirGrid(cells, CELL), FlightModel.elytra(true),
                SearchLimits.DEFAULT, 0.0).search(start, goal, 6.0);
        AirGrid ghastGrid = new AirGrid(cells, CELL, GHAST.body());
        FlightRoute ghast = new FlightPathfinder(ghastGrid, GHAST, SearchLimits.DEFAULT, 0.0)
                .search(start, goal, 6.0);

        assertTrue(elytra.complete(), "エリトラが届いていない: " + elytra.termination());
        assertTrue(elytra.points().stream().allMatch(point -> point.z > 0.0),
                "エリトラが近い横穴を使っていない: " + elytra.points());
        assertTrue(ghast.complete(), "ガストが届いていない: " + ghast.termination());
        assertTrue(ghast.points().stream().anyMatch(point -> point.z < -90.0),
                "ガストが体の入らない横穴を通っている: " + ghast.points());
        for (int i = 0; i + 1 < ghast.points().size(); i++) {
            assertTrue(ghastGrid.clearLine(ghast.points().get(i), ghast.points().get(i + 1)),
                    "ガストの経路が体の入らない所を通っている: " + ghast.points());
        }
    }
}
