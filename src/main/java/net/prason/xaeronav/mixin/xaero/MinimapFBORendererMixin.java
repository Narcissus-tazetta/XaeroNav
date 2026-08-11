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
import net.prason.xaeronav.client.MapPathOverlay;
import xaero.common.graphics.CustomRenderTypes;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.render.util.RenderBufferUtil;

/**
 * design doc §2-1/§2-5。ミニマップ側のフック。{@code useWorldMap} true/false どちらの分岐で地形が
 * 描かれても、この直後の1回目の{@code endBatch()}（ordinal 0）に両分岐が収束するため、フックは1箇所で足りる。
 *
 * <p>何をどの色で描くかは{@link MapPathOverlay}が決める（世界地図側と共有）。ここが持つのは
 * Xaero固有の描画先と座標変換、そしてFBOに載らない遠方の切り捨てだけ。
 *
 * <p>required=falseの専用mixin configに属し、対象メソッドの形が変わった場合はこの機能だけが無効化される。
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
        MapPathOverlay.Snapshot snapshot = MapPathOverlay.snapshot();
        if (!snapshot.isEmpty()) {
            VertexConsumer overlayBuffer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_CHUNK_OVERLAY);
            Matrix4f pose = matrixStack.last().pose();
            MapPathOverlay.draw(snapshot, (blockX, blockZ, red, green, blue) -> {
                if (Math.abs(blockX - xFloored) > CULL_RADIUS_BLOCKS
                        || Math.abs(blockZ - zFloored) > CULL_RADIUS_BLOCKS) {
                    return;
                }
                RenderBufferUtil.addColoredRect(pose, overlayBuffer, blockX - xFloored, blockZ - zFloored, 1, 1,
                        red, green, blue, DOT_ALPHA);
            });
        }
        original.call(renderTypeBuffers);
    }
}
