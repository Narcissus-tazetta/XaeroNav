package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;

class FlightProgressTest {

    private static final double THRESHOLD = 24.0;

    /** 頂点が100ブロック離れた、水平にまっすぐな2区間の経路。 */
    private static FlightRoute straight() {
        return new FlightRoute(List.of(
                new Vec3(0.0, 64.0, 0.0),
                new Vec3(100.0, 64.0, 0.0),
                new Vec3(200.0, 64.0, 0.0)), PathResult.Termination.REACHED_GOAL, 1, 4);
    }

    private static FlightProgress at(FlightRoute route, Vec3 position) {
        FlightProgress.INSTANCE.update(route, position);
        return FlightProgress.INSTANCE;
    }

    @Test
    void onTheLineBetweenTwoDistantPointsCountsAsZeroOffset() {
        // 頂点で測っていたらここは50ブロックのずれになる。線分で測るからこそ0になる
        FlightProgress progress = at(straight(), new Vec3(50.0, 64.0, 0.0));

        assertEquals(0.0, progress.horizontalOffset(), 1.0e-6);
        assertEquals(0.0, progress.verticalOffset(), 1.0e-6);
        assertFalse(progress.deviated(THRESHOLD));
    }

    @Test
    void driftingSidewaysWithinToleranceDoesNotCountAsDeviation() {
        assertFalse(at(straight(), new Vec3(50.0, 64.0, 20.0)).deviated(THRESHOLD));
    }

    @Test
    void driftingWellOutsideToleranceCountsAsDeviation() {
        assertTrue(at(straight(), new Vec3(50.0, 64.0, 40.0)).deviated(THRESHOLD));
    }

    @Test
    void verticalDriftIsAllowedFurtherThanHorizontal() {
        // 高度のぶれは水平より大きい。水平で外れる幅でも垂直なら許す
        double justOverHorizontal = THRESHOLD * 1.2;

        assertTrue(at(straight(), new Vec3(50.0, 64.0, justOverHorizontal)).deviated(THRESHOLD));
        assertFalse(at(straight(), new Vec3(50.0, 64.0 + justOverHorizontal, 0.0)).deviated(THRESHOLD));
    }

    @Test
    void combinedHorizontalAndVerticalDriftAddUp() {
        // 楕円体で見る理由。どちらの軸でも単独では許容内なのに、合わせると外れている
        FlightRoute route = straight();

        assertFalse(at(route, new Vec3(50.0, 64.0, 20.0)).deviated(THRESHOLD));
        assertFalse(at(route, new Vec3(50.0, 94.0, 0.0)).deviated(THRESHOLD));
        assertTrue(at(route, new Vec3(50.0, 94.0, 20.0)).deviated(THRESHOLD));
    }

    @Test
    void tracksWhichSegmentThePlayerIsOn() {
        FlightRoute route = straight();

        assertEquals(0, at(route, new Vec3(10.0, 64.0, 0.0)).segmentFor(route));
        assertEquals(1, at(route, new Vec3(150.0, 64.0, 0.0)).segmentFor(route));
    }

    @Test
    void theSegmentIndexFollowsTheRouteNotTheStartOfTheList() {
        // 点線の切り詰めはこの添字を使う。プレイヤーではなく太線の末端を渡す側の責任だが、
        // ここが「常に0」だと切り詰めが一切効かず、点線が末端から後ろへ戻って2本に見える
        FlightRoute route = straight();

        assertEquals(0, at(route, new Vec3(0.0, 64.0, 0.0)).segmentFor(route));
        assertEquals(1, at(route, new Vec3(199.0, 64.0, 0.0)).segmentFor(route));
    }

    @Test
    void carryingOverKeepsTheSegmentWhenTheRouteIsExtended() {
        // 継ぎ足しは手前の点の添字を変えないので、対応づけはそのまま通用する。
        // 引き継がないと添字が0へ戻り、伸ばした瞬間だけ通過済みの区間が描き直される
        FlightRoute route = straight();
        at(route, new Vec3(150.0, 64.0, 0.0));
        assertEquals(1, FlightProgress.INSTANCE.segmentFor(route));

        FlightRoute extended = route.append(new FlightRoute(
                List.of(new Vec3(200.0, 64.0, 0.0), new Vec3(300.0, 64.0, 0.0)),
                PathResult.Termination.REACHED_GOAL, 1, 4));
        FlightProgress.INSTANCE.carryOver(extended);

        assertEquals(4, extended.points().size(), "継ぎ足しで点が重複している");
        assertEquals(1, FlightProgress.INSTANCE.segmentFor(extended),
                "継ぎ足しで対応づけが先頭へ戻っている");
    }

    @Test
    void anEmptyRouteReportsNoDeviation() {
        assertFalse(at(FlightRoute.NONE, new Vec3(0.0, 64.0, 0.0)).deviated(THRESHOLD));
    }
}
