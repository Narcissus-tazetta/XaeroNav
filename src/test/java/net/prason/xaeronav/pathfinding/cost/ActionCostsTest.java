package net.prason.xaeronav.pathfinding.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ActionCosts}の定数同士が満たすべき大小関係の検証。
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
        assertTrue(ActionCosts.SWIM_ONE_BLOCK > ActionCosts.SPRINT_ONE_BLOCK);
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

    /**
     * 跳ぶより歩く方が安い、をどの隙間幅でも保つ。ここが逆転すると、平地に迂回路があっても
     * 跳ぶ経路が選ばれ、着地を外せば落ちる案内を勧めることになる。
     */
    @Test
    void jumpingAnyGapCostsMoreThanSprintingAroundIt() {
        for (int gap = 1; gap <= 3; gap++) {
            // 着地点は隙間の1マス先。同じ距離を平地で走った場合と比べる
            double sprintSameDistance = (gap + 1) * ActionCosts.SPRINT_ONE_BLOCK;
            assertTrue(ActionCosts.jumpAcrossGap(gap) > sprintSameDistance,
                    gap + "マスの隙間跳びが、同じ距離を走るより安くなっている");
        }
    }

    @Test
    void widerGapsCostMore() {
        assertEquals(ActionCosts.JUMP_ACROSS_GAP, ActionCosts.jumpAcrossGap(1),
                "1マスの隙間は従来どおり滞空時間そのもの");
        double previous = ActionCosts.jumpAcrossGap(1);
        for (int gap = 2; gap <= 3; gap++) {
            double cost = ActionCosts.jumpAcrossGap(gap);
            assertTrue(cost > previous, gap + "マスの隙間は" + (gap - 1) + "マスより高くつくはず");
            previous = cost;
        }
    }

    @Test
    void safeFallBlocksMatchesVanillaFallDamageThreshold() {
        assertEquals(3, ActionCosts.SAFE_FALL_BLOCKS);
    }

    /**
     * 落下ダメージの許容量を緩める探し直しが成立する条件。許せる落差が伸びたら下降の下限は
     * <b>必ず下がる</b>——ここが単調でないと、緩めた探索へ元の下限を渡し続けたときに
     * ヒューリスティックが実コストを上回る（＝非許容）ことに気付けない。
     */
    @Test
    void descentBoundNeverRisesAsTheAllowedDropGrows() {
        double previous = Double.MAX_VALUE;
        for (int maxDrop = 1; maxDrop <= 40; maxDrop++) {
            double bound = ActionCosts.descentBoundForMaxDrop(maxDrop);
            assertTrue(bound <= previous + 1e-12,
                    maxDrop + "マスまで落ちられるのに下限が上がった: " + previous + " -> " + bound);
            previous = bound;
        }
    }

    /** 下限は名前のとおり下限であること。実際の1マスあたりのコストを上回ってはいけない。 */
    @Test
    void descentBoundStaysBelowTheRealPerBlockCost() {
        for (int maxDrop = 1; maxDrop <= 40; maxDrop++) {
            double bound = ActionCosts.descentBoundForMaxDrop(maxDrop);
            for (int drop = 1; drop <= maxDrop; drop++) {
                assertTrue(bound <= ActionCosts.fallCost(drop) / drop + 1e-12,
                        "落差" + drop + "マスの実コストを下限が上回っている（maxDrop=" + maxDrop + "）");
            }
        }
    }

    /**
     * 足場を外したときの危険料は、落差に対して単調に増えて致死落差で頭打ちになること。
     * <b>両端が従来の二値と一致する</b>ことが、この傾斜を入れても既存の振る舞いが動かない根拠。
     */
    @Test
    void dropRiskGrowsWithTheDropAndStopsAtTheFatalOne() {
        int fatal = 23;
        assertEquals(0.0, ActionCosts.dropRiskPenalty(0, fatal));
        assertEquals(0.0, ActionCosts.dropRiskPenalty(ActionCosts.SAFE_FALL_BLOCKS, fatal),
                "安全に降りられる高さに危険料は付かない");
        assertEquals(ActionCosts.VOID_BRIDGE_PENALTY_TICKS, ActionCosts.dropRiskPenalty(fatal, fatal),
                "致死落差では奈落と同額");
        assertEquals(ActionCosts.VOID_BRIDGE_PENALTY_TICKS,
                ActionCosts.dropRiskPenalty(fatal + 100, fatal), "頭打ちを超えて増えない");

        double previous = -1;
        for (int drop = 0; drop <= fatal + 5; drop++) {
            double penalty = ActionCosts.dropRiskPenalty(drop, fatal);
            assertTrue(penalty >= previous - 1e-12,
                    "落差" + drop + "マスで危険料が下がった: " + previous + " -> " + penalty);
            assertTrue(penalty <= ActionCosts.VOID_BRIDGE_PENALTY_TICKS + 1e-12);
            previous = penalty;
        }
    }

    /**
     * 掘削の手間は設置より軽いこと。「掘るのと積むのが同じくらいの手数に見える場面では掘る方を
     * 選ばせたい」という{@link ActionCosts#PLACE_BLOCK_OVERHEAD_TICKS}の意図がこの順序。
     */
    @Test
    void diggingOverheadStaysLighterThanPlacing() {
        assertTrue(ActionCosts.DIG_OVERHEAD_TICKS < ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS);
    }
}
