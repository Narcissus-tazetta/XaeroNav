package net.prason.xaeronav.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.elytra.ElytraPath;

/**
 * design doc §2-5 / Phase 2項目9。Xaero非依存のワールド内描画。
 * Xaero連携（世界地図・ミニマップアダプタ）が崩れてもこれだけは生き残る構成にする。
 */
public final class PathRenderer {

    private static final float[] ELYTRA_COLOR = {0.9f, 0.95f, 1.0f};

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        PathResult groundResult = PathfindingState.INSTANCE.currentResult();
        ElytraPath elytraPath = ElytraNavState.INSTANCE.currentPath();
        boolean hasGround = groundResult != null && !groundResult.steps().isEmpty();
        boolean hasElytra = elytraPath != null && elytraPath.waypoints().size() >= 2;
        if (!hasGround && !hasElytra) {
            return;
        }

        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();

        if (hasGround) {
            BlockPos previous = mc.player.blockPosition();
            for (PathStep step : groundResult.steps()) {
                drawGroundSegment(buffer, pose, previous, step);
                previous = step.pos();
            }
        }
        if (hasElytra) {
            List<Vec3> waypoints = elytraPath.waypoints();
            for (int i = 0; i + 1 < waypoints.size(); i++) {
                drawSegment(buffer, pose, waypoints.get(i), waypoints.get(i + 1), ELYTRA_COLOR);
            }
        }

        bufferSource.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private void drawGroundSegment(VertexConsumer buffer, PoseStack.Pose pose, BlockPos from, PathStep step) {
        BlockPos to = step.pos();
        Vec3 fromVec = new Vec3(from.getX() + 0.5, from.getY() + 0.55, from.getZ() + 0.5);
        Vec3 toVec = new Vec3(to.getX() + 0.5, to.getY() + 0.55, to.getZ() + 0.5);
        drawSegment(buffer, pose, fromVec, toVec, colorFor(step));
    }

    private void drawSegment(VertexConsumer buffer, PoseStack.Pose pose, Vec3 from, Vec3 to, float[] color) {
        buffer.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
                .setColor(color[0], color[1], color[2], 1.0f).setNormal(pose, 0f, 1f, 0f);
        buffer.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
                .setColor(color[0], color[1], color[2], 1.0f).setNormal(pose, 0f, 1f, 0f);
    }

    private float[] colorFor(PathStep step) {
        if (step.risk() == PathRisk.LAVA_ADJACENT) {
            return new float[]{1.0f, 0.1f, 0.1f};
        }
        if (step.risk() == PathRisk.VOID_BELOW) {
            return new float[]{0.8f, 0.1f, 0.8f};
        }
        if (step.risk() == PathRisk.WATER_INFLOW) {
            return new float[]{0.1f, 0.7f, 1.0f};
        }
        if (step.digging()) {
            return new float[]{1.0f, 0.55f, 0.1f};
        }
        if (step.movement() == MovementType.ASCEND) {
            return new float[]{1.0f, 0.9f, 0.2f};
        }
        if (step.movement() == MovementType.DESCEND) {
            return new float[]{0.3f, 0.6f, 1.0f};
        }
        return new float[]{0.2f, 0.9f, 0.5f};
    }
}
