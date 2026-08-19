package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link CoarseMapBuilder#putFloor}の床の並び替え・上書き・上限の振る舞い。
 * ネザーの多層構造（同じXZに複数の独立した通路が上下に重なる）を{@link CoarseMap}が
 * 正しく持てるかの土台なので、境界条件を単体で押さえる。
 */
class CoarseMapBuilderTest {

    private static CoarseMapBuilder oneCell() {
        return new CoarseMapBuilder(0, 0, 1, 1);
    }

    @Test
    void aSingleFloorLandsAtIndexZero() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(0, 0, CoarseMap.LAND, 64);
        CoarseMap map = builder.build();

        assertEquals(1, map.floorCount(0, 0));
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 0));
        assertEquals(64, map.heightAtFloor(0, 0, 0));
        assertEquals(64, map.minHeightAtFloor(0, 0, 0));
        assertEquals(64, map.maxHeightAtFloor(0, 0, 0));
        assertEquals(1, map.knownCells());
    }

    @Test
    void floorsAreKeptInAscendingHeightOrderRegardlessOfInsertionOrder() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(0, 0, CoarseMap.LAND, 80);
        builder.putFloor(0, 0, CoarseMap.LAVA, 32);
        builder.putFloor(0, 0, CoarseMap.LAND, 50);
        CoarseMap map = builder.build();

        assertEquals(3, map.floorCount(0, 0));
        assertEquals(32, map.heightAtFloor(0, 0, 0));
        assertEquals(50, map.heightAtFloor(0, 0, 1));
        assertEquals(80, map.heightAtFloor(0, 0, 2));
        assertEquals(CoarseMap.LAVA, map.kindAtFloor(0, 0, 0));
    }

    @Test
    void writingTheSameHeightTwiceOverwritesInsteadOfAddingAFloor() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(0, 0, CoarseMap.LAND, 64);
        builder.putFloor(0, 0, CoarseMap.LAVA_MIXED, 64);
        CoarseMap map = builder.build();

        assertEquals(1, map.floorCount(0, 0), "同じ高さは新しい床ではなく上書き");
        assertEquals(CoarseMap.LAVA_MIXED, map.kindAtFloor(0, 0, 0));
    }

    @Test
    void aFifthFloorFartherFromThePreviousOnesIsDropped() {
        CoarseMapBuilder builder = oneCell();
        // 参照Yに近い順に渡す想定（XaeroMapReader#layersForの並びに合わせる）。
        // 5番目（最も遠い）は捨てられ、MAX_FLOORS(4)を超えない
        builder.putFloor(0, 0, CoarseMap.LAND, 50);
        builder.putFloor(0, 0, CoarseMap.LAND, 40);
        builder.putFloor(0, 0, CoarseMap.LAND, 60);
        builder.putFloor(0, 0, CoarseMap.LAND, 30);
        builder.putFloor(0, 0, CoarseMap.LAND, 200);
        CoarseMap map = builder.build();

        assertEquals(CoarseMap.MAX_FLOORS, map.floorCount(0, 0));
        assertEquals(30, map.heightAtFloor(0, 0, 0));
        assertEquals(40, map.heightAtFloor(0, 0, 1));
        assertEquals(50, map.heightAtFloor(0, 0, 2));
        assertEquals(60, map.heightAtFloor(0, 0, 3));
    }

    @Test
    void anEmptyCellHasNoFloors() {
        CoarseMap map = oneCell().build();

        assertEquals(0, map.floorCount(0, 0));
        assertEquals(0, map.knownCells());
    }

    @Test
    void outOfRangeWritesAreSilentlyDropped() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(5, 5, CoarseMap.LAND, 64);
        CoarseMap map = builder.build();

        assertEquals(0, map.floorCount(0, 0));
        assertEquals(0, map.knownCells());
    }

    @Test
    void nearestFloorPicksTheClosestHeight() {
        CoarseMapBuilder builder = oneCell();
        builder.putFloor(0, 0, CoarseMap.LAND, 30);
        builder.putFloor(0, 0, CoarseMap.LAND, 60);
        builder.putFloor(0, 0, CoarseMap.LAND, 100);
        CoarseMap map = builder.build();

        assertEquals(1, map.nearestFloor(0, 0, 65));
        assertEquals(0, map.nearestFloor(0, 0, 40));
        assertEquals(2, map.nearestFloor(0, 0, 95));
    }

    @Test
    void nearestFloorReturnsMinusOneWhenTheCellIsEmpty() {
        CoarseMap map = oneCell().build();

        assertEquals(-1, map.nearestFloor(0, 0, 64));
    }
}
