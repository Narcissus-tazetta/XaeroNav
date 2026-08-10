package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.ChunkView;

/**
 * design doc §3-4。コスト計算はあくまで事前見積もりなので、経路を提示する直前に
 * 掘削区間の安全性を再チェックする（溶岩隣接・水の流入・深い縦穴への露出）。
 * A*の探索コストには影響させず、結果に対する事後アノテーションとして分離する。
 */
public final class PathSafetyChecker {

    private static final int VOID_SCAN_DEPTH = 5;
    private static final Direction[] DIRECTIONS = Direction.values();

    private PathSafetyChecker() {
    }

    public static PathResult annotate(ChunkView view, PathResult result) {
        List<PathStep> annotated = new ArrayList<>(result.steps().size());
        for (PathStep step : result.steps()) {
            PathRisk risk = assessRisk(view, step);
            // 大半の区間は危険なし＝入力のまま。作り直す必要があるものだけ差し替える
            annotated.add(risk == step.risk() ? step
                    : new PathStep(step.pos(), step.movement(), step.cost(), step.bodyCells(), step.digCells(),
                            risk, step.placedBlockPos()));
        }
        return new PathResult(annotated, result.complete(), result.expandedNodes());
    }

    private static PathRisk assessRisk(ChunkView view, PathStep step) {
        if (step.bridging()) {
            // 置いた足場は渡っている間ずっと身体の真下にある。下が空虚なのは設置区間では前提なので見ない
            return hasAdjacent(view, step.placedBlockPos(), CellData::lava) ? PathRisk.LAVA_ADJACENT : PathRisk.NONE;
        }
        return step.digging() ? assessDigRisk(view, step.digCells()) : PathRisk.NONE;
    }

    /**
     * 到着地点だけでなく、この移動で掘る全セル（例: Traverseならbody上下2マス、Descendなら3マス、
     * さらに頭上の落下ブロック連鎖）をチェックする。到着地点1マスだけを見ると、頭上側だけが
     * 溶岩隣接、といったケースを見逃す。
     */
    private static PathRisk assessDigRisk(ChunkView view, List<BlockPos> digCells) {
        for (BlockPos cell : digCells) {
            if (hasAdjacent(view, cell, CellData::lava)) {
                return PathRisk.LAVA_ADJACENT;
            }
        }
        for (BlockPos cell : digCells) {
            if (hasAdjacent(view, cell, CellData::water)) {
                return PathRisk.WATER_INFLOW;
            }
        }
        for (BlockPos cell : digCells) {
            if (isVoidBelow(view, cell)) {
                return PathRisk.VOID_BELOW;
            }
        }
        return PathRisk.NONE;
    }

    private static boolean hasAdjacent(ChunkView view, BlockPos pos, LongPredicate test) {
        for (Direction dir : DIRECTIONS) {
            long neighbor = view.cell(pos.getX() + dir.getStepX(), pos.getY() + dir.getStepY(), pos.getZ() + dir.getStepZ());
            if (test.test(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVoidBelow(ChunkView view, BlockPos pos) {
        for (int depth = 1; depth <= VOID_SCAN_DEPTH; depth++) {
            if (!CellData.occupiableWithoutDigging(view.cell(pos.getX(), pos.getY() - depth, pos.getZ()))) {
                return false;
            }
        }
        return true;
    }
}
