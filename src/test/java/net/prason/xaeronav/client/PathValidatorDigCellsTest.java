package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import org.junit.jupiter.api.Test;

/**
 * 「手前のステップで掘るセルは、その先のステップにとっても通れる前提」の番人。
 *
 * <p>これが崩れると、掘って登る経路が検査のたびに蹴られ、探索は同じ経路を出し直すので
 * 全引き直しが永久に続く（実機ログで110秒・34回、砂利のセル1つが原因）。しかも
 * 「探索の失敗」ではないため緩和もエスカレーションも走らず、案内は2秒ごとに作り直され続ける。
 *
 * <p>{@code stepFailure}そのものは{@code Level}（＝Minecraftのレジストリ起動）を要求するので、
 * 判定の核だけを見る。
 */
class PathValidatorDigCellsTest {

    private static final BlockPos GRAVEL = new BlockPos(16, 11, -469);

    private static PathStep step(List<BlockPos> bodyCells, List<BlockPos> digCells) {
        return new PathStep(new BlockPos(16, 10, -469), MovementType.ASCEND, 1.0,
                bodyCells, digCells, PathRisk.NONE, null);
    }

    /** 手前のステップが掘る予定のセルは、いま塞がっていても不成立にしない。 */
    @Test
    void bodyCellDugByAnEarlierStepIsNotBlocked() {
        PathStep later = step(List.of(GRAVEL, GRAVEL.above()), List.of());

        List<BlockPos> mustBeClear = PathValidator.unexcavatedBodyCells(later, Set.of(GRAVEL));

        assertEquals(List.of(GRAVEL.above()), mustBeClear);
    }

    /** 自分自身が掘る予定のセルも同じく除く（元からの動作）。 */
    @Test
    void bodyCellDugByTheStepItselfIsNotBlocked() {
        PathStep digging = step(List.of(GRAVEL), List.of(GRAVEL));

        assertTrue(PathValidator.unexcavatedBodyCells(digging, Set.copyOf(digging.digCells())).isEmpty());
    }

    /** 誰も掘る予定が無いセルは残す。ここまで緩めると本当に塞がった経路まで通してしまう。 */
    @Test
    void bodyCellNobodyPlansToDigIsStillChecked() {
        PathStep plain = step(List.of(GRAVEL), List.of());

        assertEquals(List.of(GRAVEL), PathValidator.unexcavatedBodyCells(plain, Set.of()));
    }
}
