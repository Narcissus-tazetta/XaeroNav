package net.prason.xaeronav.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.client.ElytraNavState;
import net.prason.xaeronav.client.MapDots;
import net.prason.xaeronav.client.PathColors;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.elytra.ElytraPath;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.gui.GuiMap;

/**
 * design doc §2-1/§2-5。世界地図の地形描画（{@code endBatch()}呼び出し1回目、ordinal 0）の直後に
 * 経路を1ブロック四方の色付き矩形の連なりとして描き足す。{@code flooredCameraX}/{@code flooredCameraZ}への
 * 引き算だけで地図座標に変換できるのは、地形描画自体が全く同じ変換を使っているため（design doc §2-2）。
 *
 * <p>required=falseの専用mixin configに属する。Xaero's Map未導入・大規模リファクタで対象メソッドの
 * 形が変わった場合はこの1機能だけが無効化され、MOD本体はワールド内描画のみで動作を続ける。
 */
@Mixin(GuiMap.class)
public abstract class GuiMapMixin {

    private static final double DOT_ALPHA = 0.9;

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 0)
    )
    private void xaeronav$drawPath(MultiBufferSource.BufferSource renderTypeBuffers, Operation<Void> original,
                                    @Local(name = "matrixStack") PoseStack matrixStack,
                                    @Local(name = "flooredCameraX") int flooredCameraX,
                                    @Local(name = "flooredCameraZ") int flooredCameraZ) {
        PathResult groundResult = PathfindingState.INSTANCE.currentResult();
        ElytraPath elytraPath = ElytraNavState.INSTANCE.currentPath();
        boolean hasGround = groundResult != null && !groundResult.steps().isEmpty();
        boolean hasElytra = elytraPath != null && !elytraPath.waypoints().isEmpty();
        if (hasGround || hasElytra) {
            VertexConsumer overlayBuffer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY);
            var pose = matrixStack.last().pose();
            if (hasGround) {
                MapDots dots = MapDots.forPath(groundResult);
                for (int i = 0; i < dots.count; i++) {
                    drawDot(pose, overlayBuffer, dots.x[i], dots.z[i], flooredCameraX, flooredCameraZ,
                            dots.color[i * 3], dots.color[i * 3 + 1], dots.color[i * 3 + 2]);
                }
            }
            if (hasElytra) {
                for (Vec3 waypoint : elytraPath.waypoints()) {
                    drawDot(pose, overlayBuffer, (int) Math.floor(waypoint.x), (int) Math.floor(waypoint.z),
                            flooredCameraX, flooredCameraZ, PathColors.ELYTRA[0], PathColors.ELYTRA[1], PathColors.ELYTRA[2]);
                }
            }
        }
        original.call(renderTypeBuffers);
    }

    private void drawDot(Matrix4f pose, VertexConsumer overlayBuffer, int blockX, int blockZ,
                          int flooredCameraX, int flooredCameraZ, float red, float green, float blue) {
        int x1 = blockX - flooredCameraX;
        int z1 = blockZ - flooredCameraZ;
        MapRenderHelper.fillIntoExistingBuffer(pose, overlayBuffer, x1, z1, x1 + 1, z1 + 1,
                red, green, blue, (float) DOT_ALPHA);
    }
}
