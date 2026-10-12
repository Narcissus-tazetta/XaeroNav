package net.prason.xaeronav.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** 水平座標の組を詰めるキーの往復と、ビットの並び（レールの保存形式が頼っている）。 */
class PackedXZTest {

    @Test
    void negativeAndExtremeCoordinatesRoundTrip() {
        int[] values = {0, 1, -1, 1875000, -1875000, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : values) {
            for (int z : values) {
                long key = PackedXZ.pack(x, z);
                assertEquals(x, PackedXZ.x(key));
                assertEquals(z, PackedXZ.z(key));
            }
        }
    }

    @Test
    void negativeZDoesNotBleedIntoX() {
        assertNotEquals(PackedXZ.pack(0, -1), PackedXZ.pack(-1, -1));
        assertEquals(0, PackedXZ.x(PackedXZ.pack(0, -1)));
    }

    @Test
    void xIsTheHighHalfAndZTheLowHalf() {
        assertEquals(0x0000_0003_0000_0005L, PackedXZ.pack(3, 5));
        assertEquals(0xFFFF_FFFF_0000_0002L, PackedXZ.pack(-1, 2));
    }
}
