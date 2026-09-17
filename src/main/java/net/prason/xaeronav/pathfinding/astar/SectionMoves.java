package net.prason.xaeronav.pathfinding.astar;

import java.util.function.BooleanSupplier;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 航法グラフの1セクション（16³）から出る移動を、<b>探索とまったく同じ移動生成で</b>全部拾う。
 *
 * <p>別のコストモデルで辺を張り直さないのが要点——層1・層2はそれをやって「真のコストを粗くしたもの」ではなく
 * 「別の推測」になり、ガイドの質の天井を作っていた。
 *
 * <p>展開するのは{@code mask}に含まれるセルだけで、辺の行き先はセクションの外でもよい。
 */
public final class SectionMoves {

    public static final int SIZE = 16;

    /** 展開してよいセル（殻）。 */
    @FunctionalInterface
    public interface Mask {
        boolean contains(int x, int y, int z);
    }

    /** 拾った辺。座標は{@link BlockPos#asLong}、値段はtick。 */
    @FunctionalInterface
    public interface Sink {
        void edge(long from, long to, float cost);
    }

    private SectionMoves() {
    }

    /**
     * @param goalX 目的地の列。奈落の上の橋は目的地へ近づく向きにしか張られない（{@link BuildMoves#addBridge}）ので、
     *              ここで拾った辺は目的地ごとに違う
     * @return 打ち切られたら{@code false}
     */
    public static boolean build(CellSource cells, int sectionX, int sectionY, int sectionZ, Mask mask, int goalX,
                                int goalZ, Sink sink, BooleanSupplier cancelled) {
        LongArrayList seeds = new LongArrayList();
        int minX = sectionX * SIZE;
        int minY = sectionY * SIZE;
        int minZ = sectionZ * SIZE;
        for (int y = minY; y < minY + SIZE; y++) {
            for (int x = minX; x < minX + SIZE; x++) {
                for (int z = minZ; z < minZ + SIZE; z++) {
                    if (mask.contains(x, y, z)) {
                        seeds.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        if (seeds.isEmpty()) {
            return true;
        }
        // 重み0の素のDijkstraとして回す。上限は置かない——展開はセクションの殻に閉じている
        AStarPathfinder closure = new AStarPathfinder(cells, new SearchLimits(Integer.MAX_VALUE, Long.MAX_VALUE / 4, 0.0));
        closure.expandFilter((x, y, z) -> Math.floorDiv(x, SIZE) == sectionX && Math.floorDiv(y, SIZE) == sectionY
                && Math.floorDiv(z, SIZE) == sectionZ && mask.contains(x, y, z));
        closure.edgeSink((fx, fy, fz, fromBoating, tx, ty, tz, toBoating, edgeCost, kind) -> {
            // 水中の割増は探索が到達経路の息の勘定から決める。辺の値段としては「着いた先で頭が水に浸かっていて、
            // 真上へ1マス浮上するのでない」ときに掛かると見なす（AStarPathfinder#relaxと同じ免除）
            boolean surfacing = ty > fy && Math.abs(tx - fx) + Math.abs(tz - fz) <= 1;
            boolean submerged = !surfacing && CellData.water(cells.cell(tx, ty + 1, tz));
            sink.edge(BlockPos.asLong(fx, fy, fz), BlockPos.asLong(tx, ty, tz),
                    (float) (submerged ? edgeCost * ActionCosts.SUBMERGED_TRAVEL_PENALTY : edgeCost));
        });
        return closure.exhaust(seeds.elements(), seeds.size(), goalX, goalZ, cancelled) >= 0;
    }
}
