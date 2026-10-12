package net.prason.xaeronav.mixin.xaero;

//? if <1.21.11 {
//? if >=1.21.9 {
/*import net.minecraft.client.Minecraft;
*///?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import net.prason.xaeronav.client.ChatCoordinateLinks;
import net.prason.xaeronav.mixin.MixinRemap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** チャットの座標リンクを押したときの処理（1.21.10まで。1.21.11からは{@code ChatScreenClickMixin}）。 */
@Mixin(Screen.class)
public abstract class ScreenClickMixin {
    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true, remap = MixinRemap.VANILLA)
    private void xaeronav$coordinateLink(@Nullable Style style, CallbackInfoReturnable<Boolean> cir) {
        //? if >=1.21.9 {
        /*boolean shift = Minecraft.getInstance().hasShiftDown();
        *///?} else {
        boolean shift = Screen.hasShiftDown();
        //?}
        if (ChatCoordinateLinks.click(style, shift)) {
            cir.setReturnValue(true);
        }
    }
}
//?}
