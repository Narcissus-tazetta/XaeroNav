package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/**
 * 読める範囲の縁（{@link FlightHorizon}）から出る出口の見積もりを、<b>読める範囲の内側を通らない</b>
 * 回り道で測り直す。
 *
 * <p>粗い地図の残りコストの場（{@link CoarseFlightField}）はチャンク解像度なので、1チャンクより薄い壁を
 * 見落とす。それをそのまま出口の見積もりに使うと、「出口から目的地へまっすぐ戻れる」と見積もった出口が
 * 選ばれる——戻り道が通る内側は、細かい格子で塞がっていると既に分かっているのに。ネザーで目的地の
 * 手前の壁を前に、目的地の周りを反対側の縁から縁へ回り続け、最適の3〜6倍の線が出ていた。
 *
 * <p>そこで出口の見積もりは、内側にすっぽり収まるチャンクを外した場で測る。目的地が内側にあるときは、
 * 目的地を含む空間を細かい格子で塗り広げ、それが縁へ触れた所を場の起点にする（外を回って戻ってくる
 * 入口）。塗った空間がプレイヤーまで届けば内側だけで繋がっているので、出口は使わない。縁にも
 * プレイヤーにも届かなければ、目的地はこの格子の粗さでは閉じた小部屋の中にある——出口を許すと届かない
 * 目的地の周りを回り続けるので、これも出口を使わず、空から寄れる所まで引く（{@code FlightRouter#approach}）。
 * 寄れる所を探すだけなので予算は{@link #ENCLOSED_MAX_EXPANDED_NODES}に絞る（満額だと届かないことを
 * 確かめるのに毎回2秒焼く）。
 */
final class HorizonGuide {

    /**
     * 目的地から塗り広げるセル数の上限。開けた所では読める範囲の大半を塗ることになるので頭打ちにする
     * （{@link AirGrid}が事前構築をしない理由と同じ）。上限まで塗れる空間はたいてい縁にも触れている。
     */
    private static final int MAX_FLOOD_CELLS = 40_000;

    /** 目的地が閉じた小部屋の中にあるときの展開数の上限。 */
    static final int ENCLOSED_MAX_EXPANDED_NODES = 15_000;

    private static final int[][] AXES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * 探索に渡す出口と見積もり。
     *
     * @param enclosed 目的地が閉じた小部屋の中にある（{@link #ENCLOSED_MAX_EXPANDED_NODES}で探す）
     */
    record Plan(FlightHorizon horizon, FlightGuide guide, boolean enclosed) {
    }

    private HorizonGuide() {
    }

    static Plan plan(AirGrid grid, Vec3 start, Vec3 goal, FlightHorizon horizon, CoarseFlightField field,
                     boolean rockets) {
        FlightGuide inside = field::estimate;
        if (horizon.radius() == Double.POSITIVE_INFINITY) {
            return new Plan(horizon, inside, false);
        }
        CoarseFlightField.ChunkFilter interior = (chunkX, chunkZ) -> chunkInside(horizon, chunkX, chunkZ);
        CoarseFlightField outside;
        if (horizon.outside(goal.x, goal.z)) {
            outside = field.avoiding(List.of(goal), seed -> 0.0, interior);
        } else {
            Flood flood = flood(grid, start, goal, horizon);
            if (flood.reachedStart()) {
                return new Plan(FlightHorizon.NONE, inside, false);
            }
            if (flood.entrances().isEmpty() && !flood.capped()) {
                return new Plan(FlightHorizon.NONE, inside, true);
            }
            outside = field.avoiding(flood.entrances(), seed -> lowerBound(seed, goal, grid, rockets), interior);
        }
        return new Plan(horizon, (x, y, z) -> horizon.outside(x, z) ? outside.estimate(x, y, z)
                : inside.estimate(x, y, z), false);
    }

    /** チャンクの四隅がどれも縁の内側にあるか。 */
    private static boolean chunkInside(FlightHorizon horizon, int chunkX, int chunkZ) {
        for (int corner = 0; corner < 4; corner++) {
            double x = (chunkX + (corner & 1)) * 16.0;
            double z = (chunkZ + (corner >> 1)) * 16.0;
            if (horizon.outside(x, z)) {
                return false;
            }
        }
        return true;
    }

    private static double lowerBound(Vec3 from, Vec3 goal, AirGrid grid, boolean rockets) {
        double radius = grid.cellBlocks() * FlightRouter.GOAL_RADIUS_CELLS;
        double horizontal = Math.max(0.0, Math.hypot(goal.x - from.x, goal.z - from.z) - radius);
        double tolerance = Math.max(radius, FlightPathfinder.GOAL_VERTICAL_TOLERANCE_BLOCKS);
        double dy = goal.y - from.y;
        return FlightCosts.lowerBoundTicks(horizontal, dy - tolerance, dy + tolerance, rockets);
    }

    /**
     * @param entrances 塗った空間に隣り合う、縁の外の飛べるセルの中心
     */
    private static long distanceSq(long key, int x, int y, int z) {
        long dx = BlockPos.getX(key) - x;
        long dy = BlockPos.getY(key) - y;
        long dz = BlockPos.getZ(key) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private record Flood(boolean reachedStart, boolean capped, List<Vec3> entrances) {
    }

    /**
     * 目的地の領域（{@link FlightPathfinder}がゴールとみなすセル）から、縁の内側を6近傍で塗り広げる。
     * 26近傍の斜めの移動は跨ぐ箱が全て飛べるときだけなので、6近傍で繋がる範囲と同じになる。
     *
     * <p>塗る順は<b>プレイヤーに近いセルから</b>。繋がっているときはプレイヤーへまっすぐ届いて早く
     * 終わる（幅優先では読める範囲の大半を塗ってから届いていた）。繋がっていないときは
     * どの順でも同じ空間を塗り切るので、縁への入口も変わらない。
     */
    private static Flood flood(AirGrid grid, Vec3 start, Vec3 goal, FlightHorizon horizon) {
        long startCell = grid.nearestFlyable(start, FlightPathfinder.SNAP_CELL_RADIUS);
        Vec3 snapped = FlightPathfinder.snappedGoal(grid, goal);
        double radius = grid.cellBlocks() * FlightRouter.GOAL_RADIUS_CELLS;
        double tolerance = Math.max(radius, FlightPathfinder.GOAL_VERTICAL_TOLERANCE_BLOCKS);
        LongOpenHashSet seen = new LongOpenHashSet();
        int towardX = grid.toCell(start.x);
        int towardY = grid.toCell(start.y);
        int towardZ = grid.toCell(start.z);
        LongHeapPriorityQueue queue = new LongHeapPriorityQueue((a, b) -> Long.compare(
                distanceSq(a, towardX, towardY, towardZ), distanceSq(b, towardX, towardY, towardZ)));
        int reachX = (int) Math.ceil(radius / grid.cellBlocks());
        int reachY = (int) Math.ceil(tolerance / grid.cellBlocks());
        int goalX = grid.toCell(snapped.x);
        int goalY = grid.toCell(snapped.y);
        int goalZ = grid.toCell(snapped.z);
        for (int dx = -reachX; dx <= reachX; dx++) {
            for (int dy = -reachY; dy <= reachY; dy++) {
                for (int dz = -reachX; dz <= reachX; dz++) {
                    Vec3 center = grid.center(goalX + dx, goalY + dy, goalZ + dz);
                    if (Math.hypot(center.x - snapped.x, center.z - snapped.z) > radius
                            || Math.abs(center.y - snapped.y) > tolerance || horizon.outside(center.x, center.z)
                            || !grid.flyable(goalX + dx, goalY + dy, goalZ + dz)) {
                        continue;
                    }
                    long key = BlockPos.asLong(goalX + dx, goalY + dy, goalZ + dz);
                    seen.add(key);
                    queue.enqueue(key);
                }
            }
        }
        List<Vec3> entrances = new ArrayList<>();
        int visited = 0;
        while (!queue.isEmpty()) {
            long key = queue.dequeueLong();
            if (key == startCell) {
                return new Flood(true, false, entrances);
            }
            if (++visited > MAX_FLOOD_CELLS) {
                return new Flood(false, true, entrances);
            }
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            for (int[] axis : AXES) {
                int nx = x + axis[0];
                int ny = y + axis[1];
                int nz = z + axis[2];
                long next = BlockPos.asLong(nx, ny, nz);
                if (!seen.add(next) || !grid.flyable(nx, ny, nz)) {
                    continue;
                }
                Vec3 center = grid.center(nx, ny, nz);
                if (horizon.outside(center.x, center.z)) {
                    entrances.add(center);
                } else {
                    queue.enqueue(next);
                }
            }
        }
        return new Flood(false, false, entrances);
    }
}
