package net.prason.xaeronav.pathfinding.astar;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * <b>実験用。</b>経路全体を覆う3次元の粗い地図から作るcost-to-go。
 *
 * <p>答えたい問いは1つ——<b>窓の外の地形を正しく知っていれば、ネザーの遠回りは消えるのか。</b>
 * 探索の箱の中だけで組んだ3D格子は、1区間の展開ノードを26倍減らしたのに<b>経路は悪くなった</b>
 * （窓の外にある正しい迂回路を知らないまま、間違った方向へ速く進んだだけだった）。
 * ここはフィクスチャ全体＝Xaeroの地図が持つはずの範囲から組む。
 *
 * <p>値段は<b>実費に寄せる</b>。前回の試作は立てない格子を一律「疾走の7倍」にしていて、
 * 溶岩だらけのネザーでは「壁を突っ切る方が安い」と言ってしまっていた。
 */
final class WideVoxelGuide implements CostToGo {

    /** 格子の一辺（ブロック）。 */
    private static final int CELL = 4;

    /** 立てる場所を探すときの列の間隔（ブロック）。 */
    private static final int COLUMN_STEP = 2;

    private static final byte SOLID = 0;
    private static final byte OPEN = 1;
    private static final byte STANDABLE = 2;

    /** 空洞を渡る1ブロックの値段（橋）。{@code ActionCosts}の実費。 */
    private static final double BRIDGE_PER_BLOCK =
            ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS + ActionCosts.SPRINT_ONE_BLOCK;

    /** 岩を抜ける1ブロックの値段（掘削）。 */
    private static final double DIG_PER_BLOCK =
            ActionCosts.DIG_OVERHEAD_TICKS + ActionCosts.SPRINT_ONE_BLOCK;

    // Queue keys must stay fixed after insertion. Comparing through cost[] lets a
    // later relaxation change an entry's priority without restoring heap order.
    private record Entry(int index, double cost) {
    }

    private final SearchBounds box;
    private final int nx;
    private final int ny;
    private final int nz;
    private final double[] cost;
    private final double slack;

    private WideVoxelGuide(SearchBounds box, int nx, int ny, int nz, double[] cost) {
        this.box = box;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.cost = cost;
        this.slack = CELL * Math.sqrt(3.0) * ActionCosts.SPRINT_ONE_BLOCK;
    }

    static WideVoxelGuide build(CellSource view, SearchBounds box, BlockPos goal) {
        return build(view, box, goal, false);
    }

    /**
     * 床の位置だけが完全に分かる理想化モデル。床以外は岩か空洞かを区別せず、
     * 空洞と同じ値段にする。Xaeroの疎な実データを再現したものではない。
     */
    static WideVoxelGuide build(CellSource view, SearchBounds box, BlockPos goal, boolean floorsOnly) {
        int nx = (box.maxX() - box.minX()) / CELL + 1;
        int ny = (box.maxY() - box.minY()) / CELL + 1;
        int nz = (box.maxZ() - box.minZ()) / CELL + 1;
        byte[] kind = new byte[nx * ny * nz];
        if (floorsOnly) {
            Arrays.fill(kind, OPEN);
        }
        for (int x = box.minX(); x <= box.maxX(); x += COLUMN_STEP) {
            for (int z = box.minZ(); z <= box.maxZ(); z += COLUMN_STEP) {
                for (int y = box.minY() + 1; y < box.maxY(); y++) {
                    int index = index(nx, ny, nz, (x - box.minX()) / CELL, (y - box.minY()) / CELL,
                            (z - box.minZ()) / CELL);
                    if (kind[index] == STANDABLE) {
                        continue;
                    }
                    long feet = view.cell(x, y, z);
                    boolean head = CellData.occupiableWithoutDigging(feet)
                            && CellData.occupiableWithoutDigging(view.cell(x, y + 1, z));
                    if (head && CellData.standable(view.cell(x, y - 1, z))) {
                        kind[index] = STANDABLE;
                    } else if (head) {
                        kind[index] = OPEN;
                    }
                }
            }
        }
        double[] cost = new double[kind.length];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        WideVoxelGuide guide = new WideVoxelGuide(box, nx, ny, nz, cost);
        int goalIndex = nearest(kind, box, nx, ny, nz, goal);
        if (goalIndex < 0) {
            return guide;
        }
        cost[goalIndex] = 0.0;
        boolean[] closed = new boolean[cost.length];
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        open.add(new Entry(goalIndex, 0.0));
        while (!open.isEmpty()) {
            Entry entry = open.poll();
            int current = entry.index();
            if (closed[current] || entry.cost() != cost[current]) {
                continue;
            }
            closed[current] = true;
            int i = current % nx;
            int k = (current / nx) % nz;
            int j = current / (nx * nz);
            for (int di = -1; di <= 1; di++) {
                for (int dk = -1; dk <= 1; dk++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dk == 0 && dj == 0) {
                            continue;
                        }
                        int ni = i + di;
                        int nj = j + dj;
                        int nk = k + dk;
                        if (ni < 0 || ni >= nx || nj < 0 || nj >= ny || nk < 0 || nk >= nz) {
                            continue;
                        }
                        int neighbor = index(nx, ny, nz, ni, nj, nk);
                        if (closed[neighbor]) {
                            continue;
                        }
                        double blocks = CELL * Math.sqrt(di * di + dj * dj + dk * dk);
                        double rate = switch (kind[neighbor]) {
                            case STANDABLE -> ActionCosts.SPRINT_ONE_BLOCK;
                            case OPEN -> BRIDGE_PER_BLOCK;
                            default -> DIG_PER_BLOCK;
                        };
                        double next = cost[current] + blocks * rate;
                        if (next < cost[neighbor]) {
                            cost[neighbor] = next;
                            open.add(new Entry(neighbor, next));
                        }
                    }
                }
            }
        }
        return guide;
    }

    private static int nearest(byte[] kind, SearchBounds box, int nx, int ny, int nz, BlockPos goal) {
        if (!box.contains(goal.getX(), goal.getY(), goal.getZ())) {
            return -1;
        }
        int i = (goal.getX() - box.minX()) / CELL;
        int k = (goal.getZ() - box.minZ()) / CELL;
        int j = (goal.getY() - box.minY()) / CELL;
        for (int spread = 0; spread < ny; spread++) {
            for (int candidate : new int[] {j - spread, j + spread}) {
                if (candidate >= 0 && candidate < ny
                        && kind[index(nx, ny, nz, i, candidate, k)] == STANDABLE) {
                    return index(nx, ny, nz, i, candidate, k);
                }
            }
        }
        return -1;
    }

    private static int index(int nx, int ny, int nz, int i, int j, int k) {
        return (j * nz + k) * nx + i;
    }

    @Override
    public double estimate(int x, int y, int z) {
        if (!box.contains(x, y, z)) {
            return 0.0;
        }
        double value = cost[index(nx, ny, nz, (x - box.minX()) / CELL, (y - box.minY()) / CELL,
                (z - box.minZ()) / CELL)];
        if (Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value - slack);
    }
}
