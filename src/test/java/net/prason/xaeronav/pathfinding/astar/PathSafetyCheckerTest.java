package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * 提示直前の安全性チェック。コストで表現しきれない「歩けるが条件がある」区間に
 * 印が付くかを見る。
 */
class PathSafetyCheckerTest {

    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    /**
     * マグマブロックは足場として通行可（スニークすれば無傷）だが、走って踏めば燃える。
     * 通れる以上、条件を伝えないと「案内どおり歩いたら焼かれた」になる。
     */
    @Test
    void marksStepsOverMagmaAsNeedingASneak() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                .....
                #MMM#""")
                .extrudeZ(-1, 1);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), new BlockPos(4, 61, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.complete(), "マグマブロックは通行可");
        assertEquals(3, annotated.steps().stream()
                        .filter(step -> step.risk() == PathRisk.SNEAK_OVER_MAGMA).count(),
                "マグマの上を通る3歩すべてに印が付くはず: " + annotated.steps());
    }

    /**
     * 奈落の上に架ける橋。足場を1つ外せば落ちて助からないので、底のある割れ目に架ける橋
     * （印なし＝シアン）とは区別して描く必要がある。
     */
    @Test
    void marksBridgesOverABottomlessGap() {
        CellSource cells = FakeCells.empty(new SearchBounds(-8, 28, 0, 12, 93, 0))
                .canPlaceBlocks(true)
                .set(0, 60, 0, FakeCells.BEDROCK)
                .set(4, 60, 0, FakeCells.BEDROCK);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), new BlockPos(4, 61, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.complete(), "奈落の上にも橋は架かる: " + annotated.steps());
        assertEquals(3, annotated.steps().stream()
                        .filter(step -> step.risk() == PathRisk.VOID_BELOW).count(),
                "奈落の上の足場すべてに印が付くはず: " + annotated.steps());
    }

    /**
     * 底のある割れ目に架ける橋は警告しない。足場を外しても落ちるだけで、そこから登り直せる。
     *
     * <p>割れ目を4マス深くしてあるのは、降りて歩いて登る経路を潰して<b>橋を強制する</b>ため
     * （安全な落下は3マスまで）。1マスの段差にすると、橋が一本も出ないまま
     * 「警告が無い」ことだけを確かめる空のテストになる。
     */
    @Test
    void leavesBridgesOverAFlooredGapUnmarked() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                .....
                #...#
                #...#
                #...#
                #...#
                #####""")
                .canPlaceBlocks(true)
                .jumpGapEnabled(false)
                .fillWith(FakeCells.BEDROCK);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 65, 0), new BlockPos(4, 65, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.complete(), "底のある割れ目は渡れる: " + annotated.steps());
        assertEquals(3, annotated.steps().stream().filter(PathStep::bridging).count(),
                "この割れ目は橋でしか渡れない: " + annotated.steps());
        assertTrue(annotated.steps().stream().allMatch(step -> step.risk() == PathRisk.NONE),
                "床の見える割れ目の橋に警告を出してはいけない: " + annotated.steps());
    }

    @Test
    void leavesOrdinaryGroundUnmarked() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                .....
                #####""")
                .extrudeZ(-1, 1);

        PathResult raw = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), new BlockPos(4, 61, 0), NOT_CANCELLED);
        PathResult annotated = PathSafetyChecker.annotate(cells, raw);

        assertTrue(annotated.steps().stream().allMatch(step -> step.risk() == PathRisk.NONE),
                "普通の地面に印を付けてはいけない: " + annotated.steps());
    }
}
