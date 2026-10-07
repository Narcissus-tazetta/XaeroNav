package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/** 馬の仲間・ラクダに乗ったままで通れる道。 */
class MountRoutesTest {

    private static final int Y = 64;
    private static final int MIN_X = -10;
    private static final int MAX_X = 30;
    private static final int MIN_Z = -10;
    private static final int MAX_Z = 10;

    private static final MountState HORSE = new MountState(MountState.Kind.HORSE, 0.225, 0.5);
    private static final MountState JUMPER = new MountState(MountState.Kind.HORSE, 0.225, 1.0);
    private static final MountState CAMEL = new MountState(MountState.Kind.CAMEL, 0.09, 0.42);

    /** 岩盤の平らな床（上に立つ高さが{@link #Y}）。 */
    private static FakeCells flat() {
        FakeCells cells = FakeCells.empty(new SearchBounds(MIN_X, Y - 20, MIN_Z, MAX_X, Y + 20, MAX_Z));
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                cells.set(x, Y - 1, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /** {@code x}に全幅の岩盤の壁を立て、{@code z}の範囲だけ高さ{@code height}の口を空ける。 */
    private static void wallWithOpening(FakeCells cells, int x, int fromZ, int toZ, int height) {
        for (int z = MIN_Z; z <= MAX_Z; z++) {
            for (int y = Y; y < Y + 8; y++) {
                cells.set(x, y, z, FakeCells.BEDROCK);
            }
        }
        opening(cells, x, fromZ, toZ, height);
    }

    private static void opening(FakeCells cells, int x, int fromZ, int toZ, int height) {
        for (int z = fromZ; z <= toZ; z++) {
            for (int y = Y; y < Y + height; y++) {
                cells.set(x, y, z, FakeCells.AIR);
            }
        }
    }

    private static PathResult search(FakeCells cells, int fromX, int toX) {
        return new AStarPathfinder(cells).search(new BlockPos(fromX, Y, 0), new BlockPos(toX, Y, 0), () -> false);
    }

    private static boolean ridesAllTheWay(PathResult result) {
        return result.complete() && result.steps().stream().allMatch(step -> step.movement() == MovementType.MOUNT);
    }

    /** 着くまでに1回だけ、{@code x}より手前で降りる。 */
    private static boolean getsOffBefore(PathResult result, int x) {
        List<PathStep> dismounts = result.steps().stream()
                .filter(step -> step.movement() == MovementType.DISMOUNT).toList();
        return result.complete() && dismounts.size() == 1 && dismounts.get(0).pos().getX() < x;
    }

    private static boolean passesCell(PathResult result, int x, int z) {
        return result.steps().stream().flatMap(step -> step.bodyCells().stream())
                .anyMatch(cell -> cell.getX() == x && cell.getZ() == z);
    }

    @Test
    void horseTakesTheTwoWideOpeningAndWalkerTheNarrowOne() {
        FakeCells cells = flat();
        wallWithOpening(cells, 10, 0, 0, 3);
        opening(cells, 10, 6, 7, 3);

        PathResult walker = search(cells, 0, 20);
        PathResult horse = search(cells.mount(HORSE), 0, 20);

        assertTrue(walker.complete());
        assertTrue(passesCell(walker, 10, 0), "徒歩は近い1マス幅を通る");
        assertTrue(horse.complete());
        assertFalse(passesCell(horse, 10, 0), "馬は1マス幅を通れない");
        assertTrue(passesCell(horse, 10, 6) && passesCell(horse, 10, 7));
        assertTrue(horse.steps().stream().allMatch(step -> step.movement() == MovementType.MOUNT));
    }

    @Test
    void horseNeedsThreeBlocksOfHeadroom() {
        FakeCells low = flat();
        wallWithOpening(low, 10, -1, 0, 2);
        FakeCells high = flat();
        wallWithOpening(high, 10, -1, 0, 3);

        assertTrue(search(low, 0, 20).complete(), "徒歩は高さ2で通れる");
        assertTrue(getsOffBefore(search(low.mount(HORSE), 0, 20), 10), "乗り手の目が天井に入るので手前で降りる");
        assertTrue(ridesAllTheWay(search(high.mount(HORSE), 0, 20)));
    }

    @Test
    void camelNeedsFourBlocksOfHeadroom() {
        FakeCells three = flat();
        wallWithOpening(three, 10, -1, 0, 3);
        FakeCells four = flat();
        wallWithOpening(four, 10, -1, 0, 4);

        assertTrue(getsOffBefore(search(three.mount(CAMEL), 0, 20), 10));
        assertTrue(ridesAllTheWay(search(four.mount(CAMEL), 0, 20)));
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
    void jumpsOnlyAsHighAsItsJumpStrength() {
        BlockPos start = new BlockPos(0, Y, 0);
        BlockPos goal = new BlockPos(20, Y + 3, 0);

        PathResult weak = new AStarPathfinder(ledge(10, 3).mount(HORSE)).search(start, goal, () -> false);
        PathResult strong = new AStarPathfinder(ledge(10, 3).mount(JUMPER)).search(start, goal, () -> false);

        assertFalse(weak.complete(), "跳躍力0.5は1.7マスまで");
        assertTrue(strong.complete(), "跳躍力1.0は5マス以上届く");
        assertTrue(strong.steps().stream().anyMatch(step -> step.pos().getY() == Y + 3 && step.cost() > 3 * 3.0));
    }

    @Test
    void stepsUpOneBlockWithoutJumping() {
        PathResult result = new AStarPathfinder(ledge(10, 1).mount(HORSE))
                .search(new BlockPos(0, Y, 0), new BlockPos(20, Y + 1, 0), () -> false);

        assertTrue(result.complete());
    }

    /** {@code x}から手前を{@code drop}段高い台地にして、そこから降りる。 */
    private static PathResult dropDown(int drop, MountState mount) {
        FakeCells cells = flat();
        for (int x = MIN_X; x < 10; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                for (int y = Y; y < Y + drop; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        cells.mount(mount);
        return new AStarPathfinder(cells).search(new BlockPos(0, Y + drop, 0), new BlockPos(20, Y, 0), () -> false);
    }

    @Test
    void fallsSixBlocksUnhurtButNotSeven() {
        assertTrue(dropDown(6, HORSE).complete(), "馬は6マスまで無傷");
        assertFalse(dropDown(7, HORSE).complete(), "7マスはダメージ1で、既定の許容量0を超える");
        assertFalse(dropDown(6, MountState.NONE).complete(), "徒歩は3マスまで");
    }

    /** {@code x}から{@code width}列の川。{@code ford}が真なら{@code z=6..7}だけ浅瀬。 */
    private static FakeCells river(int x, int width, int depth, boolean ford) {
        FakeCells cells = flat();
        for (int cx = x; cx < x + width; cx++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                int bottom = ford && (z == 6 || z == 7) ? Y - 1 : Y - depth;
                for (int y = bottom; y < Y; y++) {
                    cells.set(cx, y, z, FakeCells.WATER);
                }
                cells.set(cx, bottom - 1, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    @Test
    void getsOffBeforeWaterEvenAtAFord() {
        FakeCells forded = river(10, 10, 3, true).jumpGapEnabled(false).mount(HORSE);
        FakeCells narrow = river(10, 3, 3, false).mount(HORSE);

        for (FakeCells cells : List.of(forded, narrow)) {
            PathResult result = search(cells, 0, 25);
            assertTrue(getsOffBefore(result, 10), "浅瀬も、跳べば越えられる川も、乗ったままは渡らない");
            for (PathStep step : result.steps()) {
                if (step.movement() == MovementType.MOUNT) {
                    assertTrue(step.bodyCells().stream().noneMatch(cell -> CellData.water(
                            cells.cell(cell.getX(), cell.getY() - 1, cell.getZ()))), step.pos().toString());
                }
            }
        }
    }

    @Test
    void getsOffBeforeAOneWideTunnel() {
        FakeCells cells = flat();
        wallWithOpening(cells, 10, 0, 0, 3);

        assertTrue(getsOffBefore(search(cells.mount(HORSE), 0, 20), 10));
    }

    @Test
    void ridesAroundUnlessLeavingTheHorseIsFree() {
        // 2つの壁の2マス幅の口を互い違いに置き、乗ったままだとジグザグに約45ブロック、歩けば1マス幅の口を抜けて20ブロック
        FakeCells cells = flat();
        wallWithOpening(cells, 8, 0, 0, 3);
        opening(cells, 8, 8, 9, 3);
        wallWithOpening(cells, 13, 0, 0, 3);
        opening(cells, 13, -9, -8, 3);

        assertTrue(ridesAllTheWay(search(cells.mount(HORSE), 0, 20)), "既定の割増なら遠回りしても乗ったまま");
        assertTrue(getsOffBefore(search(cells.mount(HORSE).mountLeaveBehindTicks(0), 0, 20), 8),
                "割増0なら近い1マス幅の口を歩いて通る");
    }

    @Test
    void ridingCostsTheHorsesOwnPace() {
        double walking = totalCost(search(flat(), 0, 20));
        PathResult ridden = search(flat().mount(HORSE), 0, 20);
        double average = totalCost(ridden);
        double fastest = totalCost(search(flat().mount(new MountState(MountState.Kind.HORSE, 0.3375, 0.5)), 0, 20));

        // 定常の歩み0.225×0.98/0.454 b/t
        assertTrue(ridesAllTheWay(ridden));
        assertEquals(ridden.steps().size() * 0.454 / (0.225 * 0.98), average, 1e-6, "1歩1ブロックで直進");
        assertTrue(fastest < average && average < walking, fastest + " < " + average + " < " + walking);
    }

    @Test
    void ridesAroundAPondRatherThanSwimmingIt() {
        // 幅5の池が床をほぼ塞ぎ、乗ったままだと端の乾いた帯まで回り込む
        FakeCells cells = flat();
        for (int x = 8; x <= 12; x++) {
            for (int z = MIN_Z; z <= 5; z++) {
                for (int y = Y - 4; y < Y; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
                cells.set(x, Y - 5, z, FakeCells.BEDROCK);
            }
        }

        assertTrue(ridesAllTheWay(search(cells.mount(HORSE).mountLeaveBehindTicks(0), 0, 20)),
                "置いていく割増が無くても、回り込む方が降りて泳ぐより速い");
    }

    private static double totalCost(PathResult result) {
        assertTrue(result.complete());
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    @Test
    void startsOnFootWhenTheHorseIsInDeepWater() {
        FakeCells cells = river(10, 6, 3, false).mount(HORSE);

        PathResult result = new AStarPathfinder(cells).search(new BlockPos(12, Y - 1, 0), new BlockPos(25, Y, 0), () -> false);

        assertTrue(result.complete());
        assertTrue(result.steps().get(0).movement() == MovementType.DISMOUNT, "まず降りると言う");
        assertTrue(result.steps().stream().skip(1).noneMatch(step -> step.movement() == MovementType.MOUNT
                || step.movement() == MovementType.DISMOUNT));
    }

    /** {@code x}から{@code width}列の深い谷（底まで10マス）。 */
    private static FakeCells ravine(int x, int width) {
        FakeCells cells = flat();
        for (int cx = x; cx < x + width; cx++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                cells.set(cx, Y - 1, z, FakeCells.AIR);
                cells.set(cx, Y - 11, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    @Test
    void jumpsAThreeWideRavine() {
        PathResult result = search(ravine(10, 3).mount(HORSE), 0, 20);

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() == Y), "谷へ降りずに跳び越える");
    }

    @Test
    void doesNotJumpWhenJumpingIsDisabled() {
        PathResult result = search(ravine(10, 3).jumpGapEnabled(false).mount(HORSE), 0, 20);

        assertFalse(result.complete(), "底まで10マスはダメージ2で降りられず、跳ぶほかに渡れない");
    }

    /** 起伏のある地形を歩いた経路の体のセルが、どれも掘らずに通れる（掘らない・置かないので、塞がっていたら経路が嘘）。 */
    @Test
    void bodyCellsOfRoutesOverRollingHillsAreAllOpen() {
        int checkedSteps = 0;
        for (int seed = 1; seed <= 20; seed++) {
            FakeCells cells = FakeCells.empty(new SearchBounds(MIN_X, Y - 20, MIN_Z, MAX_X, Y + 20, MAX_Z));
            Random random = new Random(seed);
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    int top = Y - 1 + random.nextInt(2);
                    for (int y = Y - 3; y <= top; y++) {
                        cells.set(x, y, z, FakeCells.BEDROCK);
                    }
                }
            }
            cells.mount(JUMPER);

            PathResult result = new AStarPathfinder(cells)
                    .search(new BlockPos(0, Y + 1, 0), new BlockPos(20, Y, 6), () -> false);

            for (PathStep step : result.steps()) {
                checkedSteps++;
                for (BlockPos cell : step.bodyCells()) {
                    assertTrue(CellData.occupiableWithoutDigging(cells.cell(cell.getX(), cell.getY(), cell.getZ())),
                            "seed " + seed + " step " + step.pos() + " cell " + cell);
                }
            }
        }
        assertTrue(checkedSteps > 100);
    }

    /**
     * 斜めに1段下りる手。角の2列は下りた先の高さでは塞がっていてよい（体はまだ上の段にいる）——
     * そこを体のセルに数えると、経路の検証が「地形が変わった」と取り違えて経路を捨てる。
     */
    @Test
    void diagonalDescentDoesNotClaimTheLowCornersAsBodyCells() {
        FakeCells cells = flat();
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                if (x <= 0 || z <= 0) {
                    cells.set(x, Y, z, FakeCells.BEDROCK);
                }
            }
        }
        cells.mount(HORSE);

        PathResult result = new AStarPathfinder(cells).search(new BlockPos(0, Y + 1, 0), new BlockPos(2, Y, 2), () -> false);

        assertTrue(result.complete());
        assertTrue(result.steps().size() == 1, "斜めに1手で下りる");
        for (BlockPos cell : result.steps().get(0).bodyCells()) {
            assertTrue(CellData.occupiableWithoutDigging(cells.cell(cell.getX(), cell.getY(), cell.getZ())), cell.toString());
        }
    }
}
