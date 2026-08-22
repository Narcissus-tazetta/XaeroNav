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
import net.prason.xaeronav.xaero.XaeroHookMarker;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.gui.GuiMap;

/**
 * design doc §2-1/§2-5。世界地図の地形描画（{@code endBatch()}呼び出し1回目、ordinal 0）の直後に
 * 経路を1ブロック四方の色付き矩形の連なりとして描き足す。{@code flooredCameraX}/{@code flooredCameraZ}への
 * 引き算だけで地図座標に変換できるのは、地形描画自体が全く同じ変換を使っているため（design doc §2-2）。
 *
 * <p>何をどの色で描くかは{@link MapPathOverlay}が決める（ミニマップ側と共有）。ここが持つのは
 * Xaero固有の描画先と座標変換だけに留める。
 *
 * <p>required=falseの専用mixin configに属する。Xaero's Map未導入・大規模リファクタで対象メソッドの
 * 形が変わった場合はこの1機能だけが無効化され、MOD本体はワールド内描画のみで動作を続ける。
 */
@Mixin(GuiMap.class)
public abstract class GuiMapMixin implements XaeroHookMarker {

    private static final float DOT_ALPHA = 0.9f;

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 0)
    )
    private void xaeronav$drawPath(MultiBufferSource.BufferSource renderTypeBuffers, Operation<Void> original,
                                    @Local(name = "matrixStack") PoseStack matrixStack,
                                    @Local(name = "flooredCameraX") int flooredCameraX,
                                    @Local(name = "flooredCameraZ") int flooredCameraZ) {
        MapPathOverlay.Snapshot snapshot = MapPathOverlay.snapshot();
        if (!snapshot.isEmpty()) {
            VertexConsumer overlayBuffer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY);
            Matrix4f pose = matrixStack.last().pose();
            MapPathOverlay.draw(snapshot, (blockX, blockZ, red, green, blue) -> {
                int x1 = blockX - flooredCameraX;
                int z1 = blockZ - flooredCameraZ;
                MapRenderHelper.fillIntoExistingBuffer(pose, overlayBuffer, x1, z1, x1 + 1, z1 + 1,
                        red, green, blue, DOT_ALPHA);
            });
        }
        original.call(renderTypeBuffers);
    }
}
