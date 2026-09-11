package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import net.prason.xaeronav.pathfinding.world.WindowedCells;

/** Saved terrain with logged foot passages supplied explicitly; does not test Xaero waypoint selection. */
@Tag("slow")
class NetherStallReproTest {
    @Test
    void recordedFootPassagesEscapeSavedPlayerStall() throws Exception {
        FakeCells cells = TerrainFixture.load("/nether_wide.txt.gz", b -> FakeCells.empty(b)
                .canPlaceBlocks(true).maxFallDamagePoints(0).fatalFallBlocks(23)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .avoidRiskyJumps(true).boatAvailable(true)
                .minDescentTicksPerBlock(ActionCosts.descentBoundForMaxDrop(3)));
        BlockPos player = new BlockPos(-328, 64, 696);
        BlockPos goal = new BlockPos(-259, 64, 379);
        BlockPos from = player;
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos[] goals = {new BlockPos(-437, 91, 671), new BlockPos(-435, 97, 598), goal};
        int waypoint = 0;
        for (int leg = 0; leg < 12 && waypoint < goals.length; leg++) {
            BlockPos target = goals[waypoint];
            WindowedCells view = new WindowedCells(cells, player, 240,
                    ProgressiveWalk.searchBox(cells, from, target, 240));
            PathResult chosen = executor.submit(view, from, target,
                    new SearchLimits(800_000, 30_000, 1.5), true).get(40, TimeUnit.SECONDS);
            System.out.println("leg=" + leg + " start=" + from + " target=" + target + " "
                    + chosen.termination() + " nodes=" + chosen.expandedNodes() + " steps="
                    + chosen.steps().size() + " end="
                    + (chosen.steps().isEmpty() ? "none" : chosen.steps().get(chosen.steps().size() - 1).pos()));
            assertFalse(chosen.steps().isEmpty(), "No progress toward logged passage " + target);
            for (PathStep step : chosen.steps()) {
                for (BlockPos dug : step.digCells()) {
                    cells.set(dug.getX(), dug.getY(), dug.getZ(), FakeCells.AIR);
                }
                BlockPos placed = step.placedBlockPos();
                if (placed != null) {
                    cells.set(placed.getX(), placed.getY(), placed.getZ(), FakeCells.BEDROCK);
                }
            }
            from = chosen.steps().get(chosen.steps().size() - 1).pos();
            player = from;
            if (chosen.complete()) {
                waypoint++;
            }
        }
        assertEquals(goals.length, waypoint, "All logged passages and final goal must be reached");
        assertEquals(goal, from, "Arrival must include the correct floor");
    }
}
