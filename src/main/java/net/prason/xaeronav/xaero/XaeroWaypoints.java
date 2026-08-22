package net.prason.xaeronav.xaero;

import net.minecraft.core.BlockPos;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * 目的地をXaeroのミニマップへ<b>一時ウェイポイント</b>として置く。
 *
 * <p>自前で地図へピンを描くのではなくXaeroのウェイポイントに乗せるのは、この描画がXaeroの内側でしか
 * できないことを含むため——ミニマップの回転を打ち消して常に立った向きで出る、画面の外にある目的地は
 * 縁に寄せて距離を添える、ワールド内にも同じ印が出る。どれも我々がFBOへ描き込む位置（回転が掛かる前）
 * からは実現できない。
 *
 * <p><b>一時</b>ウェイポイントなのが要点。ディスクへ保存されないので、消し忘れても次回の起動には
 * 残らない。それでも{@link #clearDestination()}で明示的に消すのは、セッション中はウェイポイント一覧に
 * 出続けるため。
 *
 * <p>呼ぶ前に{@link XaeroPresence#minimapPresent()}を確認すること。ミニマップ未導入の環境では
 * このクラスのロード自体が失敗する。
 */
public final class XaeroWaypoints {

    /** ウェイポイントのアイコンに出る文字。Xaero自身の一時ウェイポイントと同じ。 */
    private static final String SYMBOL = "X";

    /** いま置いてあるウェイポイントと、それが属する集合。消すときに同じ集合を引く必要がある。 */
    private static Waypoint placed;
    private static WaypointSet placedIn;

    private XaeroWaypoints() {
    }

    /** 目的地のウェイポイントを置き直す。置けたなら{@code true}。 */
    public static boolean setDestination(BlockPos goal, String name) {
        clearDestination();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null) {
            return false;
        }
        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null) {
            return false;
        }
        WaypointSet set = world.getCurrentWaypointSet();
        if (set == null) {
            return false;
        }
        Waypoint waypoint = new Waypoint(goal.getX(), goal.getY(), goal.getZ(), name, SYMBOL,
                WaypointColor.BLUE, WaypointPurpose.NORMAL);
        waypoint.setTemporary(true);
        set.add(waypoint);
        placed = waypoint;
        placedIn = set;
        return true;
    }

    /** 置いたウェイポイントを消す。置いていなければ何もしない。 */
    public static void clearDestination() {
        if (placed == null) {
            return;
        }
        placedIn.remove(placed);
        placed = null;
        placedIn = null;
    }
}
