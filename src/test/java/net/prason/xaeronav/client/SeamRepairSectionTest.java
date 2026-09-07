package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 繋ぎ目をまたぐ区間だけを差し替えたとき、経路と<b>区間の境目</b>が正しく張り直されること。
 *
 * <p>境目はHUDの「何番目の中継地点へ向かっているか」と、地図の点線（未通過ぶんだけ描く）が
 * 見ている。差し替えは前後の添字を動かすので、ここがずれると案内の数字だけが飛ぶ。
 */
class SeamRepairSectionTest {

    private static final int Y = 64;

    private static PathStep step(int x, double cost) {
        return new PathStep(new BlockPos(x, Y, 0), MovementType.TRAVERSE, cost,
                List.of(), List.of(), PathRisk.NONE, null);
    }

    /** x=1..12へ1歩ずつ進む、コスト4の経路。 */
    private static List<PathStep> straight() {
        List<PathStep> steps = new ArrayList<>();
        for (int x = 1; x <= 12; x++) {
            steps.add(step(x, 4.0));
        }
        return steps;
    }

    private static PathfindingState.DisplayedPath shown(List<PathStep> steps,
                                                        List<PathfindingState.PathSegment> segments) {
        PathResult result = new PathResult(steps, PathResult.Termination.REACHED_GOAL, 0, 0);
        return new PathfindingState.DisplayedPath(result, PathfindingState.PathMode.WAYPOINT, 2,
                segments);
    }

    /** 差し替えた区間の外は1ステップも動かない。 */
    @Test
    void keepsEverythingOutsideTheSection() {
        List<PathStep> steps = straight();
        PathfindingState.DisplayedPath before = shown(steps,
                List.of(new PathfindingState.PathSegment(11, 2)));
        // x=4..8（添字3..7）を、コストの安い2ステップへ差し替える
        List<PathStep> section = List.of(step(20, 1.0), step(8, 1.0));

        List<PathStep> after = PathfindingState.withSection(before, section, 3, 7).result().steps();

        assertEquals(steps.subList(0, 3), after.subList(0, 3));
        assertEquals(steps.subList(8, 12), after.subList(after.size() - 4, after.size()));
        assertEquals(3 + section.size() + 4, after.size());
    }

    /** 差し替えた中にあった境目は消え、その中間目標の番号は後ろの区間が引き取る。 */
    @Test
    void dropsSegmentBoundariesInsideTheSection() {
        List<PathStep> steps = straight();
        PathfindingState.DisplayedPath before = shown(steps, List.of(
                new PathfindingState.PathSegment(2, 0),
                new PathfindingState.PathSegment(6, 1),
                new PathfindingState.PathSegment(11, 2)));
        List<PathStep> section = List.of(step(20, 1.0), step(8, 1.0));

        PathfindingState.DisplayedPath after = PathfindingState.withSection(before, section, 3, 7);

        assertEquals(List.of(new PathfindingState.PathSegment(2, 0),
                        new PathfindingState.PathSegment(after.result().steps().size() - 1, 2)),
                after.segments());
    }

    /** 経路の末尾まで差し替えても、最後の区間は必ず末端まで届く。 */
    @Test
    void alwaysCoversTheEnd() {
        List<PathStep> steps = straight();
        PathfindingState.DisplayedPath before = shown(steps, List.of(
                new PathfindingState.PathSegment(5, 1),
                new PathfindingState.PathSegment(11, 2)));
        List<PathStep> section = List.of(step(20, 1.0));

        PathfindingState.DisplayedPath after = PathfindingState.withSection(before, section, 8, 11);

        List<PathfindingState.PathSegment> segments = after.segments();
        assertEquals(after.result().steps().size() - 1, segments.get(segments.size() - 1).endStep());
        assertEquals(2, after.waypointIndexAtStep(after.result().steps().size() - 1));
    }

    /** 差し替えた区間が前後と同じ位置を踏んでいたら畳む（繋ぎ目で線が重ならない）。 */
    @Test
    void foldsOverlapAtTheNewSeam() {
        List<PathStep> steps = straight();
        PathfindingState.DisplayedPath before = shown(steps,
                List.of(new PathfindingState.PathSegment(11, 2)));
        // 差し替えた区間が、手前で通ったx=2へ戻ってから進む
        List<PathStep> section = List.of(step(2, 1.0), step(8, 1.0));

        List<PathStep> after = PathfindingState.withSection(before, section, 3, 7).result().steps();

        long visits = after.stream().filter(s -> s.pos().getX() == 2).count();
        assertEquals(1, visits);
        assertTrue(after.size() < 3 + section.size() + 4);
    }
}
