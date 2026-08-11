package net.prason.xaeronav.pathfinding.elytra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * エリトラ経路の直線判定（Amanatides–Wooのボクセル走査）。
 *
 * <p>ここの粗さはそのまま事故になる。エリトラは秒速30〜40マスで飛ぶので、見落とした壁は激突と同義。
 * 一定間隔で点を打つ方式なら見落とす「1マス厚の壁」を確実に捕まえられることを確かめる。
 */
class ElytraTraversalTest {

    private static final SearchBounds BOUNDS = new SearchBounds(-64, 0, -64, 64, 128, 64);

    private static ElytraPath findPath(FakeCells cells, Vec3 start, Vec3 goal) {
        return new ElytraPathfinder(cells).findPath(start, goal);
    }

    private static FakeCells openSky() {
        return FakeCells.empty(BOUNDS);
    }

    @Test
    void clearAirGivesAStraightTwoPointPath() {
        ElytraPath path = findPath(openSky(), new Vec3(0.5, 100.5, 0.5), new Vec3(40.5, 100.5, 0.5));

        assertTrue(path.complete());
        assertEquals(2, path.waypoints().size(), "遮るものが無ければ始点と終点だけで足りる");
    }

    @Test
    void aOneBlockThickWallIsNotFlownThrough() {
        // x=20 に1マス厚の壁。5〜10マス間隔でサンプルする方式ではまたいで見落とす厚さ
        FakeCells cells = openSky();
        for (int y = 90; y <= 110; y++) {
            for (int z = -5; z <= 5; z++) {
                cells.set(20, y, z, FakeCells.STONE);
            }
        }

        ElytraPath path = findPath(cells, new Vec3(0.5, 100.5, 0.5), new Vec3(40.5, 100.5, 0.5));

        assertFalse(path.waypoints().size() == 2 && path.complete(),
                "1マス厚の壁を素通りする直線経路を返してはいけない");
        assertTrue(noSegmentCrossesTheWall(path.waypoints()),
                "組み立てた経路のどの区間も壁を貫通していない: " + path.waypoints());
    }

    @Test
    void aWallIsClearedByGoingOverIt() {
        // 壁の上が開いている。高度を上げれば越えられるので、完了扱いで越える経路が出るべき
        FakeCells cells = openSky();
        for (int y = 0; y <= 100; y++) {
            for (int z = -8; z <= 8; z++) {
                cells.set(20, y, z, FakeCells.STONE);
            }
        }

        ElytraPath path = findPath(cells, new Vec3(0.5, 100.5, 0.5), new Vec3(40.5, 100.5, 0.5));

        assertTrue(path.complete(), "越えられる壁は越える");
        assertTrue(path.waypoints().stream().anyMatch(point -> point.y > 105.0),
                "尾根より上へ高度を上げる: " + path.waypoints());
        assertTrue(noSegmentCrossesTheWall(path.waypoints()));
    }

    @Test
    void aSealedGoalIsReportedAsIncomplete() {
        // 目的地が岩盤の箱の中。届かないことを「完了」と言ってはいけない
        FakeCells cells = openSky();
        for (int x = 38; x <= 42; x++) {
            for (int y = 98; y <= 102; y++) {
                for (int z = -2; z <= 2; z++) {
                    boolean shell = x == 38 || x == 42 || y == 98 || y == 102 || z == -2 || z == 2;
                    if (shell) {
                        cells.set(x, y, z, FakeCells.BEDROCK);
                    }
                }
            }
        }

        ElytraPath path = findPath(cells, new Vec3(0.5, 100.5, 0.5), new Vec3(40.5, 100.5, 0.5));

        assertFalse(path.complete(), "密閉された目的地へは到達できない");
    }

    @Test
    void degenerateSegmentsAreHandled() {
        // 始点と終点が同じセル。境界を1つも跨がないので、走査ループが止まることを確かめる
        ElytraPath path = findPath(openSky(), new Vec3(10.5, 100.5, 10.5), new Vec3(10.5, 100.5, 10.5));

        assertTrue(path.complete());
        assertEquals(2, path.waypoints().size());
    }

    /** どの区間も x=20 の壁面を横切っていないか（横切るなら壁を貫通している）。 */
    private static boolean noSegmentCrossesTheWall(List<Vec3> waypoints) {
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            Vec3 from = waypoints.get(i);
            Vec3 to = waypoints.get(i + 1);
            boolean crosses = (from.x < 20.0 && to.x > 21.0) || (from.x > 21.0 && to.x < 20.0);
            // 壁の高さ(y<=110)を保ったまま横切るのが貫通。上を越えるぶんには問題ない
            if (crosses && Math.min(from.y, to.y) <= 110.0) {
                return false;
            }
        }
        return true;
    }
}
