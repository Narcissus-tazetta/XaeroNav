package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/** 継ぎ足しが手前へ戻ってきたときの切り落とし。 */
class TurnBackTest {

    private static final List<Vec3> AHEAD = List.of(new Vec3(0, 64, 0), new Vec3(100, 64, 0), new Vec3(200, 64, 0));

    @Test
    void goingOnIsNotCut() {
        List<Vec3> extension = List.of(new Vec3(200, 64, 0), new Vec3(300, 64, 40));

        assertNull(TurnBack.cut(AHEAD, extension, (a, b) -> true));
    }

    @Test
    void cutsTheExcursionWhereTheExtensionComesBack() {
        // 末端(200,0)まで行って、(60,10)の近くへ戻ってから北へ抜ける。行って戻る区間を消し、(60,0)付近で繋ぐ
        List<Vec3> extension = List.of(new Vec3(200, 64, 0), new Vec3(200, 64, 30), new Vec3(60, 64, 10),
                new Vec3(60, 64, 200));

        TurnBack.Cut cut = TurnBack.cut(AHEAD, extension, (a, b) -> true);

        assertNotNull(cut);
        Vec3 joint = cut.aheadKept().get(cut.aheadKept().size() - 1);
        assertTrue(joint.x < 100, "戻ってきた点より先で繋いでいる: " + joint);
        assertEquals(new Vec3(60, 64, 200), cut.rest().get(cut.rest().size() - 1));
    }

    @Test
    void doesNotCutThroughAWall() {
        List<Vec3> extension = List.of(new Vec3(200, 64, 0), new Vec3(200, 64, 30), new Vec3(60, 64, 10),
                new Vec3(60, 64, 200));

        assertNull(TurnBack.cut(AHEAD, extension, (a, b) -> false));
    }
}
