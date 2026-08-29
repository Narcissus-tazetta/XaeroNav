package net.prason.xaeronav.pathfinding.astar;

import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 「平地でナビの線がL字/階段になる」の再現。合成の完全平地と、実機のオーバーワールド保存データ
 * （{@link RealOverworldTerrainTest}が書き出す列データ）の両方で、既定の重み(1.5)と重み1.0の
 * 経路の形を比べる。断定ではなく観測——CIのアサートは緩めにして、標準出力に形を出す。
 */
class FlatGroundDiagonalReproTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static FakeCells flatFloor(int x0, int z0, int x1, int z1, int floorY) {
        SearchBounds bounds = new SearchBounds(
                x0 - 16, floorY - 8, z0 - 16, x1 + 16, floorY + 32, z1 + 16);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true);
        for (int x = x0 - 16; x <= x1 + 16; x++) {
            for (int z = z0 - 16; z <= z1 + 16; z++) {
                cells.set(x, floorY, z, FakeCells.STONE);
            }
        }
        return cells;
    }

    @Test
    void flatGround_defaultWeight_vs_optimal() {
        int floorY = 63;
        BlockPos start = new BlockPos(0, floorY + 1, 0);
        BlockPos goal = new BlockPos(80, floorY + 1, 25);
        FakeCells cells = flatFloor(-8, -8, 96, 40, floorY);

        for (double weight : new double[] {AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, 1.0}) {
            SearchLimits limits = new SearchLimits(500_000, 20_000, weight);
            PathResult result = new AStarPathfinder(cells, limits).search(start, goal, NEVER);
            report("平地 合成  weight=" + weight, start, goal, result, cells);
        }
    }

    static void report(String label, BlockPos start, BlockPos goal, PathResult result) {
        report(label, start, goal, result, null);
    }

    /** その XZ で立てる床の Y（{@code aroundY} から上下に走査）。無ければ {@code Integer.MIN_VALUE}。 */
    private static int floorY(CellSource cells, int x, int z, int aroundY) {
        for (int dy = 0; dy <= 4; dy++) {
            for (int y : new int[] {aroundY - dy, aroundY + dy}) {
                if (CellData.standable(cells.cell(x, y - 1, z))
                        && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                        && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z))) {
                    return y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    /** {@code a}→{@code b} の直線を歩けるか（各列に段差1以内で立てる床がある）。 */
    private static boolean walkableLine(CellSource cells, int ax, int ay, int az, int bx, int by, int bz) {
        int steps = Math.max(Math.abs(bx - ax), Math.abs(bz - az));
        if (steps == 0) {
            return true;
        }
        int prevY = ay;
        for (int i = 1; i <= steps; i++) {
            int x = ax + (bx - ax) * i / steps;
            int z = az + (bz - az) * i / steps;
            int y = floorY(cells, x, z, prevY);
            if (y == Integer.MIN_VALUE || Math.abs(y - prevY) > 1) {
                return false;
            }
            prevY = y;
        }
        return true;
    }

    /** 貪欲な string-pull。歩ける直線で結べる限り中間点を飛ばす。 */
    private static java.util.List<BlockPos> stringPull(CellSource cells, java.util.List<BlockPos> pts) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        out.add(pts.get(0));
        int anchor = 0;
        while (anchor < pts.size() - 1) {
            int best = anchor + 1;
            for (int j = anchor + 2; j < pts.size(); j++) {
                BlockPos a = pts.get(anchor);
                BlockPos b = pts.get(j);
                if (walkableLine(cells, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ())) {
                    best = j;
                }
            }
            out.add(pts.get(best));
            anchor = best;
        }
        return out;
    }

    private static double maxDeviation(java.util.List<BlockPos> poly, BlockPos start, BlockPos goal) {
        double gx = goal.getX() - start.getX();
        double gz = goal.getZ() - start.getZ();
        double len = Math.hypot(gx, gz);
        double devi = 0;
        // ポリラインの各線分を細かくサンプルして直線からのずれを測る
        for (int s = 0; s < poly.size() - 1; s++) {
            BlockPos p = poly.get(s);
            BlockPos q = poly.get(s + 1);
            int n = Math.max(Math.abs(q.getX() - p.getX()), Math.abs(q.getZ() - p.getZ())) + 1;
            for (int i = 0; i < n; i++) {
                double rx = p.getX() + (q.getX() - p.getX()) * (i / (double) n) - start.getX();
                double rz = p.getZ() + (q.getZ() - p.getZ()) * (i / (double) n) - start.getZ();
                devi = Math.max(devi, Math.abs(rx * gz - rz * gx) / len);
            }
        }
        return devi;
    }

    static void report(String label, BlockPos start, BlockPos goal, PathResult result, CellSource cells) {
        List<PathStep> steps = result.steps();
        int diag = 0;
        int card = 0;
        int turns = 0;
        int prevDx = 0;
        int prevDz = 0;
        int px = start.getX();
        int pz = start.getZ();
        int maxRun = 0;
        int run = 0;
        int lastKind = -1;
        StringBuilder shape = new StringBuilder();
        for (PathStep step : steps) {
            int dx = Integer.signum(step.pos().getX() - px);
            int dz = Integer.signum(step.pos().getZ() - pz);
            px = step.pos().getX();
            pz = step.pos().getZ();
            boolean isDiag = dx != 0 && dz != 0;
            int kind = isDiag ? 2 : (dx != 0 ? 0 : 1);
            if (isDiag) {
                diag++;
            } else if (dx != 0 || dz != 0) {
                card++;
            }
            if (kind == lastKind) {
                run++;
            } else {
                maxRun = Math.max(maxRun, run);
                run = 1;
                lastKind = kind;
            }
            if ((dx != prevDx || dz != prevDz) && (dx != 0 || dz != 0)) {
                turns++;
            }
            prevDx = dx;
            prevDz = dz;
            shape.append(isDiag ? '\\' : (dx != 0 ? '-' : '|'));
        }
        maxRun = Math.max(maxRun, run);

        // 始点→終点の直線からの最大ずれ（L字ほど大きい）
        double devi = 0;
        double gx = goal.getX() - start.getX();
        double gz = goal.getZ() - start.getZ();
        double len = Math.hypot(gx, gz);
        px = start.getX();
        pz = start.getZ();
        for (PathStep step : steps) {
            double rx = step.pos().getX() - start.getX();
            double rz = step.pos().getZ() - start.getZ();
            double cross = Math.abs(rx * gz - rz * gx) / len;
            devi = Math.max(devi, cross);
        }

        String pulled = "";
        if (cells != null && !steps.isEmpty()) {
            java.util.List<BlockPos> pts = new java.util.ArrayList<>();
            pts.add(start);
            for (PathStep st : steps) {
                pts.add(st.pos());
            }
            java.util.List<BlockPos> sp = stringPull(cells, pts);
            pulled = String.format("  ->stringPull: segments=%d maxDeviation=%.1f",
                    sp.size() - 1, maxDeviation(sp, start, goal));
        }

        System.out.printf(
                "%-22s | complete=%-5s term=%-13s steps=%-3d diag=%-3d card=%-3d turns=%-2d "
                        + "longestStraightRun=%-3d maxDeviation=%.1f%s%n",
                label, result.complete(), result.termination(), steps.size(), diag, card, turns,
                maxRun, devi, pulled);
        System.out.println("   " + shape);
    }
}
