package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 長距離ルートの点線が、地図上で現在地から切り離されないこと。
 *
 * <p>粗いルートは目的地が変わらない限り引き直さないので、中間目標だけを順に結ぶと、進むほど
 * 点線の始点が「ルートを計算した当時の位置」に取り残される。地図上では古いルートが残って
 * いるようにしか見えず、実際に何度も誤診を招いた箇所なので、始点の連続性をここで固定する。
 */
class MapPathOverlayTest {

    private static final int Y = 64;

    /** 粗いルートの点線（{@link PathColors#COARSE_ROUTE}）だけを拾う。 */
    private static List<BlockPos> coarseDots(MapPathOverlay.Snapshot snapshot) {
        List<BlockPos> dots = new ArrayList<>();
        MapPathOverlay.draw(snapshot, (x, z, red, green, blue) -> {
            if (red == PathColors.COARSE_ROUTE[0] && green == PathColors.COARSE_ROUTE[1]
                    && blue == PathColors.COARSE_ROUTE[2]) {
                dots.add(new BlockPos(x, Y, z));
            }
        });
        return dots;
    }

    private static PathResult path(List<BlockPos> positions) {
        List<PathStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new PathStep(pos, MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null));
        }
        return new PathResult(steps, PathResult.Termination.REACHED_GOAL, positions.size(), positions.size());
    }

    private static boolean hasDotBetween(List<BlockPos> dots, int fromX, int toX) {
        return dots.stream().anyMatch(dot -> dot.getX() > fromX && dot.getX() < toX);
    }

    @Test
    void coarseRouteReachesBackToThePlayerWhenNoDetailPathExists() {
        BlockPos player = new BlockPos(0, Y, 0);
        List<BlockPos> waypoints = List.of(new BlockPos(100, Y, 0), new BlockPos(200, Y, 0));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(null, new BlockPos(200, Y, 0), player, waypoints, List.of(), 0, List.of());

        assertTrue(hasDotBetween(coarseDots(snapshot), 0, 100),
                "最初の中間目標までの区間が描かれず、点線がプレイヤーから離れて浮いている");
    }

    @Test
    void coarseRouteContinuesFromTheEndOfTheDetailPath() {
        BlockPos player = new BlockPos(0, Y, 0);
        PathResult detail = path(List.of(new BlockPos(0, Y, 0), new BlockPos(25, Y, 0), new BlockPos(50, Y, 0)));
        List<BlockPos> waypoints = List.of(new BlockPos(150, Y, 0), new BlockPos(250, Y, 0));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(detail, new BlockPos(250, Y, 0), player, waypoints, List.of(), 0, List.of());

        assertTrue(hasDotBetween(coarseDots(snapshot), 50, 150),
                "詳細経路の末端と最初の中間目標の間が繋がっていない");
    }

    /**
     * 詳細経路が中間目標を辿っていない間（層2の精緻化中は本来の目的地へ直接向かう）、通過済みの
     * 目印は進まないので未通過ぶんの先頭は現在地の遥か後ろに残る。そのまま順に結ぶと、後ろへ戻る線と
     * ルート本体の線が並んで走り、黄色い点線が2本出ているようにしか見えない。
     */
    @Test
    void coarseRouteDoesNotRunBackToWaypointsAlreadyBehind() {
        BlockPos player = new BlockPos(300, Y, 0);
        PathResult detail = path(List.of(new BlockPos(300, Y, 0), new BlockPos(325, Y, 0), new BlockPos(350, Y, 0)));
        List<BlockPos> waypoints = List.of(
                new BlockPos(0, Y, 0), new BlockPos(100, Y, 0), new BlockPos(200, Y, 0),
                new BlockPos(300, Y, 0), new BlockPos(400, Y, 0), new BlockPos(500, Y, 0));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(detail, new BlockPos(500, Y, 0), player, waypoints, List.of(), 0, List.of());

        assertFalse(hasDotBetween(coarseDots(snapshot), 0, 340),
                "経路の末端より後ろの中間目標まで点線が引き返し、黄色い点線が2本出ている");
    }

    /** 引き返しの読み飛ばしが、本当に後戻りするルート（始点が行き過ぎている）まで削らないこと。 */
    @Test
    void coarseRouteKeepsAGenuineBacktrack() {
        BlockPos player = new BlockPos(120, Y, 0);
        List<BlockPos> waypoints = List.of(new BlockPos(100, Y, 0), new BlockPos(100, Y, 200));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(null, new BlockPos(100, Y, 200), player, waypoints, List.of(), 0, List.of());

        assertTrue(coarseDots(snapshot).stream().anyMatch(dot -> dot.getZ() > 20 && dot.getZ() < 180),
                "後戻りを含むルートの先頭が読み飛ばされ、点線がルートから外れている");
    }
}
