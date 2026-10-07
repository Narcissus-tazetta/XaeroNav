package net.prason.xaeronav.rail;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * 1つのワールド・次元で見たことのあるレールの、ある時点の写し。読み込まれていない所の線路も引ける。
 *
 * <p>作った後は変わらないので、どのスレッドから読んでもよい。新しく見たレールは{@link #version}の違う写しになる。
 */
public final class RailNetwork {

    public static final RailNetwork EMPTY = new RailNetwork("", 0, new Long2ObjectOpenHashMap<>());

    private final String key;
    private final long version;
    private final Long2IntOpenHashMap tracks = new Long2IntOpenHashMap();
    /** チャンクごとのレールの座標（{@link BlockPos#asLong}）。範囲で引くため。 */
    private final Long2ObjectOpenHashMap<long[]> byChunk = new Long2ObjectOpenHashMap<>();

    /**
     * @param chunks チャンク（{@code chunkX << 32 | chunkZ}）ごとの{@link RailCell}。写すので後で変えてよい
     */
    public RailNetwork(String key, long version, Long2ObjectMap<int[]> chunks) {
        this.key = key;
        this.version = version;
        tracks.defaultReturnValue(CartRide.NONE);
        for (Long2ObjectMap.Entry<int[]> entry : chunks.long2ObjectEntrySet()) {
            int chunkX = (int) (entry.getLongKey() >> 32);
            int chunkZ = (int) entry.getLongKey();
            int[] cells = entry.getValue();
            long[] positions = new long[cells.length];
            for (int i = 0; i < cells.length; i++) {
                int cell = cells[i];
                long pos = BlockPos.asLong(chunkX << 4 | RailCell.localX(cell), RailCell.y(cell),
                        chunkZ << 4 | RailCell.localZ(cell));
                positions[i] = pos;
                tracks.put(pos, cell);
            }
            byChunk.put(chunkKey(chunkX, chunkZ), positions);
        }
    }

    /** どのワールド・次元の写しか（{@code RailMemory}の見分け）。 */
    public String key() {
        return key;
    }

    public long version() {
        return version;
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    public int size() {
        return tracks.size();
    }

    /** {@link CartRide.Tracks}として使える。 */
    public int track(int x, int y, int z) {
        return tracks.get(BlockPos.asLong(x, y, z));
    }

    @FunctionalInterface
    public interface RailVisitor {
        void visit(int x, int y, int z, int cell);
    }

    /** 水平の範囲（両端を含む）にあるレールを、チャンクごとにまとめて渡す。 */
    public void forEachIn(int minX, int minZ, int maxX, int maxZ, RailVisitor visitor) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                long[] positions = byChunk.get(chunkKey(chunkX, chunkZ));
                if (positions == null) {
                    continue;
                }
                for (long pos : positions) {
                    int x = BlockPos.getX(pos);
                    int z = BlockPos.getZ(pos);
                    if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                        visitor.visit(x, BlockPos.getY(pos), z, tracks.get(pos));
                    }
                }
            }
        }
    }

    /** レールのあるチャンクごとに、そのチャンクのレールの座標を渡す。 */
    public void forEachChunk(ChunkVisitor visitor) {
        for (Long2ObjectMap.Entry<long[]> entry : byChunk.long2ObjectEntrySet()) {
            visitor.visit((int) (entry.getLongKey() >> 32), (int) entry.getLongKey(), entry.getValue());
        }
    }

    @FunctionalInterface
    public interface ChunkVisitor {
        void visit(int chunkX, int chunkZ, long[] positions);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }
}
