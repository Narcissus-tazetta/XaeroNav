package net.prason.xaeronav.pathfinding.elytra;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * design doc §5。徒歩用のマス単位A*とは別アルゴリズム。3段構え：
 * <ol>
 *   <li>直線判定（5〜10マス間隔でレイキャスト）</li>
 *   <li>ダメなら区間最高地形+マージンまで高度を上げて再判定</li>
 *   <li>それでもダメな区間だけ粗いグリッド（4〜8マス刻み）A*にフォールバック</li>
 * </ol>
 * ブロック格子ではなく連続座標（{@link Vec3}）で経路を表す。
 */
public final class ElytraPathfinder {

    /**
     * 高度上げのために地形の高さを見る間隔（マス）。1マス刻みにするのは、間隔を空けると
     * 尖った峰や1マス幅の柱を跨いで見落とすため。
     */
    private static final double TERRAIN_SCAN_INTERVAL = 1.0;

    private static final double TERRAIN_MARGIN = 15.0;
    private static final double COARSE_GRID_STEP = 6.0;
    private static final int MAX_EXPANDED_NODES = 20_000;

    private final CellSource view;

    public ElytraPathfinder(CellSource view) {
        this.view = view;
    }

    public ElytraPath findPath(Vec3 start, Vec3 goal) {
        if (!intersectsTerrain(start, goal)) {
            return new ElytraPath(List.of(start, goal), true);
        }

        double raiseY = Math.min(maxTerrainHeightAlong(start, goal) + TERRAIN_MARGIN, view.bounds().maxY());
        Vec3 up1 = new Vec3(start.x, Math.max(start.y, raiseY), start.z);
        Vec3 up2 = new Vec3(goal.x, Math.max(goal.y, raiseY), goal.z);
        List<Vec3> raised = List.of(start, up1, up2, goal);
        if (isPathClear(raised)) {
            return new ElytraPath(raised, true);
        }

        return coarseGridSearch(start, goal);
    }

    private boolean isPathClear(List<Vec3> waypoints) {
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            if (intersectsTerrain(waypoints.get(i), waypoints.get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 線分が通るセルを1つ残らず調べる（Amanatides–Wooのボクセル走査）。
     *
     * <p>一定間隔で点を打つ方式では、サンプルとサンプルの間にある壁や尾根をまるごと跨いで
     * 見落とす。徒歩なら「進んでみたら塞がっていた」で済むが、エリトラは秒速30〜40マスで
     * 飛ぶので、見落とした壁は激突と同義になる。判定の粗さがそのまま事故になる場所なので、
     * ここは間引かずに全セルを見る。
     */
    private boolean intersectsTerrain(Vec3 a, Vec3 b) {
        int x = Mth.floor(a.x);
        int y = Mth.floor(a.y);
        int z = Mth.floor(a.z);
        int lastX = Mth.floor(b.x);
        int lastY = Mth.floor(b.y);
        int lastZ = Mth.floor(b.z);

        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        int stepX = (int) Math.signum(dx);
        int stepY = (int) Math.signum(dy);
        int stepZ = (int) Math.signum(dz);
        // 線分の長さを1としたときの、次のセル境界までの距離とセル1つ分の距離
        double nextX = boundaryFraction(a.x, stepX, dx);
        double nextY = boundaryFraction(a.y, stepY, dy);
        double nextZ = boundaryFraction(a.z, stepZ, dz);
        double spanX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double spanY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double spanZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);

        while (true) {
            if (isSolid(x, y, z)) {
                return true;
            }
            if (x == lastX && y == lastY && z == lastZ) {
                return false;
            }
            // 最も近い境界を1つだけ跨ぐ。1を超えたらもう線分の外
            if (nextX <= nextY && nextX <= nextZ) {
                if (nextX > 1.0) {
                    return false;
                }
                x += stepX;
                nextX += spanX;
            } else if (nextY <= nextZ) {
                if (nextY > 1.0) {
                    return false;
                }
                y += stepY;
                nextY += spanY;
            } else {
                if (nextZ > 1.0) {
                    return false;
                }
                z += stepZ;
                nextZ += spanZ;
            }
        }
    }

    /** 進行方向にある最初のセル境界までの距離（線分の長さを1とした比率）。 */
    private static double boundaryFraction(double position, int step, double delta) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double offsetInCell = position - Math.floor(position);
        return (step > 0 ? 1.0 - offsetInCell : offsetInCell) / Math.abs(delta);
    }

    private boolean isSolid(int x, int y, int z) {
        // 範囲外・未ロードチャンクはABSENTになり、passableEmptyがfalseなので自動的に障害物扱いになる
        return !CellData.passableEmpty(view.cell(x, y, z));
    }

    private double maxTerrainHeightAlong(Vec3 a, Vec3 b) {
        double horizontal = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
        int samples = Math.max(1, (int) Math.ceil(horizontal / TERRAIN_SCAN_INTERVAL));
        double maxY = Math.max(a.y, b.y);
        for (int i = 0; i <= samples; i++) {
            Vec3 p = a.lerp(b, (double) i / samples);
            maxY = Math.max(maxY, columnHeight(p.x, p.z));
        }
        return maxY;
    }

    private double columnHeight(double x, double z) {
        SearchBounds bounds = view.bounds();
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            long cell = view.cell(bx, y, bz);
            if (CellData.present(cell) && !CellData.passableEmpty(cell)) {
                return y + 1;
            }
        }
        return bounds.minY();
    }

    private record GridNode(int gx, int gy, int gz) {
        Vec3 toVec3() {
            return new Vec3(gx * COARSE_GRID_STEP, gy * COARSE_GRID_STEP, gz * COARSE_GRID_STEP);
        }
    }

    private static GridNode snapToGrid(Vec3 v) {
        return new GridNode(
                (int) Math.round(v.x / COARSE_GRID_STEP),
                (int) Math.round(v.y / COARSE_GRID_STEP),
                (int) Math.round(v.z / COARSE_GRID_STEP));
    }

    /**
     * 実座標を格子に丸めるだけだと、最寄りの格子点との間に地形が挟まっていても
     * 検証されないまま経路の始点・終点として使われてしまう。ここで実座標から直線が
     * 通る格子点を探し、それを探索の起点/終点アンカーにする。
     * （見つからない場合は最寄り格子点をそのまま返す。最終的な安全性は{@link #buildResult}側の
     * 全区間チェックが担保する）
     */
    private GridNode findAnchor(Vec3 exact) {
        GridNode primary = snapToGrid(exact);
        if (!intersectsTerrain(exact, primary.toVec3())) {
            return primary;
        }
        GridNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    GridNode candidate = new GridNode(primary.gx() + dx, primary.gy() + dy, primary.gz() + dz);
                    Vec3 candidateVec = candidate.toVec3();
                    if (intersectsTerrain(exact, candidateVec)) {
                        continue;
                    }
                    double dist = exact.distanceTo(candidateVec);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = candidate;
                    }
                }
            }
        }
        return best != null ? best : primary;
    }

    private static double heuristic(GridNode a, GridNode b) {
        double dx = (a.gx() - b.gx()) * COARSE_GRID_STEP;
        double dy = (a.gy() - b.gy()) * COARSE_GRID_STEP;
        double dz = (a.gz() - b.gz()) * COARSE_GRID_STEP;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 直線判定・高度上げでは越えられない区間（谷・洞窟等）専用の、粗いグリッドA*（design doc §5-2 Step3）。 */
    private ElytraPath coarseGridSearch(Vec3 start, Vec3 goal) {
        GridNode startNode = findAnchor(start);
        GridNode goalNode = findAnchor(goal);

        record OpenEntry(GridNode node, double f) {
        }

        PriorityQueue<OpenEntry> open = new PriorityQueue<>(Comparator.comparingDouble(OpenEntry::f));
        Map<GridNode, Double> gScore = new HashMap<>();
        Map<GridNode, GridNode> cameFrom = new HashMap<>();
        Set<GridNode> closed = new HashSet<>();

        gScore.put(startNode, 0.0);
        open.add(new OpenEntry(startNode, heuristic(startNode, goalNode)));

        GridNode bestSoFar = startNode;
        double bestSoFarH = heuristic(startNode, goalNode);
        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_EXPANDED_NODES) {
            OpenEntry current = open.poll();
            if (closed.contains(current.node())) {
                continue;
            }
            if (current.node().equals(goalNode)) {
                return buildResult(cameFrom, startNode, goalNode, start, goal, true);
            }
            closed.add(current.node());
            expanded++;

            double h = heuristic(current.node(), goalNode);
            if (h < bestSoFarH) {
                bestSoFarH = h;
                bestSoFar = current.node();
            }

            double currentG = gScore.get(current.node());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        GridNode neighbor = new GridNode(current.node().gx() + dx, current.node().gy() + dy, current.node().gz() + dz);
                        if (closed.contains(neighbor)) {
                            continue;
                        }
                        Vec3 from = current.node().toVec3();
                        Vec3 to = neighbor.toVec3();
                        if (!view.isInBounds(Mth.floor(to.x), Mth.floor(to.y), Mth.floor(to.z)) || intersectsTerrain(from, to)) {
                            continue;
                        }
                        double tentativeG = currentG + from.distanceTo(to);
                        Double existing = gScore.get(neighbor);
                        if (existing == null || tentativeG < existing) {
                            gScore.put(neighbor, tentativeG);
                            cameFrom.put(neighbor, current.node());
                            open.add(new OpenEntry(neighbor, tentativeG + heuristic(neighbor, goalNode)));
                        }
                    }
                }
            }
        }

        return buildResult(cameFrom, startNode, bestSoFar, start, goal, false);
    }

    private ElytraPath buildResult(Map<GridNode, GridNode> cameFrom, GridNode startNode, GridNode endNode,
                                    Vec3 exactStart, Vec3 exactGoal, boolean complete) {
        List<Vec3> waypoints = new ArrayList<>();
        GridNode cursor = endNode;
        while (!cursor.equals(startNode)) {
            waypoints.add(cursor.toVec3());
            GridNode prev = cameFrom.get(cursor);
            if (prev == null) {
                break;
            }
            cursor = prev;
        }
        // startNode自体も明示的に経由点へ含める。exactStart→startNodeの区間は
        // findAnchorで検証済みなので、これを省略せず出力に残すことで区間の連続性が保たれる
        waypoints.add(startNode.toVec3());
        Collections.reverse(waypoints);

        List<Vec3> result = new ArrayList<>();
        result.add(exactStart);
        result.addAll(waypoints);
        if (complete) {
            result.add(exactGoal);
        }

        // findAnchorが有効な格子点を見つけられなかった場合の保険として、実際に組み立てた
        // 経路の全区間を最後にもう一度確認する。ここで引っかかったら「完了」を名乗らない
        // （§4-4と同じ考え方：安全側に倒して不完全扱いにする）
        boolean verified = complete && isPathClear(result);
        return new ElytraPath(result, verified);
    }
}
