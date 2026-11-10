package net.prason.xaeronav.mixin.xaero.diorama;

//? if diorama {
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.WaypointOverlay;
import net.minecraft.client.gui.GuiGraphics;
import net.prason.xaeronav.diorama.DioramaRouteCanvas;
import net.prason.xaeronav.xaero.XaeroHookMarker;

/**
 * Dioramaの世界地図へ経路を描く。ウェイポイントのピンを描く処理の先頭に差し込み、線がピンと
 * プレイヤーの印の下、地形と描き込みの上に来るようにする。
 *
 * <p>地図画面の描画メソッド（{@code Screen#render}のoverride）へ直接入れないのは、そこだけMinecraftの名前で
 * ローダーごとに変わるため。ピンの描画はDiorama自身のメソッドなので、どのローダーでも同じ名前で指せる。
 * 世界地図では毎フレーム必ず呼ばれる（ミニマップではウェイポイント表示を切ると呼ばれないので、
 * ミニマップ側は{@link DioramaMinimapHudMixin}が受け持つ）。
 */
@Mixin(WaypointOverlay.class)
public abstract class DioramaWaypointOverlayMixin implements XaeroHookMarker {

    @Inject(method = "draw", at = @At("HEAD"), remap = false)
    private static void xaeronav$drawRoute(GuiGraphics graphics, MapViewport.View view, List<WaypointOverlay.Pin> pins,
                                           boolean fullscreen, WaypointOverlay.Pin hovered, CallbackInfo ci) {
        if (fullscreen) {
            DioramaRouteCanvas.drawOnWorldMap(graphics, view);
        }
    }
}
//?}
