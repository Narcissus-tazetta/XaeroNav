package net.prason.xaeronav.diorama;

//? if diorama {
import java.util.ArrayList;
import java.util.List;

import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import net.minecraft.core.BlockPos;

/**
 * 目的地と経由地をDioramaの一時ウェイポイントとして置く。一時ウェイポイントはDioramaが保存しないので、
 * ワールドを出れば消える。
 *
 * <p>呼ぶ前に{@link DioramaPresence#present()}を確認すること。Diorama未導入の環境では
 * このクラスのロード自体が失敗する。
 */
public final class DioramaWaypoints {

    private static final int DESTINATION_COLOR = 0xFF0090FF;
    private static final int STOP_COLOR = 0xFF7CE2FE;

    /** いま置いてあるウェイポイントと、それを足した先。ワールドを移るとDioramaは別の入れ物へ替えるので、消すときは同じ入れ物を引く。 */
    private static final List<Waypoint> placed = new ArrayList<>();
    private static WaypointStore placedIn;

    private DioramaWaypoints() {
    }

    /** 置く印。{@code symbol}はピンに出る文字。経由地は色を変えて最終目的地と見分ける。 */
    public record Marker(BlockPos pos, String name, String symbol, boolean stop) {
    }

    /** 目的地と経由地のウェイポイントを置き直す。置けたなら{@code true}。 */
    public static boolean setDestination(List<Marker> markers) {
        clearDestination();
        WaypointStore store = WaypointStore.get();
        String dimension = Waypoints.dimension();
        if (store == null || dimension == null) {
            return false;
        }
        for (Marker marker : markers) {
            BlockPos pos = marker.pos();
            Waypoint waypoint = new Waypoint(marker.name(), pos.getX(), pos.getY(), pos.getZ(), dimension,
                    marker.stop() ? STOP_COLOR : DESTINATION_COLOR);
            waypoint.initials = marker.symbol();
            waypoint.kind = Waypoint.Kind.TEMPORARY;
            store.add(waypoint);
            placed.add(waypoint);
        }
        placedIn = store;
        return true;
    }

    /** 置いたウェイポイントを消す。置いていなければ何もしない。 */
    public static void clearDestination() {
        if (placed.isEmpty()) {
            return;
        }
        for (Waypoint waypoint : placed) {
            placedIn.remove(waypoint);
        }
        placed.clear();
        placedIn = null;
    }
}
//?}
