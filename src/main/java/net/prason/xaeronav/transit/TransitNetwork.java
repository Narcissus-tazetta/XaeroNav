package net.prason.xaeronav.transit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * MTRの{@code stations-and-routes}の応答から、乗り換え案内の答えを読むのに要る分だけを取り出したもの。
 * 1つの次元の駅・路線と、サーバーが持つ次元の並び。
 *
 * <p>乗り換え案内の答えは駅と路線をIDで、ホームを名前で返す。座標はここの路線ごとのホームの列にしか無い。
 */
public final class TransitNetwork {

    /** 路線が止まるホーム。{@code position}はホームの線路の中点で、床の上ではない。 */
    public record Platform(String stationId, String name, BlockPos position) {
    }

    private record Route(String name, List<Platform> platforms) {
    }

    private final List<String> dimensions;
    private final Map<String, String> stationNames;
    private final Map<String, Route> routes;

    private TransitNetwork(List<String> dimensions, Map<String, String> stationNames, Map<String, Route> routes) {
        this.dimensions = dimensions;
        this.stationNames = stationNames;
        this.routes = routes;
    }

    /** @param data 応答の{@code data}。形が違えば{@link TransitFormatException} */
    public static TransitNetwork parse(JsonObject data) {
        List<String> dimensions = new ArrayList<>();
        for (JsonElement dimension : array(data, "dimensions")) {
            dimensions.add(stringValue(dimension, "dimensions entry"));
        }
        Map<String, String> stationNames = new HashMap<>();
        for (JsonElement element : array(data, "stations")) {
            JsonObject station = object(element, "station");
            stationNames.put(string(station, "id"), string(station, "name"));
        }
        Map<String, Route> routes = new HashMap<>();
        for (JsonElement element : array(data, "routes")) {
            JsonObject route = object(element, "route");
            List<Platform> platforms = new ArrayList<>();
            for (JsonElement stop : array(route, "stations")) {
                JsonObject platform = object(stop, "platform");
                platforms.add(new Platform(string(platform, "id"), string(platform, "name"),
                        new BlockPos(integer(platform, "x"), integer(platform, "y"), integer(platform, "z"))));
            }
            routes.put(string(route, "id"), new Route(string(route, "name"), platforms));
        }
        return new TransitNetwork(dimensions, stationNames, routes);
    }

    /** {@code worldId}（{@code minecraft/overworld}の形）がサーバーの何番目の次元か。無ければ-1。 */
    public int dimensionIndex(String worldId) {
        return dimensions.indexOf(worldId);
    }

    public @Nullable String stationName(String stationId) {
        return stationNames.get(stationId);
    }

    public @Nullable String routeName(String routeId) {
        Route route = routes.get(routeId);
        return route == null ? null : route.name();
    }

    /** 路線{@code routeId}が駅{@code stationId}で止まる、名前が{@code platformName}のホーム。 */
    public @Nullable Platform platform(String routeId, String stationId, String platformName) {
        Route route = routes.get(routeId);
        if (route == null) {
            return null;
        }
        for (Platform platform : route.platforms()) {
            if (platform.stationId().equals(stationId) && platform.name().equals(platformName)) {
                return platform;
            }
        }
        return null;
    }

    static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        // 駅も路線も無い次元では、配列ごと省かれる
        if (value == null) {
            return new JsonArray();
        }
        if (!value.isJsonArray()) {
            throw new TransitFormatException("'" + key + "' is not an array");
        }
        return value.getAsJsonArray();
    }

    static String string(JsonObject object, String key) {
        return stringValue(object.get(key), key);
    }

    static JsonObject object(@Nullable JsonElement value, String key) {
        if (value == null || !value.isJsonObject()) {
            throw new TransitFormatException("expected an object for '" + key + "'");
        }
        return value.getAsJsonObject();
    }

    private static String stringValue(@Nullable JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new TransitFormatException("expected a string for '" + key + "'");
        }
        return value.getAsString();
    }

    static long integer64(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new TransitFormatException("missing number '" + key + "'");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (NumberFormatException | ArithmeticException invalidNumber) {
            throw new TransitFormatException("expected an integer for '" + key + "'");
        }
    }

    private static int integer(JsonObject object, String key) {
        long number = integer64(object, key);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new TransitFormatException("integer out of range for '" + key + "'");
        }
        return (int) number;
    }
}
