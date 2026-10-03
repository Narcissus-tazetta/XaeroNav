package net.prason.xaeronav.pathfinding.flight;

import java.util.Arrays;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/**
 * 粗い空中地図（{@link CoarseAirMap}）の上で、目的地まで飛ぶ残りコストを全ての（チャンク, 帯）について
 * 求めた場。空中経路の探索の見積もりに使う。
 *
 * <p>直線の見積もりだけで読み込み済みの範囲の縁（{@link FlightHorizon}）から出口を選ぶと、縁の先が
 * 行き止まりでも直線で目的地に近い側から出る。次の継ぎ足しでそれが分かって引き返す——ネザーで
 * 目的地から最大200ブロック遠ざかってから戻る経路が出ていた。歩行が層1の残りコストの場を窓の外の
 * 推定に使っているのと同じ直し方で、こちらは地図の上の回り道ごと見積もる。
 *
 * <p>辺のコストは{@link CoarseFlightRouter}と同じ（同じ地図で同じ経路を選ぶ）。向きのある辺なので、
 * 目的地から逆向きに解くときは「隣から自分へ入る」コストで緩和する。
 */
public final class CoarseFlightField {

    private static final int CELL_BLOCKS = 16;
    private static final int BAND_LINK_GAP_BLOCKS = 8;
    private static final double UNKNOWN_MULTIPLIER = 1.3;

    private final CoarseAirMap map;
    private final double[] cost;

    private CoarseFlightField(CoarseAirMap map, double[] cost) {
        this.map = map;
        this.cost = cost;
    }

    /** {@code goal}への場。目的地が地図の外か壁の中なら{@code null}。 */
    public static CoarseFlightField toward(CoarseAirMap map, BlockPos goal, boolean rockets) {
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        if (!map.containsChunk(goalX, goalZ) || map.blocked(goalX, goalZ)) {
            return null;
        }
        double[] cost = new double[map.chunksX() * map.chunksZ() * CoarseAirMap.MAX_BANDS];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        int goalState = state(map, goalX, goalZ, map.bandAt(goalX, goalZ, goal.getY()));
        cost[goalState] = 0.0;
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[0]), Double.longBitsToDouble(b[0])));
        open.add(new long[] {Double.doubleToRawLongBits(0.0), goalState});
        while (!open.isEmpty()) {
            long[] top = open.poll();
            int current = (int) top[1];
            double known = Double.longBitsToDouble(top[0]);
            if (known > cost[current]) {
                continue;
            }
            int chunkX = chunkX(map, current);
            int chunkZ = chunkZ(map, current);
            int band = current % CoarseAirMap.MAX_BANDS;
            int bottom = map.bandBottom(chunkX, chunkZ, band);
            int topY = map.bandTop(chunkX, chunkZ, band);
            double enterMultiplier = map.unknown(chunkX, chunkZ) ? UNKNOWN_MULTIPLIER : 1.0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int fromX = chunkX + dx;
                    int fromZ = chunkZ + dz;
                    if (!map.containsChunk(fromX, fromZ) || map.blocked(fromX, fromZ)) {
                        continue;
                    }
                    double horizontal = Math.sqrt(dx * dx + dz * dz) * CELL_BLOCKS;
                    for (int fromBand = 0; fromBand < map.stateBands(fromX, fromZ); fromBand++) {
                        int vertical = gap(map.bandBottom(fromX, fromZ, fromBand), map.bandTop(fromX, fromZ, fromBand),
                                bottom, topY);
                        if (Math.abs(vertical) > BAND_LINK_GAP_BLOCKS) {
                            continue;
                        }
                        double step = FlightCosts.segmentTicks(horizontal, vertical, rockets) * enterMultiplier;
                        int from = state(map, fromX, fromZ, fromBand);
                        double tentative = known + step;
                        if (tentative < cost[from]) {
                            cost[from] = tentative;
                            open.add(new long[] {Double.doubleToRawLongBits(tentative), from});
                        }
                    }
                }
            }
        }
        return new CoarseFlightField(map, cost);
    }

    /**
     * その位置から目的地までの残りコスト（tick）。地図の外・地図の上で目的地へ繋がらない所は
     * {@link Double#NaN}（分からない）——粗い地図の「繋がらない」はチャンク解像度の推定でしかない。
     */
    public double estimate(double x, double y, double z) {
        int chunkX = (int) Math.floor(x) >> 4;
        int chunkZ = (int) Math.floor(z) >> 4;
        if (!map.containsChunk(chunkX, chunkZ) || map.blocked(chunkX, chunkZ)) {
            return Double.NaN;
        }
        double value = cost[state(map, chunkX, chunkZ, map.bandAt(chunkX, chunkZ, (int) Math.floor(y)))];
        return value < Double.POSITIVE_INFINITY ? value : Double.NaN;
    }

    /** 帯{@code [bottom, top]}から帯{@code [toBottom, toTop]}へ移るのに要る昇降（上が正）。 */
    private static int gap(int bottom, int top, int toBottom, int toTop) {
        if (toBottom > top) {
            return toBottom - top;
        }
        if (toTop < bottom) {
            return toTop - bottom;
        }
        return 0;
    }

    private static int state(CoarseAirMap map, int chunkX, int chunkZ, int band) {
        return ((chunkZ - map.minChunkZ()) * map.chunksX() + (chunkX - map.minChunkX())) * CoarseAirMap.MAX_BANDS
                + band;
    }

    private static int chunkX(CoarseAirMap map, int state) {
        return map.minChunkX() + (state / CoarseAirMap.MAX_BANDS) % map.chunksX();
    }

    private static int chunkZ(CoarseAirMap map, int state) {
        return map.minChunkZ() + (state / CoarseAirMap.MAX_BANDS) / map.chunksX();
    }
}
