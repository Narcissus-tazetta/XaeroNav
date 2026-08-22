package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** 目印の大きさは画面上のピクセルで決まる。テストは1ブロック＝1ピクセルの縮尺で見る。 */
    private static final double PIXELS_PER_BLOCK = 1.0;

    /** 粗いルートの点線（{@link PathColors#COARSE_ROUTE}）だけを拾う。 */
    private static List<BlockPos> coarseDots(MapPathOverlay.Snapshot snapshot) {
        List<BlockPos> dots = new ArrayList<>();
        MapPathOverlay.draw(snapshot, (x1, z1, x2, z2, red, green, blue) -> {
            if (red == PathColors.COARSE_ROUTE[0] && green == PathColors.COARSE_ROUTE[1]
                    && blue == PathColors.COARSE_ROUTE[2]) {
                dots.add(new BlockPos(x1, Y, z1));
            }
        }, PIXELS_PER_BLOCK);
        return dots;
    }

    /** 目的地の目印を構成する矩形だけを拾う。 */
    private static List<int[]> markerRects(MapPathOverlay.Snapshot snapshot) {
        return markerRects(snapshot, PIXELS_PER_BLOCK);
    }

    private static List<int[]> markerRects(MapPathOverlay.Snapshot snapshot, double pixelsPerBlock) {
        List<int[]> rects = new ArrayList<>();
        MapPathOverlay.draw(snapshot, (x1, z1, x2, z2, red, green, blue) -> {
            if (isMarkerColor(red, green, blue)) {
                rects.add(new int[] {x1, z1, x2, z2});
            }
        }, pixelsPerBlock);
        return rects;
    }

    private static boolean isMarkerColor(float red, float green, float blue) {
        for (float[] color : List.of(PathColors.GOAL_MARKER, PathColors.GOAL_MARKER_HOLE,
                PathColors.GOAL_MARKER_OUTLINE)) {
            if (red == color[0] && green == color[1] && blue == color[2]) {
                return true;
            }
        }
        return false;
    }

    /** 目印を構成する矩形が覆うブロックの集合。 */
    private static Set<BlockPos> markerBlocks(MapPathOverlay.Snapshot snapshot) {
        Set<BlockPos> blocks = new HashSet<>();
        for (int[] rect : markerRects(snapshot)) {
            for (int x = rect[0]; x < rect[2]; x++) {
                for (int z = rect[1]; z < rect[3]; z++) {
                    blocks.add(new BlockPos(x, Y, z));
                }
            }
        }
        return blocks;
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
                new MapPathOverlay.Snapshot(null, new BlockPos(200, Y, 0), true, false,
                        player, waypoints, List.of(), 0, List.of());

        assertTrue(hasDotBetween(coarseDots(snapshot), 0, 100),
                "最初の中間目標までの区間が描かれず、点線がプレイヤーから離れて浮いている");
    }

    @Test
    void coarseRouteContinuesFromTheEndOfTheDetailPath() {
        BlockPos player = new BlockPos(0, Y, 0);
        PathResult detail = path(List.of(new BlockPos(0, Y, 0), new BlockPos(25, Y, 0), new BlockPos(50, Y, 0)));
        List<BlockPos> waypoints = List.of(new BlockPos(150, Y, 0), new BlockPos(250, Y, 0));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(detail, new BlockPos(250, Y, 0), true, false,
                        player, waypoints, List.of(), 0, List.of());

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
                new MapPathOverlay.Snapshot(detail, new BlockPos(500, Y, 0), true, false,
                        player, waypoints, List.of(), 0, List.of());

        assertFalse(hasDotBetween(coarseDots(snapshot), 0, 340),
                "経路の末端より後ろの中間目標まで点線が引き返し、黄色い点線が2本出ている");
    }

    /** 引き返しの読み飛ばしが、本当に後戻りするルート（始点が行き過ぎている）まで削らないこと。 */
    @Test
    void coarseRouteKeepsAGenuineBacktrack() {
        BlockPos player = new BlockPos(120, Y, 0);
        List<BlockPos> waypoints = List.of(new BlockPos(100, Y, 0), new BlockPos(100, Y, 200));
        MapPathOverlay.Snapshot snapshot =
                new MapPathOverlay.Snapshot(null, new BlockPos(100, Y, 200), true, false,
                        player, waypoints, List.of(), 0, List.of());

        assertTrue(coarseDots(snapshot).stream().anyMatch(dot -> dot.getZ() > 20 && dot.getZ() < 180),
                "後戻りを含むルートの先頭が読み飛ばされ、点線がルートから外れている");
    }

    /**
     * 目的地の目印は、経路や点線を消していても出ること。地図を開いて分からないのは
     * 「どこへ向かっているのか」であって、そこは点線の設定とは別の話。
     */
    @Test
    void goalPinStandsOnTheDestinationWithoutTheDottedLine() {
        BlockPos goal = new BlockPos(400, Y, -200);
        MapPathOverlay.Snapshot snapshot = new MapPathOverlay.Snapshot(null, goal, false, true,
                new BlockPos(0, Y, 0), List.of(), List.of(), 0, List.of());

        Set<BlockPos> blocks = markerBlocks(snapshot);
        assertTrue(blocks.contains(goal), "ピンの先端が目的地のブロックを指していない");
        assertTrue(blocks.stream().allMatch(block -> block.getZ() <= goal.getZ()),
                "ピンが目的地より南へはみ出している（先端で指すのではなく目的地を跨いでいる）");
        assertTrue(blocks.stream().anyMatch(block -> block.getZ() < goal.getZ() - 5),
                "ピンに高さが無く、先端しか描かれていない");
    }

    @Test
    void goalMarkerIsAbsentWhenTurnedOff() {
        BlockPos goal = new BlockPos(400, Y, -200);
        MapPathOverlay.Snapshot snapshot = new MapPathOverlay.Snapshot(null, goal, true, false,
                new BlockPos(0, Y, 0), List.of(), List.of(), 0, List.of());

        assertTrue(markerRects(snapshot).isEmpty(), "設定で切っているのに目的地の目印が描かれている");
    }

    /**
     * 目印は地図を縮小しても画面上の大きさを保つ＝縮尺が半分なら、覆うブロック数は倍になる。
     * ここが崩れると、全体を見渡すために縮小したときに目印だけが小さくなって消える——
     * 目的地を見失うのはまさにその場面なので、この性質が目印の存在意義そのものになる。
     */
    @Test
    void goalMarkerKeepsItsSizeOnScreenAsTheMapZoomsOut() {
        MapPathOverlay.Snapshot snapshot = new MapPathOverlay.Snapshot(null, new BlockPos(0, Y, 0), false, true,
                new BlockPos(0, Y, 0), List.of(), List.of(), 0, List.of());

        int atOnePixelPerBlock = markerSpanBlocks(snapshot, 1.0);
        int zoomedOut = markerSpanBlocks(snapshot, 0.5);

        assertTrue(zoomedOut >= atOnePixelPerBlock * 2 - 2,
                "縮小したのに目印がブロック数で大きくならず、画面上では小さくなっている（"
                        + atOnePixelPerBlock + " → " + zoomedOut + "）");
    }

    /** 目印が覆う範囲の一辺（ブロック）。 */
    private static int markerSpanBlocks(MapPathOverlay.Snapshot snapshot, double pixelsPerBlock) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] rect : markerRects(snapshot, pixelsPerBlock)) {
            min = Math.min(min, rect[0]);
            max = Math.max(max, rect[2]);
        }
        return max - min;
    }
}
