package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 継ぎ足しが狙う先の選び方。<b>「先」は列の添字（＝ルートの順序）で決まる</b>ことを固定する。
 */
class FlightExtensionTargetTest {

    private static final Vec3 GOAL = new Vec3(400.0, 64.0, 0.0);

    @Test
    void picksTheFarthestWaypointWithinReach() {
        List<BlockPos> waypoints = List.of(
                new BlockPos(64, 64, 0), new BlockPos(128, 64, 0),
                new BlockPos(192, 64, 0), new BlockPos(256, 64, 0));

        Vec3 target = PathfindingState.flightExtensionTarget(new Vec3(0.0, 64.0, 0.0), GOAL, waypoints, 140.0);

        assertEquals(128.5, target.x, 1.0e-6, "届く範囲で最も遠い点を選べていない");
    }

    @Test
    void doesNotTurnBackToAWaypointThatIsCloserToTheGoalButBehind() {
        // ルートがコの字に曲がっている。末端(0,64,300)から見ると、後ろの(0,64,0)の方が
        // 直線距離ではゴールに近い——「ゴールに近い方」で選ぶと経路が引き返す
        List<BlockPos> waypoints = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 150),
                new BlockPos(0, 64, 300),
                new BlockPos(80, 64, 300));
        Vec3 tail = new Vec3(0.0, 64.0, 300.0);

        Vec3 target = PathfindingState.flightExtensionTarget(tail, GOAL, waypoints, 200.0);

        assertTrue(target.z > 200.0, "後ろの中間目標へ引き返している: " + target);
        assertEquals(80.5, target.x, 1.0e-6);
    }

    @Test
    void aimsStraightAtTheGoalWhenItIsWithinReach() {
        Vec3 target = PathfindingState.flightExtensionTarget(new Vec3(300.0, 64.0, 0.0), GOAL,
                List.of(new BlockPos(64, 64, 0)), 200.0);

        assertEquals(GOAL, target);
    }

    @Test
    void fallsBackToAPointTowardTheGoalWithNoWaypoints() {
        // 未訪問領域では長距離ルートが無い。目的地の方向へleadぶん進んだ点を狙う
        Vec3 target = PathfindingState.flightExtensionTarget(new Vec3(0.0, 64.0, 0.0), GOAL, List.of(), 100.0);

        assertEquals(100.0, target.x, 1.0e-6);
    }
}
