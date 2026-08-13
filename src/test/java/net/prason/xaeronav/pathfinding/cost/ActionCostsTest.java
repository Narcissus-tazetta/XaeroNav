package net.prason.xaeronav.pathfinding.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ActionCosts}の定数同士が満たすべき大小関係の検証（design doc §3-1/§4-1）。
 * 数値そのものはバニラの実測値からの直接計算なので固定するテストは書かないが、
 * 「歩くより走る方が安い」のような、崩れると経路の質が静かに悪化する関係はここで縛る。
 */
class ActionCostsTest {

    @Test
    void sprintingIsCheaperThanWalking() {
        assertTrue(ActionCosts.SPRINT_ONE_BLOCK < ActionCosts.WALK_ONE_BLOCK);
    }

    @Test
    void waterAndCobwebAreSlowerThanOpenGround() {
        assertTrue(ActionCosts.WALK_ONE_IN_WATER > ActionCosts.SPRINT_ONE_BLOCK);
        assertTrue(ActionCosts.SPRINT_ONE_IN_COBWEB > ActionCosts.SPRINT_ONE_BLOCK);
    }

    @Test
    void paddlingIsFasterThanSprinting() {
        assertTrue(ActionCosts.PADDLE_ONE_BLOCK < ActionCosts.SPRINT_ONE_BLOCK);
    }

    @Test
    void ascendIsAtLeastAsExpensiveAsWalkingOrJumping() {
        assertTrue(ActionCosts.ASCEND_ONE_BLOCK >= ActionCosts.WALK_ONE_BLOCK);
        assertTrue(ActionCosts.ASCEND_ONE_BLOCK >= ActionCosts.JUMP_ONE_BLOCK);
    }

    /**
     * 斜め昇りは、水平1マス＋垂直1マスをカーディナル2手（登り+直進）に分解するより安くなければ
     * 意味がない。逆転すると、探索が斜め移動を一度も選ばなくなる（{@code MIN_IMPROVEMENT}未満の
     * 差ではなく明確に安いことを求める）。
     */
    @Test
    void diagonalAscendIsCheaperThanTwoCardinalHops() {
        assertTrue(ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK
                < ActionCosts.ASCEND_ONE_BLOCK + ActionCosts.SPRINT_ONE_BLOCK);
    }

    /** {@link #diagonalAscendIsCheaperThanTwoCardinalHops}の降り側。 */
    @Test
    void diagonalDescendIsCheaperThanTwoCardinalHops() {
        assertTrue(ActionCosts.DIAGONAL_DESCEND_ONE_BLOCK
                < ActionCosts.DESCEND_ONE_BLOCK + ActionCosts.SPRINT_ONE_BLOCK);
    }

    /**
     * 斜めに1段登るのは、同じ距離を平らに斜め移動するより高くつく。ここが等しくなると
     * 「ただで高さが稼げる」ことになり、{@code Heuristic}の上昇成分が丸ごと0になって
     * 山の上を目指す経路で探索が不必要に広がる（カーディナル側は既にこの関係を満たしている）。
     */
    @Test
    void climbingDiagonallyCostsMoreThanMovingDiagonallyOnFlatGround() {
        double diagonalOnFlat = ActionCosts.SPRINT_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE;
        assertTrue(ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK > diagonalOnFlat,
                "斜めの登坂ペナルティが消えている: " + ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK + " vs " + diagonalOnFlat);
    }

    @Test
    void fallCostIsMonotonicWithDistance() {
        double previous = 0.0;
        for (int blocks = 1; blocks <= ActionCosts.SAFE_FALL_BLOCKS + 5; blocks++) {
            double cost = ActionCosts.fallCost(blocks);
            assertTrue(cost > previous, blocks + "マスの落下は" + (blocks - 1) + "マスより高くつくはず");
            previous = cost;
        }
    }

    /**
     * 1マスの隙間跳びは、同じ2マスを走るより高くつく（滞空中は着地までコストを打ち切れない）。
     * これが逆転すると、平地でも常に跳ぶ方が安くなり、無意味なジャンプだらけの経路になる。
     */
    @Test
    void jumpingAcrossAGapCostsMoreThanSprintingTheSameDistance() {
        assertTrue(ActionCosts.JUMP_ACROSS_GAP > 2 * ActionCosts.SPRINT_ONE_BLOCK);
    }

    @Test
    void safeFallBlocksMatchesVanillaFallDamageThreshold() {
        assertEquals(3, ActionCosts.SAFE_FALL_BLOCKS);
    }
}
