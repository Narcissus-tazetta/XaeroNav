package net.prason.xaeronav.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

//? if >=1.19 {
import org.joml.Matrix4f;
//?} else {
/*import com.mojang.math.Matrix4f;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if >=1.21.11 {
/*import xaero.lib.client.graphics.XaeroBufferProvider;
*///?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.prason.xaeronav.client.MapPathOverlay;
import net.prason.xaeronav.xaero.XaeroHookMarker;
import net.prason.xaeronav.xaero.XaeroHookProbe;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.gui.GuiMap;

/**
 * 世界地図の地形描画の直後に経路を1ブロック四方の色付き矩形の連なりとして描き足す。
 * {@code flooredCameraX}/{@code flooredCameraZ}への引き算だけで地図座標に変換できるのは、
 * 地形描画自体が全く同じ変換を使っているため。
 *
 * <p>{@code endBatch()}呼び出しのordinalはバージョンで違う。1.17+は地形描画のflushが1回目
 * （ordinal 0）だが、1.16.5の{@code GuiMap#render}は最初に前フレームの取り残しをflushする
 * 呼び出しが先頭付近にもう1回あり、地形＋オーバーレイのflushは2回目（ordinal 1）になる
 * （Xaero 1.46.0のデコンパイルで確認）。
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
            //? if >=1.21.11 {
            /*at = @At(value = "INVOKE", target = "Lxaero/lib/client/graphics/XaeroBufferProvider;endBatch()V", ordinal = 0)
            *///?} else if <1.17 {
            /*at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 1)
            *///?} else {
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 0)
            //?}
    )
    private void xaeronav$drawPath(
            //? if >=1.21.11 {
            /*XaeroBufferProvider renderTypeBuffers,
            *///?} else {
            MultiBufferSource.BufferSource renderTypeBuffers,
            //?}
            Operation<Void> original,
            @Local(name = "matrixStack") PoseStack matrixStack,
            @Local(name = "flooredCameraX") int flooredCameraX,
            @Local(name = "flooredCameraZ") int flooredCameraZ) {
        XaeroHookProbe.record(XaeroHookProbe.Point.WORLD_MAP_RENDER);
        MapPathOverlay.Snapshot snapshot = MapPathOverlay.snapshot();
        if (!snapshot.isEmpty()) {
            VertexConsumer overlayBuffer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY);
            Matrix4f pose = matrixStack.last().pose();
            MapPathOverlay.draw(snapshot, (blockX1, blockZ1, blockX2, blockZ2, red, green, blue) ->
                    MapRenderHelper.fillIntoExistingBuffer(pose, overlayBuffer,
                            blockX1 - flooredCameraX, blockZ1 - flooredCameraZ,
                            blockX2 - flooredCameraX, blockZ2 - flooredCameraZ,
                            red, green, blue, DOT_ALPHA),
                    MapPathOverlay.pixelsPerBlock(pose));
        }
        original.call(renderTypeBuffers);
    }
}
