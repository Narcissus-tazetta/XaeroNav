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
    void ascendIsAtLeastAsExpensiveAsWalkingOrJumping() {
        assertTrue(ActionCosts.ASCEND_ONE_BLOCK >= ActionCosts.WALK_ONE_BLOCK);
        assertTrue(ActionCosts.ASCEND_ONE_BLOCK >= ActionCosts.JUMP_ONE_BLOCK);
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
