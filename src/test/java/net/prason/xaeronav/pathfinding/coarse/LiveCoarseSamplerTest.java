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
    void columnsWithNoDataAreLeftUnknown() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.ABSENT);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(0, map.knownCells());
        assertEquals(CoarseMap.NO_DATA, map.kindAtChunk(0, 0));
    }
}
