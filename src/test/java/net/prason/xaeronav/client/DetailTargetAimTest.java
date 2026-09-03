package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * 詳細探索が「どこを狙うか」の2つの決まり。どちらも破ると、探索が原理的に成立しない目標を
 * 渡され続ける。
 *
 * <h4>遠すぎる目的地は手前へ切る</h4>
 *
 * <p>探索の箱は描画距離で切られるので、その外の目的地には到達しようがない。実機のprobeで、
 * 509ブロック先の目的地を上限なしで狙わせると465,536ノード・2秒を焼いて届かなかった
 * （箱は140×305）。それが数秒おきに繰り返される。
 *
 * <h4>近すぎる点は狙わない</h4>
 *
 * <p>ゴールは領域なので、始点がその中にあれば探索は0ステップで「到達」を返す。経路は空、
 * しかも失敗ではないのでエスカレーションも走らない（issue #32の症状）。
 */
class DetailTargetAimTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final int REACH = 96;

    /** 一度に狙える距離の中にある目的地は、そのまま狙う（手前で切ると永久に到着しない）。 */
    @Test
    void aimsAtTheGoalWhenItIsWithinReach() {
        BlockPos goal = new BlockPos(REACH, 64, 0);

        assertEquals(goal, PathfindingState.aimTowardGoal(START, goal, REACH));
    }

    /** 遠すぎる目的地は、その方向へちょうど{@code reach}だけ進んだ点に切り替える。 */
    @Test
    void clipsAGoalBeyondReachToAPointAlongTheWay() {
        BlockPos goal = new BlockPos(509, 64, 0);

        BlockPos aim = PathfindingState.aimTowardGoal(START, goal, REACH);

        assertNotEquals(goal, aim, "箱の外の目的地をそのまま狙っている");
        assertEquals(REACH, aim.getX(), "目的地の方向へreachぶん進んだ点になっていない");
        assertEquals(0, aim.getZ());
    }

    /** 斜めでも距離で切る（軸ごとに切ると近い軸だけ先に飽和して方向がずれる）。 */
    @Test
    void clipsDiagonallyByDistanceNotPerAxis() {
        BlockPos goal = new BlockPos(400, 64, 300);

        BlockPos aim = PathfindingState.aimTowardGoal(START, goal, REACH);

        double distance = Math.sqrt(aim.getX() * aim.getX() + (double) aim.getZ() * aim.getZ());
        assertEquals(REACH, distance, 1.0, "切った点までの距離がreachと合っていない");
        assertEquals(400.0 / 300.0, (double) aim.getX() / aim.getZ(), 0.05, "方向がずれている");
    }

    /** 目標が近すぎると探索は0ステップで終わる——その距離を境として扱う。 */
    @Test
    void refusesAnAimTooCloseToProduceAPath() {
        assertTrue(PathfindingState.tooCloseToAim(START, START), "自分の位置を狙うのを止めていない");
        assertTrue(PathfindingState.tooCloseToAim(START, new BlockPos(10, 64, 0)));
    }

    /** 十分離れていれば普通に狙う。ここまで拒むと、まともな中間目標まで捨ててしまう。 */
    @Test
    void acceptsAnAimFarEnoughAway() {
        assertFalse(PathfindingState.tooCloseToAim(START, new BlockPos(40, 64, 0)));
    }
}
