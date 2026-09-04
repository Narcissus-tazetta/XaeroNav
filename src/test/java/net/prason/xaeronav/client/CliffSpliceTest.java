package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * <b>崖から飛び降りたあと、崖の上へ登り直させないこと。</b>
 *
 * <p>ユーザー報告「行き先が左上なんだけど、崖から降りたら崖に戻される」。逸脱したときの合流は
 * 「最も近いステップ」を狙うので、飛び降りた直後は<b>真上の経路</b>が最も近いままになる。
 * そこへ合流できてしまうと、案内は登り直す道を出す。
 *
 * <p>合流そのものは要る（島渡りのように高くついた経路を逸脱のたびに捨てると、同じ経路を
 * 引き当て直せる保証が無い）。止めたいのは<b>引き返しになる合流だけ</b>。
 */
class CliffSpliceTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int TOP = 80;
    private static final int BOTTOM = 60;

    /**
     * 北側(z≦20)が高台、南側(z≧26)が低地。<b>西端(x≦20)の坂だけ</b>が両者を繋ぐ。
     * 目的地は高台の西、出発は高台の東。途中で崖から飛び降りると低地に立つ。
     */
    private static FakeCells terrain() {
        SearchBounds bounds = new SearchBounds(-8, 40, -8, 208, 120, 68);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(true).maxFallDamagePoints(6);
        for (int x = 0; x <= 200; x++) {
            for (int z = 0; z <= 60; z++) {
                int top;
                if (z <= 20) {
                    top = TOP;
                } else if (z >= 26) {
                    top = BOTTOM;
                } else if (x <= 20) {
                    top = TOP - (z - 20) * 4;
                } else {
                    continue;
                }
                for (int y = BOTTOM - 6; y <= top; y++) {
                    cells.set(x, y, z, FakeCells.SOFT);
                }
            }
        }
        return cells;
    }

    private static PathResult solve(FakeCells cells, BlockPos from, BlockPos to) {
        return new AStarPathfinder(cells,
                new SearchLimits(200_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT))
                .search(from, to, NEVER);
    }

    private static double cost(List<PathStep> steps) {
        return steps.stream().mapToDouble(PathStep::cost).sum();
    }

    @Test
    void doesNotClimbBackUpAfterDroppingOffACliff() {
        FakeCells cells = terrain();
        BlockPos goal = new BlockPos(10, TOP + 1, 10);
        PathResult onTheCliff = solve(cells, new BlockPos(190, TOP + 1, 10), goal);
        assertTrue(onTheCliff.complete(), "高台を西へ向かう経路が出るはず");

        BlockPos player = new BlockPos(140, BOTTOM + 1, 34);
        int join = PathfindingState.joinableStepIndex(onTheCliff.steps(),
                new net.minecraft.world.phys.Vec3(player.getX() + 0.5, player.getY() + 0.5,
                        player.getZ() + 0.5), 0, i -> true);
        BlockPos joinPos = onTheCliff.steps().get(join).pos();
        assertTrue(joinPos.getY() >= TOP, "合流先は崖の上のはず（この地形では他に経路が無い）");

        PathResult toJoin = solve(cells, player, joinPos);
        assertTrue(toJoin.complete(), "登り直す道自体は存在する（だから黙って採用されてしまう）");

        assertFalse(PathfindingState.spliceWorthTaking(cost(toJoin.steps()), player, joinPos, goal),
                "崖を登り直す合流が採用されている: 合流区間=" + Math.round(cost(toJoin.steps())) + "tick");
    }

    /** 経路の横数ブロックへずれただけなら、合流はそのまま採る（合流を殺してはいけない）。 */
    @Test
    void ordinaryDeviationStillSplices() {
        FakeCells cells = terrain();
        BlockPos goal = new BlockPos(10, TOP + 1, 10);
        PathResult path = solve(cells, new BlockPos(190, TOP + 1, 10), goal);

        BlockPos player = new BlockPos(140, TOP + 1, 16);
        int join = PathfindingState.joinableStepIndex(path.steps(),
                new net.minecraft.world.phys.Vec3(player.getX() + 0.5, player.getY() + 0.5,
                        player.getZ() + 0.5), 0, i -> true);
        BlockPos joinPos = path.steps().get(join).pos();
        PathResult toJoin = solve(cells, player, joinPos);

        assertTrue(PathfindingState.spliceWorthTaking(cost(toJoin.steps()), player, joinPos, goal),
                "普通の逸脱で合流が拒まれている: 合流区間=" + Math.round(cost(toJoin.steps())) + "tick");
    }
}
