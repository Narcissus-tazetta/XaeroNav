package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.prason.xaeronav.pathfinding.world.BlockSnapshotData;
import net.prason.xaeronav.pathfinding.world.WorldSnapshot;

/**
 * design doc §3-4。コスト計算はあくまで事前見積もりなので、経路を提示する直前に
 * 掘削区間の安全性を再チェックする（溶岩隣接・水の流入・深い縦穴への露出）。
 * A*の探索コストには影響させず、結果に対する事後アノテーションとして分離する。
 */
public final class PathSafetyChecker {

    private static final int VOID_SCAN_DEPTH = 5;

    private PathSafetyChecker() {
    }

    public static PathResult annotate(WorldSnapshot snapshot, PathResult result) {
        List<PathStep> annotated = new ArrayList<>(result.steps().size());
        for (PathStep step : result.steps()) {
            PathRisk risk = step.digging() ? assessRisk(snapshot, step.bodyCells()) : PathRisk.NONE;
            annotated.add(new PathStep(step.pos(), step.movement(), step.cost(), step.digging(), step.bodyCells(), risk));
        }
        return new PathResult(annotated, result.complete(), result.expandedNodes());
    }

    /**
     * 到着地点だけでなく、この移動で実際に掘った可能性のある全セル（例: Traverseならbody上下2マス、
     * Descendなら3マス）をチェックする。到着地点1マスだけを見ると、頭上側だけが溶岩隣接、といった
     * ケースを見逃す。
     */
    private static PathRisk assessRisk(WorldSnapshot snapshot, List<BlockPos> bodyCells) {
        for (BlockPos cell : bodyCells) {
            if (hasAdjacent(snapshot, cell, BlockSnapshotData::lava)) {
                return PathRisk.LAVA_ADJACENT;
            }
        }
        for (BlockPos cell : bodyCells) {
            if (hasAdjacent(snapshot, cell, BlockSnapshotData::water)) {
                return PathRisk.WATER_INFLOW;
            }
        }
        for (BlockPos cell : bodyCells) {
            if (isVoidBelow(snapshot, cell)) {
                return PathRisk.VOID_BELOW;
            }
        }
        return PathRisk.NONE;
    }

    private static boolean hasAdjacent(WorldSnapshot snapshot, BlockPos pos, java.util.function.Predicate<BlockSnapshotData> test) {
        for (Direction dir : Direction.values()) {
            BlockSnapshotData neighbor = snapshot.get(pos.relative(dir));
            if (neighbor != null && test.test(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVoidBelow(WorldSnapshot snapshot, BlockPos pos) {
        BlockPos cursor = pos.below();
        for (int i = 0; i < VOID_SCAN_DEPTH; i++) {
            BlockSnapshotData cell = snapshot.get(cursor);
            if (cell == null || !cell.isOccupiableWithoutDigging()) {
                return false;
            }
            cursor = cursor.below();
        }
        return true;
    }
}
