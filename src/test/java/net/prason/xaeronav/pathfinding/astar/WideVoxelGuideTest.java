package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class WideVoxelGuideTest {

    @Test
    void matchesAllPairsShortestPathsAcrossMixedTerrain() {
        // Floyd–Warshall is independent of the guide's priority queue. Each voxel is
        // uniformly rock, open air, or a floor, so sampling cannot change its kind.
        int side = 10;
        int count = side * side;
        Random random = new Random(20260908L);
        SearchBounds box = new SearchBounds(0, 0, 0, side * 4 - 1, 3, side * 4 - 1);
        for (int trial = 0; trial < 200; trial++) {
            FakeCells cells = FakeCells.empty(box);
            int[] kinds = new int[count];
            double[] rates = new double[count];
            for (int i = 0; i < count; i++) {
                kinds[i] = i == count - 1 ? 2 : random.nextInt(3);
                rates[i] = ActionCosts.SPRINT_ONE_BLOCK + switch (kinds[i]) {
                    case 0 -> ActionCosts.DIG_OVERHEAD_TICKS;
                    case 1 -> ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS;
                    default -> 0.0;
                };
                for (int dx = 0; dx < 4; dx++) {
                    for (int dz = 0; dz < 4; dz++) {
                        for (int y = 0; y < 4; y++) {
                            if (kinds[i] == 0 || (kinds[i] == 2 && y == 0)) {
                                cells.set((i % side) * 4 + dx, y, (i / side) * 4 + dz, '#');
                            }
                        }
                    }
                }
            }
            double[][] shortest = new double[count][count];
            for (int from = 0; from < count; from++) {
                for (int to = 0; to < count; to++) {
                    int dx = Math.abs(from % side - to % side);
                    int dz = Math.abs(from / side - to / side);
                    shortest[from][to] = from == to ? 0.0
                            : dx <= 1 && dz <= 1 ? 4 * Math.hypot(dx, dz) * rates[from]
                            : Double.POSITIVE_INFINITY;
                }
            }
            for (int via = 0; via < count; via++) {
                for (int from = 0; from < count; from++) {
                    for (int to = 0; to < count; to++) {
                        shortest[from][to] = Math.min(shortest[from][to],
                                shortest[from][via] + shortest[via][to]);
                    }
                }
            }
            WideVoxelGuide guide = WideVoxelGuide.build(cells, box,
                    new BlockPos((side - 1) * 4, 1, (side - 1) * 4));
            double slack = 4 * Math.sqrt(3) * ActionCosts.SPRINT_ONE_BLOCK;
            for (int i = 0; i < count; i++) {
                assertEquals(Math.max(0, shortest[i][count - 1] - slack),
                        guide.estimate((i % side) * 4, 1, (i / side) * 4), 1e-8,
                        "trial=" + trial + ", voxel=" + i);
            }
        }
    }
}
