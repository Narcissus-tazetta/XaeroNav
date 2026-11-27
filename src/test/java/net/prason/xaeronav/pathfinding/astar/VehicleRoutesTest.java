package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/** Create Aeronauticsの車（幅×幅の足場）に乗ったままで通れる道。 */
class VehicleRoutesTest {

    private static final int Y = 64;
    private static final int MIN_X = -12;
    private static final int MAX_X = 36;
    private static final int MIN_Z = -14;
    private static final int MAX_Z = 14;
    private static final int HEIGHT = 4;

    private static MountState car(int width) {
        return MountState.vehicle(width, HEIGHT);
    }

    private static FakeCells flat() {
        FakeCells cells = FakeCells.empty(new SearchBounds(MIN_X, Y - 20, MIN_Z, MAX_X, Y + 20, MAX_Z));
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                cells.set(x, Y - 1, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /** {@code x}に全幅の壁を立て、{@code fromZ..toZ}だけ口を空ける。 */
    private static void wallWithOpening(FakeCells cells, int x, int fromZ, int toZ) {
        for (int z = MIN_Z; z <= MAX_Z; z++) {
            for (int y = Y; y < Y + 8; y++) {
                cells.set(x, y, z, z >= fromZ && z <= toZ ? FakeCells.AIR : FakeCells.BEDROCK);
            }
        }
    }

    private static PathResult search(FakeCells cells, int toX) {
        return new AStarPathfinder(cells).search(new BlockPos(0, Y, 0), new BlockPos(toX, Y, 0), () -> false);
    }

    private static boolean drivesAllTheWay(PathResult result) {
        return result.complete() && !result.steps().isEmpty()
                && result.steps().stream().allMatch(step -> step.movement() == MovementType.DRIVE);
    }

    private static boolean getsOutBefore(PathResult result, int x) {
        List<PathStep> dismounts = result.steps().stream()
                .filter(step -> step.movement() == MovementType.DISMOUNT).toList();
        return result.complete() && dismounts.size() == 1 && dismounts.get(0).pos().getX() < x;
    }

    private static boolean passesCell(PathResult result, int x, int z) {
        return result.steps().stream().flatMap(step -> step.bodyCells().stream())
                .anyMatch(cell -> cell.getX() == x && cell.getZ() == z);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 7})
    void takesTheOpeningAsWideAsTheCar(int width) {
        FakeCells cells = flat();
        // 近くに1マス狭い口、遠くに車幅ちょうどの口
        wallWithOpening(cells, 12, -(width - 1) / 2, width - 2 - (width - 1) / 2);
        for (int z = 6; z < 6 + width; z++) {
            for (int y = Y; y < Y + 8; y++) {
                cells.set(12, y, z, FakeCells.AIR);
            }
        }

        PathResult result = search(cells.mount(car(width)), 26);

        assertTrue(drivesAllTheWay(result), "広い口を回って最後まで車で行く");
        assertTrue(passesCell(result, 12, 6) && passesCell(result, 12, 5 + width), "車幅の口を通る");
        assertFalse(passesCell(result, 12, 0), "狭い口は通らない");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 7})
    void getsOutWhereOnlyANarrowerGapLeads(int width) {
        FakeCells cells = flat();
        wallWithOpening(cells, 12, 0, width - 2);

        assertTrue(getsOutBefore(search(cells.mount(car(width)), 26), 12), "行ける所まで車で行き、降りて歩く");
    }

    /** {@code x}から先を{@code rise}段高い台地にする。 */
    private static FakeCells ledge(int x, int rise) {
        FakeCells cells = flat();
        for (int cx = x; cx <= MAX_X; cx++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                for (int y = Y; y < Y + rise; y++) {
                    cells.set(cx, y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    @Test
    void climbsOneStepButNotTwo() {
        BlockPos start = new BlockPos(0, Y, 0);

        PathResult one = new AStarPathfinder(ledge(10, 1).mount(car(5))).search(start, new BlockPos(20, Y + 1, 0), () -> false);
        PathResult two = new AStarPathfinder(ledge(10, 2).mount(car(5))).search(start, new BlockPos(20, Y + 2, 0), () -> false);

        assertTrue(drivesAllTheWay(one), "1段は車のまま上がる");
        // 岩盤の2段は徒歩でも登れない地形。車のまま上の段へ着く手が無いことだけを見る
        assertFalse(two.steps().stream().anyMatch(step -> step.movement() == MovementType.DRIVE && step.pos().getY() >= Y + 2),
                "2段は車では上がらない");
    }

    @Test
    void drivesAroundWaterInsteadOfThroughIt() {
        FakeCells cells = flat();
        // 道の真ん中に池。北側を回れる
        for (int x = 8; x <= 16; x++) {
            for (int z = -8; z <= 4; z++) {
                cells.set(x, Y - 1, z, FakeCells.WATER);
            }
        }

        PathResult result = search(cells.mount(car(3)), 26);

        assertTrue(drivesAllTheWay(result));
        assertFalse(passesCell(result, 12, 0), "水には入らない");
    }

    @Test
    void doesNotHangOffANarrowBridge() {
        FakeCells cells = flat();
        // x=8..16は奈落。真ん中に1マス幅の橋だけ架かっている
        for (int x = 8; x <= 16; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                for (int y = Y - 20; y < Y; y++) {
                    cells.set(x, y, z, FakeCells.AIR);
                }
            }
            cells.set(x, Y - 1, 0, FakeCells.BEDROCK);
        }

        PathResult walker = search(cells, 26);
        PathResult car = search(cells.mount(car(5)), 26);

        assertTrue(walker.complete(), "徒歩は橋を渡る");
        assertTrue(getsOutBefore(car, 9), "幅5の車は1マス幅の橋に乗らず、手前で降りる");
    }

    @Test
    void oneWideCarUsesTheOneWideBridge() {
        FakeCells cells = flat();
        for (int x = 8; x <= 16; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                for (int y = Y - 20; y < Y; y++) {
                    cells.set(x, y, z, FakeCells.AIR);
                }
            }
            cells.set(x, Y - 1, 0, FakeCells.BEDROCK);
        }

        assertTrue(drivesAllTheWay(search(cells.mount(car(1)), 26)));
    }

    @Test
    void needsHeadroomForTheWholeBody() {
        FakeCells cells = flat();
        // 天井の低いトンネル（高さ3）。車の高さは4
        for (int x = 10; x <= 14; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                cells.set(x, Y + 3, z, FakeCells.BEDROCK);
            }
        }

        assertTrue(getsOutBefore(search(cells.mount(car(3)), 26), 10));
    }
}
