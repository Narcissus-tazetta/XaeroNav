package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * 地形を触る手（掘削・設置）と、横へ回り込む迂回の釣り合い。
 *
 * <p><b>ここが低すぎると自然地形では歩くたびに地形を壊す／積むことになる。</b>2マスの段差や幅1の壁は
 * 数ブロックおきにあるので、迂回数ブロックで触る側に倒れる値だと経路の1〜3割が掘削・設置になる
 * （ユーザー報告「地上を歩いてる時に無駄なブロックを掘る動作や、ブロックを置く動作が多い」）。
 *
 * <p>倒れる位置は{@link ActionCosts#DIG_OVERHEAD_TICKS}・
 * {@link ActionCosts#PLACE_BLOCK_OVERHEAD_TICKS}の意味そのものなので、片方だけが動いたら
 * どちらかが壊れている。<b>算術上の比（手間 ÷ {@link ActionCosts#SIDESTEP_ONE_BLOCK}）より
 * わずかに手前で倒れる</b>のは重み付きA*が目的地から離れる手を嫌うためで、
 * 定数を動かすときに見るのは比ではなくここで測る位置。
 *
 * <p>掘削には{@link FakeCells#SOFT}（土・草を鉄のシャベル）を使う。{@link FakeCells#STONE}は
 * 石を<b>素手で</b>掘る値なので、道具を持って歩いている普段のプレイでは常に迂回が勝ってしまい、
 * この釣り合いを測れない。
 */
class TerrainEditVersusDetourTest {

    private static final BooleanSupplier NEVER = () -> false;

    /** 行程のX距離。斜めで横ずれを吸収できるだけの長さが要る（足りないと迂回が割高に見える）。 */
    private static final int SPAN = 40;

    /**
     * 障害物のX。<b>ここまでのX距離が、斜めで吸収できる横ずれの上限になる</b>——障害物が近いと
     * 迂回が「斜め2手」ではなく「真横へ1手」の値段になり、測っている釣り合いが変わってしまう。
     */
    private static final int OBSTACLE_X = 20;

    private static PathResult search(CellSource cells, BlockPos start, BlockPos goal) {
        return new AStarPathfinder(cells).search(start, goal, NEVER);
    }

    /** 平地。障害物は{@code z < detour}にだけ置くので、{@code z = detour}から先が迂回路になる。 */
    private static FakeCells flatGround(int detour) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-40, 20, -40, 90, 120, 90));
        for (int x = -4; x <= SPAN + 4; x++) {
            for (int z = -6; z <= detour + 8; z++) {
                cells.set(x, 60, z, FakeCells.STONE);
            }
        }
        return cells;
    }

    private static long digs(PathResult result) {
        return result.steps().stream().filter(PathStep::digging).count();
    }

    private static double totalCost(PathResult result) {
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    private static long places(PathResult result) {
        return result.steps().stream().filter(PathStep::bridging).count();
    }

    /** 2マスの壁は登れないので、掘るか回り込むかしかない。設置は切って掘削だけを問う。 */
    private static PathResult acrossWall(int detour) {
        FakeCells cells = flatGround(detour).canPlaceBlocks(false);
        for (int z = -6; z < detour; z++) {
            cells.set(OBSTACLE_X, 61, z, FakeCells.SOFT);
            cells.set(OBSTACLE_X, 62, z, FakeCells.SOFT);
        }
        return search(cells, new BlockPos(0, 61, 0), new BlockPos(SPAN, 61, 0));
    }

    /**
     * 2マスの段差。跳んでは登れないので、柱を1本立てるか回り込むかになる。台地を岩盤にするのは
     * 掘削という第3の道を消すため（残すと、迂回が長いときに柱ではなく天井を崩す方が選ばれる）。
     */
    private static PathResult upOntoLedge(int detour) {
        FakeCells cells = flatGround(detour).canPlaceBlocks(true);
        for (int x = OBSTACLE_X; x <= SPAN + 4; x++) {
            for (int z = -6; z <= detour + 8; z++) {
                cells.set(x, 61, z, FakeCells.BEDROCK);
                if (z < detour) {
                    cells.set(x, 62, z, FakeCells.BEDROCK);
                }
            }
        }
        return search(cells, new BlockPos(0, 61, 0), new BlockPos(SPAN, 63, 0));
    }

    /**
     * <b>下の2つが測っているものの前提。</b>横へ1ブロックずれる迂回が
     * {@link ActionCosts#SIDESTEP_ONE_BLOCK}（＝斜め2手が直進2手を置き換える）で済んでいること。
     * 斜めの手が出なくなると迂回は真横への1手（{@link ActionCosts#SPRINT_ONE_BLOCK}の2倍）に
     * 跳ね上がり、倒れる位置が<b>定数を動かさないまま</b>変わる。
     */
    @Test
    void aSidestepCostsTwoDiagonalStepsWorthOfExtraTravel() {
        double oneBlock = totalCost(acrossWall(1));
        double twoBlocks = totalCost(acrossWall(2));

        assertEquals(ActionCosts.SIDESTEP_ONE_BLOCK, twoBlocks - oneBlock, 1e-6);
    }

    @Test
    void digsThroughAWallOnlyWhenTheDetourExceedsSevenBlocks() {
        assertEquals(0, digs(acrossWall(6)), "迂回6ブロックなら回り込む");
        assertEquals(1, digs(acrossWall(7)), "迂回7ブロックからは掘って通る");

        assertTrue(acrossWall(6).complete() && acrossWall(7).complete());
    }

    @Test
    void pillarsOntoALedgeOnlyWhenTheDetourExceedsElevenBlocks() {
        assertEquals(0, places(upOntoLedge(10)), "迂回10ブロックなら回り込む");
        assertEquals(1, places(upOntoLedge(11)), "迂回11ブロックからは柱を立てる");

        assertTrue(upOntoLedge(10).complete() && upOntoLedge(11).complete());
    }
}
