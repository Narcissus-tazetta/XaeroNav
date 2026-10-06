package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void theBoatOutlineSitsOneStepPastBoarding() {
        // 岸(0,64,0)から東へ漕ぎ出し、4マス先で岸へ上がる
        List<PathStep> steps = List.of(
                step(1, 63, 0, MovementType.BOAT),
                step(2, 63, 0, MovementType.BOAT),
                step(3, 63, 0, MovementType.BOAT),
                step(4, 64, 0, MovementType.TRAVERSE));

        PathGeometry.BoatLaunch[] launches = PathGeometry.boatLaunches(steps, new BlockPos(0, 64, 0));

        assertEquals(1, launches.length);
        assertEquals(1, launches[0].step(), "乗り込む手の1つ先");
        assertEquals(2.5, launches[0].x(), 1.0e-9);
        assertEquals(1.0, launches[0].forwardX(), 1.0e-9, "東を向く");
        assertEquals(0.0, launches[0].forwardZ(), 1.0e-9);
    }

    @Test
    void aOneStepBoatRideKeepsItsOutlineOnTheBoardingStep() {
        List<PathStep> steps = List.of(
                step(1, 64, 0, MovementType.TRAVERSE),
                step(2, 63, 0, MovementType.BOAT),
                step(3, 64, 0, MovementType.TRAVERSE),
                step(4, 63, 1, MovementType.BOAT),
                step(5, 63, 2, MovementType.BOAT));

        PathGeometry.BoatLaunch[] launches = PathGeometry.boatLaunches(steps, new BlockPos(0, 64, 0));

        assertEquals(2, launches.length, "ボートの区間ごとに1つ");
        assertEquals(1, launches[0].step());
        assertEquals(4, launches[1].step());
        double diagonal = Math.sqrt(0.5);
        assertEquals(diagonal, launches[1].forwardX(), 1.0e-9, "斜めに入ってくるなら斜めを向く");
        assertEquals(diagonal, launches[1].forwardZ(), 1.0e-9);
    }

    @Test
    void theBoatOutlineFollowsTheDrawnLine() {
        // 手は東向きだが、線は(0,0)→(10,10)へ斜めに畳まれている
        PathGeometry.BoatLaunch[] launches = {new PathGeometry.BoatLaunch(1, 2.5, 64.0, 0.5, 1.0, 0.0)};

        PathGeometry.alignToLine(launches, new double[] {0.0, 10.0}, new double[] {0.0, 10.0}, new int[] {5}, 1);

        double diagonal = Math.sqrt(0.5);
        assertEquals(diagonal, launches[0].forwardX(), 1.0e-9, "線と同じ向き");
        assertEquals(diagonal, launches[0].forwardZ(), 1.0e-9);
        assertEquals(launches[0].x(), launches[0].z(), 1.0e-9, "線の上に載る");
    }

    private static PathStep step(int x, int y, int z, MovementType movement) {
        return new PathStep(new BlockPos(x, y, z), movement, 1.0, List.of(), List.of(), PathRisk.NONE, null);
    }
}
