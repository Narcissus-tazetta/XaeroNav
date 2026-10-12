package net.prason.xaeronav.mixin.xaero;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.prason.xaeronav.client.ChatCoordinateLinks;
import net.prason.xaeronav.mixin.MixinRemap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * チャット欄に入るメッセージの座標をリンクにする。プレイヤーの発言・システムメッセージ・自分の発言が
 * すべて通る1か所を選んでいる（Fabricのイベントではプレイヤーの発言を書き換えられない）。
 *
 * <p>1.20.4以前の注入先は、チャット欄の幅を変えたときの並べ直しでも呼ばれる。リンク済みの部分には
 * {@link ChatCoordinateLinks#decorate}が手を付けないので、二度通っても結果は変わらない。
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    //? if >=26.1 {
    /*@ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = MixinRemap.VANILLA)
    *///?} else if >=1.20.5 {
    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = MixinRemap.VANILLA)
    //?} else if >=1.19 {
    /*@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = MixinRemap.VANILLA)
    *///?} else {
    /*@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;IIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = MixinRemap.VANILLA)
    *///?}
    private Component xaeronav$linkCoordinates(Component message) {
        return ChatCoordinateLinks.decorate(message);
    }
}
