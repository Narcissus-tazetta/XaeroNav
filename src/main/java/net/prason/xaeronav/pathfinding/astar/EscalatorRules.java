package net.prason.xaeronav.pathfinding.astar;

import net.minecraft.core.Direction;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * MTRのエスカレーターに乗り降りする手の値段と、通れない手。床（足の1つ下）のどちらかが段のときだけ働く。
 *
 * <ul>
 * <li>段の上は向き（facing）の軸に沿ってしか進めない。手すりが列の外側を塞ぐので、横へ抜ける手・斜めの手は無い。
 * 2列の間の横移動だけは許す。</li>
 * <li>動いている段を押す向きと逆へは進めない。疾走しても乗り口から1〜2マスで押し戻されて止まる（実機で確認）。</li>
 * <li>押す向きへ歩く手は{@link ActionCosts#ESCALATOR_ONE_BLOCK}。止まっている段は普通の階段と同じ値段のまま。</li>
 * </ul>
 */
final class EscalatorRules {

    private EscalatorRules() {
    }

    static double edgeCost(CellSource view, PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        // 辺ごとに通る所なので、MTRが無ければ床を読まずに返す
        if (!CellData.escalatorsExist()) {
            return edgeCost;
        }
        long fromFloor = view.cell(from.x, from.y - 1, from.z);
        long toFloor = view.cell(x, y - 1, z);
        boolean fromEscalator = CellData.escalator(fromFloor);
        boolean toEscalator = CellData.escalator(toFloor);
        int dx = x - from.x;
        int dz = z - from.z;
        if (!fromEscalator && !toEscalator) {
            // 段の外どうしの斜めの手でも、角の列が段だと手すりの端をかすめる（手すりのセルは体を置ける扱いなので、
            // 角の通行の判定では止まらない）
            return dx != 0 && dz != 0 && (cornerOnEscalator(view, from.x + dx, from.z, from.y, y)
                    || cornerOnEscalator(view, from.x, from.z + dz, from.y, y)) ? ActionCosts.INFEASIBLE : edgeCost;
        }
        // 斜め・真上下（柱・梯子）・2マス以上の跳躍は、手すりか段に掛かる
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            return ActionCosts.INFEASIBLE;
        }
        Direction facing = CellData.escalatorFacing(fromEscalator ? fromFloor : toFloor);
        if (fromEscalator && toEscalator && CellData.escalatorFacing(toFloor) != facing) {
            return ActionCosts.INFEASIBLE;
        }
        boolean along = (facing.getStepX() != 0) == (dx != 0);
        if (!along) {
            return betweenColumns(fromFloor, toFloor, fromEscalator && toEscalator, y == from.y, facing, dx, dz)
                    ? edgeCost : ActionCosts.INFEASIBLE;
        }
        Direction push = fromEscalator ? CellData.escalatorPush(fromFloor) : null;
        if (push == null && toEscalator) {
            push = CellData.escalatorPush(toFloor);
        }
        if (push == null) {
            return edgeCost;
        }
        if (dx * push.getStepX() + dz * push.getStepZ() < 0) {
            return ActionCosts.INFEASIBLE;
        }
        return switch (kind) {
            case TRAVERSE, ASCEND, DESCEND -> ActionCosts.ESCALATOR_ONE_BLOCK;
            default -> edgeCost;
        };
    }

    private static boolean cornerOnEscalator(CellSource view, int x, int z, int fromY, int toY) {
        return CellData.escalator(view.cell(x, fromY - 1, z)) || CellData.escalator(view.cell(x, toY - 1, z));
    }

    /** 同じエスカレーターの左の列から右の列へ（またはその逆へ）、同じ高さで1マス横へ移る手か。 */
    private static boolean betweenColumns(long fromFloor, long toFloor, boolean bothSteps, boolean level,
                                          Direction facing, int dx, int dz) {
        if (!bothSteps || !level) {
            return false;
        }
        boolean fromRight = CellData.escalatorRightSide(fromFloor);
        if (fromRight == CellData.escalatorRightSide(toFloor)) {
            return false;
        }
        Direction toward = fromRight ? facing.getCounterClockWise() : facing.getClockWise();
        return dx == toward.getStepX() && dz == toward.getStepZ();
    }
}
