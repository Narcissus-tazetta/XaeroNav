package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.IntBinaryOperator;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/** 空の下で光の柱を立てる地点と、HUDの矢印の向き。 */
class SkyGuideTest {

    private static final IntBinaryOperator FLAT_64 = (x, z) -> 64;

    @Test
    void standsOnTheGoalWhenTheRouteStaysOnTheSurface() {
        BlockPos goal = new BlockPos(300, 64, 0);
        List<BlockPos> route = List.of(new BlockPos(100, 64, 0), new BlockPos(200, 65, 0));

        assertEquals(goal, SkyGuide.descentPoint(goal, route, FLAT_64));
    }

    @Test
    void standsWhereTheRouteGoesUnderground() {
        BlockPos goal = new BlockPos(400, 20, 0);
        List<BlockPos> route = List.of(new BlockPos(100, 64, 0), new BlockPos(200, 64, 0),
                new BlockPos(260, 30, 0), new BlockPos(330, 22, 0));

        assertEquals(new BlockPos(200, 64, 0), SkyGuide.descentPoint(goal, route, FLAT_64));
    }

    @Test
    void standsAboveAnUndergroundGoalWithoutCaveData() {
        // 要塞のように目的地だけが地下で、ルートは地表を通っている。目的地の真上の地表に立てる
        BlockPos goal = new BlockPos(400, 20, 0);
        List<BlockPos> route = List.of(new BlockPos(100, 64, 0), new BlockPos(200, 64, 0));

        assertEquals(new BlockPos(400, 64, 0), SkyGuide.descentPoint(goal, route, FLAT_64));
    }

    @Test
    void doesNotCallUnknownColumnsUnderground() {
        BlockPos goal = new BlockPos(400, 20, 0);
        List<BlockPos> route = List.of(new BlockPos(100, 10, 0));

        assertEquals(goal, SkyGuide.descentPoint(goal, route, (x, z) -> Integer.MIN_VALUE));
    }

    @Test
    void arrowPointsRelativeToWhereThePlayerFaces() {
        // ヨー0は南(+Z)向き。南の目的地は正面、西(-X)は右、東(+X)は左、北は後ろ
        assertEquals("↑", NavHud.bearingArrow(0, 0, 0f, 0, 100));
        assertEquals("→", NavHud.bearingArrow(0, 0, 0f, -100, 0));
        assertEquals("←", NavHud.bearingArrow(0, 0, 0f, 100, 0));
        assertEquals("↓", NavHud.bearingArrow(0, 0, 0f, 0, -100));
        // 西を向いている（ヨー90）なら、西の目的地が正面
        assertEquals("↑", NavHud.bearingArrow(0, 0, 90f, -100, 0));
        assertEquals("↗", NavHud.bearingArrow(0, 0, -720f, -100, 100));
    }
}
