package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 経路から外れたときに、どのステップへ合流するか。
 *
 * <p><b>合流点より手前は捨てられる</b>ので、先のステップへ合流できるほど残りの道のりが短くなる。
 * 距離だけで「最も近い1点」を選ぶと、経路が曲がっている所で自分より手前のステップが選ばれ、
 * いま歩いてきた区間をもう一度歩かされる——ユーザー報告「引き直したときに、先に決まっていた
 * ルートとの間が最適じゃない」の形。
 */
class SpliceJoinTest {

    private static final int Y = 64;

    private static PathStep step(int x, int z) {
        return new PathStep(new BlockPos(x, Y, z), MovementType.TRAVERSE, 4.0,
                List.of(), List.of(), PathRisk.NONE, null);
    }

    /** 東へ10進んでから南へ10曲がる、直角の経路。 */
    private static List<PathStep> corner() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            steps.add(step(x, 0));
        }
        for (int z = 1; z <= 10; z++) {
            steps.add(step(10, z));
        }
        return steps;
    }

    private static int join(List<PathStep> steps, Vec3 position) {
        return PathfindingState.joinableStepIndex(steps, position, 0, i -> true);
    }

    /**
     * 直角の内側に立ったとき、<b>曲がった先へ合流すること</b>。
     *
     * <p>この位置から最も近いのは曲がる前の腕（3ブロック）だが、そこへ合流すると角を回る
     * 14ブロックがまるごと残る。曲がった先はほんの少し遠いだけで、残りははるかに短い。
     */
    @Test
    void joinsPastTheCornerInsteadOfBacktracking() {
        List<PathStep> steps = corner();
        int index = join(steps, new Vec3(3.5, Y + 0.5, 3.5));

        BlockPos joined = steps.get(index).pos();
        assertEquals(10, joined.getX(), "曲がる前の腕へ戻っている: " + joined.toShortString());
        assertTrue(joined.getZ() >= 5,
                "角のすぐ先ではなく、同じくらい近い中でいちばん先へ合流するはず: " + joined.toShortString());
    }

    /** 経路の真横に居るだけなら、そのまま自分の位置のステップへ合流する。 */
    @Test
    void joinsBesideItselfOnAStraightPath() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 40; x++) {
            steps.add(step(x, 0));
        }
        int index = join(steps, new Vec3(20.5, Y + 0.5, 3.5));

        // 真横なので、余裕(8)ぶん先までは同じくらい近い。手前へは戻らないことが要点
        assertTrue(steps.get(index).pos().getX() >= 20,
                "自分より手前へ合流している: " + steps.get(index).pos().toShortString());
        assertTrue(steps.get(index).pos().getX() <= 30,
                "余裕を超えて遠くへ飛んでいる: " + steps.get(index).pos().toShortString());
    }

    /** 足場を置いて渡る区間へは合流できない（まだ存在しないブロックの上に立てない）。 */
    @Test
    void neverJoinsOntoABridge() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 10; x++) {
            steps.add(step(x, 0));
        }
        for (int x = 11; x <= 20; x++) {
            steps.add(new PathStep(new BlockPos(x, Y, 0), MovementType.TRAVERSE, 4.0,
                    List.of(), List.of(), PathRisk.NONE, new BlockPos(x, Y - 1, 0)));
        }
        int index = join(steps, new Vec3(14.5, Y + 0.5, 0.5));

        assertTrue(index <= 9, "橋の上へ合流している: " + steps.get(index).pos().toShortString());
    }

    /** 通れなくなったステップは飛ばして、その手前へ合流する。 */
    @Test
    void skipsStepsThatAreNoLongerPassable() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 40; x++) {
            steps.add(step(x, 0));
        }
        // x >= 22 が塞がっている
        int index = PathfindingState.joinableStepIndex(steps, new Vec3(20.5, Y + 0.5, 0.5), 0,
                i -> steps.get(i).pos().getX() < 22);

        assertEquals(21, steps.get(index).pos().getX(),
                "塞がっていない中でいちばん先へ合流するはず: " + steps.get(index).pos().toShortString());
    }

    /**
     * <b>近い範囲が全部塞がっていても諦めないこと。</b>範囲は検査を掛けずに測った「最も近い
     * ステップ」から取るので、その一帯が塞がっていると範囲ごと外れる。塞がった箇所を迂回する
     * 場面がまさにそれで、ここで-1を返すと合流できるのに全部引き直すことになる。
     */
    @Test
    void looksBeyondTheSlackWhenEverythingNearIsBlocked() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 40; x++) {
            steps.add(step(x, 0));
        }
        // プレイヤーの周り（余裕8ブロックぶん）がまるごと塞がっている
        int index = PathfindingState.joinableStepIndex(steps, new Vec3(20.5, Y + 0.5, 0.5), 0,
                i -> steps.get(i).pos().getX() < 8 || steps.get(i).pos().getX() > 32);

        assertTrue(steps.get(index).pos().getX() > 32,
                "塞がった一帯の手前で諦めている: " + steps.get(index).pos().toShortString());
    }

    /** {@code minIndex}より手前は候補にしない（塞がった箇所を迂回するとき用）。 */
    @Test
    void respectsTheMinimumIndex() {
        List<PathStep> steps = corner();
        int index = PathfindingState.joinableStepIndex(steps, new Vec3(1.5, Y + 0.5, 0.5), 15,
                i -> true);

        assertTrue(index >= 15, "minIndexより手前へ合流している: " + index);
    }
}
