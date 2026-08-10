package net.prason.xaeronav.pathfinding.elytra;

import java.util.List;

import net.minecraft.world.phys.Vec3;

/**
 * @param waypoints 始点を含む連続的な経由点列（block格子に量子化しない、design doc §5）
 * @param complete  ゴールに到達できたか
 */
public record ElytraPath(List<Vec3> waypoints, boolean complete) {
}
