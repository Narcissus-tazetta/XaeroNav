package net.prason.xaeronav.pathfinding.navgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.rail.RailNetwork;
import net.prason.xaeronav.rail.TestRails;

/** 窓のガイドが、線路をトロッコで走る手を値に入れるか。 */
class WindowRidesTest {

    private static final int FLOOR_Y = 64;
    private static final int Y = FLOOR_Y + 1;
    private static final int RAIL_Z = 16;
    private static final BlockPos START = new BlockPos(10, Y, RAIL_Z);
    private static final BlockPos GOAL = new BlockPos(245, Y, RAIL_Z);

    /** 長さ256・幅32の平らな床。線路は z=16 の x=20..235。 */
    private static final RailNetwork LINE = new TestRails().eastWest(20, 235, Y, RAIL_Z, 8).network();

    private static WindowField field(WindowRides rides) {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 48, 0, 255, 96, 31));
        for (int x = 0; x < 256; x++) {
            for (int z = 0; z < 32; z++) {
                for (int y = 48; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
            }
        }
        NavGraph graph = new NavGraph(GOAL, 48, 96);
        LoadedArea everything = LoadedArea.square(128, 16, 1 << 20);
        long[] keys = graph.missingSections(128, 16, 128, everything);
        assertTrue(graph.build(cells, keys, 0, keys.length, everything, () -> false));
        WindowField field = graph.field(128, 16, 128, FarField.UNKNOWN, rides, () -> false);
        assertNotNull(field);
        return field;
    }

    private static double atStart(WindowField field) {
        return field.estimate(START.getX(), START.getY(), START.getZ());
    }

    @Test
    void ridingTheLineIsCheaperThanWalkingWhenCarryingAMinecart() {
        double walking = atStart(field(WindowRides.NONE));
        WindowField riding = field(new WindowRides(LINE, true, LongSets.EMPTY_SET));

        // 歩けば235×3.56≈838tick。215ブロックを全速2.5tickで走り、乗り降りの74tickを払っても約150tick安い
        assertTrue(atStart(riding) < walking - 100, atStart(riding) + " vs " + walking);

        List<BlockPos> trail = new ArrayList<>();
        WindowField.Descent descent = riding.descend(START.getX(), START.getY(), START.getZ(),
                (x, y, z) -> trail.add(new BlockPos(x, y, z)));
        assertNotNull(descent);
        assertTrue(descent.reachedGoal());
        assertEquals(atStart(riding), descent.inside(), 1e-6, "辿った値段がガイドの値と一致する");
        assertTrue(trail.stream().anyMatch(pos -> pos.getX() == 200 && pos.getZ() == RAIL_Z),
                "線路の上を辿る: " + trail.size() + " points");
    }

    @Test
    void usesAMinecartParkedOnTheLineWithoutCarryingOne() {
        double walking = atStart(field(WindowRides.NONE));
        LongOpenHashSet parked = new LongOpenHashSet();
        parked.add(BlockPos.asLong(40, Y, RAIL_Z));

        double viaParked = atStart(field(new WindowRides(LINE, false, parked)));

        assertTrue(viaParked < walking - 100, viaParked + " vs " + walking);
    }

    @Test
    void ignoresTheLineWithoutAMinecart() {
        double walking = atStart(field(WindowRides.NONE));

        assertEquals(walking, atStart(field(new WindowRides(LINE, false, LongSets.EMPTY_SET))), 1e-9);
    }
}
