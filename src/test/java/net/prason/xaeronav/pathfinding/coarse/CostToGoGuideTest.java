package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 層1のcost-to-goガイド（{@link CoarseRouter#costToGo}）が詳細探索の経路をゆがめないこと。
 *
 * <p>ガイドは幾何学的な{@code Heuristic}とのmaxで使われるので、<b>実コストを上回った瞬間に
 * 経路の形が変わる</b>。表はチャンク（16ブロック）単位でしか値を持たないため、素のまま引くと
 * セルの中のどこにいても同じ値になり、hに16ブロック周期の鋸歯が乗る——ユーザー報告
 * 「直角にカクカクした挙動が多い」「経路が直感的ではない」の正体。
 */
class CostToGoGuideTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int GROUND_Y = 60;
    private static final int STAND_Y = 61;
    private static final int RADIUS = 96;

    private static FakeCells flatGround() {
        FakeCells cells = FakeCells.empty(
                new SearchBounds(-RADIUS, GROUND_Y - 8, -RADIUS, RADIUS, GROUND_Y + 32, RADIUS));
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                cells.set(x, GROUND_Y, z, FakeCells.STONE);
            }
        }
        return cells;
    }

    private static PathResult search(FakeCells cells, BlockPos start, BlockPos goal, CostToGo guide) {
        return new AStarPathfinder(cells,
                new SearchLimits(200_000, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT), guide)
                .search(start, goal, NEVER);
    }

    private static CostToGo guideFor(FakeCells cells, BlockPos start, BlockPos goal) {
        CoarseMap map = LiveCoarseSampler.sample(cells, cells.bounds(), start.getY(), NEVER);
        return CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.BRIDGE);
    }

    private static int diagonalSteps(BlockPos start, PathResult result) {
        int diagonals = 0;
        BlockPos previous = start;
        for (PathStep step : result.steps()) {
            if (step.pos().getX() != previous.getX() && step.pos().getZ() != previous.getZ()) {
                diagonals++;
            }
            previous = step.pos();
        }
        return diagonals;
    }

    /**
     * <b>開けた平地では、ガイドの有無で経路が1手も変わらないこと。</b>地形に理由が無いのだから、
     * 層1が経路の形に口を出す余地は無い。
     *
     * <p>ガイドがセル境界で実コストを上回っていた頃は、45度の目的地へ<b>斜め40手で足りる区間が
     * 60手（斜め22・直進38）</b>になっていた——チャンク境界へ吸い寄せられ、10ブロック以上の
     * 直進と直角だけで進んでいた。
     */
    @Test
    void theGuideDoesNotBendThePathOnOpenGround() {
        FakeCells cells = flatGround();
        BlockPos start = new BlockPos(0, STAND_Y, 0);
        for (BlockPos goal : new BlockPos[] {
                new BlockPos(40, STAND_Y, 40), new BlockPos(40, STAND_Y, 20),
                new BlockPos(60, STAND_Y, 15), new BlockPos(37, STAND_Y, 43)}) {
            PathResult plain = search(cells, start, goal, null);
            PathResult guided = search(cells, start, goal, guideFor(cells, start, goal));
            assertEquals(plain.steps().size(), guided.steps().size(),
                    "ガイドが経路を伸ばしている: goal=" + goal.toShortString());
            assertEquals(diagonalSteps(start, plain), diagonalSteps(start, guided),
                    "ガイドが斜めを直角に置き換えている: goal=" + goal.toShortString());
        }
    }

    /**
     * ガイドが幾何学的な下限（{@link Heuristic}）を上回らないこと。開けた平地では
     * {@code Heuristic}が実コストそのものなので、上回った時点でA*は非許容になる。
     *
     * <p>経路の形で見る{@link #theGuideDoesNotBendThePathOnOpenGround}より手前の性質を直接見る。
     * こちらだけが落ちるなら、ゆがみが出るほどではないが下限は壊れている、と切り分けられる。
     */
    @Test
    void theGuideStaysUnderTheGeometricLowerBound() {
        FakeCells cells = flatGround();
        BlockPos start = new BlockPos(0, STAND_Y, 0);
        BlockPos goal = new BlockPos(40, STAND_Y, 40);
        CostToGo guide = guideFor(cells, start, goal);
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 40; z++) {
                double lowerBound = Heuristic.estimate(x, STAND_Y, z, goal.getX(), goal.getY(), goal.getZ());
                assertTrue(guide.estimate(x, STAND_Y, z) <= lowerBound + 1.0e-9,
                        "ガイドが実コストを上回っている: " + x + "," + z
                                + " guide=" + guide.estimate(x, STAND_Y, z) + " 下限=" + lowerBound);
            }
        }
    }
}
