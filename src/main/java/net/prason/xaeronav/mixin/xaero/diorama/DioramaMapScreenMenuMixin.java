package net.prason.xaeronav.mixin.xaero.diorama;

//? if diorama {
import java.util.List;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import dev.diorama.MapMenu;
import dev.diorama.MapScreen;
import dev.diorama.render.MapLevel;
import dev.diorama.waypoint.Waypoint;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.diorama.DioramaMenu;
import net.prason.xaeronav.xaero.XaeroHookMarker;

/**
 * Dioramaの世界地図の右クリックメニューに「ここへ案内」と経由地の項目を足す。
 *
 * <p>別の次元の地図を開いているとき（{@code otherDimension}が入っている）は足さない。座標がいまいる次元の
 * ものではなく、歩いて行けない。
 *
 * <p>注入先はどれもDiorama自身のメソッドでMinecraftの対応表に無いので、remapさせない。
 * required=falseのmixin configに属し、Dioramaが形を変えた版ではこの機能だけが無効になる。
 */
@Mixin(MapScreen.class)
public abstract class DioramaMapScreenMenuMixin implements XaeroHookMarker {

    @Shadow(remap = false)
    private String otherDimension;

    @ModifyReturnValue(method = "spotItems", at = @At("RETURN"), remap = false)
    private List<MapMenu.Item> xaeronav$addSpotItems(List<MapMenu.Item> original, MapLevel.Spot found, BlockPos rough) {
        if (otherDimension != null) {
            return original;
        }
        return DioramaMenu.withSpotItems(original, found.pos(), rough);
    }

    @ModifyReturnValue(method = "waypointItems", at = @At("RETURN"), remap = false)
    private List<MapMenu.Item> xaeronav$addWaypointItems(List<MapMenu.Item> original, Waypoint waypoint) {
        if (otherDimension != null) {
            return original;
        }
        return DioramaMenu.withWaypointItems(original, waypoint);
    }
}
//?}
