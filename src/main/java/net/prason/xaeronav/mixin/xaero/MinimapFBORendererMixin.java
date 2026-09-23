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

import net.minecraft.client.renderer.MultiBufferSource;
import net.prason.xaeronav.client.MapPathOverlay;
import net.prason.xaeronav.xaero.XaeroHookMarker;
import net.prason.xaeronav.xaero.XaeroHookProbe;
import xaero.common.graphics.CustomRenderTypes;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.render.util.RenderBufferUtil;

/**
 * ミニマップ側のフック。{@code useWorldMap} true/false どちらの分岐で地形が
 * 描かれても、この直後の{@code endBatch()}に両分岐が収束するため、フックは1箇所で足りる。
 *
 * <p>{@code endBatch()}呼び出しのordinalはバージョンで違う。1.17+は対象の{@code renderTypeBuffers}
 * への1回目のflushがordinal 0。1.16.5の{@code renderChunksToFBO}はその手前に
 * {@code this.mc.renderBuffers().bufferSource().endBatch()}（メインゲーム側の別バッファ）が
 * 先に1回あり、狙うべき{@code renderTypeBuffers}自身のflushはordinal 1になる
 * （デコンパイルで確認、`docs/port-1.16.5.md`参照）。ordinal 0のままだと例外にはならないが
 * 別バッファへ描いてしまい、経路がミニマップに出ない。
 *
 * <p>何をどの色で描くかは{@link MapPathOverlay}が決める（世界地図側と共有）。ここが持つのは
 * Xaero固有の描画先と座標変換、そしてFBOに載らない遠方の切り捨てだけ。
 *
 * <p>required=falseの専用mixin configに属し、対象メソッドの形が変わった場合はこの機能だけが無効化される。
 */
@Mixin(MinimapFBORenderer.class)
public abstract class MinimapFBORendererMixin implements XaeroHookMarker {

    private static final float DOT_ALPHA = 0.9f;

    /**
     * ミニマップのFBOは512x512で、この描画では1単位が1ブロックにあたる。したがって中心から
     * 256ブロックより先は原理的にFBOへ落ちない。余裕を取ってこの距離で切り、遠方まで伸びた経路が
     * 毎フレーム丸ごと積まれるのを防ぐ。
     */
    private static final int CULL_RADIUS_BLOCKS = 320;

    /**
     * FBO上の1単位が画面上のおおよそ何ピクセルになるか。目的地の目印だけは画面上で一定の大きさに
     * したいが、FBOはここでは1単位＝1ブロックで描かれ、画面への倍率は貼り付けるとき（この描画の
     * 外側）に掛かるので行列からは読めない。既定の大きさ・ズームでの代表値を使う——ズーム設定まで
     * 読みに行くとXaeroの設定クラスへの依存が増えるわりに、目印が数ピクセル変わるだけになる。
     */
    private static final double SCREEN_PIXELS_PER_BLOCK = 2.0;

    @WrapOperation(
            method = "renderChunksToFBO",
            //? if <1.17 {
            /*at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 1)
            *///?} else {
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 0)
            //?}
    )
    private void xaeronav$drawPath(MultiBufferSource.BufferSource renderTypeBuffers, Operation<Void> original,
                                    @Local(name = "matrixStack") PoseStack matrixStack,
                                    @Local(name = "xFloored") int xFloored,
                                    @Local(name = "zFloored") int zFloored) {
        XaeroHookProbe.record(XaeroHookProbe.Point.MINIMAP_RENDER);
        MapPathOverlay.Snapshot snapshot = MapPathOverlay.snapshot();
        if (!snapshot.isEmpty()) {
            VertexConsumer overlayBuffer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_CHUNK_OVERLAY);
            Matrix4f pose = matrixStack.last().pose();
            MapPathOverlay.draw(snapshot, (blockX1, blockZ1, blockX2, blockZ2, red, green, blue) -> {
                int x1 = blockX1 - xFloored;
                int z1 = blockZ1 - zFloored;
                int x2 = blockX2 - xFloored;
                int z2 = blockZ2 - zFloored;
                // 矩形ごと外にあるものだけを捨てる。角が1つでも入っていれば描く
                if (x2 < -CULL_RADIUS_BLOCKS || x1 > CULL_RADIUS_BLOCKS
                        || z2 < -CULL_RADIUS_BLOCKS || z1 > CULL_RADIUS_BLOCKS) {
                    return;
                }
                RenderBufferUtil.addColoredRect(pose, overlayBuffer, x1, z1, x2 - x1, z2 - z1,
                        red, green, blue, DOT_ALPHA);
            }, MapPathOverlay.pixelsPerBlock(pose) * SCREEN_PIXELS_PER_BLOCK);
        }
        original.call(renderTypeBuffers);
    }
}
