package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGridBuilder;

/**
 * 長距離ルート層2（{@link SurfaceCellSource}）の振る舞い。列(x,z)ごとの地表高さだけから
 * 既存の{@link AStarPathfinder}が正しい移動を生成するかを見る——新しい探索ロジックは無いので、
 * ここで確かめるのは{@link SurfaceGrid}から{@code CellData}のビットへの合成が正しいかだけ。
 */
class SurfaceCellSourceTest {

    private static final int RADIUS = 20;

    private static PathResult search(SurfaceGrid grid, BlockPos start, BlockPos goal) {
        SearchBounds bounds = new SearchBounds(-RADIUS, 0, -RADIUS, RADIUS, 200, RADIUS);
        return new AStarPathfinder(new SurfaceCellSource(grid, bounds)).search(start, goal, () -> false);
    }

    private static List<MovementType> movements(PathResult result) {
        return result.steps().stream().map(PathStep::movement).toList();
    }

    @Test
    void walksStraightAcrossFlatLand() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.put(x, z, CoarseMap.LAND, 64);
            }
        }

        PathResult result = search(builder.build(), new BlockPos(0, 65, 0), new BlockPos(10, 65, 0));

        assertTrue(result.complete(), "平坦な陸は必ず到達できる");
        assertTrue(result.steps().stream().noneMatch(PathStep::digging), "層2は掘削を扱わない");
    }

    @Test
    void cannotCrossASheerCliffWithoutADetour() {
        // 幅5だけの帯。x=5で高さが64→20へ44ブロック落ちる。落下は3ブロックまでしか繋がらないので、
        // 帯の外に迂回できないここでは経路が伸びない
        SurfaceGridBuilder builder = new SurfaceGridBuilder(-2, -2, 20, 5);
        for (int x = -2; x < 18; x++) {
            for (int z = -2; z < 3; z++) {
                builder.put(x, z, CoarseMap.LAND, x < 5 ? 64 : 20);
            }
        }
        SearchBounds bounds = new SearchBounds(-2, 0, -2, 18, 200, 3);
        PathResult result = new AStarPathfinder(new SurfaceCellSource(builder.build(), bounds))
                .search(new BlockPos(0, 65, 0), new BlockPos(15, 21, 0), () -> false);

        assertFalse(result.complete(), "44ブロックの崖は迂回路が無ければ越えられない");
    }

    @Test
    void swimsAcrossAWaterGap() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                if (x >= 5 && x <= 9) {
                    // 水底55・水面64。水面は隣接する陸の地面(64)と同じYにする——addDescend/addAscendは
                    // どちらも1段分の移動で、陸のfeet(65)から1下がった64がそのまま水面と一致して初めて
                    // 「陸→水→陸」が繋がる（水面をこれより低く取ると、陸側へ上がる着地先が地中に埋まる）
                    builder.put(x, z, CoarseMap.WATER, 55, 64);
                } else {
                    builder.put(x, z, CoarseMap.LAND, 64);
                }
            }
        }

        PathResult result = search(builder.build(), new BlockPos(0, 65, 0), new BlockPos(15, 65, 0));

        assertTrue(result.complete());
        assertTrue(movements(result).contains(MovementType.SWIM), "水の区間は泳ぎとして出す: " + movements(result));
    }

    @Test
    void neverEntersLava() {
        // 幅5だけの帯。溶岩は迂回路が無ければ越えられない（層2は掘削も設置も扱わない）
        SurfaceGridBuilder builder = new SurfaceGridBuilder(-2, -2, 20, 5);
        for (int x = -2; x < 18; x++) {
            for (int z = -2; z < 3; z++) {
                byte kind = x >= 5 && x <= 9 ? CoarseMap.LAVA : CoarseMap.LAND;
                builder.put(x, z, kind, 64);
            }
        }
        SearchBounds bounds = new SearchBounds(-2, 0, -2, 18, 200, 3);
        PathResult result = new AStarPathfinder(new SurfaceCellSource(builder.build(), bounds))
                .search(new BlockPos(0, 65, 0), new BlockPos(15, 65, 0), () -> false);

        assertFalse(result.complete(), "溶岩は迂回路が無ければ越えられない");
        assertTrue(result.steps().stream().noneMatch(step -> step.pos().getX() >= 5 && step.pos().getX() <= 9),
                "溶岩帯に踏み込んだ: " + result.steps());
    }

    @Test
    void outOfRangeColumnsAreDiscarded() {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(0, 0, 4, 4);
        builder.put(0, 0, CoarseMap.LAND, 64);
        builder.put(100, 100, CoarseMap.LAND, 64);
        SurfaceGrid grid = builder.build();

        assertEquals(CoarseMap.NO_DATA, grid.kindAt(100, 100));
        assertEquals(SurfaceGrid.UNKNOWN_HEIGHT, grid.groundHeightAt(100, 100));
    }
}
