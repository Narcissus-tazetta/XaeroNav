package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>持ち物の大半を使い切る経路が出たら、少し遠回りしてでも設置を減らす</b>
 * （{@code PathfindingExecutor#refineQuality}の節約の段）。
 *
 * <p>予算（{@code CellSource#placedBlockBudget}、{@link BlockBudgetTest}）は<b>実行できるか</b>の
 * 線引きでしかない。手持ち6個で4個置く経路は実行できるが、渡り切った先で手元が空になる——
 * 経路キャッシュのキーは目的地だけなので、減ったことを理由に引き直されることもない。
 *
 * <p><b>対照を2つ置く。</b>「遠回りするようになった」だけでは、(a) 希少さと無関係にいつも
 * 遠回りしている (b) どんなに高くついても遠回りする、のどちらでもないことを示せない。
 */
class BlockThriftTest {

    private static final SearchLimits LIMITS = new SearchLimits(200_000, 20_000, 1.5);

    /** 裂け目の幅＝渡るのに要る設置数。跳んで越えられる上限(3)より広くする。 */
    private static final int GAP = 4;

    /**
     * 東西に伸びる棚。x=20 から幅{@link #GAP}の底無しの裂け目が南の {@code gapReachZ} まで伸びていて、
     * その先で繋がっている。{@link BlockBudgetTest}と同じ形だが、<b>10%の関門を超える遠回り</b>を
     * 作れるところまで棚を南へ伸ばしてある。
     *
     * <p>実測（設置の値段を何倍にするかを振ったときの、選ばれる経路と本来の値段での総コスト）:
     *
     * <pre>
     * gapReachZ  等倍        1.5倍       2倍         3倍
     * 24         橋4/270.6  回り0/243.5 回り0/243.5 回り0/243.5
     * 30         橋4/270.6  橋4/270.6   回り0/286.3 回り0/286.3
     * 34         橋4/270.6  橋4/270.6   回り0/314.8 回り0/314.8
     * </pre>
     *
     * <p>24は<b>等倍でも回り込む方が安い</b>のに橋が出ている＝重み付きA*の貪欲さの話で、
     * 節約とは別の引き金（奈落を渡る経路の引き直し）が拾う。節約が効くのは30と34で、
     * 悪化は+5.8%と+16.3%——{@code THRIFT_MAX_COST_INCREASE}(10%)がこの2つを分ける。
     */
    private static FakeCells ledgeWithGap(int gapReachZ) {
        SearchBounds bounds = new SearchBounds(-8, 0, -8, 48, 96, 64);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR)
                .canPlaceBlocks(true).maxFallDamagePoints(0);
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 52; z++) {
                if (x >= 20 && x < 20 + GAP && z <= gapReachZ) {
                    continue;
                }
                cells.set(x, 64, z, FakeCells.STONE);
            }
        }
        return cells;
    }

    private static long placements(PathResult result) {
        return result.steps().stream().filter(PathStep::bridging).count();
    }

    private static PathResult solve(FakeCells cells) throws Exception {
        return new PathfindingExecutor()
                .submit(cells, new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), LIMITS, false).get();
    }

    /** <b>本体。</b>手持ち6個のうち4個を使う経路なので、+5.8%の遠回りを買って設置を0にする。 */
    @Test
    void walksAroundWhenTheBridgeWouldUseUpMostOfTheInventory() throws Exception {
        PathResult result = solve(ledgeWithGap(30).placedBlockBudget(6));

        assertTrue(result.complete(), "回り込む道があるので着けるはず: " + result.termination());
        assertEquals(0, placements(result), "節約したのに橋が残っている");
    }

    /**
     * <b>対照1。</b>同じ地形・同じ遠回りでも、持ち物に余裕があれば近い方を通る。
     * 節約の引き金が<b>希少さ</b>であって遠回りの好みではないことの固定。
     */
    @Test
    void keepsTheShortPathWhenBlocksAreNotScarce() throws Exception {
        PathResult result = solve(ledgeWithGap(30).placedBlockBudget(64));

        assertTrue(result.complete());
        assertEquals(GAP, placements(result), "余裕があるのに遠回りした");
    }

    /** <b>対照2。</b>予算そのものが無い（クリエイティブ・設定off）なら希少さの概念が無い。 */
    @Test
    void keepsTheShortPathWithoutABudget() throws Exception {
        PathResult result = solve(ledgeWithGap(30));

        assertTrue(result.complete());
        assertEquals(GAP, placements(result), "予算が無いのに節約した");
    }

    /**
     * <b>対照3。</b>節約はブロックを<b>時間で買う</b>ので買値に上限がある。同じ4個でも、
     * 遠回りが+16.3%になる地形では買わない。ここが抜けると、持ち物が乏しいだけで
     * どこまでも遠回りする案内になる。
     */
    @Test
    void refusesToBuyBlocksAtAnyPrice() throws Exception {
        PathResult result = solve(ledgeWithGap(34).placedBlockBudget(6));

        assertTrue(result.complete());
        assertEquals(GAP, placements(result), "割に合わない遠回りを採った");
    }
}
