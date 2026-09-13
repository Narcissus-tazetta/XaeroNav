package net.prason.xaeronav.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.xaero.XaeroMapCoords;
import xaero.map.gui.GuiMap;

/**
 * 世界地図画面でマウスカーソルが指す座標へ経路探索を設定するキー（{@link XaeroNavKeys#GOTO_MAP_CURSOR}）。
 * Xaero自身の地図内ショートカット（B=ウェイポイント作成 等）と同じくGuiMap#keyPressedの中でだけ
 * 効かせる必要があるため、通常プレイ中のキー処理（{@code XaeroNavKeys#handleInput}）とは別に、
 * ここへの{@code @Inject}で直接判定する。
 *
 * <p>{@code isUsingTextField()}のチェックを先頭に置くのは、地図内の座標入力欄などにフォーカスが
 * あるときはXaero側の想定通りテキスト入力を優先させるため（Xaero自身の{@code keyPressed}も
 * 同じ順序でこのチェックを最初に行っている）。
 *
 * <p>required=falseの専用mixin configに属し、対象メソッドが見つからない場合はこの機能だけが無効化される。
 */
@Mixin(GuiMap.class)
public abstract class GuiMapKeyMixin {

    @Shadow(remap = false)
    private int mouseBlockPosX;

    @Shadow(remap = false)
    private int mouseBlockPosY;

    @Shadow(remap = false)
    private int mouseBlockPosZ;

    @Shadow(remap = false)
    private ResourceKey<Level> mouseBlockDim;

    @Shadow(remap = false)
    private boolean isUsingTextField() {
        throw new UnsupportedOperationException();
    }

    // 本番がSRG名で動く1.20.1-forgeだけはrefmapでm_7933_へ引く必要がある。公式マッピングの
    // ノードでremapさせるとAPが「マッピング無し」でビルドを止める（Fabricはloomが別途引く）
    //? if forge && <1.21 {
    /*@Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    *///?} else {
    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    //?}
    private void xaeronav$onKeyPressed(int keyCode, int scanCode, int modifiers,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (this.isUsingTextField() || !XaeroNavKeys.GOTO_MAP_CURSOR.matches(keyCode, scanCode)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!XaeroMapCoords.isSameDimensionAsPlayer(mouseBlockDim, mc.level)) {
            return;
        }

        int goalY = XaeroMapCoords.resolveGoalY(mouseBlockPosY, mc.player);
        BlockPos goal = new BlockPos(mouseBlockPosX, goalY, mouseBlockPosZ);
        BlockPos resolved = PathfindingState.INSTANCE.setGoal(goal);
        if (resolved != null) {
            mc.player.displayClientMessage(Component.translatable("commands.xaeronav.goal_walk",
                    resolved.toShortString()), true);
        }
        cir.setReturnValue(true);
    }
}
