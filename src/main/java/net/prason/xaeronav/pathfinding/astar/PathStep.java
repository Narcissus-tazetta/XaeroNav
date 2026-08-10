package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * @param digging   この区間の移動に掘削が含まれるか（design doc §8「掘削経路を別色で表示」用）
 * @param bodyCells この移動で身体が通過する（＝掘削が必要になりうる）セル一覧。{@link PathSafetyChecker}が
 *                  到着地点だけでなく全セルの安全性を確認するために使う
 * @param risk      §3-4の安全確認レイヤーによる事後チェック結果。A*探索直後はNONE固定で、
 *                  {@link PathSafetyChecker#annotate}で埋める
 */
public record PathStep(BlockPos pos, MovementType movement, double cost, boolean digging,
                        List<BlockPos> bodyCells, PathRisk risk) {
}
