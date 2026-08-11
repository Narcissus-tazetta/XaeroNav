package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 曲がり角の検出（カーナビの「次にどちらへ曲がるか」）。
 *
 * <p>ここが暴れると案内として使い物にならない。とくに斜めに進む区間は東→北東→東…と1マスごとに
 * 向きが振れるので、1ステップずつ向きの変化を見ると「右・左・右・左」と出てしまう。
 * 帯（レグ）でまとめる仕組みが効いていることを確かめる。
 *
 * <p>+X が東、+Z が南。進行方向から見て左を向くと外積が負になる。
 */
class NavGuidanceTest {

    private static final int Y = 60;

    /** ステップ列から経路を組む。コストは1マス1tick相当の当たり障りのない値にする。 */
    private static PathResult path(List<BlockPos> positions, boolean complete) {
        List<PathStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new PathStep(pos, MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null));
        }
        return new PathResult(steps, complete, positions.size());
    }

    private static List<BlockPos> straightEast(int length) {
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= length; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        return positions;
    }

    @Test
    void aStraightRunReportsNoTurn() {
        PathResult result = path(straightEast(30), true);

        NavGuidance guidance = NavGuidance.forPath(result, new BlockPos(0, Y, 0));

        assertEquals(NavGuidance.Turn.STRAIGHT, guidance.turn);
        // 道のりは対応づいたステップから先を数える（プレイヤーの正確な位置からではない）。
        // 30ステップの経路なら steps[0]→steps[29] の29マス
        assertEquals(29, guidance.remainingBlocks);
        assertTrue(guidance.complete);
    }

    @Test
    void aRightAngleTurnIsReportedOnceWithItsDistance() {
        // 東へ10マス進んでから南へ10マス
        List<BlockPos> positions = straightEast(10);
        for (int i = 1; i <= 10; i++) {
            positions.add(new BlockPos(10, Y, i));
        }

        NavGuidance guidance = NavGuidance.forPath(path(positions, true), new BlockPos(0, Y, 0));

        assertEquals(NavGuidance.Turn.RIGHT, guidance.turn, "東から南は右折");
        assertEquals(10, guidance.turnDistance, "角までの道のり");
        assertEquals(19, guidance.remainingBlocks);
    }

    @Test
    void turningTheOtherWayIsReportedAsLeft() {
        // 東へ10マス進んでから北へ10マス
        List<BlockPos> positions = straightEast(10);
        for (int i = 1; i <= 10; i++) {
            positions.add(new BlockPos(10, Y, -i));
        }

        NavGuidance guidance = NavGuidance.forPath(path(positions, true), new BlockPos(0, Y, 0));

        assertEquals(NavGuidance.Turn.LEFT, guidance.turn, "東から北は左折");
    }

    @Test
    void aDiagonalStaircaseIsNotReportedAsASeriesOfTurns() {
        // A*が斜めに進むとき、格子の上では東→南東→東→南東…とジグザグになる。
        // 1ステップずつ向きを見ると案内が「右・左・右・左」と暴れる
        List<BlockPos> positions = new ArrayList<>();
        int x = 0;
        int z = 0;
        for (int i = 0; i < 20; i++) {
            x++;
            if (i % 2 == 1) {
                z++;
            }
            positions.add(new BlockPos(x, Y, z));
        }

        NavGuidance guidance = NavGuidance.forPath(path(positions, true), new BlockPos(0, Y, 0));

        assertEquals(NavGuidance.Turn.STRAIGHT, guidance.turn,
                "帯の中に収まるジグザグは1本の直線として扱う");
    }

    @Test
    void arrivalReplacesTheTurnInstructionNearTheGoal() {
        PathResult result = path(straightEast(2), true);

        NavGuidance guidance = NavGuidance.forPath(result, new BlockPos(0, Y, 0));

        assertEquals(NavGuidance.Turn.ARRIVE, guidance.turn);
    }

    @Test
    void anIncompleteRouteNeverSaysArriving() {
        // 探索が打ち切られた経路の末端は目的地ではない。ここで「まもなく到着」と出すと、
        // 目的地はまだ遠いのに着いたと思わせてしまう
        PathResult result = path(straightEast(2), false);

        NavGuidance guidance = NavGuidance.forPath(result, new BlockPos(0, Y, 0));

        assertEquals(NavGuidance.Turn.STRAIGHT, guidance.turn);
        assertTrue(!guidance.complete);
    }

    @Test
    void verticalOnlySectionsDoNotHideLaterTurns() {
        // 梯子や掘り下げのように真上・真下だけへ動く区間は水平の向きが決まらない。
        // そこで打ち切ると、その先にある曲がり角を全部見落とす
        List<BlockPos> positions = new ArrayList<>();
        positions.add(new BlockPos(1, Y, 0));
        for (int i = 1; i <= 5; i++) {
            positions.add(new BlockPos(1, Y + i, 0));
        }
        for (int i = 2; i <= 11; i++) {
            positions.add(new BlockPos(i, Y + 5, 0));
        }
        for (int i = 1; i <= 10; i++) {
            positions.add(new BlockPos(11, Y + 5, i));
        }

        NavGuidance guidance = NavGuidance.forPath(path(positions, true), new BlockPos(0, Y, 0));

        assertEquals(NavGuidance.Turn.RIGHT, guidance.turn,
                "縦移動を挟んでも、その先の曲がり角は見つかる");
    }
}
