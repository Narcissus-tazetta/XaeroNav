package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.LoadedArea;
import net.prason.xaeronav.pathfinding.navgraph.NavGraph;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * <b>橋を架けないと進めない地形で、前へ進む合流を「引き返し」と取り違えないこと。</b>
 *
 * <p>実機ログ（2026-09-18、ネザーの溶岩の海）で合流8回のうち5回が引き返し扱いになり、うち2回は
 * その場で完走ルートの破棄に直結した。橋1本は約35.6tick＝疾走10ブロック相当なので、幾何下限で測る限り
 * 「進んだぶん」は実コストの1/10にしかならず、{@code Splice#SPLICE_DETOUR_ALLOWANCE_TICKS}でも埋まらない。
 *
 * <p>地形は南の陸（z≦6）と北の島（18≦z≦24）が溶岩11マスで隔てられたもの。目的地は南の陸の東端。
 */
class LavaBridgeSpliceTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int FLOOR_Y = 63;
    private static final int STAND_Y = FLOOR_Y + 1;

    /** 実機の既定（{@code XaeroNavConfig}）。溶岩の幅11マスはこの中に収まる。 */
    private static final int LAVA_BRIDGE_BLOCKS = 30;

    private static final BlockPos GOAL = new BlockPos(60, STAND_Y, 3);
    /** 島の上。目的地へ行くにも、経路へ戻るにも、同じ溶岩を渡ることになる。 */
    private static final BlockPos ON_THE_ISLAND = new BlockPos(30, STAND_Y, 21);
    /** 南の陸の上。目的地は同じ陸の東なので、ここから島へ渡るのは純粋な寄り道。 */
    private static final BlockPos ON_THE_MAINLAND = new BlockPos(30, STAND_Y, 3);

    private static final int WINDOW_CENTER_X = 32;
    private static final int WINDOW_CENTER_Z = 16;
    private static final int WINDOW_RADIUS = 64;

    private static FakeCells terrain() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 56, -8, 88, 96, 48))
                .canPlaceBlocks(true)
                .maxLavaBridgeRunBlocks(LAVA_BRIDGE_BLOCKS);
        for (int x = 0; x <= 80; x++) {
            for (int z = 0; z <= 40; z++) {
                char symbol = z <= 6 || (z >= 18 && z <= 24) ? FakeCells.BEDROCK : FakeCells.LAVA;
                for (int y = 56; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, symbol);
                }
            }
        }
        return cells;
    }

    private static WindowField guide(FakeCells cells) {
        NavGraph graph = new NavGraph(GOAL, cells.bounds().minY(), cells.bounds().maxY());
        LoadedArea everything = LoadedArea.square(WINDOW_CENTER_X, WINDOW_CENTER_Z, 1 << 20);
        long[] keys = graph.missingSections(WINDOW_CENTER_X, WINDOW_CENTER_Z, WINDOW_RADIUS, everything);
        assertTrue(graph.build(cells, keys, 0, keys.length, everything, NEVER));
        WindowField field = graph.field(WINDOW_CENTER_X, WINDOW_CENTER_Z, WINDOW_RADIUS, FarField.UNKNOWN, NEVER);
        assertNotNull(field);
        return field;
    }

    private static double spliceCost(FakeCells cells, BlockPos from, BlockPos to) {
        PathResult result = new AStarPathfinder(cells,
                new SearchLimits(200_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT))
                .search(from, to, NEVER);
        assertTrue(result.complete(), "橋を架ければ繋がるはずの区間が出ていない");
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    @Test
    void takesABridgingSpliceThatMovesTowardTheGoal() {
        FakeCells cells = terrain();
        double cost = spliceCost(cells, ON_THE_ISLAND, ON_THE_MAINLAND);

        assertTrue(Splice.spliceWorthTaking(cost, ON_THE_ISLAND, ON_THE_MAINLAND, GOAL, guide(cells)),
                "橋が要るだけの前進する合流が引き返し扱いになっている: 合流区間=" + Math.round(cost) + "tick");
    }

    /**
     * ガイドが無ければ幾何下限で測るしかなく、同じ合流が拒まれる。<b>この地形でガイドが要る理由</b>が
     * ここに出ている——直したのは物差しであって、余裕（{@code SPLICE_DETOUR_ALLOWANCE_TICKS}）ではない。
     */
    @Test
    void theGeometricYardstickAloneRefusesTheSameSplice() {
        FakeCells cells = terrain();
        double cost = spliceCost(cells, ON_THE_ISLAND, ON_THE_MAINLAND);

        assertFalse(Splice.spliceWorthTaking(cost, ON_THE_ISLAND, ON_THE_MAINLAND, GOAL, null),
                "幾何下限でも通ってしまうなら、この地形はガイドの要否を測れていない");
    }

    /** 橋が安く見えるようになっても、目的地から遠ざかる合流は断ること（崖の判定を殺していない）。 */
    @Test
    void stillRefusesASpliceThatLeadsAwayFromTheGoal() {
        FakeCells cells = terrain();
        double cost = spliceCost(cells, ON_THE_MAINLAND, ON_THE_ISLAND);

        assertFalse(Splice.spliceWorthTaking(cost, ON_THE_MAINLAND, ON_THE_ISLAND, GOAL, guide(cells)),
                "陸から島へ渡る寄り道が採用されている: 合流区間=" + Math.round(cost) + "tick");
    }
}
