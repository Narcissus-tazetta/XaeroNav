package net.prason.xaeronav.mixin.xaero.diorama;

//? if diorama {
import com.llamalad7.mixinextras.sugar.Local;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.diorama.MinimapHud;
import dev.diorama.render.MapViewport;
import net.minecraft.client.gui.GuiGraphics;
import net.prason.xaeronav.diorama.DioramaRouteCanvas;
import net.prason.xaeronav.xaero.XaeroHookMarker;

/**
 * Dioramaのミニマップへ経路を描く。プレイヤーの印を描く直前に差し込む（ピンを描く呼び出しは
 * ウェイポイント表示を切ると通らないので、目印にできない）。
 */
@Mixin(MinimapHud.class)
public abstract class DioramaMinimapHudMixin implements XaeroHookMarker {

    @Inject(method = "draw",
            at = @At(value = "INVOKE", target = "Ldev/diorama/render/Markers;player", remap = false),
            remap = false)
    private void xaeronav$drawRoute(GuiGraphics graphics, float partialTick, boolean right, boolean top, int effects,
                                    int wanted, CallbackInfoReturnable<int[]> cir, @Local MapViewport.View view) {
        DioramaRouteCanvas.draw(graphics, view);
    }
}
//?}
