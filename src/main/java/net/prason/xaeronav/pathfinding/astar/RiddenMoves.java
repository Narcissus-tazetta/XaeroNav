package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * 乗り物に乗ったままの移動候補（{@link PathNode#mounted}のノード）。ノードの座標が何を指すかは乗り物ごとに違い、
 * 足場が{@link #span()}×{@link #span()}の列のうち{@code (x, z)}を角とする。
 */
interface RiddenMoves {

    void expand(PathNode from);

    /** 乗っている始点。乗ったままでは居られない所なら{@code null}——歩きで始める。 */
    @Nullable BlockPos resolveStart(BlockPos start);

    /** 平らに進む1ブロックの時間。{@link AStarPathfinder}の下限はこれを割らないように置く。 */
    double ticksPerBlock();

    /** 足場の幅（列数）。 */
    int span();

    /** 足場の高さ{@code nodeY}で、高さ{@code goalY}のゴールに着いたとみなせるか。 */
    boolean reachesGoalHeight(int nodeY, int goalY);

    /** 無傷で落ちられる落差。落ちる手が無ければ0。 */
    int safeFallBlocks();

    /** 移動で体が通るセル。 */
    List<BlockPos> bodyCells(PathNode from, PathNode to);

    /** ゴールとの距離・ガイドを引くときに使う、足場の列のうち{@code target}に一番近い列。 */
    default int nearest(int corner, int target) {
        return Math.max(corner, Math.min(corner + span() - 1, target));
    }
}
