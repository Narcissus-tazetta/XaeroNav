package net.prason.xaeronav.rail;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 見たレールの記録が、書き出して読み直しても同じ中身で戻ること。 */
class RailStoreTest {

    @TempDir
    Path dir;

    @Test
    void cellRoundTripsEveryField() {
        int cell = RailCell.pack(15, -64, 7, TrackShape.ASCENDING_SOUTH, RailKind.ACTIVATOR, true);

        assertEquals(15, RailCell.localX(cell));
        assertEquals(7, RailCell.localZ(cell));
        assertEquals(-64, RailCell.y(cell));
        assertEquals(TrackShape.ASCENDING_SOUTH, RailCell.shape(cell));
        assertEquals(RailKind.ACTIVATOR, RailCell.kind(cell));
        assertTrue(RailCell.powered(cell));
        assertTrue(RailCell.valid(cell));
    }

    @Test
    void reopeningReadsBackWhatWasFlushed() throws IOException {
        int[] east = {
            RailCell.pack(0, 70, 3, TrackShape.EAST_WEST, RailKind.RAIL, false),
            RailCell.pack(1, 70, 3, TrackShape.EAST_WEST, RailKind.POWERED, true),
        };
        int[] farAway = {RailCell.pack(5, -10, 5, TrackShape.NORTH_EAST, RailKind.RAIL, false)};
        RailStore store = RailStore.open(dir);
        store.putChunk(10, -3, east);
        store.putChunk(-1000, 2000, farAway);
        store.flush();

        RailStore reopened = RailStore.open(dir);

        assertArrayEquals(east, reopened.chunk(10, -3));
        assertArrayEquals(farAway, reopened.chunk(-1000, 2000));
        assertEquals(3, reopened.railCount());
        assertEquals(2, reopened.chunkCount());
    }

    @Test
    void aChunkSeenWithoutRailsIsForgottenAndItsEmptyRegionFileRemoved() throws IOException {
        RailStore store = RailStore.open(dir);
        store.putChunk(0, 0, new int[] {RailCell.pack(0, 64, 0, TrackShape.NORTH_SOUTH, RailKind.RAIL, false)});
        store.flush();

        store.putChunk(0, 0, new int[0]);
        store.flush();

        assertNull(RailStore.open(dir).chunk(0, 0));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void unchangedContentsDoNotMarkAnythingForWriting() throws IOException {
        int[] rails = {RailCell.pack(2, 64, 2, TrackShape.SOUTH_WEST, RailKind.DETECTOR, false)};
        RailStore store = RailStore.open(dir);
        store.putChunk(4, 4, rails);
        store.flush();

        store.putChunk(4, 4, rails.clone());

        assertFalse(store.dirty());
    }

    @Test
    void aCorruptedFileIsSkippedInsteadOfFailingTheWorld() throws IOException {
        Files.write(dir.resolve("r.0.0.rails"), new byte[] {1, 2, 3});

        RailStore store = RailStore.open(dir);

        assertEquals(0, store.chunkCount());
    }
}
