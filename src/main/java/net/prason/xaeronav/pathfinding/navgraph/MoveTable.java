package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * 辺の「移動」（相対座標と値段の組）に通し番号を振る表。辺はこの番号（2バイト）だけを持つ。
 *
 * <p>移動の種類は窓全体でも数千に満たない（実測: 広域の窓で相対座標は70種・値段は約100種、1セクションで最大319種）
 * ので、辺ごとに座標と値段を持つより5分の1で済む。
 *
 * <p>番号は追記するだけで振り直さない。{@link #view}で取った表は、それより前に振った番号をすべて引ける。
 * ワーカースレッドから並行に呼んでよい。
 */
final class MoveTable {

    /** 番号は{@code char}に収める。 */
    private static final int CAPACITY = 1 << 16;

    /** 引くための表。取った時点までの番号を読める。 */
    static final class View {
        final byte[] dx;
        final short[] dy;
        final byte[] dz;
        final float[] cost;
        /** 振った番号の値段の最小値。まだ1つも無ければ{@link Float#MAX_VALUE}。 */
        final float minCost;

        private View(byte[] dx, short[] dy, byte[] dz, float[] cost, float minCost) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.cost = cost;
            this.minCost = minCost;
        }
    }

    private final Long2IntOpenHashMap index = new Long2IntOpenHashMap();
    private volatile View view = new View(new byte[64], new short[64], new byte[64], new float[64], Float.MAX_VALUE);
    private int size;

    MoveTable() {
        index.defaultReturnValue(-1);
    }

    View view() {
        return view;
    }

    /** 相対座標の鍵 {@code dx(8) | dz(8) | dy(16)}。 */
    static int offsetKey(int dx, int dy, int dz) {
        return (dx & 0xFF) << 24 | (dz & 0xFF) << 16 | (dy & 0xFFFF);
    }

    /**
     * {@code offsets[i]}（{@link #offsetKey}）と{@code costs[i]}の組に番号を振り、{@code ids[i]}へ書く。
     *
     * @throws IllegalStateException 移動の種類が{@code char}に収まらない。値段がブロックごとにばらばらな世界でも
     *                               数千種に留まるので、ここへ来るなら移動生成の側がおかしい
     */
    synchronized void intern(int[] offsets, float[] costs, int count, char[] ids) {
        View current = view;
        byte[] dx = current.dx;
        short[] dy = current.dy;
        byte[] dz = current.dz;
        float[] cost = current.cost;
        float minCost = current.minCost;
        for (int i = 0; i < count; i++) {
            long key = (long) offsets[i] << 32 | Float.floatToIntBits(costs[i]) & 0xFFFFFFFFL;
            int id = index.get(key);
            if (id < 0) {
                if (size == CAPACITY) {
                    throw new IllegalStateException("移動の種類が" + CAPACITY + "を超えた");
                }
                if (size == dx.length) {
                    int grown = Math.min(CAPACITY, size * 2);
                    dx = Arrays.copyOf(dx, grown);
                    dy = Arrays.copyOf(dy, grown);
                    dz = Arrays.copyOf(dz, grown);
                    cost = Arrays.copyOf(cost, grown);
                }
                id = size++;
                dx[id] = (byte) (offsets[i] >> 24);
                dz[id] = (byte) (offsets[i] >> 16);
                dy[id] = (short) offsets[i];
                cost[id] = costs[i];
                minCost = Math.min(minCost, costs[i]);
                index.put(key, id);
            }
            ids[i] = (char) id;
        }
        // 読み手は番号を公開してからしか引かないので、配列を使い回したまま差し替えてよい
        view = new View(dx, dy, dz, cost, minCost);
    }
}
