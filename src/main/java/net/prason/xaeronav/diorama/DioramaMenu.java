package net.prason.xaeronav.diorama;

//? if diorama {
import java.util.ArrayList;
import java.util.List;

import dev.diorama.MapMenu;
import dev.diorama.waypoint.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.client.TextCompat;
import org.jspecify.annotations.Nullable;

/**
 * Dioramaの世界地図の右クリックメニューへ足す項目。並びと出し分けはXaero側
 * （{@code GuiMapRightClickMixin}・{@code WaypointReaderMixin}）に合わせてある。
 *
 * <p>mixin適用後はDioramaのクラスから呼ばれるので、mixinパッケージの外に置く。
 */
public final class DioramaMenu {

    private DioramaMenu() {
    }

    /**
     * 地面を右クリックしたときのメニュー。
     *
     * @param spot  Dioramaが割り出した「そこに立つ位置」。地形が分からない場所では{@code null}
     * @param rough 地形が分からないときのおおよそのXZ。それも無ければ{@code null}
     */
    public static List<MapMenu.Item> withSpotItems(List<MapMenu.Item> original, @Nullable BlockPos spot,
                                                   @Nullable BlockPos rough) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return original;
        }
        BlockPos target;
        if (spot != null) {
            target = spot;
        } else if (rough != null) {
            // 高さが分からない座標はプレイヤーと同じ高さを狙う（探索範囲は上下にも広がるため近くまでは届く）
            target = new BlockPos(rough.getX(), mc.player.blockPosition().getY(), rough.getZ());
        } else {
            return original;
        }
        List<MapMenu.Item> items = new ArrayList<>(original);
        // 先頭は座標の行。操作はその下に並べる。地形が分からないときは情報の行しか無いので末尾へ
        int index = spot != null ? Math.min(1, items.size()) : items.size();
        items.add(index++, action("gui.xaeronav_goto_here", true, () -> PathfindingState.INSTANCE.setGoal(target)));
        index = addStopItems(items, index, target);
        items.add(index, clearItem());
        return items;
    }

    /** ウェイポイントを右クリックしたときのメニュー。 */
    public static List<MapMenu.Item> withWaypointItems(List<MapMenu.Item> original, Waypoint waypoint) {
        BlockPos target = new BlockPos(waypoint.x, waypoint.y, waypoint.z);
        List<MapMenu.Item> items = new ArrayList<>(original);
        // 先頭の2行は名前と座標
        int index = Math.min(2, items.size());
        items.add(index++, action("gui.xaeronav_goto_waypoint", true,
                () -> PathfindingState.INSTANCE.setGoal(target)));
        if (PathfindingState.INSTANCE.isStopMarker(target)) {
            items.add(index++, action("gui.xaeronav_remove_stop", true,
                    () -> PathfindingState.INSTANCE.removeStop(target)));
        }
        index = addStopItems(items, index, target);
        items.add(index, clearItem());
        return items;
    }

    /** 経由地の項目は案内中だけ出す。案内を始める前から並んでいると、ただ「ここへ案内」を押したいときに邪魔になる。 */
    private static int addStopItems(List<MapMenu.Item> items, int index, BlockPos target) {
        if (PathfindingState.INSTANCE.goal() == null) {
            return index;
        }
        items.add(index++, action("gui.xaeronav_add_stop", true, () -> PathfindingState.INSTANCE.addStop(target)));
        items.add(index++, action("gui.xaeronav_append_stop", true,
                () -> PathfindingState.INSTANCE.appendStop(target)));
        return index;
    }

    private static MapMenu.Item clearItem() {
        return action("gui.xaeronav_clear_route", PathfindingState.INSTANCE.goal() != null,
                PathfindingState.INSTANCE::clear);
    }

    private static MapMenu.Item action(String key, boolean active, Runnable run) {
        return MapMenu.Item.action(TextCompat.translatable(key), active, run);
    }
}
//?}
