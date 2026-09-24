package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import org.junit.jupiter.api.Test;

/**
 * 「手前のステップでこれから置く橋は、その先のステップにとって足場がある前提」の番人。
 *
 * <p>継ぎ足しの探索は手前の設置を足場に使うので、経路が自分の橋の上を通り直すことがある。
 * これが崩れると、まだ置いていない橋が「足場が無い」と判定され、途中までの経路が丸ごと
 * 引き直される（実機のネザーで621ステップの経路が75ステップへ縮んだ）。
 */
class PathValidatorPlannedBridgeTest {

    private static final BlockPos BRIDGE = new BlockPos(53, 49, 695);

    private static PathStep walk(BlockPos pos) {
        return new PathStep(pos, MovementType.TRAVERSE, 1.0, List.of(pos, pos.above()), List.of(), PathRisk.NONE, null);
    }

    private static PathStep bridge(BlockPos pos) {
        return new PathStep(pos, MovementType.TRAVERSE, 1.0, List.of(pos, pos.above()), List.of(), PathRisk.NONE,
                pos.below());
    }

    private static final List<PathStep> ROUTE = List.of(
            walk(new BlockPos(51, 50, 695)),
            bridge(BRIDGE.above()),
            walk(new BlockPos(54, 50, 695)),
            walk(BRIDGE.above()));

    @Test
    void bridgeAheadCountsAsFooting() {
        assertTrue(PathValidator.bridgeStillToBePlaced(ROUTE, 0, 3, BRIDGE));
    }

    /** 通過済みの橋は置かれているはず。無ければ本物の変化なので見逃さない。 */
    @Test
    void bridgeAlreadyPassedIsStillChecked() {
        assertFalse(PathValidator.bridgeStillToBePlaced(ROUTE, 2, 3, BRIDGE));
    }

    /** 自分自身や後ろのステップで置く橋は、このステップの足場にならない。 */
    @Test
    void bridgePlacedLaterIsNotFooting() {
        assertFalse(PathValidator.bridgeStillToBePlaced(ROUTE, 0, 1, BRIDGE));
    }

    @Test
    void unrelatedCellIsNotFooting() {
        assertFalse(PathValidator.bridgeStillToBePlaced(ROUTE, 0, 3, BRIDGE.east()));
    }
}
