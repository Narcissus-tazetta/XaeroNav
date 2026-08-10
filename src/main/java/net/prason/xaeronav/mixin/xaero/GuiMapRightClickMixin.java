package net.prason.xaeronav.mixin.xaero;

import java.util.ArrayList;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.client.PathfindingState;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * 世界地図の何もない場所を右クリックしたときのメニューに「ここへ経路探索」を足す。
 * {@code GuiMap}自身が{@code IRightClickableElement}で、地図の背景を右クリックしたときだけ
 * この{@code getRightClickOptions}が呼ばれる（ウェイポイント上での右クリックは
 * {@link WaypointReaderMixin}側が受け持つ）。
 *
 * <p>required=falseの専用mixin configに属し、対象メソッドが見つからない場合はこの機能だけが無効化される。
 */
@Mixin(GuiMap.class)
public abstract class GuiMapRightClickMixin {

    /**
     * 地図に高さの情報が無い座標であることを表す値。Xaero自身も、この値のときは
     * 座標表示からYを省いている。
     */
    private static final int UNKNOWN_HEIGHT = 32767;

    @Shadow
    private int rightClickX;

    @Shadow
    private int rightClickY;

    @Shadow
    private int rightClickZ;

    @Shadow
    private ResourceKey<Level> rightClickDim;

    @ModifyReturnValue(method = "getRightClickOptions", at = @At("RETURN"))
    private ArrayList<RightClickOption> xaeronav$addGoHereOption(ArrayList<RightClickOption> original) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return original;
        }
        // 別次元の地図を見ているときは、座標が縮尺変換された値になるうえ、そもそも歩いて行けない
        if (rightClickDim != null && rightClickDim != mc.level.dimension()) {
            return original;
        }

        int goalX = rightClickX;
        int goalZ = rightClickZ;
        // 高さが分からない座標はプレイヤーと同じ高さを狙う。探索範囲は目的地の上下にも広がるので、
        // 地表の高さが違っていても近いところまでは経路が出る
        int goalY = rightClickY == UNKNOWN_HEIGHT ? mc.player.blockPosition().getY() : rightClickY;
        original.add(new RightClickOption("gui.xaeronav_goto_here", original.size(), (GuiMap) (Object) this) {
            @Override
            public void onAction(Screen screen) {
                PathfindingState.INSTANCE.setGoal(new BlockPos(goalX, goalY, goalZ));
            }
        });
        return original;
    }
}
