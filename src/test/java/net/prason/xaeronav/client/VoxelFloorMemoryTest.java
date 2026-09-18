package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link NetherVoxelGuide}が覚えている床の詰め方。座標が負でもYの端でも往復できないと、
 * 覚えた床が別の場所へ化けて格子が壊れる。
 */
class VoxelFloorMemoryTest {

    private static void roundTrips(int x, int z, int y, boolean lava) {
        long packed = NetherVoxelGuide.packFloor(x, z, y, lava);
        assertEquals(x, NetherVoxelGuide.unpackX(packed), "X");
        assertEquals(z, NetherVoxelGuide.unpackZ(packed), "Z");
        assertEquals(y, NetherVoxelGuide.unpackY(packed), "Y");
        assertEquals(lava, NetherVoxelGuide.unpackLava(packed), "溶岩");
    }

    @Test
    void roundTripsNegativeCoordinates() {
        roundTrips(-591, 962, 0, true);
        roundTrips(264, -256, 127, false);
        roundTrips(0, 0, 64, true);
    }

    @Test
    void roundTripsTheEdgesOfEveryField() {
        // ネザーのワールド境界（±29,999,984 ÷ 8）と、現世の高さの端
        roundTrips(-3_749_998, 3_749_998, -64, false);
        roundTrips(3_749_998, -3_749_998, 319, true);
    }

    @Test
    void differentFloorsStayDifferent() {
        long lava = NetherVoxelGuide.packFloor(10, 20, 30, true);
        long solid = NetherVoxelGuide.packFloor(10, 20, 30, false);
        assertFalse(lava == solid, "溶岩かどうかで別の値になること");
        assertTrue(NetherVoxelGuide.packFloor(10, 20, 30, true)
                == NetherVoxelGuide.packFloor(10, 20, 30, true), "同じ床は同じ値になること");
    }
}
