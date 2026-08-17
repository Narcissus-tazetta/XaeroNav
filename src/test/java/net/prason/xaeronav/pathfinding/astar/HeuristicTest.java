package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * {@link Heuristic}の軸別下限値（design doc §4-2）が式どおりに積算されているかの検証。
 * A*はヒューリスティックが実コストの下限（admissible）であることに最適性を依存しているので、
 * ここが実コストを上回る方向へ壊れると、経路が最適から静かにずれても誰も気付けない。
 */
class HeuristicTest {

    private static final double STRAIGHT = ActionCosts.SPRINT_ONE_BLOCK;
    private static final double DIAGONAL = STRAIGHT * ActionCosts.DIAGONAL_DISTANCE;

    @Test
    void sameCellCostsNothing() {
        assertEquals(0.0, Heuristic.estimate(5, 60, -3, 5, 60, -3));
    }

    @Test
    void axisAlignedHorizontalMovementIsPureStraightCost() {
        assertEquals(7 * STRAIGHT, Heuristic.estimate(0, 64, 0, 7, 64, 0), 1e-9);
        assertEquals(7 * STRAIGHT, Heuristic.estimate(0, 64, 0, 0, 64, 7), 1e-9);
    }

    @Test
    void pureDiagonalMovementUsesOctileDistance() {
        // dx == dz のときは斜め移動だけで踏破できるので、n歩ぶんのDIAGONALに一致するはず
        assertEquals(4 * DIAGONAL, Heuristic.estimate(0, 64, 0, 4, 64, 4), 1e-9);
    }

    @Test
    void horizontalDistanceIsSymmetricUnderAxisSwap() {
        double dxLarger = Heuristic.estimate(0, 64, 0, 9, 64, 2);
        double dzLarger = Heuristic.estimate(0, 64, 0, 2, 64, 9);
        assertEquals(dxLarger, dzLarger, 1e-9);
    }

    @Test
    void mixedHorizontalMatchesOctileFormula() {
        int dx = 9;
        int dz = 2;
        double diagonalSaving = DIAGONAL - 2 * STRAIGHT;
        double expected = STRAIGHT * (dx + dz) + diagonalSaving * Math.min(dx, dz);
        assertEquals(expected, Heuristic.estimate(0, 64, 0, dx, 64, dz), 1e-9);
    }

    /**
     * 水平移動を伴わない純粋な昇りは、水平移動を必要としない移動（梯子のClimbUpが最安）の
     * コストで見積もる。以前はAscendの水平込みの値（JUMP_ONE_BLOCK）を使っていたが、
     * 水平移動0の区間にAscendは使えない（Ascendは必ず水平1歩を伴う）ので下限として不正確だった。
     */
    @Test
    void pureVerticalAscendUsesTheClimbCostPerBlock() {
        assertEquals(5 * ActionCosts.LADDER_UP_ONE_BLOCK, Heuristic.estimate(0, 64, 0, 0, 69, 0), 1e-9);
    }

    /**
     * 締め直した下限（{@code LADDER_UP_ONE_BLOCK}）が、実際に純粋な昇りを実現する手段
     * （ClimbUp・SwimUp・Pillar）のどれよりも高くなってはいけない——admissibilityの核心。
     */
    @Test
    void pureVerticalAscendNeverExceedsAnyRealMoveThatAchievesIt() {
        double estimate = Heuristic.estimate(0, 64, 0, 0, 65, 0);
        assertTrue(estimate <= ActionCosts.LADDER_UP_ONE_BLOCK + 1e-9);
        assertTrue(estimate <= ActionCosts.WALK_ONE_IN_WATER + 1e-9,
                "SwimUpより高く見積もってはいけない");
        assertTrue(estimate <= ActionCosts.ASCEND_ONE_BLOCK + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS + 1e-9,
                "Pillar（Ascend相当+設置オーバーヘッド）より高く見積もってはいけない");
    }

    @Test
    void descendingUsesTheAsymptoticFallLowerBound() {
        assertEquals(5 * ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK,
                Heuristic.estimate(0, 64, 0, 0, 59, 0), 1e-9);
    }

    /**
     * 斜めのショートカット分（{@code DIAGONAL_SAVING}）が効きすぎて、水平のヒューリスティックが
     * 実際のoctile距離の最小コスト（カーディナルのみで進んだ場合の下限）を下回ってはいけない。
     */
    @Test
    void horizontalEstimateNeverExceedsWalkingEachAxisSeparately() {
        for (int dx = 0; dx <= 20; dx += 3) {
            for (int dz = 0; dz <= 20; dz += 3) {
                double estimate = Heuristic.estimate(0, 64, 0, dx, 64, dz);
                assertTrue(estimate <= STRAIGHT * (dx + dz) + 1e-9,
                        "dx=" + dx + " dz=" + dz + "でカーディナル移動の合計コストを上回ってはいけない");
            }
        }
    }

    /**
     * 水平1マス＋上昇1マスを1手でこなす{@code Ascend}の実コストは{@code ASCEND_ONE_BLOCK}
     * （水平移動時間と跳躍時間の大きい方）なので、ヒューリスティックはそれを上回ってはいけない。
     * 現行の実装は水平成分と垂直成分を独立に加算しているため、この検証は現状では失敗する
     * （回帰テスト。Heuristicを軸別の相乗り計算に直すことで通す）。
     */
    @Test
    void cardinalAscendEstimateDoesNotExceedItsRealCost() {
        double estimate = Heuristic.estimate(0, 64, 0, 1, 65, 0);
        assertTrue(estimate <= ActionCosts.ASCEND_ONE_BLOCK + 1e-9,
                "1手のAscendの実コストを上回ってはいけない: estimate=" + estimate);
    }

    /**
     * 斜め1マスで1段登る{@code DiagonalAscend}についても同様。カーディナル分を独立加算する
     * 現行実装ではさらに大きく過大評価になる。
     */
    @Test
    void diagonalAscendEstimateDoesNotExceedItsRealCost() {
        double estimate = Heuristic.estimate(0, 64, 0, 1, 65, 1);
        assertTrue(estimate <= ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK + 1e-9,
                "1手のDiagonalAscendの実コストを上回ってはいけない: estimate=" + estimate);
    }
}
