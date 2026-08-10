package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * @param bodyCells      この移動で身体が通過するセル一覧。{@link PathSafetyChecker}や経路の再確認が
 *                       到着地点だけでなく全セルを見るために使う
 * @param digCells       この移動で実際に壊すセル一覧（design doc §8「掘削経路を別色で表示」用）。
 *                       身体が通過しない落下ブロック連鎖（頭上の砂・砂利）もここに含まれる
 * @param risk           §3-4の安全確認レイヤーによる事後チェック結果。A*探索直後はNONE固定で、
 *                       {@link PathSafetyChecker#annotate}で埋める
 * @param placedBlockPos 空洞をブロックを置いて渡る区間の場合、置く先の座標。それ以外はnull
 */
public record PathStep(BlockPos pos, MovementType movement, double cost,
                        List<BlockPos> bodyCells, List<BlockPos> digCells, PathRisk risk, BlockPos placedBlockPos) {

    public boolean digging() {
        return !digCells.isEmpty();
    }

    public boolean bridging() {
        return placedBlockPos != null;
    }

    public boolean swimming() {
        return movement == MovementType.SWIM;
    }

    public boolean climbing() {
        return movement == MovementType.CLIMB;
    }
}
