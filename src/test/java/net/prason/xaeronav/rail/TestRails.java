package net.prason.xaeronav.rail;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** テスト用の線路網。 */
public final class TestRails {

    private final Long2ObjectOpenHashMap<IntArrayList> chunks = new Long2ObjectOpenHashMap<>();

    public TestRails rail(int x, int y, int z, TrackShape shape, RailKind kind, boolean powered) {
        chunks.computeIfAbsent((long) (x >> 4) << 32 | (z >> 4) & 0xFFFFFFFFL, key -> new IntArrayList())
                .add(RailCell.pack(x & 15, y, z & 15, shape, kind, powered));
        return this;
    }

    /** {@code z}の東西の直線を{@code fromX}から{@code toX}まで（両端を含む）。{@code poweredEvery}本おきに通電パワード（0なら無し）。 */
    public TestRails eastWest(int fromX, int toX, int y, int z, int poweredEvery) {
        for (int x = fromX; x <= toX; x++) {
            boolean powered = poweredEvery > 0 && (x - fromX) % poweredEvery == 0;
            rail(x, y, z, TrackShape.EAST_WEST, powered ? RailKind.POWERED : RailKind.RAIL, powered);
        }
        return this;
    }

    public RailNetwork network() {
        Long2ObjectOpenHashMap<int[]> packed = new Long2ObjectOpenHashMap<>();
        chunks.forEach((key, cells) -> packed.put((long) key, cells.toIntArray()));
        return new RailNetwork("test", 1, packed);
    }
}
