package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.prason.xaeronav.pathfinding.astar.RunCaps;
import net.prason.xaeronav.pathfinding.astar.Tolerances;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 詰んだときに緩める梯子の<b>段の順序</b>（{@code PathfindingExecutor#capStages}）。
 *
 * <p>順序そのものが要件で、しかも純粋な関数で決まる。実機ジ・エンドの島渡りで確かめると
 * 1ケース8秒かかるが、ここは地形も探索も要らない——<b>実機の探索が守るべきなのは
 * 「規模が大きいと設置の枝が消えていく」という近似の穴の方</b>で、どの段を先に試すかではない。
 */
class CapStagesTest {

    private static FakeCells view() {
        return FakeCells.empty(new SearchBounds(0, 0, 0, 16, 16, 16))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(30)
                .maxVoidBridgeRunBlocks(30)
                .maxLavaBridgeRunBlocks(10)
                .maxSubmergedTicks(40)
                .maxFallDamagePoints(6)
                .placedBlockBudget(20);
    }

    private static List<Tolerances> stages(boolean budgetBlocked, boolean emptyInventoryBlocked) {
        return PathfindingExecutor.capStages(view(), true, budgetBlocked, emptyInventoryBlocked);
    }

    /**
     * <b>本体。</b>予算で設置を捨てたなら、上限を緩めるより先に予算を外す段が来る。
     *
     * <p>他の上限は「その移動を作らない」だけだが、予算は前線が進むほど設置の枝を消していく
     * （{@code PathNode.placedTotal}がノードの同一性に含まれない近似）。順序が逆だと、
     * 予算が原因の地形で上限だけを2倍4倍にした探索を空振りで払い続ける。
     */
    @Test
    void liftsThePlacedBlockBudgetBeforeLooseningAnyRunCap() {
        List<Tolerances> stages = stages(true, false);

        assertEquals(0, stages.get(0).placedBlockBudget(), "予算を外す段が先頭に無い");
        assertEquals(RunCaps.of(view()), stages.get(0).caps(),
                "予算を外す段が上限まで一緒に緩めている＝何が効いたか分からなくなる");
    }

    /** 持ち物が空で設置を捨てた場合も、上限を緩める前に開ける（橋がそもそも生成されない壁は動かない）。 */
    @Test
    void liftsTheEmptyInventoryBlockBeforeLooseningAnyRunCap() {
        List<Tolerances> stages = stages(false, true);

        assertTrue(stages.get(0).placeWithoutBlocks(), "持ち物が空の段が先頭に無い");
        assertEquals(RunCaps.of(view()), stages.get(0).caps(), "同時に上限まで緩めている");
    }

    /** 両方が原因なら予算が先。予算は探索の形を歪めるので、空の持ち物より先に取り除く。 */
    @Test
    void theBudgetComesBeforeTheEmptyInventoryWhenBothBlocked() {
        List<Tolerances> stages = stages(true, true);

        assertEquals(0, stages.get(0).placedBlockBudget());
        assertFalse(stages.get(0).placeWithoutBlocks(), "先頭で2つ同時に開けている");
        assertTrue(stages.get(1).placeWithoutBlocks(), "持ち物が空の段が2番目に無い");
    }

    /**
     * 設置が原因でないなら、予算の段は積まない。積むと「予算を外しただけの同じ探索」を
     * 丸ごと1回払う。
     */
    @Test
    void doesNotAddABudgetStageWhenNothingWasBlockedByIt() {
        List<Tolerances> stages = stages(false, false);

        assertEquals(view().placedBlockBudget(), stages.get(0).placedBlockBudget(),
                "設置が詰まっていないのに予算を外す段が積まれている");
    }

    /**
     * 梯子の最後は必ず「上限なし・予算なし」。ここが残っていないと、上限のせいで探索範囲内に
     * 道が一本も無い地形で詰みが確定する。
     */
    @Test
    void theLastStageLiftsEveryRunCapAndTheBudget() {
        List<Tolerances> stages = stages(false, false);
        Tolerances last = stages.get(stages.size() - 1);

        assertEquals(RunCaps.NONE, last.caps(), "最後の段に上限が残っている");
        assertEquals(0, last.placedBlockBudget(), "最後の段に予算が残っている");
    }

    /**
     * <b>落下ダメージだけは無制限の段を作らない。</b>他の上限と違って、外すと即死する落下が
     * そのまま案内に出る。体力から決まる上限（既定の1.5倍）で止まること。
     */
    @Test
    void neverOffersAnUnlimitedFallDamageStage() {
        int loosened = 6 * 3 / 2;
        for (Tolerances stage : stages(true, true)) {
            assertEquals(loosened, stage.maxFallDamagePoints(),
                    "落下ダメージの許容が体力由来の上限から動いている");
        }
    }

    /** 設定で落下ダメージを断っているなら、詰み回避でも0のまま開けない。 */
    @Test
    void keepsFallDamageAtZeroWhenTheSettingForbidsIt() {
        FakeCells noFallDamage = view().maxFallDamagePoints(0);

        for (Tolerances stage : PathfindingExecutor.capStages(noFallDamage, true, true, true)) {
            assertEquals(0, stage.maxFallDamagePoints(), "断られているのに痛い落下を提示している");
        }
    }
}
