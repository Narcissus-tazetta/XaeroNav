package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 描画用に焼き固めた経路の幾何。
 *
 * <p>ここで確かめるのは<b>通り過ぎた区間を切り詰める点</b>だけ。水の区間は一直線でなくても
 * 1本へ畳むので、畳む前のステップ位置は畳んだ線から外れている——そこを切り口にすると、
 * 1手進むごとに線の手前側が別の向きへ振れる。
 */
class PathGeometryTest {

    @Test
    void theCutPointStaysOnTheLine() {
        double[] out = new double[3];

        // 弦(0,0,0)-(10,0,0)から1マス横へ外れた生のステップ位置
        PathGeometry.projectOntoSegment(4.0, 0.0, 1.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, out);

        assertEquals(4.0, out[0], 1.0e-9);
        assertEquals(0.0, out[1], 1.0e-9);
        assertEquals(0.0, out[2], 1.0e-9, "弦の上へ戻す");
    }

    @Test
    void theCutPointDoesNotRunOffTheEnds() {
        double[] out = new double[3];

        PathGeometry.projectOntoSegment(-5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, out);

        assertEquals(0.0, out[0], 1.0e-9, "区間の手前へは出さない（前の区間へ食い込む）");
    }

    @Test
    void onlyTheBlockPlacedWhereThePlayerStandsIsAPillar() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos standBelowStep = new BlockPos(1, 64, 0);
        List<PathStep> steps = List.of(
                step(standBelowStep, MovementType.TRAVERSE, null),
                // 足元に置いて真上へ上る
                step(new BlockPos(1, 65, 0), MovementType.ASCEND, standBelowStep),
                // 橋: 次に立つ所の下へ置く
                step(new BlockPos(2, 65, 0), MovementType.TRAVERSE, new BlockPos(2, 64, 0)));

        assertTrue(PathGeometry.placesUnderPrevious(steps, 1, start));
        assertFalse(PathGeometry.placesUnderPrevious(steps, 2, start));
        assertFalse(PathGeometry.placesUnderPrevious(steps, 0, start), "置かない手");
    }

    private static PathStep step(BlockPos pos, MovementType movement, BlockPos placed) {
        return new PathStep(pos, movement, 1.0, List.of(pos, pos.above()), List.of(), PathRisk.NONE, placed);
    }
}
