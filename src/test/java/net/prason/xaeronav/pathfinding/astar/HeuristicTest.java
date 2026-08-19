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
     * 水平移動を伴わない純粋な昇りは、高さを1段稼ぐ全ての移動のうち最安の{@code Ascend}で見積もる。
     * {@code Ascend}は水平1歩を伴うが、その1歩は折り返せば戻せるので、正味の水平変位0でも使える。
     */
    @Test
    void pureVerticalAscendUsesTheCheapestMoveThatGainsHeight() {
        assertEquals(5 * ActionCosts.ASCEND_ONE_BLOCK, Heuristic.estimate(0, 64, 0, 0, 69, 0), 1e-9);
    }

    /**
     * 純粋な昇りの下限が、それを実現する手段のどれよりも高くなってはいけない——admissibilityの核心。
     * 梯子・遊泳・Pillarだけでなく<b>折り返し階段</b>（{@code Ascend}の往復）も手段に含める。
     */
    @Test
    void pureVerticalAscendNeverExceedsAnyRealMoveThatAchievesIt() {
        double estimate = Heuristic.estimate(0, 64, 0, 0, 65, 0);
        assertTrue(estimate <= ActionCosts.ASCEND_ONE_BLOCK + 1e-9,
                "折り返し階段（Ascendの往復）より高く見積もってはいけない");
        assertTrue(estimate <= ActionCosts.LADDER_UP_ONE_BLOCK + 1e-9);
        assertTrue(estimate <= ActionCosts.WALK_ONE_IN_WATER + 1e-9,
                "SwimUpより高く見積もってはいけない");
        assertTrue(estimate <= ActionCosts.ASCEND_ONE_BLOCK + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS + 1e-9,
                "Pillar（Ascend相当+設置オーバーヘッド）より高く見積もってはいけない");
    }

    /**
     * 折り返し階段。水平変位1・高さ3は{@code Ascend}3手（うち1手は水平を戻す）で登れるので、
     * 見積もりが3手ぶんを超えてはいけない。梯子を下限に置いていた頃はここが 21.65 &gt; 13.90 で
     * 非許容だった。
     */
    @Test
    void switchbackStaircaseIsNotOverEstimated() {
        double threeAscends = 3 * ActionCosts.ASCEND_ONE_BLOCK;
        assertTrue(Heuristic.estimate(0, 64, 0, 1, 67, 0) <= threeAscends + 1e-9,
                "折り返し階段の実コストを上回ってはいけない");
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
