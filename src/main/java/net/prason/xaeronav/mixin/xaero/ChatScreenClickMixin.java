package net.prason.xaeronav.mixin.xaero;

//? if >=1.21.11 {
/*import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Style;
import net.prason.xaeronav.client.ChatCoordinateLinks;
import net.prason.xaeronav.mixin.MixinRemap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/^*
 * チャットの座標リンクを押したときの処理（1.21.11から）。この版からクリックの振り分けがチャット画面側へ移り、
 * Shiftが押されているかは引数で渡される。
 ^/
@Mixin(ChatScreen.class)
public abstract class ChatScreenClickMixin {
    @Inject(method = "handleComponentClicked(Lnet/minecraft/network/chat/Style;Z)Z", at = @At("HEAD"), cancellable = true, remap = MixinRemap.VANILLA)
    private void xaeronav$coordinateLink(Style style, boolean shift, CallbackInfoReturnable<Boolean> cir) {
        if (ChatCoordinateLinks.click(style, shift)) {
            cir.setReturnValue(true);
        }
    }
}
*///?}
