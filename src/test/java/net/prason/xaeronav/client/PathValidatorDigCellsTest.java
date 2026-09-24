package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.prason.xaeronav.pathfinding.world.FakeCells;
import org.junit.jupiter.api.Test;

/**
 * 「経路が掘る予定のセルは、塞がっていても空いていても通れる前提」の番人。
 *
 * <p>塞がっているのを蹴ると、掘って登る経路が検査のたびに蹴られ、探索は同じ経路を出し直すので
 * 全引き直しが永久に続く（実機ログで110秒・34回、砂利のセル1つが原因）。空いているのを蹴ると、
 * プレイヤーが指示どおりに掘るたびに数百手の経路が捨てられる（実機のネザー: 723手→39手）。
 * ただし掘った後に溶岩が流れ込んだセルは通れない——ネザーでは掘る予定でないセルへの流れ込みが
 * 実機ログに何度も出ている。
 *
 * <p>{@code stepFailure}そのものは{@code Level}（＝Minecraftのレジストリ起動）を要求するので、
 * 判定の核だけを見る。
 */
class PathValidatorDigCellsTest {

    private static final FakeCells CELLS = FakeCells.of(0, 0, 0, "#.L");
    private static final long STONE = CELLS.cell(0, 0, 0);
    private static final long AIR = CELLS.cell(1, 0, 0);
    private static final long LAVA = CELLS.cell(2, 0, 0);

    @Test
    void plannedDigStillSolidIsNotBlocked() {
        assertFalse(PathValidator.bodyCellBlocked(STONE, true));
    }

    @Test
    void plannedDigAlreadyDugIsNotBlocked() {
        assertFalse(PathValidator.bodyCellBlocked(AIR, true));
    }

    @Test
    void plannedDigFilledWithLavaIsBlocked() {
        assertTrue(PathValidator.bodyCellBlocked(LAVA, true));
    }

    @Test
    void solidCellNobodyPlansToDigIsBlocked() {
        assertTrue(PathValidator.bodyCellBlocked(STONE, false));
    }

    @Test
    void lavaCellNobodyPlansToDigIsBlocked() {
        assertTrue(PathValidator.bodyCellBlocked(LAVA, false));
    }
}
