package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 持ち物のブロック数を経路の設置数の上限にする（{@code CellSource#placedBlockBudget}）。
 *
 * <p>ユーザー報告「設置が多く、途中でブロックが尽きて結局掘る羽目になる。掘った方が早かった」。
 * 橋の長さの上限（{@code maxBridgeRunBlocks}）は<b>連続長</b>なので、短い橋を何度も架ける経路は
 * 素通りしていた。
 *
 * <p><b>対照を必ず置く。</b>「予算を絞ったら橋が消えた」だけでは、元から橋が出ない地形を
 * 検証していることに気付けない（{@code RiskyJumpTest}で実際に踏んだ空振り）。
 */
class BlockBudgetTest {

    private static final BooleanSupplier NEVER = () -> false;
    private static final SearchLimits LIMITS = new SearchLimits(200_000, 20_000, 1.5);

    /** 裂け目の幅。跳んで越えられる上限(3)より広くして、渡る手段を設置だけに絞る。 */
    private static final int GAP = 4;

    /**
     * 東西に伸びる棚。x=20 から幅{@link #GAP}の底無しの裂け目が南の {@code gapReachZ} まで伸びていて、
     * そこから先は繋がっている（回り込める）。
     *
     * <p><b>回り込みの長さが対照の成否を決める。</b>奈落の橋は1マス約35.6tick（うち32が設置と
     * 危険料）なので、幅4なら追加128tick。回り込みは往復ぶんの水平移動で、26マス南下して戻ると
     * 約185tick——<b>橋の方が安い</b>状態にしておかないと、予算を絞る前から回り込んでしまう。
     */
    private static FakeCells ledgeWithGap(int gapReachZ) {
        SearchBounds bounds = new SearchBounds(-8, 0, -8, 48, 96, 44);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR)
                .canPlaceBlocks(true).maxFallDamagePoints(0);
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 32; z++) {
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

    private static int maxZ(PathResult result) {
        return result.steps().stream().mapToInt(s -> s.pos().getZ()).max().orElse(0);
    }

    private static PathResult solve(FakeCells cells) {
        return new AStarPathfinder(cells, LIMITS)
                .search(new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), NEVER, 0);
    }

    /**
     * <b>対照。</b>予算が無ければ（従来の挙動）、回り込むより短い裂け目には橋を架ける。
     * これが成り立たない地形では下のテストが空振りになる。
     */
    @Test
    void bridgesTheGapWhenNoBudgetIsSet() {
        PathResult result = solve(ledgeWithGap(26));

        assertTrue(result.complete(), "回り込めば必ず着ける: " + result.termination());
        assertTrue(placements(result) > 0, "橋を架けずに渡っている＝対照が成立していない");
        assertTrue(maxZ(result) < 10, "回り込まずまっすぐ渡るはず: maxZ=" + maxZ(result));
    }

    /** 予算が足りていれば、上の対照と同じ経路が出る（予算があるだけで狭めない）。 */
    @Test
    void keepsBridgingWhenTheBudgetCoversIt() {
        PathResult withoutBudget = solve(ledgeWithGap(26));
        PathResult withBudget = solve(ledgeWithGap(26).placedBlockBudget(64));

        assertTrue(withBudget.complete());
        assertEquals(placements(withoutBudget), placements(withBudget),
                "予算が足りているのに設置の数が変わった");
    }

    /**
     * <b>本体。</b>予算が裂け目より少なければ、橋を架けずに回り込む。
     */
    @Test
    void walksAroundWhenTheBudgetIsTooSmall() {
        PathResult result = solve(ledgeWithGap(26).placedBlockBudget(2));

        assertTrue(result.complete(), "回り込む道があるので着けるはず: " + result.termination());
        assertTrue(placements(result) <= 2, "予算を超えて設置している: " + placements(result));
        assertTrue(maxZ(result) > 26, "回り込んでいない: maxZ=" + maxZ(result));
    }

    /**
     * 予算が足りず、しかも回り込む道が無いときは<b>緩和の梯子が開いて経路を出す</b>——
     * 詰みよりは「足りないが道はある」を見せる方がよい（不足は案内側が伝える）。
     */
    @Test
    void loosensTheBudgetWhenThereIsNoOtherWay() throws Exception {
        // 裂け目が探索範囲の端まで貫いていて回り込めない地形
        FakeCells cells = ledgeWithGap(Integer.MAX_VALUE).placedBlockBudget(2);

        AStarPathfinder strict = new AStarPathfinder(cells, LIMITS);
        PathResult blocked = strict.search(new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), NEVER, 0);
        assertTrue(strict.placedBudgetBlocked(), "予算で設置を捨てたのにフラグが立っていない");
        assertTrue(!blocked.complete(), "予算内で渡れてしまう＝地形が対照になっていない");

        PathResult loosened = new PathfindingExecutor()
                .submit(cells, new BlockPos(0, 65, 0), new BlockPos(40, 65, 0), LIMITS, false).get();
        assertTrue(loosened.complete(), "緩和の梯子が開かなかった: " + loosened.termination());
        assertTrue(placements(loosened) > 2, "緩めたのに予算内のままの経路が出ている");
    }
}
