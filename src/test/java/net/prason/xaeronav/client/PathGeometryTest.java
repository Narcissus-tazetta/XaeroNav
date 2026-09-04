package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
}
