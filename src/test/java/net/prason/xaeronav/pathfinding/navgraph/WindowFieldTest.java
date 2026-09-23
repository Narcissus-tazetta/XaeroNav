package net.prason.xaeronav.pathfinding.navgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class WindowFieldTest {

    private static final int FLOOR_Y = 64;

    /** 64×64の平らな石の床。{@code wall}なら x=32 に岩盤の壁を立て、z=8..9 だけ通れる隙間を空ける。 */
    private static FakeCells world(boolean wall) {
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 48, 0, 63, 96, 63));
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                cells.set(x, FLOOR_Y, z, FakeCells.BEDROCK);
                for (int y = 48; y < FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.BEDROCK);
                }
                if (wall && x == 32 && (z < 8 || z > 9)) {
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
                        cells.set(x, y, z, FakeCells.BEDROCK);
                    }
                }
            }
        }
        return cells;
    }

    private static double optimalCost(FakeCells cells, BlockPos start, BlockPos goal) {
        PathResult result = new AStarPathfinder(cells, new SearchLimits(2_000_000, 60_000, 1.0))
                .search(start, goal, () -> false);
        assertTrue(result.complete(), "基準の探索が届かない");
        return result.steps().stream().mapToDouble(PathStep::cost).sum();
    }

    private static NavGraph built(FakeCells cells, BlockPos goal, int centerX, int centerZ, int radius) {
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        LoadedArea everything = LoadedArea.square(centerX, centerZ, 1 << 20);
        long[] keys = graph.missingSections(centerX, centerZ, radius, everything);
        assertTrue(graph.build(cells, keys, 0, keys.length, everything, () -> false));
        return graph;
    }

    @Test
    void matchesTheOptimalSearchAroundAWall() {
        FakeCells cells = world(true);
        BlockPos start = new BlockPos(10, FLOOR_Y + 1, 50);
        BlockPos goal = new BlockPos(54, FLOOR_Y + 1, 50);
        NavGraph graph = built(cells, goal, 32, 32, 40);
        WindowField field = graph.field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);

        double optimal = optimalCost(cells, start, goal);
        // 壁の隙間まで回り込むので、直線（幾何下限）よりずっと高い
        assertTrue(optimal > 1.5 * Heuristic.estimate(start.getX(), start.getY(), start.getZ(), goal.getX(),
                goal.getY(), goal.getZ()));
        assertEquals(optimal, field.estimate(start.getX(), start.getY(), start.getZ()), optimal * 0.01);
        assertEquals(0.0, field.estimate(goal.getX(), goal.getY(), goal.getZ()), 1e-9);
    }

    @Test
    void retargetingTheGoalHeightMatchesAFreshGraph() {
        // 実機のネザー: 地図のYが岩の中に落ちた目的地を、列が読み込まれてから立てる高さへ寄せ直す
        FakeCells cells = world(true);
        BlockPos inRock = new BlockPos(54, FLOOR_Y - 8, 50);
        BlockPos standable = new BlockPos(54, FLOOR_Y + 1, 50);
        NavGraph retargeted = built(cells, inRock, 32, 32, 40);
        retargeted.retarget(standable);
        WindowField reused = retargeted.field(32, 32, 40, FarField.UNKNOWN, () -> false);
        WindowField fresh = built(cells, standable, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(reused);
        assertNotNull(fresh);
        for (int x = 0; x < 64; x += 3) {
            for (int z = 0; z < 64; z += 3) {
                assertEquals(fresh.estimate(x, FLOOR_Y + 1, z), reused.estimate(x, FLOOR_Y + 1, z), 1e-9,
                        "(" + x + ", " + z + ")");
            }
        }
    }

    @Test
    void refusesToRetargetToAnotherColumn() {
        NavGraph graph = new NavGraph(new BlockPos(54, FLOOR_Y + 1, 50), 48, 96);
        assertThrows(IllegalArgumentException.class, () -> graph.retarget(new BlockPos(55, FLOOR_Y + 1, 50)));
    }

    @Test
    void neverReturnsZeroOffTheGraph() {
        FakeCells cells = world(true);
        BlockPos goal = new BlockPos(54, FLOOR_Y + 1, 50);
        WindowField field = built(cells, goal, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        // 殻（立てる高さの上下2）より高い空中。グラフのノードではない
        double high = field.estimate(10, FLOOR_Y + 12, 50);
        assertTrue(high >= Heuristic.estimate(10, FLOOR_Y + 12, 50, goal.getX(), goal.getY(), goal.getZ()),
                "グラフに無い点が幾何下限を下回った: " + high);
        // 立ち位置の真上1マス（置いたブロックの上に立つ形）。近くのノードの値から延びて、壁の回り込みを知っている
        double onPlacedBlock = field.estimate(10, FLOOR_Y + 2, 50);
        assertTrue(onPlacedBlock > field.estimate(10, FLOOR_Y + 1, 50) * 0.9, "近くの値から延びていない: " + onPlacedBlock);
    }

    @Test
    void seedsTheWindowEdgeFromTheFarField() {
        FakeCells cells = world(false);
        BlockPos start = new BlockPos(8, FLOOR_Y + 1, 32);
        BlockPos goal = new BlockPos(60, FLOOR_Y + 1, 32);
        // 窓は x=-16..40 付近まで。目的地は外
        NavGraph graph = built(cells, goal, 12, 32, 24);
        // 平らな床の上では直線（幾何下限）がそのまま最適なので、外の値として正確
        FarField far = (x, y, z) -> Heuristic.estimate(x, y, z, goal.getX(), goal.getY(), goal.getZ());
        WindowField field = graph.field(12, 32, 24, far, () -> false);
        assertNotNull(field);
        double optimal = optimalCost(cells, start, goal);
        assertEquals(optimal, field.estimate(start.getX(), start.getY(), start.getZ()), optimal * 0.01);
    }

    @Test
    void tellsASealedPocketApart() {
        // 地面の16ブロック下に、掘らないと出られない小部屋。殻は立てる点の上下2までなので、地表と繋がらない
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 40, 0, 63, 96, 63));
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                for (int y = 40; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        int pocketY = FLOOR_Y - 16;
        for (int x = 9; x <= 11; x++) {
            for (int z = 49; z <= 51; z++) {
                cells.set(x, pocketY, z, FakeCells.AIR);
                cells.set(x, pocketY + 1, z, FakeCells.AIR);
            }
        }
        BlockPos goal = new BlockPos(54, FLOOR_Y + 1, 50);
        WindowField field = built(cells, goal, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        assertTrue(field.connects(10, FLOOR_Y + 1, 50), "地表の点が繋がっていない");
        assertFalse(field.connects(10, pocketY, 50), "地表と繋がっていない小部屋を繋がっているとした");
        // 殻の外の空中（置いたブロックの上など）は、近くの値を延ばせばよいので断らない
        assertTrue(field.connects(10, FLOOR_Y + 12, 50));
    }

    @Test
    void bridgesAVoidWiderThanTheShell() {
        // ジ・エンドの外側の島。間の奈落（40ブロック）は殻の水平幅の2倍より広く、橋の途中が殻に入らない
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 40, 0, 79, 96, 31)).canPlaceBlocks(true)
                .maxVoidBridgeRunBlocks(96);
        for (int x = 0; x < 80; x++) {
            if (x >= 16 && x < 56) {
                continue;
            }
            for (int z = 0; z < 32; z++) {
                for (int y = FLOOR_Y - 4; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        BlockPos start = new BlockPos(4, FLOOR_Y + 1, 16);
        BlockPos goal = new BlockPos(72, FLOOR_Y + 1, 16);
        WindowField field = built(cells, goal, 40, 16, 40).field(40, 16, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        assertTrue(field.connects(start.getX(), start.getY(), start.getZ()), "向こう岸の島が目的地へ繋がっていない");
        double optimal = optimalCost(cells, start, goal);
        assertEquals(optimal, field.exact(start.getX(), start.getY(), start.getZ()), optimal * 0.01);
    }

    @Test
    void bridgesALavaSeaWiderThanTheShell() {
        // ネザーの溶岩の海を挟んだ島。間の溶岩（20ブロック）は殻の水平幅の2倍より広く、橋の上限（30）より狭い
        FakeCells cells = FakeCells.empty(new SearchBounds(0, 40, 0, 63, 96, 31)).canPlaceBlocks(true)
                .maxBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30);
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 32; z++) {
                boolean sea = x >= 22 && x < 42;
                for (int y = 40; y <= FLOOR_Y; y++) {
                    cells.set(x, y, z, sea && y > FLOOR_Y - 8 ? FakeCells.LAVA : FakeCells.STONE);
                }
                if (!sea) {
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 6; y++) {
                        cells.set(x, y, z, FakeCells.STONE);
                    }
                }
            }
        }
        // 島の上面は溶岩の面より7ブロック高い
        BlockPos start = new BlockPos(4, FLOOR_Y + 7, 16);
        BlockPos goal = new BlockPos(58, FLOOR_Y + 7, 16);
        WindowField field = built(cells, goal, 32, 16, 40).field(32, 16, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        assertTrue(Double.isFinite(field.exact(start.getX(), start.getY(), start.getZ())),
                "溶岩の海の向こうの島が目的地へ繋がっていない");
        double optimal = optimalCost(cells, start, goal);
        assertEquals(optimal, field.exact(start.getX(), start.getY(), start.getZ()), optimal * 0.02);
    }

    @Test
    void refusesToGuideWhenTheGoalIsCutOff() {
        FakeCells cells = world(false);
        // 目的地は窓の中だが、岩盤に埋まっていて殻のどこからも入れない
        BlockPos goal = new BlockPos(40, FLOOR_Y - 10, 32);
        WindowField field = built(cells, goal, 32, 32, 40).field(32, 32, 40, FarField.UNKNOWN, () -> false);
        assertNotNull(field);
        assertFalse(field.reachesGoal());
    }
}
