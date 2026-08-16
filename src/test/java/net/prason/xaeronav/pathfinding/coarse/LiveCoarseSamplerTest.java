package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class LiveCoarseSamplerTest {

    @Test
    void detectsACliffWithinASingleChunk() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            int floorY = x < 8 ? 64 : 60;
            for (int z = 0; z < 16; z++) {
                cells.set(x, floorY, z, FakeCells.STONE);
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAND, map.kindAtChunk(0, 0));
        assertEquals(60, map.minHeightAtChunk(0, 0));
        assertEquals(64, map.maxHeightAtChunk(0, 0));
    }

    @Test
    void classifiesAMostlyWaterChunkAsWater() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 60, z, FakeCells.WATER);
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.WATER, map.kindAtChunk(0, 0));
    }

    @Test
    void lavaMixedChunkHeightIgnoresTheLavaSurface() {
        // 溶岩の海の縁: 1/4が溶岩面(Y=31、LAVA_MIXEDの下限)、残りがそれより高い地面(Y=40)。
        // 代表高さが溶岩面へ引っ張られると、waypointが立てない場所に落ちる
        SearchBounds bounds = new SearchBounds(0, 20, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x < 4) {
                    cells.set(x, 31, z, FakeCells.LAVA);
                } else {
                    cells.set(x, 40, z, FakeCells.STONE);
                }
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAVA_MIXED, map.kindAtChunk(0, 0));
        assertEquals(40, map.heightAtChunk(0, 0));
    }

    /**
     * ネザーの形。岩盤天井があるので{@code openSkyY}は天井（ここでは探索範囲の遥か上）を指すが、
     * 地図に載せたいのは範囲内にある足元の地形。走査開始を範囲上端で頭打ちにしないと、
     * 範囲外を読んで全列がABSENTになり、地図が1セルも埋まらない。
     */
    @Test
    void samplesTheGroundInsideBoundsWhenTheCeilingIsAboveThem() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 74, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 42, z, FakeCells.STONE);
            }
        }
        // 探索範囲の外にある岩盤天井。openSkyYはこれを指す
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(1, map.knownCells(), "天井の下にある地形が地図に載らなければならない");
        assertEquals(CoarseMap.LAND, map.kindAtChunk(0, 0));
        assertEquals(42, map.heightAtChunk(0, 0));
    }

    /** 範囲の上端から遠く下にある溶岩の海も拾う（走査が浅すぎると海そのものが地図に載らない）。 */
    @Test
    void reachesLavaFarBelowTheTopOfBounds() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 74, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 31, z, FakeCells.LAVA);
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAVA, map.kindAtChunk(0, 0), "溶岩の海が地図に載らなければ迂回もできない");
    }

    @Test
    void columnsWithNoDataAreLeftUnknown() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.ABSENT);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(0, map.knownCells());
        assertEquals(CoarseMap.NO_DATA, map.kindAtChunk(0, 0));
    }
}
