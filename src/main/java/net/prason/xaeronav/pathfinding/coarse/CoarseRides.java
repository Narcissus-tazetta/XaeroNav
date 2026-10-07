package net.prason.xaeronav.pathfinding.coarse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.rail.CartRide;
import net.prason.xaeronav.rail.RailNetwork;

/**
 * 層1（{@link CoarseRouter}）に足す、トロッコで線路を走る辺。乗る点のチャンクから降りる点のチャンクへの1本で、
 * 値段は持ってきたトロッコを置いて乗り（{@link ActionCosts#CART_BOARD_TICKS}）、走り、壊して拾う
 * （{@link ActionCosts#CART_STOW_TICKS}）まで。
 *
 * <p>乗る点はチャンクごとに線路の端に近いレールを数本だけ取る。チャンクの中で乗る点を変えても層1の値はセル単位なので
 * ほとんど変わらず、全部のレールから模擬すると線路の長さの2乗で増える。降りる点は数チャンクおきの、チャンクに入った
 * 最初のレールと、線路の終わり。
 *
 * <p>作った後は変わらないので、どのスレッドから読んでもよい。
 */
public final class CoarseRides {

    public static final CoarseRides EMPTY = new CoarseRides(RailNetwork.EMPTY);

    /** 1チャンクで乗る点にするレールの数の上限。 */
    private static final int BOARDS_PER_CHUNK = 4;

    /** 1回の乗車で模擬する長さ（tick）。5分走れば層1の地図の外へ出る。 */
    private static final int MAX_RIDE_TICKS = 6000;

    /** 降りる点にする間隔（チャンク）。長距離ルートの経由地の間隔（4チャンク）に揃える。 */
    private static final int ALIGHT_EVERY_CHUNKS = 4;

    /** 一直線に走るよりこれ以上遅い乗車は辺にしない（{@code CartMoves}と同じ。押して進むだけの線路を除く）。 */
    private static final double MAX_DETOUR_FACTOR = 2.0;

    private final RailNetwork network;
    private final LongArrayList board = new LongArrayList();
    private final LongArrayList alight = new LongArrayList();
    private final IntArrayList exit = new IntArrayList();
    private final DoubleArrayList cost = new DoubleArrayList();
    private final Long2ObjectOpenHashMap<IntArrayList> byBoardChunk = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<IntArrayList> byAlightChunk = new Long2ObjectOpenHashMap<>();

    private CoarseRides(RailNetwork network) {
        this.network = network;
    }

    /** 線路網から辺を組む。線路の長さに比例した模擬を乗る点の数だけ回すので、ワーカーで呼ぶこと。 */
    public static CoarseRides of(RailNetwork network) {
        CoarseRides rides = new CoarseRides(network);
        network.forEachChunk((chunkX, chunkZ, positions) -> {
            for (long from : boards(positions)) {
                rides.addRidesFrom(from);
            }
        });
        return rides;
    }

    /**
     * チャンクの中で、乗る点にするレール。対角2方向それぞれの両端（{@code x+z}と{@code x-z}の最小・最大）を取る。
     * 直線もジグザグも、チャンクを横切る線路はこのどれかが線路の端に当たる。
     */
    private static long[] boards(long[] positions) {
        long[] picked = new long[BOARDS_PER_CHUNK];
        int[] best = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (long pos : positions) {
            int sum = BlockPos.getX(pos) + BlockPos.getZ(pos);
            int diff = BlockPos.getX(pos) - BlockPos.getZ(pos);
            if (sum < best[0]) {
                best[0] = sum;
                picked[0] = pos;
            }
            if (sum > best[1]) {
                best[1] = sum;
                picked[1] = pos;
            }
            if (diff < best[2]) {
                best[2] = diff;
                picked[2] = pos;
            }
            if (diff > best[3]) {
                best[3] = diff;
                picked[3] = pos;
            }
        }
        return Arrays.stream(picked).distinct().toArray();
    }

    private void addRidesFrom(long from) {
        int fromX = BlockPos.getX(from);
        int fromY = BlockPos.getY(from);
        int fromZ = BlockPos.getZ(from);
        long fromChunk = chunkKey(fromX >> 4, fromZ >> 4);
        for (int e = 0; e < 2; e++) {
            int fromExit = e;
            LongOpenHashSet entered = new LongOpenHashSet();
            entered.add(fromChunk);
            long[] last = {Long.MIN_VALUE, 0};
            CartRide.ride(network::track, fromX, fromY, fromZ, fromExit, 0.0, true, MAX_RIDE_TICKS,
                    (x, y, z, tick, forcedExit) -> {
                        long cell = BlockPos.asLong(x, y, z);
                        last[0] = cell;
                        last[1] = tick;
                        if (!entered.add(chunkKey(x >> 4, z >> 4))) {
                            return true;
                        }
                        // 降りる点は入ったチャンクの{@link #ALIGHT_EVERY_CHUNKS}つおき。全部のチャンクに張ると
                        // 辺の数が線路の長さの2乗で増える（2万ブロックの線路で70万本・約28MB）
                        if (entered.size() % ALIGHT_EVERY_CHUNKS == 1) {
                            addIfWorth(from, fromExit, cell, tick);
                        }
                        return true;
                    });
            // 線路の終わり（止まる・行き止まり）はどこで乗っても降りる点になる
            if (last[0] != Long.MIN_VALUE && !entered.isEmpty() && entered.size() % ALIGHT_EVERY_CHUNKS != 1) {
                addIfWorth(from, fromExit, last[0], (int) last[1]);
            }
        }
    }

    private void addIfWorth(long from, int fromExit, long to, int tick) {
        double total = ActionCosts.CART_BOARD_TICKS + tick + ActionCosts.CART_STOW_TICKS;
        double dx = BlockPos.getX(to) - BlockPos.getX(from);
        double dz = BlockPos.getZ(to) - BlockPos.getZ(from);
        if (BlockPos.getX(to) >> 4 != BlockPos.getX(from) >> 4 || BlockPos.getZ(to) >> 4 != BlockPos.getZ(from) >> 4) {
            if (total <= MAX_DETOUR_FACTOR * Math.sqrt(dx * dx + dz * dz) * ActionCosts.SPRINT_ONE_BLOCK) {
                add(from, fromExit, to, total);
            }
        }
    }

    private void add(long from, int fromExit, long to, double total) {
        int index = cost.size();
        board.add(from);
        alight.add(to);
        exit.add(fromExit);
        cost.add(total);
        byBoardChunk.computeIfAbsent(chunkKey(BlockPos.getX(from) >> 4, BlockPos.getZ(from) >> 4),
                key -> new IntArrayList()).add(index);
        byAlightChunk.computeIfAbsent(chunkKey(BlockPos.getX(to) >> 4, BlockPos.getZ(to) >> 4),
                key -> new IntArrayList()).add(index);
    }

    public RailNetwork network() {
        return network;
    }

    public int size() {
        return cost.size();
    }

    /** チャンク({@code chunkX}, {@code chunkZ})で乗る辺。無ければ空。 */
    IntArrayList boardingIn(int chunkX, int chunkZ) {
        IntArrayList edges = byBoardChunk.get(chunkKey(chunkX, chunkZ));
        return edges == null ? NONE : edges;
    }

    /** チャンク({@code chunkX}, {@code chunkZ})で降りる辺。無ければ空。 */
    IntArrayList alightingIn(int chunkX, int chunkZ) {
        IntArrayList edges = byAlightChunk.get(chunkKey(chunkX, chunkZ));
        return edges == null ? NONE : edges;
    }

    long board(int edge) {
        return board.getLong(edge);
    }

    long alight(int edge) {
        return alight.getLong(edge);
    }

    double cost(int edge) {
        return cost.getDouble(edge);
    }

    /** 辺{@code edge}で通るレールを、乗る点の次から降りる点まで{@code every}ブロックおきに（降りる点は必ず）。 */
    List<BlockPos> path(int edge, int every) {
        long from = board.getLong(edge);
        long to = alight.getLong(edge);
        List<BlockPos> cells = new ArrayList<>();
        int[] count = {0};
        CartRide.ride(network::track, BlockPos.getX(from), BlockPos.getY(from), BlockPos.getZ(from), exit.getInt(edge),
                0.0, true, MAX_RIDE_TICKS, (x, y, z, tick, forcedExit) -> {
                    boolean end = BlockPos.asLong(x, y, z) == to;
                    if (end || ++count[0] % every == 0) {
                        cells.add(new BlockPos(x, y, z));
                    }
                    return !end;
                });
        return cells;
    }

    private static final IntArrayList NONE = new IntArrayList();

    private static long chunkKey(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
    }
}
