package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class FlightPathfinderTest {

    private static final SearchBounds BOUNDS = new SearchBounds(-160, 0, -160, 160, 128, 160);
    private static final int CELL = 4;
    private static final double GOAL_RADIUS = 6.0;

    private static FlightRoute route(FakeCells cells, Vec3 start, Vec3 goal, boolean rockets) {
        return new FlightPathfinder(new AirGrid(cells, CELL), rockets, SearchLimits.DEFAULT)
                .search(start, goal, GOAL_RADIUS);
    }

    /** 天井と床のあるネザー状の空間。{@code floor}以下と{@code ceiling}以上を岩で埋める。 */
    private static FakeCells nether(int floor, int ceiling) {
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int x = -160; x <= 160; x++) {
            for (int z = -160; z <= 160; z++) {
                for (int y = 0; y <= floor; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
                for (int y = ceiling; y <= 128; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    /** 折れ線の全区間が飛行可なセルだけを通っているか。 */
    private static boolean staysInOpenAir(FlightRoute route, FakeCells cells) {
        AirGrid grid = new AirGrid(cells, CELL);
        for (int i = 0; i + 1 < route.points().size(); i++) {
            if (!grid.clearLine(route.points().get(i), route.points().get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    private static double maxY(FlightRoute route) {
        return route.points().stream().mapToDouble(Vec3::y).max().orElse(Double.NaN);
    }

    @Test
    void openSkyCollapsesToASingleStraightSegment() {
        FlightRoute route = route(FakeCells.empty(BOUNDS), new Vec3(-100.0, 64.0, 0.0),
                new Vec3(100.0, 64.0, 0.0), false);

        assertTrue(route.complete(), "何も無い空で目的地に届いていない: " + route.termination());
        assertEquals(2, route.points().size(), "平滑化しても折れが残っている: " + route.points());
    }

    @Test
    void threadsThroughTheOnlyGapInAWall() {
        FakeCells cells = nether(32, 120);
        // X=0 に天井まで届く壁。Z=40 付近だけ開けてある
        for (int y = 33; y < 120; y++) {
            for (int z = -160; z <= 160; z++) {
                if (z >= 36 && z <= 48) {
                    continue;
                }
                cells.set(0, y, z, FakeCells.STONE);
                cells.set(1, y, z, FakeCells.STONE);
            }
        }

        FlightRoute route = route(cells, new Vec3(-100.0, 80.0, 0.0), new Vec3(100.0, 80.0, 0.0), false);

        assertTrue(route.complete(), "唯一の隙間を抜けられていない: " + route.termination());
        assertTrue(staysInOpenAir(route, cells), "経路が岩を貫いている: " + route.points());
        double gapZ = route.points().stream()
                .filter(point -> Math.abs(point.x) < 8.0)
                .mapToDouble(Vec3::z)
                .findFirst()
                .orElse(Double.NaN);
        assertTrue(gapZ >= 32.0 && gapZ <= 52.0, "壁を通る位置が隙間からずれている: z=" + gapZ);
    }

    @Test
    void goesAroundRatherThanOverWhenTheCeilingIsInTheWay() {
        // ネザーの本質。壁は天井まで届いているので「上を越える」が原理的に選べない
        FakeCells cells = nether(32, 96);
        for (int y = 33; y < 96; y++) {
            for (int z = -160; z <= 60; z++) {
                cells.set(0, y, z, FakeCells.STONE);
                cells.set(1, y, z, FakeCells.STONE);
            }
        }

        FlightRoute route = route(cells, new Vec3(-100.0, 64.0, 0.0), new Vec3(100.0, 64.0, 0.0), false);

        assertTrue(route.complete(), "壁を回り込めていない: " + route.termination());
        assertTrue(staysInOpenAir(route, cells), "経路が岩を貫いている: " + route.points());
        assertTrue(maxY(route) < 96.0, "岩盤天井より上を通る経路が出ている: " + maxY(route));
        assertTrue(route.points().stream().anyMatch(point -> point.z > 55.0),
                "壁の端（z>60）を回り込んでいない: " + route.points());
    }

    @Test
    void doesNotRouteThroughUnloadedSpace() {
        // 読めない先へ経路を引いてはいけない。範囲の外は点線の担当
        FakeCells cells = FakeCells.empty(new SearchBounds(-160, 0, -160, 160, 128, 8));

        FlightRoute route = route(cells, new Vec3(0.0, 64.0, 0.0), new Vec3(0.0, 64.0, 120.0), false);

        assertFalse(route.complete(), "範囲外の目的地に届いたことになっている");
        assertTrue(route.points().stream().allMatch(point -> point.z < 8.0),
                "経路が範囲外へ伸びている: " + route.points());
    }

    @Test
    void climbsWhenItHasToRegardlessOfRockets() {
        // 上がるしか道が無い地形。ロケットの有無でコストは変わるが、どちらでも経路は出ること
        FakeCells cells = nether(32, 120);
        for (int y = 33; y <= 80; y++) {
            for (int z = -160; z <= 160; z++) {
                cells.set(0, y, z, FakeCells.STONE);
                cells.set(1, y, z, FakeCells.STONE);
            }
        }

        for (boolean rockets : new boolean[] {false, true}) {
            FlightRoute route = route(cells, new Vec3(-100.0, 40.0, 0.0), new Vec3(100.0, 40.0, 0.0), rockets);

            assertTrue(route.complete(), "ロケット" + rockets + "で壁を越えられていない: " + route.termination());
            assertTrue(staysInOpenAir(route, cells), "経路が岩を貫いている: " + route.points());
            assertTrue(maxY(route) > 80.0, "壁を越えていない: " + maxY(route));
        }
    }

    @Test
    void prefersDescendingOverStayingLevelWhenBothAreOpen() {
        // 「水平飛行はすでに登り」。同じ場所へ行けるなら、滑空で降りられる方が安い
        FlightRoute route = route(FakeCells.empty(BOUNDS), new Vec3(-100.0, 100.0, 0.0),
                new Vec3(100.0, 40.0, 0.0), false);

        assertTrue(route.complete());
        List<Vec3> points = route.points();
        for (int i = 0; i + 1 < points.size(); i++) {
            assertTrue(points.get(i + 1).y <= points.get(i).y + 1.0e-6,
                    "降りていく途中で登り返している: " + points);
        }
    }

    @Test
    void reportsNoRouteFromInsideSolidRock() {
        FakeCells cells = FakeCells.empty(BOUNDS).fillWith(FakeCells.STONE);

        assertTrue(route(cells, new Vec3(0.0, 64.0, 0.0), new Vec3(100.0, 64.0, 0.0), false).isEmpty());
    }
}
