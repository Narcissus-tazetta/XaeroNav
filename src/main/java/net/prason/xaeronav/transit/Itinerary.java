package net.prason.xaeronav.transit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * MTRの乗り換え案内の答えのうち、電車に乗る区間の列。歩く区間は持たない（歩きはXaeroNavが自分で引く）。
 *
 * <p>時刻はどれも「答えが作られた時点から何ミリ秒後か」。サーバーの時計の値のままだと、マルチでは
 * クライアントの時計とずれる。
 *
 * @param totalMillis 目的地に着くまで。最後の歩きの区間も含む（歩きはMTRの見積もりで、マンハッタン距離を4 m/s）
 */
public record Itinerary(List<Ride> rides, long totalMillis) {

    /**
     * @param board  乗るホームの線路の中点
     * @param alight 降りるホームの線路の中点
     */
    public record Ride(String routeName, String fromStation, String fromPlatform, String toStation, String toPlatform,
                       long departInMillis, long arriveInMillis, BlockPos board, BlockPos alight) {
    }

    public Ride first() {
        return rides.get(0);
    }

    /**
     * {@code directions}の応答を読む。電車に乗る区間が1つも無い答え（歩いた方が速い・駅が遠い）なら{@code null}。
     *
     * <p>MTRは電車が役に立たないとき、空ではなく歩きだけの区間を返す。乗る区間は路線のIDが空でないもの。
     *
     * @throws TransitFormatException 応答の形が違う、または{@code network}に無い路線・ホームを指している
     */
    public static @Nullable Itinerary parse(JsonObject response, TransitNetwork network) {
        JsonElement code = response.get("code");
        if (code == null || !code.isJsonPrimitive() || code.getAsInt() != 200) {
            throw new TransitFormatException("response code " + code);
        }
        long now = TransitNetwork.integer64(response, "currentTime");
        JsonElement data = response.get("data");
        if (data == null || !data.isJsonObject() || !data.getAsJsonObject().has("connections")) {
            throw new TransitFormatException("no 'connections' in the response");
        }
        List<Ride> rides = new ArrayList<>();
        long end = now;
        for (JsonElement element : TransitNetwork.array(data.getAsJsonObject(), "connections")) {
            JsonObject connection = element.getAsJsonObject();
            end = Math.max(end, TransitNetwork.integer64(connection, "endTime"));
            String routeId = TransitNetwork.string(connection, "routeId");
            if (routeId.isEmpty()) {
                continue;
            }
            String fromStation = TransitNetwork.string(connection, "startStationId");
            String toStation = TransitNetwork.string(connection, "endStationId");
            String fromPlatform = TransitNetwork.string(connection, "startPlatformName");
            String toPlatform = TransitNetwork.string(connection, "endPlatformName");
            TransitNetwork.Platform board = network.platform(routeId, fromStation, fromPlatform);
            TransitNetwork.Platform alight = network.platform(routeId, toStation, toPlatform);
            if (board == null || alight == null) {
                throw new TransitFormatException("route " + routeId + " has no platform '" + fromPlatform + "' at "
                        + fromStation + " or '" + toPlatform + "' at " + toStation);
            }
            rides.add(new Ride(displayName(network.routeName(routeId)), displayName(network.stationName(fromStation)),
                    fromPlatform, displayName(network.stationName(toStation)), toPlatform,
                    TransitNetwork.integer64(connection, "startTime") - now,
                    TransitNetwork.integer64(connection, "endTime") - now, board.position(), alight.position()));
        }
        return rides.isEmpty() ? null : new Itinerary(List.copyOf(rides), end - now);
    }

    /**
     * MTRの名前の書き方を1行にする。{@code |}は言語ごとの名前の区切り、{@code ||}より後ろは表示しない補足。
     */
    static String displayName(@Nullable String raw) {
        if (raw == null) {
            return "?";
        }
        int hidden = raw.indexOf("||");
        String shown = hidden < 0 ? raw : raw.substring(0, hidden);
        return shown.replace('|', ' ').trim();
    }
}
