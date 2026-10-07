package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/** オウムガイに乗ったままで進める水中の道。 */
class NautilusRoutesTest {

    private static final int Y = 64;
    private static final MountState NAUTILUS = new MountState(MountState.Kind.NAUTILUS, 1.0, 0.0);
    /** 定常速度0.325ブロック/tick（{@code 0.0325×速さ/(1-0.9)}）。 */
    private static final double NAUTILUS_TICKS_PER_BLOCK = 1.0 / 0.325;

    /** 岩盤で埋めた世界。掘って抜ける道を作らせない。 */
    private static FakeCells bedrock() {
        return FakeCells.empty(new SearchBounds(-5, Y - 5, -5, 80, Y + 15, 5)).fillWith(FakeCells.BEDROCK);
    }

    private static void fill(FakeCells cells, int fromX, int toX, int fromY, int toY, char symbol) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = fromY; y <= toY; y++) {
                    cells.set(x, y, z, symbol);
                }
            }
        }
    }

    private static PathResult search(FakeCells cells, BlockPos from, BlockPos to) {
        return new AStarPathfinder(cells).search(from, to, () -> false);
    }

    private static List<PathStep> dismounts(PathResult result) {
        return result.steps().stream().filter(step -> step.movement() == MovementType.DISMOUNT).toList();
    }

    private static double cost(PathResult result) {
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    @Test
    void ridesThroughOpenWaterAtTheNautilusPace() {
        FakeCells cells = bedrock();
        fill(cells, 0, 40, Y, Y + 3, FakeCells.WATER);
        fill(cells, 0, 40, Y + 4, Y + 8, FakeCells.AIR);
        BlockPos from = new BlockPos(0, Y, 0);
        BlockPos to = new BlockPos(40, Y, 0);

        PathResult riding = search(cells.mount(NAUTILUS), from, to);

        assertTrue(riding.complete(), riding.termination().name());
        assertTrue(riding.steps().stream().allMatch(step -> step.movement() == MovementType.MOUNT_SWIM),
                "降りずに乗ったまま進む: " + riding.steps());
        assertEquals(40 * NAUTILUS_TICKS_PER_BLOCK, cost(riding), 1.0e-3);
        assertTrue(cost(riding) < 40 * ActionCosts.SWIM_ONE_BLOCK);
    }

    @Test
    void breathDoesNotRunOutWhileRiding() {
        FakeCells cells = bedrock().maxSubmergedTicks(250);
        // 水面の無い長いトンネル。泳ぐと70×5.6≒390tickで息の上限を超える
        fill(cells, 0, 70, Y, Y + 2, FakeCells.WATER);
        BlockPos from = new BlockPos(0, Y, 0);
        BlockPos to = new BlockPos(70, Y, 0);

        assertFalse(search(cells, from, to).complete(), "泳いでは息が続かない");
        PathResult riding = search(cells.mount(NAUTILUS), from, to);
        assertTrue(riding.complete(), riding.termination().name());
        assertTrue(dismounts(riding).isEmpty());
    }

    @Test
    void getsOffBeforeATunnelOnlyTwoBlocksHigh() {
        FakeCells cells = bedrock();
        fill(cells, 0, 10, Y, Y + 2, FakeCells.WATER);
        fill(cells, 11, 20, Y, Y + 1, FakeCells.WATER);
        fill(cells, 21, 30, Y, Y + 2, FakeCells.WATER);

        PathResult riding = search(cells.mount(NAUTILUS), new BlockPos(0, Y, 0), new BlockPos(30, Y, 0));

        assertTrue(riding.complete(), riding.termination().name());
        List<PathStep> dismounts = dismounts(riding);
        assertEquals(1, dismounts.size(), "降りるのは1回だけ: " + riding.steps());
        assertTrue(dismounts.get(0).pos().getX() <= 11, "高さ2の手前で降りる: " + dismounts.get(0).pos());
        int at = riding.steps().indexOf(dismounts.get(0));
        assertTrue(riding.steps().subList(0, at).stream().allMatch(step -> step.movement() == MovementType.MOUNT_SWIM));
        assertTrue(riding.steps().subList(at + 1, riding.steps().size()).stream()
                .noneMatch(step -> step.movement() == MovementType.MOUNT_SWIM), "降りた後に乗り直している");
    }

    @Test
    void getsOffAtTheShoreAndWalksOn() {
        FakeCells cells = bedrock();
        fill(cells, 0, 20, Y, Y + 3, FakeCells.WATER);
        fill(cells, 0, 30, Y + 4, Y + 8, FakeCells.AIR);

        PathResult riding = search(cells.mount(NAUTILUS), new BlockPos(0, Y, 0), new BlockPos(28, Y + 4, 0));

        assertTrue(riding.complete(), riding.termination().name());
        List<PathStep> dismounts = dismounts(riding);
        assertEquals(1, dismounts.size(), riding.steps().toString());
        assertTrue(dismounts.get(0).pos().getX() >= 19, "岸の近くまで乗っていく: " + dismounts.get(0).pos());
    }
}
