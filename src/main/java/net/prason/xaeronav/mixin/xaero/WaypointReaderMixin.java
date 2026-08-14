package net.prason.xaeronav.mixin.xaero;

import java.util.ArrayList;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.client.PathfindingState;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;
import xaero.map.mods.gui.WaypointReader;

/**
 * design doc §2-1/§2-5。ウェイポイントの右クリックメニュー（Edit/Teleport/Share/Disable/Delete）の末尾に
 * 「ここへ経路探索」を追加する。{@code getRightClickOptions}はジェネリクス消去によるブリッジメソッドと
 * 同名で存在するため、記述子を明示して{@code Waypoint}版だけを対象にする。
 *
 * <p>required=falseの専用mixin configに属し、対象メソッドが見つからない場合はこの機能だけが無効化される。
 */
@Mixin(WaypointReader.class)
public abstract class WaypointReaderMixin {

    @ModifyReturnValue(
            method = "getRightClickOptions(Lxaero/map/mods/gui/Waypoint;Lxaero/map/gui/IRightClickableElement;)Ljava/util/ArrayList;",
            at = @At("RETURN")
    )
    private ArrayList<RightClickOption> xaeronav$addGoHereOption(ArrayList<RightClickOption> original,
                                                                   Waypoint element, IRightClickableElement target) {
        if (original == null) {
            return null;
        }
        original.add(new RightClickOption("gui.xaeronav_goto_waypoint", original.size(), target) {
            @Override
            public boolean isActive() {
                return element.isyIncluded();
            }

            @Override
            public void onAction(Screen screen) {
                PathfindingState.INSTANCE.setGoal(new BlockPos(element.getX(), element.getY(), element.getZ()));
            }
        });
        // 「ここへ経路探索」のすぐ下に置く。目的地が無い間は押しても意味が無いので灰色表示にする
        original.add(new RightClickOption("gui.xaeronav_clear_route", original.size(), target) {
            @Override
            public boolean isActive() {
                return PathfindingState.INSTANCE.goal() != null;
            }

            @Override
            public void onAction(Screen screen) {
                PathfindingState.INSTANCE.clear();
            }
        });
        return original;
    }
}
