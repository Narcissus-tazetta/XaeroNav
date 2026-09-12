package net.prason.xaeronav.pathfinding.astar;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;

/**
 * 座標からノードを引くための表。{@link MemoCells}と同じページ配列で、こちらはセルではなく
 * {@link PathNode}を持つ。
 *
 * <p>ノードは1回の展開につき50回前後引かれる（移動候補の数だけ）。展開したノードの周りは
 * 連続して埋まるので、座標から直に配列の添字を作れば、ハッシュ表を引かずに済む。
 */
final class NodeTable {

    /** ページ1辺の大きさ（2の冪の指数）。{@link MemoCells}と同じ理由で4×4×4。 */
    private static final int PAGE_BITS = 2;
    private static final int PAGE_MASK = (1 << PAGE_BITS) - 1;
    private static final int PAGE_CELLS = 1 << (PAGE_BITS * 3);

    private static final int RECENT_BITS = 4;
    private static final int RECENT = 1 << RECENT_BITS;
    private static final long MIX = 0x9E3779B97F4A7C15L;

    private final Long2ObjectOpenHashMap<PathNode[]> pages = new Long2ObjectOpenHashMap<>();
    private final long[] recentKeys = new long[RECENT];
    private final PathNode[][] recentPages = new PathNode[RECENT][];

    /** その座標を含むページ。無ければ作る。 */
    PathNode[] page(int x, int y, int z) {
        long key = BlockPos.asLong(x >> PAGE_BITS, y >> PAGE_BITS, z >> PAGE_BITS);
        int slot = (int) ((key * MIX) >>> (64 - RECENT_BITS));
        PathNode[] page = recentPages[slot];
        if (page != null && recentKeys[slot] == key) {
            return page;
        }
        page = pages.get(key);
        if (page == null) {
            page = new PathNode[PAGE_CELLS];
            pages.put(key, page);
        }
        recentKeys[slot] = key;
        recentPages[slot] = page;
        return page;
    }

    static int index(int x, int y, int z) {
        return ((y & PAGE_MASK) << (PAGE_BITS * 2)) | ((x & PAGE_MASK) << PAGE_BITS) | (z & PAGE_MASK);
    }
}
