package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.MinecartState;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.rail.RailKind;
import net.prason.xaeronav.rail.TrackShape;

/** 線路があるときに、トロッコで走る経路を選ぶか・選ばないか。 */
class CartRidePathTest {

    private static final int Y = 64;

    /** z=0の列に石の床を敷き、その上の{@code 0..length-1}に東西のレールを置く。{@code poweredEvery}本おきに通電パワード。 */
    private static FakeCells line(int length, int poweredEvery) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-40, Y - 16, -8, length + 40, Y + 16, 8));
        for (int x = -40; x < length + 40; x++) {
            cells.set(x, Y - 1, 0, FakeCells.STONE);
        }
        for (int x = 0; x < length; x++) {
            boolean powered = poweredEvery > 0 && x % poweredEvery == 0;
            cells.rail(x, Y, 0, TrackShape.EAST_WEST, powered ? RailKind.POWERED : RailKind.RAIL, powered);
        }
        return cells;
    }

    private static PathResult search(FakeCells cells, int fromX, int toX) {
        return new AStarPathfinder(cells).search(new BlockPos(fromX, Y, 0), new BlockPos(toX, Y, 0), () -> false);
    }

    private static long rideSteps(PathResult result) {
        return result.steps().stream().filter(step -> step.movement() == MovementType.CART).count();
    }

    private static double totalCost(PathResult result) {
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    @Test
    void ridesAPoweredLineWhenCarryingAMinecart() {
        PathResult result = search(line(250, 8).minecart(MinecartState.carrying()), 0, 249);

        assertTrue(result.complete());
        assertEquals(249, rideSteps(result), "乗る点の次から終点まで走る");
        // 歩けば249×3.56≈887tick。全速2.5tick/ブロックに乗る手間と降りる手間が乗る
        assertTrue(totalCost(result) < 249 * 2.5 + ActionCosts.CART_BOARD_TICKS + ActionCosts.CART_STOW_TICKS + 60,
                "cost " + totalCost(result));
    }

    @Test
    void walksWithoutAMinecart() {
        PathResult result = search(line(250, 8), 0, 249);

        assertTrue(result.complete());
        assertEquals(0, rideSteps(result));
    }

    @Test
    void walksAShortLineBecauseBoardingCostsMoreThanItSaves() {
        PathResult result = search(line(20, 8).minecart(MinecartState.carrying()), 0, 19);

        assertTrue(result.complete());
        assertEquals(0, rideSteps(result));
    }

    @Test
    void walksAlongAnUnpoweredLineThatCanOnlyBePushed() {
        PathResult result = search(line(250, 0).minecart(MinecartState.carrying()), 0, 249);

        assertTrue(result.complete());
        assertEquals(0, rideSteps(result));
    }

    @Test
    void ridesOnWithoutBoardingAgainWhenAlreadyInACart() {
        FakeCells cells = line(250, 8).minecart(new MinecartState(true, true, 100, Y, 0, 2.0, 1.0, 0.0));

        PathResult result = search(cells, 100, 249);

        assertTrue(result.complete());
        assertEquals(149, rideSteps(result));
        assertTrue(result.steps().get(0).cost() < ActionCosts.CART_BOARD_TICKS, "first " + result.steps().get(0));
    }

    @Test
    void getsOffWhereTheGoalIsBesideTheLine() {
        FakeCells cells = line(250, 8).minecart(MinecartState.carrying());
        for (int z = 1; z <= 3; z++) {
            cells.set(150, Y - 1, z, FakeCells.STONE);
        }

        PathResult result = new AStarPathfinder(cells)
                .search(new BlockPos(0, Y, 0), new BlockPos(150, Y, 3), () -> false);

        assertTrue(result.complete());
        int lastRide = -1;
        for (int i = 0; i < result.steps().size(); i++) {
            if (result.steps().get(i).movement() == MovementType.CART) {
                lastRide = i;
            }
        }
        // 降りた先から斜めに踏み出せるので、真横の1つ手前で降りてもよい
        BlockPos alight = result.steps().get(lastRide).pos();
        assertTrue(alight.getZ() == 0 && Math.abs(alight.getX() - 150) <= 1, "alight " + alight);
    }
}
