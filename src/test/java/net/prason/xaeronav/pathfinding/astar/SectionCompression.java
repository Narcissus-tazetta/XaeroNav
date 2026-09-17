package net.prason.xaeronav.pathfinding.astar;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * <b>実験用。</b>16³のセクションをクラスタにしたHPA*が、完璧なグラフから何を失うかを測る。
 *
 * <p>HPA*の経路は「クラスタの中は自由に動き、クラスタを跨ぐのは代表の出入口だけ」なので、
 * その距離は<b>跨ぐ辺のうち代表だけを残したグラフでの最短距離</b>と一致する。
 * ここはその辺の選び方だけを作る——クラスタ内の出入口間を前計算する本番の形とは値が同じで、
 * 計算の仕方だけが違う。
 *
 * <p>跨ぐ辺は（出るセクション, 入るセクション, 安い移動か）で組に分け、組の中で
 * 出発点が26近傍で繋がる塊を1つの出入口とみなす。代表は、跨ぐ方向でない軸の
 * セクション内座標が{@code spacing}の格子に乗る出発点。格子に1つも乗らない出入口は、
 * 重心に最も近い出発点を1つ残す（狭い通路の開口を落とさないため）。
 */
final class SectionCompression {

    /** 移動量に対してこの倍率を超える値段の辺は、掘削・設置を含む「高い」出入口として別に扱う。 */
    private static final double CHEAP_FACTOR = 3.0;

    private record Group(long from, long to, boolean cheap) {
    }

    /** 選んだ辺と、セクションあたりの代表の数。 */
    record Result(BitSet kept, double representativesPerSection, int crossingEdges, int keptCrossing,
                  IntOpenHashSet representatives) {
    }

    private SectionCompression() {
    }

    static long section(ClosureGraph graph, int id) {
        return BlockPos.asLong(graph.xs.getInt(id) >> 4, graph.ys.getInt(id) >> 4, graph.zs.getInt(id) >> 4);
    }

    /**
     * @param landingMustBeRepresentative trueなら、跨いだ先の点も<b>その先のセクションの代表</b>で
     *                                    なければ辺を残さない。セクションを互いに独立に組む本番では
     *                                    着地点の距離が前計算されている保証が無いので、こちらが本番の制約
     */
    static Result compress(ClosureGraph graph, int spacing, boolean landingMustBeRepresentative) {
        int m = (int) graph.edges();
        BitSet kept = new BitSet(m);
        Map<Group, IntOpenHashSet> sources = new HashMap<>();
        LongOpenHashSet sections = new LongOpenHashSet();
        int crossing = 0;
        for (int e = 0; e < m; e++) {
            int u = graph.from.getInt(e);
            int v = graph.to.getInt(e);
            long cu = section(graph, u);
            long cv = section(graph, v);
            sections.add(cu);
            if (cu == cv) {
                kept.set(e);
                continue;
            }
            crossing++;
            sources.computeIfAbsent(new Group(cu, cv, cheap(graph, e)), k -> new IntOpenHashSet()).add(u);
        }
        Map<Group, IntOpenHashSet> representatives = new HashMap<>();
        long total = 0;
        for (Map.Entry<Group, IntOpenHashSet> entry : sources.entrySet()) {
            IntOpenHashSet chosen = choose(graph, entry.getKey(), entry.getValue(), spacing);
            representatives.put(entry.getKey(), chosen);
            total += chosen.size();
        }
        IntOpenHashSet everyRepresentative = new IntOpenHashSet();
        representatives.values().forEach(everyRepresentative::addAll);
        int keptCrossing = 0;
        for (int e = 0; e < m; e++) {
            if (kept.get(e)) {
                continue;
            }
            int u = graph.from.getInt(e);
            Group group = new Group(section(graph, u), section(graph, graph.to.getInt(e)), cheap(graph, e));
            if (representatives.get(group).contains(u)
                    && (!landingMustBeRepresentative || everyRepresentative.contains(graph.to.getInt(e)))) {
                kept.set(e);
                keptCrossing++;
            }
        }
        return new Result(kept, sections.isEmpty() ? 0 : (double) total / sections.size(), crossing, keptCrossing,
                everyRepresentative);
    }

    private static boolean cheap(ClosureGraph graph, int e) {
        int u = graph.from.getInt(e);
        int v = graph.to.getInt(e);
        double dx = graph.xs.getInt(v) - graph.xs.getInt(u);
        double dy = graph.ys.getInt(v) - graph.ys.getInt(u);
        double dz = graph.zs.getInt(v) - graph.zs.getInt(u);
        double moved = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
        return graph.cost.getFloat(e) <= CHEAP_FACTOR * ActionCosts.SPRINT_ONE_BLOCK * moved;
    }

    private static IntOpenHashSet choose(ClosureGraph graph, Group group, IntOpenHashSet members, int spacing) {
        int[] ids = members.toIntArray();
        Long2IntOpenHashMap index = new Long2IntOpenHashMap(ids.length);
        index.defaultReturnValue(-1);
        for (int i = 0; i < ids.length; i++) {
            index.put(BlockPos.asLong(graph.xs.getInt(ids[i]), graph.ys.getInt(ids[i]), graph.zs.getInt(ids[i])), i);
        }
        int[] parent = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < ids.length; i++) {
            int x = graph.xs.getInt(ids[i]);
            int y = graph.ys.getInt(ids[i]);
            int z = graph.zs.getInt(ids[i]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int j = index.get(BlockPos.asLong(x + dx, y + dy, z + dz));
                        if (j >= 0) {
                            union(parent, i, j);
                        }
                    }
                }
            }
        }
        boolean crossX = BlockPos.getX(group.from()) != BlockPos.getX(group.to());
        boolean crossY = BlockPos.getY(group.from()) != BlockPos.getY(group.to());
        boolean crossZ = BlockPos.getZ(group.from()) != BlockPos.getZ(group.to());
        Map<Integer, IntArrayList> components = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            components.computeIfAbsent(find(parent, i), k -> new IntArrayList()).add(i);
        }
        IntOpenHashSet chosen = new IntOpenHashSet();
        for (IntArrayList component : components.values()) {
            boolean any = false;
            double cx = 0;
            double cy = 0;
            double cz = 0;
            for (int k = 0; k < component.size(); k++) {
                int id = ids[component.getInt(k)];
                int x = graph.xs.getInt(id);
                int y = graph.ys.getInt(id);
                int z = graph.zs.getInt(id);
                cx += x;
                cy += y;
                cz += z;
                if ((crossX || onLattice(x, spacing)) && (crossY || onLattice(y, spacing))
                        && (crossZ || onLattice(z, spacing))) {
                    chosen.add(id);
                    any = true;
                }
            }
            if (any) {
                continue;
            }
            cx /= component.size();
            cy /= component.size();
            cz /= component.size();
            int best = -1;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int k = 0; k < component.size(); k++) {
                int id = ids[component.getInt(k)];
                double dx = graph.xs.getInt(id) - cx;
                double dy = graph.ys.getInt(id) - cy;
                double dz = graph.zs.getInt(id) - cz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestDistance) {
                    bestDistance = d;
                    best = id;
                }
            }
            chosen.add(best);
        }
        return chosen;
    }

    private static boolean onLattice(int coordinate, int spacing) {
        return (coordinate & 15) % spacing == spacing / 2 % spacing;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[ra] = rb;
        }
    }
}
