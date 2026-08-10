package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

import net.minecraft.core.BlockPos;

/** @param bodyCells この移動で身体が通過する（＝掘削が必要になりうる）セル一覧。安全確認レイヤーが使う。 */
record Edge(BlockPos to, double cost, MovementType movement, boolean digging, List<BlockPos> bodyCells) {
}
