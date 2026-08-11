package net.prason.xaeronav.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.client.ElytraNavState;
import net.prason.xaeronav.client.MapDots;
import net.prason.xaeronav.client.PathColors;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.client.StraightDots;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.elytra.ElytraPath;
import xaero.common.graphics.CustomRenderTypes;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.render.util.RenderBufferUtil;

/**
 * design doc §2-1/§2-5。ミニマップ側のフック。{@code useWorldMap} true/false どちらの分岐で地形が
 * 描かれても、この直後の1回目の{@code endBatch()}（ordinal 0）に両分岐が収束するため、フックは1箇所で足りる。
 * required=falseの専用mixin configに属し、対象メソッドの形が変わった場合はこの機能だけが無効化される。
 */
@Mixin(MinimapFBORenderer.class)
public abstract class MinimapFBORendererMixin {

    private static final float DOT_ALPHA = 0.9f;

    /**
     * ミニマップのFBOは512x512で、この描画では1単位が1ブロックにあたる。したがって中心から
     * 256ブロックより先は原理的にFBOへ落ちない。余裕を取ってこの距離で切り、遠方まで伸びた経路が
     * 毎フレーム丸ごと積まれるのを防ぐ。
     */
    private static final int CULL_RADIUS_BLOCKS = 320;

    @WrapOperation(
            method = "renderChunksToFBO",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 0)
    )
    private void xaeronav$drawPath(MultiBufferSource.BufferSource renderTypeBuffers, Operation<Void> original,
                                    @Local(name = "matrixStack") PoseStack matrixStack,
                                    @Local(name = "xFloored") int xFloored,
                                    @Local(name = "zFloored") int zFloored) {
        PathResult groundResult = PathfindingState.INSTANCE.currentResult();
        ElytraPath elytraPath = ElytraNavState.INSTANCE.currentPath();
        BlockPos goal = PathfindingState.INSTANCE.goal();
        boolean hasGround = groundResult != null && !groundResult.steps().isEmpty();
        boolean hasElytra = elytraPath != null && !elytraPath.waypoints().isEmpty();
        boolean hasStraight = goal != null && XaeroNavConfig.INSTANCE.straightLineEnabled();
        if (hasGround || hasElytra || hasStraight) {
            VertexConsumer overlayBuffer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_CHUNK_OVERLAY);
            Matrix4f pose = matrixStack.last().pose();
            MapDots dots = hasGround ? MapDots.forPath(groundResult) : null;
            if (dots != null) {
                for (int i = 0; i < dots.count; i++) {
                    int blockX = dots.x[i];
                    int blockZ = dots.z[i];
                    if (outOfRange(blockX, blockZ, xFloored, zFloored)) {
                        continue;
                    }
                    drawDot(pose, overlayBuffer, blockX, blockZ, xFloored, zFloored,
                            dots.color[i * 3], dots.color[i * 3 + 1], dots.color[i * 3 + 2]);
                }
            }
            if (hasStraight) {
                BlockPos from = dots != null && dots.count > 0
                        ? new BlockPos(dots.x[dots.count - 1], 0, dots.z[dots.count - 1])
                        : Minecraft.getInstance().player.blockPosition();
                StraightDots.forEach(from.getX(), from.getZ(), goal.getX(), goal.getZ(), (x, z) -> {
                    if (!outOfRange(x, z, xFloored, zFloored)) {
                        drawDot(pose, overlayBuffer, x, z, xFloored, zFloored,
                                PathColors.STRAIGHT[0], PathColors.STRAIGHT[1], PathColors.STRAIGHT[2]);
                    }
                });
            }
            if (hasElytra) {
                for (Vec3 waypoint : elytraPath.waypoints()) {
                    drawDot(pose, overlayBuffer, (int) Math.floor(waypoint.x), (int) Math.floor(waypoint.z),
                            xFloored, zFloored, PathColors.ELYTRA[0], PathColors.ELYTRA[1], PathColors.ELYTRA[2]);
                }
            }
        }
        original.call(renderTypeBuffers);
    }

    private boolean outOfRange(int blockX, int blockZ, int xFloored, int zFloored) {
        return Math.abs(blockX - xFloored) > CULL_RADIUS_BLOCKS
                || Math.abs(blockZ - zFloored) > CULL_RADIUS_BLOCKS;
    }

    private void drawDot(Matrix4f pose, VertexConsumer overlayBuffer, int blockX, int blockZ,
                          int xFloored, int zFloored, float red, float green, float blue) {
        RenderBufferUtil.addColoredRect(pose, overlayBuffer, blockX - xFloored, blockZ - zFloored, 1, 1,
                red, green, blue, DOT_ALPHA);
    }
}
