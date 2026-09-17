package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 1セクションから出る辺。出発点はセクション内の位置（12ビット）、行き先は出発点からの相対座標で持つ。
 *
 * <p>1辺10バイト。同じ出発点・行き先の辺は種類違いで何本も生成されるので、いちばん安いものだけ残す。
 */
final class SectionEdges {

    static final SectionEdges EMPTY = new SectionEdges(new short[0], new byte[0], new short[0], new byte[0],
            new float[0]);

    private static final int POSITIONS = SectionMoves.SIZE * SectionMoves.SIZE * SectionMoves.SIZE;

    /** 出発点のセクション内の位置 {@code lx | lz << 4 | ly << 8}。昇順に並ぶ。 */
    final short[] from;
    final byte[] dx;
    final short[] dy;
    final byte[] dz;
    final float[] cost;

    /** セクション内の位置から、出発点としての通し番号。出発点でなければ-1。 */
    final short[] localOf;
    final int nodes;

    private SectionEdges(short[] from, byte[] dx, short[] dy, byte[] dz, float[] cost) {
        this.from = from;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.cost = cost;
        this.localOf = new short[POSITIONS];
        Arrays.fill(localOf, (short) -1);
        int count = 0;
        for (short position : from) {
            if (localOf[position] < 0) {
                localOf[position] = (short) count++;
            }
        }
        this.nodes = count;
    }

    int size() {
        return from.length;
    }

    static int local(int x, int y, int z) {
        return Math.floorMod(x, SectionMoves.SIZE) | Math.floorMod(z, SectionMoves.SIZE) << 4
                | Math.floorMod(y, SectionMoves.SIZE) << 8;
    }

    /** @return 打ち切られたら{@code null} */
    static @Nullable SectionEdges build(CellSource cells, SectionShell shell, int sectionX,
                                        int sectionY, int sectionZ, int goalX, int goalZ, BooleanSupplier cancelled) {
        // 鍵は 出発点(12) | dx(8) | dz(8) | dy(16)
        Long2FloatOpenHashMap cheapest = new Long2FloatOpenHashMap();
        cheapest.defaultReturnValue(Float.NaN);
        boolean finished = SectionMoves.build(cells, sectionX, sectionY, sectionZ, shell, goalX, goalZ,
                (fromPos, toPos, edgeCost) -> {
                    int fx = BlockPos.getX(fromPos);
                    int fy = BlockPos.getY(fromPos);
                    int fz = BlockPos.getZ(fromPos);
                    int ddx = BlockPos.getX(toPos) - fx;
                    int ddy = BlockPos.getY(toPos) - fy;
                    int ddz = BlockPos.getZ(toPos) - fz;
                    if (ddx < Byte.MIN_VALUE || ddx > Byte.MAX_VALUE || ddz < Byte.MIN_VALUE || ddz > Byte.MAX_VALUE
                            || ddy < Short.MIN_VALUE || ddy > Short.MAX_VALUE) {
                        throw new IllegalStateException("移動が長すぎて辺に収まらない: " + BlockPos.of(fromPos)
                                + " → " + BlockPos.of(toPos));
                    }
                    long key = (long) local(fx, fy, fz) << 32 | (long) (ddx & 0xFF) << 24 | (ddz & 0xFF) << 16
                            | (ddy & 0xFFFF);
                    float known = cheapest.get(key);
                    if (Float.isNaN(known) || edgeCost < known) {
                        cheapest.put(key, edgeCost);
                    }
                }, cancelled);
        if (!finished) {
            return null;
        }
        if (cheapest.isEmpty()) {
            return EMPTY;
        }
        long[] keys = cheapest.keySet().toLongArray();
        Arrays.sort(keys);
        int n = keys.length;
        short[] from = new short[n];
        byte[] dx = new byte[n];
        short[] dy = new short[n];
        byte[] dz = new byte[n];
        float[] cost = new float[n];
        for (int i = 0; i < n; i++) {
            long key = keys[i];
            from[i] = (short) (key >>> 32);
            dx[i] = (byte) (key >>> 24);
            dz[i] = (byte) (key >>> 16);
            dy[i] = (short) key;
            cost[i] = cheapest.get(key);
        }
        return new SectionEdges(from, dx, dy, dz, cost);
    }
}
