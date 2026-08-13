package net.prason.xaeronav.mixin.xaero;

import java.util.ArrayList;
import java.util.Set;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
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
     * Xaeroが末尾に独自描画する距離表示（例: "245.0m"、{@code getRightClickOptions}が返す
     * リストの要素ではない）の下に埋もれないよう、先頭の情報行（タイトル・チャンク座標・ブロック座標）
     * より後ろ、最初の操作項目より前に挿入したい。ただし先頭の情報行の数は
     * 「Display Map Distances」設定やタイル選択の有無で0〜2件と変動するため固定インデックスでは
     * 決め打ちできない（実機フィードバックで発覚、2026-08-13）。そこで、実際に見つかった最初の
     * 操作項目（Xaero自身の{@code GuiMap#getRightClickOptions}実装が追加する翻訳キー）の直前に
     * 挿入する。どれも見つからない場合は末尾へ（元の挙動と同じ、安全側）。
     */
    private static final Set<String> FIRST_ACTION_KEYS = Set.of(
            "gui.xaero_right_click_map_create_waypoint",
            "gui.xaero_right_click_map_create_temporary_waypoint",
            "gui.xaero_right_click_map_teleport",
            "gui.xaero_wm_right_click_map_teleport_not_allowed",
            "gui.xaero_right_click_map_cant_teleport",
            "gui.xaero_right_click_map_cant_teleport_world",
            "gui.xaero_right_click_map_share_location",
            "gui.xaero_right_click_map_waypoints_menu",
            "gui.xaero_right_click_box_map_export",
            "gui.xaero_right_click_box_map_settings");

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
        if (!XaeroMapCoords.isSameDimensionAsPlayer(rightClickDim, mc.level)) {
            return original;
        }

        int goalX = rightClickX;
        int goalZ = rightClickZ;
        int goalY = XaeroMapCoords.resolveGoalY(rightClickY, mc.player);

        int insertIndex = firstActionIndex(original);
        original.add(insertIndex, new RightClickOption("gui.xaeronav_goto_here", insertIndex, (GuiMap) (Object) this) {
            @Override
            public void onAction(Screen screen) {
                PathfindingState.INSTANCE.setGoal(new BlockPos(goalX, goalY, goalZ));
            }
        });
        return original;
    }

    private static int firstActionIndex(ArrayList<RightClickOption> options) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).getDisplayName().getContents() instanceof TranslatableContents translatable
                    && FIRST_ACTION_KEYS.contains(translatable.getKey())) {
                return i;
            }
        }
        return options.size();
    }
}
