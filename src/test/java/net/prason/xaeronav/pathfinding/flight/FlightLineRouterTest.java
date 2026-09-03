package net.prason.xaeronav.pathfinding.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class FlightLineRouterTest {

    private static final SearchBounds BOUNDS = new SearchBounds(-200, 0, -200, 200, 300, 200);

    private static final Vec3 START = new Vec3(-100.0, 100.0, 0.0);
    private static final Vec3 GOAL = new Vec3(100.0, 100.0, 0.0);

    /** X=0付近に、高さ{@code top}・X方向の厚み{@code halfWidth*2}・Z方向の幅{@code halfDepth*2}の壁を置く。 */
    private static FakeCells wall(int halfWidth, int top, int halfDepth) {
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -halfDepth; z <= halfDepth; z++) {
                for (int y = BOUNDS.minY(); y <= top; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        return cells;
    }

    /** {@link #wall}と同じ形の水塊。 */
    private static FakeCells water(int halfWidth, int top, int halfDepth) {
        FakeCells cells = FakeCells.empty(BOUNDS);
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -halfDepth; z <= halfDepth; z++) {
                for (int y = BOUNDS.minY(); y <= top; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }
        return cells;
    }

    private static List<Vec3> route(FakeCells cells) {
        return new FlightLineRouter(cells).findGuideLine(START, GOAL);
    }

    /** 曲がり点が中点からどちらへ、どれだけずれたか。 */
    private static Vec3 bendOffset(List<Vec3> line) {
        return line.get(1).subtract(START.add(GOAL).scale(0.5));
    }

    @Test
    void goesStraightWhenNothingIsInTheWay() {
        assertEquals(List.of(START, GOAL), route(FakeCells.empty(BOUNDS)));
    }

    @Test
    void bendsAroundAThinTallSpire() {
        // 薄くて高い尖峰。越えるには何十マスも上がる必要があるが、横へは数マスで抜けられる
        List<Vec3> line = route(wall(2, 260, 2));

        assertEquals(3, line.size(), "曲がり点が入らず、尖峰を突き抜けたままになっている");
        Vec3 offset = bendOffset(line);
        assertTrue(Math.abs(offset.z) > Math.abs(offset.y),
                "細い尖峰は横に避けるべきだが、上を越えようとしている: " + offset);
    }

    @Test
    void climbsOverALowButVeryWideRidge() {
        // 低いが左右に広い尾根。横へ抜けるには探索半径いっぱいでも足りず、上へ数マス上がる方が安い
        List<Vec3> line = route(wall(4, 110, 180));

        assertEquals(3, line.size(), "曲がり点が入らず、尾根を突き抜けたままになっている");
        Vec3 offset = bendOffset(line);
        assertTrue(offset.y > Math.abs(offset.z),
                "広い尾根は上を越えるべきだが、横へ避けようとしている: " + offset);
    }

    @Test
    void keepsTheBentLineClearOfTheTerrain() {
        FakeCells cells = wall(2, 260, 2);
        List<Vec3> line = route(cells);

        FlightLineRouter router = new FlightLineRouter(cells);
        for (int i = 0; i + 1 < line.size(); i++) {
            assertTrue(router.findGuideLine(line.get(i), line.get(i + 1)).size() == 2,
                    "曲げた後の区間 " + i + " がまだ地形を貫いている");
        }
    }

    @Test
    void fallsBackToTheStraightLineWhenNothingClears() {
        // 上下・左右いずれの向きにも探索範囲を超えて広がる壁。曲げようが無い
        List<Vec3> line = route(wall(4, BOUNDS.maxY(), 200));

        assertEquals(List.of(START, GOAL), line,
                "避けられないときは素の直線へ落とすべき（線ごと消してはいけない）");
    }

    @Test
    void bendsAroundWater() {
        // 水も障害物。滑空の点線が水面を貫かないための挙動
        assertEquals(3, route(water(2, 260, 2)).size(), "水塊を突き抜けたまま曲がっていない");
    }

    @Test
    void ignoresUnknownCellsInsteadOfTreatingThemAsWalls() {
        // 未読み込み扱い(ABSENT)で埋めた空間。データが無いだけの場所を壁と見なすと、
        // 描画距離の遥か先を指す目的地では毎回「貫いている」判定になってしまう
        FakeCells cells = FakeCells.empty(BOUNDS).fillWith(FakeCells.ABSENT);

        assertEquals(List.of(START, GOAL), route(cells));
    }
}
