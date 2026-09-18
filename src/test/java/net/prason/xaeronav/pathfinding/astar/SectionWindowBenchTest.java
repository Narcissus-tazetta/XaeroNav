package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * 窓の中の正確なグラフを<b>セクション単位で</b>組む値段。全体を並列に組む初回と、16ブロック歩いたぶんの
 * 帯だけ組む更新、組み立て＋逆Dijkstraを分けて測る。判定を持たない計測。
 */
@Tag("bench")
class SectionWindowBenchTest {

    private static final int WINDOW = 160;
    private static final int SHELL_HORIZONTAL = 8;
    private static final int SHELL_VERTICAL = 2;

    /** 1セクションぶんの辺（出発点はセクションの中）。 */
    record SectionEdges(long[] from, long[] to, float[] cost, int expanded) {
    }

    /** 自然に立てる点のビット（列ごと、Yは世界の最小から）。チャンクごとに覚える。 */
    static final class Naturals {
        private final CellSource cells;
        private final int minY;
        private final int height;
        private final int words;
        private final ConcurrentHashMap<Long, long[]> chunks = new ConcurrentHashMap<>();

        Naturals(CellSource cells) {
            this.cells = cells;
            this.minY = cells.bounds().minY();
            this.height = cells.bounds().maxY() - minY + 1;
            this.words = (height + 63) >> 6;
        }

        /** その列の自然なYのビット。 */
        long[] column(int x, int z) {
            long[] chunk = chunks.computeIfAbsent(((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL),
                    key -> scan(x >> 4, z >> 4));
            int base = ((x & 15) + (z & 15) * 16) * words;
            return Arrays.copyOfRange(chunk, base, base + words);
        }

        private long[] scan(int chunkX, int chunkZ) {
            long[] bits = new long[256 * words];
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int x = (chunkX << 4) + lx;
                    int z = (chunkZ << 4) + lz;
                    int base = (lx + lz * 16) * words;
                    long below = cells.cell(x, minY, z);
                    long feet = cells.cell(x, minY + 1, z);
                    for (int y = minY + 1; y < minY + height - 1; y++) {
                        long head = cells.cell(x, y + 1, z);
                        boolean surfaceWater = CellData.water(feet) && !CellData.water(head);
                        if (CellData.occupiableWithoutDigging(feet) && CellData.occupiableWithoutDigging(head)
                                && (CellData.standable(below) && !CellData.water(feet) || surfaceWater
                                        || CellData.climbable(feet))) {
                            bits[base + ((y - minY) >> 6)] |= 1L << (y - minY);
                        }
                        below = feet;
                        feet = head;
                    }
                }
            }
            return bits;
        }

        int words() {
            return words;
        }
    }

    static SectionEdges build(CellSource cells, Naturals naturals, int sx, int sy, int sz) {
        int minY = cells.bounds().minY();
        int words = naturals.words();
        int span = 16 + 2 * SHELL_HORIZONTAL;
        int originX = (sx << 4) - SHELL_HORIZONTAL;
        int originZ = (sz << 4) - SHELL_HORIZONTAL;
        // 周りの列の自然なビットを垂直に膨らませ、X方向・Z方向の順に水平へ膨らませる
        long[][] area = new long[span * span][];
        for (int ax = 0; ax < span; ax++) {
            for (int az = 0; az < span; az++) {
                long[] bits = naturals.column(originX + ax, originZ + az);
                long[] grown = new long[words];
                for (int w = 0; w < words; w++) {
                    long b = bits[w];
                    grown[w] |= b;
                    for (int k = 1; k <= SHELL_VERTICAL; k++) {
                        grown[w] |= b << k | b >>> k;
                    }
                }
                area[ax + az * span] = grown;
            }
        }
        long[][] alongX = new long[16 * span][];
        for (int lx = 0; lx < 16; lx++) {
            for (int az = 0; az < span; az++) {
                long[] merged = new long[words];
                for (int d = 0; d <= 2 * SHELL_HORIZONTAL; d++) {
                    long[] bits = area[(lx + d) + az * span];
                    for (int w = 0; w < words; w++) {
                        merged[w] |= bits[w];
                    }
                }
                alongX[lx + az * 16] = merged;
            }
        }
        long[][] shell = new long[256][];
        LongArrayList seeds = new LongArrayList();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = (sx << 4) + lx;
                int z = (sz << 4) + lz;
                long[] allowed = new long[words];
                for (int d = 0; d <= 2 * SHELL_HORIZONTAL; d++) {
                    long[] bits = alongX[lx + (lz + d) * 16];
                    for (int w = 0; w < words; w++) {
                        allowed[w] |= bits[w];
                    }
                }
                shell[lx + lz * 16] = allowed;
                for (int y = sy << 4; y < (sy + 1) << 4; y++) {
                    int bit = y - minY;
                    if (bit >= 0 && bit < allowed.length * 64 && (allowed[bit >> 6] >> bit & 1) != 0) {
                        seeds.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        if (seeds.isEmpty()) {
            return new SectionEdges(new long[0], new long[0], new float[0], 0);
        }
        LongArrayList from = new LongArrayList();
        LongArrayList to = new LongArrayList();
        FloatArrayList cost = new FloatArrayList();
        AStarPathfinder closure = new AStarPathfinder(cells, new SearchLimits(Integer.MAX_VALUE, 3_600_000L, 0.0));
        closure.expandFilter((x, y, z) -> {
            if (x >> 4 != sx || y >> 4 != sy || z >> 4 != sz) {
                return false;
            }
            int bit = y - minY;
            long[] allowed = shell[(x & 15) + (z & 15) * 16];
            return bit >= 0 && bit < allowed.length * 64 && (allowed[bit >> 6] >> bit & 1) != 0;
        });
        closure.edgeSink((fx, fy, fz, fb, tx, ty, tz, tb, edgeCost, kind) -> {
            from.add(BlockPos.asLong(fx, fy, fz));
            to.add(BlockPos.asLong(tx, ty, tz));
            cost.add((float) edgeCost);
        });
        int expanded = closure.exhaust(seeds.toLongArray(), seeds.size(), Integer.MAX_VALUE / 2, 0, () -> false);
        return new SectionEdges(from.toLongArray(), to.toLongArray(), cost.toFloatArray(), expanded);
    }

    private static List<long[]> sectionsIn(CellSource cells, int minX, int maxX, int minZ, int maxZ) {
        SearchBounds world = cells.bounds();
        List<long[]> sections = new ArrayList<>();
        for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
            for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
                for (int sy = world.minY() >> 4; sy <= world.maxY() >> 4; sy++) {
                    sections.add(new long[] {sx, sy, sz});
                }
            }
        }
        return sections;
    }

    private static void measure(String name, CellSource cells, BlockPos player) {
        SearchBounds world = cells.bounds();
        int minX = Math.max(world.minX(), player.getX() - WINDOW);
        int maxX = Math.min(world.maxX(), player.getX() + WINDOW);
        int minZ = Math.max(world.minZ(), player.getZ() - WINDOW);
        int maxZ = Math.min(world.maxZ(), player.getZ() + WINDOW);
        Naturals naturals = new Naturals(cells);
        List<long[]> sections = sectionsIn(cells, minX, maxX, minZ, maxZ);

        long began = System.nanoTime();
        SectionEdges[] built = new SectionEdges[sections.size()];
        IntStream.range(0, sections.size()).parallel().forEach(i -> {
            long[] s = sections.get(i);
            built[i] = build(cells, naturals, (int) s[0], (int) s[1], (int) s[2]);
        });
        long parallel = System.nanoTime();

        // 16ブロック東へ歩いたぶんの帯（新しく窓へ入るセクションの列）を単スレッドで
        List<long[]> strip = sectionsIn(cells, maxX + 1, Math.min(world.maxX(), maxX + 16), minZ, maxZ);
        Naturals fresh = new Naturals(cells);
        long stripBegan = System.nanoTime();
        int stripEdges = 0;
        for (long[] s : strip) {
            stripEdges += build(cells, fresh, (int) s[0], (int) s[1], (int) s[2]).from().length;
        }
        long stripDone = System.nanoTime();

        long assembleBegan = System.nanoTime();
        Long2IntOpenHashMap ids = new Long2IntOpenHashMap();
        ids.defaultReturnValue(-1);
        long edges = 0;
        long expanded = 0;
        for (SectionEdges section : built) {
            edges += section.from().length;
            expanded += section.expanded();
            for (int e = 0; e < section.from().length; e++) {
                id(ids, section.from()[e]);
                id(ids, section.to()[e]);
            }
        }
        int n = ids.size();
        int[] start = new int[n + 1];
        for (SectionEdges section : built) {
            for (int e = 0; e < section.to().length; e++) {
                start[ids.get(section.to()[e]) + 1]++;
            }
        }
        for (int i = 0; i < n; i++) {
            start[i + 1] += start[i];
        }
        int[] fill = Arrays.copyOf(start, n);
        int[] predecessor = new int[(int) edges];
        float[] weight = new float[(int) edges];
        for (SectionEdges section : built) {
            for (int e = 0; e < section.to().length; e++) {
                int slot = fill[ids.get(section.to()[e])]++;
                predecessor[slot] = ids.get(section.from()[e]);
                weight[slot] = section.cost()[e];
            }
        }
        long assembled = System.nanoTime();
        double[] distance = new double[n];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        ClosureGraph.MinHeap heap = new ClosureGraph.MinHeap();
        BlockPos far = new BlockPos(player.getX() + 2000, player.getY(), player.getZ());
        for (Long2IntOpenHashMap.Entry entry : ids.long2IntEntrySet()) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            if (pos.getX() >= maxX - 1) {
                double value = Heuristic.estimate(pos.getX(), pos.getY(), pos.getZ(), far.getX(), far.getY(), far.getZ());
                distance[entry.getIntValue()] = value;
                heap.push(value, entry.getIntValue());
            }
        }
        int settled = 0;
        while (!heap.isEmpty()) {
            double d = heap.topKey();
            int node = heap.pop();
            if (d > distance[node]) {
                continue;
            }
            settled++;
            for (int slot = start[node]; slot < start[node + 1]; slot++) {
                int p = predecessor[slot];
                double candidate = d + weight[slot];
                if (candidate < distance[p]) {
                    distance[p] = candidate;
                    heap.push(candidate, p);
                }
            }
        }
        long dijkstra = System.nanoTime();
        System.out.printf(Locale.ROOT,
                "%s: セクション%d 並列初回%dms（展開%d・辺%d・%dコア） 帯%dセクション%dms（辺%d） 組み立て%dms"
                        + " Dijkstra%dms（ノード%d・確定%d）%n",
                name, sections.size(), (parallel - began) / 1_000_000, expanded, edges,
                Runtime.getRuntime().availableProcessors(), strip.size(), (stripDone - stripBegan) / 1_000_000,
                stripEdges, (assembled - assembleBegan) / 1_000_000, (dijkstra - assembled) / 1_000_000, n, settled);
    }

    private static int id(Long2IntOpenHashMap ids, long key) {
        int id = ids.get(key);
        if (id < 0) {
            id = ids.size();
            ids.put(key, id);
        }
        return id;
    }

    private static BlockPos standableNear(CellSource cells, int x, int z) {
        for (int r = 0; r < 200; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    try {
                        return TerrainFixture.onGround(cells, cells.bounds(), new BlockPos(x + dx, 0, z + dz));
                    } catch (IllegalStateException e) {
                        // この列には立てない。次の列を見る
                    }
                }
            }
        }
        throw new IllegalStateException("立てる列が無い: " + x + "," + z);
    }

    private static FakeCells overworld(String resource) throws IOException {
        return TerrainFixture.load(resource, bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(6));
    }

    @Test
    void sectionWindowCost() throws IOException {
        FakeCells wide = overworld("/overworld_wide.txt.gz");
        measure("地上/広域", wide, StanceFinder.resolveStart(wide, standableNear(wide, 700, -80)));
        FakeCells mountains = overworld("/overworld_mountains.txt.gz");
        measure("地上/山岳", mountains, StanceFinder.resolveStart(mountains, standableNear(mountains, -384, 16)));
        FakeCells end = overworld("/end_terrain_columns_2481.txt.gz");
        measure("エンド", end, StanceFinder.resolveStart(end, standableNear(end, 2536, -432)));
        FakeCells nether = NetherLiveWalkTest.terrain();
        measure("ネザー", nether, StanceFinder.resolveStart(nether, new BlockPos(-317, 44, 567)));
    }
}
