package net.prason.xaeronav.transit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.platform.ModPresence;
import org.jspecify.annotations.Nullable;

/**
 * CRN（Create Railways Navigator 0.10系）とCreateがクライアント側に持つものへの口。どちらにも依存せず、名前で引く。
 *
 * <p>経路検索はサーバーにあり、CRNのパケット（{@code ModNetworkManager.NAVIGATE}・{@code GET_NEAREST_STATION}）で
 * 問い合わせる。パケットはCRNが使うDragonLibの{@code SendAndReceive#send(送り先, 要求, CompletableFuture)}で送り、
 * 答えはDragonLibの受信スレッドで返る。駅の座標はCRNの答えに無いので、Createがログイン時に全部送ってくる線路の網
 * （{@code CreateClient.RAILWAYS}）から駅の名前で引く。
 *
 * <p>0.8系以前は通信の作りが丸ごと違い（{@code DataAccessor}）、ここで引く名前が無いので使えないものとして扱う。
 */
public final class CrnLink {

    public static final String MOD_ID = "createrailwaysnavigator";

    /** CRNの答えを待つ時間。DragonLibの既定（60秒）は案内の提案には長すぎる。 */
    private static final long TIMEOUT_SECONDS = 10L;
    private static final String CARRIAGE_ENTITY = "com.simibubi.create.content.trains.entity.CarriageContraptionEntity";

    private CrnLink() {
    }

    public static boolean present() {
        return Holder.API != null;
    }

    /** Createの列車（の座席）に乗っているか。 */
    public static boolean riding(Entity player) {
        Entity vehicle = player.getVehicle();
        return vehicle != null && vehicle.getClass().getName().equals(CARRIAGE_ENTITY);
    }

    /** CRNの時計の今（ゲームtick）。答えの時刻はこれと同じ基準。 */
    public static long now() {
        return (Long) api().invoke(api().now, null);
    }

    /** {@code pos}に一番近い駅（同じ次元）の、CRNで問い合わせに使う名前。駅が無ければ空。 */
    public static CompletableFuture<Optional<String>> nearestStation(BlockPos pos) {
        Api api = api();
        return api.send(api.nearestPacket, api.newInstance(api.nearestRequest, pos)).thenApply(response -> {
            Object result = api.invoke(api.nearestResult, response);
            Optional<?> tag = (Optional<?>) api.get(api.nearestTag, result);
            return tag.map(name -> (String) api.invoke(api.tagNameGet, name));
        });
    }

    /** {@code fromStation}から{@code toStation}への便。プレイヤーごとのCRNの設定（乗り換え時間など）がサーバーで効く。 */
    public static CompletableFuture<List<CrnRoutes.Journey>> navigate(String fromStation, String toStation, UUID player) {
        Api api = api();
        return api.send(api.navigatePacket, api.newInstance(api.navigateRequest, fromStation, toStation, player))
                .thenApply(response -> {
                    List<?> journeys = (List<?>) api.invoke(api.navigateData, response);
                    if (journeys == null) {
                        throw new TransitFormatException("CRN navigate answered without data");
                    }
                    List<CrnRoutes.Journey> out = new ArrayList<>();
                    for (Object journey : journeys) {
                        out.add(api.journey(journey));
                    }
                    return out;
                });
    }

    /** 今の次元の、名前が{@code name}の駅のうち{@code near}に一番近いもののブロックの位置。クライアントスレッドで呼ぶこと。 */
    public static @Nullable BlockPos station(ResourceKey<Level> dimension, String name, BlockPos near) {
        Api api = api();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Map<?, ?> networks = (Map<?, ?>) api.get(api.trackNetworks, api.get(api.railways, null));
        for (Object graph : networks.values()) {
            for (Object station : (Collection<?>) api.invoke(api.getPoints, graph, api.get(api.stationType, null))) {
                BlockPos pos = (BlockPos) api.get(api.blockEntityPos, station);
                if (pos == null || !name.equals(api.get(api.stationName, station))) {
                    continue;
                }
                Object location = api.invoke(api.coupleFirst, api.get(api.edgeLocation, station));
                if (!dimension.equals(api.get(api.nodeDimension, location))) {
                    continue;
                }
                double distance = pos.distSqr(near);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = pos;
                }
            }
        }
        return best;
    }

    private static Api api() {
        Api api = Holder.API;
        if (api == null) {
            throw new IllegalStateException("CRN is not available; check present() first");
        }
        return api;
    }

    /** CRNのクラスへ触るのは、CRNが読み込まれていると分かってから（{@link ModPresence}の注意を参照）。 */
    private static final class Holder {
        static final @Nullable Api API = ModPresence.isLoaded(MOD_ID) ? Api.lookUp() : null;
    }

    /** 引いたクラス・メソッド・フィールド。どれか1つでも引けなければ、CRNは使えないものとして扱う。 */
    private static final class Api {
        Object navigatePacket;
        Object nearestPacket;
        Object toServer;
        Method send;
        java.lang.reflect.Constructor<?> navigateRequest;
        java.lang.reflect.Constructor<?> nearestRequest;
        Method navigateData;
        Method nearestResult;
        Field nearestTag;
        Method tagNameGet;
        Method now;
        Method journeyLegs;
        Method legTrainName;
        Method legLine;
        Method lineName;
        Method legBoarding;
        Method legAlighting;
        Method callStation;
        Method callScheduled;
        Method callRealtime;
        Method stationNameOf;
        Method stationDisplayName;
        Method stationPlatform;
        Method timesArrival;
        Method timesDeparture;
        Method timesKnown;
        Field railways;
        Field trackNetworks;
        Method getPoints;
        Field stationType;
        Field stationName;
        Field blockEntityPos;
        Field edgeLocation;
        Method coupleFirst;
        Field nodeDimension;

        static @Nullable Api lookUp() {
            try {
                Api api = new Api();
                Class<?> network = Class.forName("de.mrjulsen.crn.registry.ModNetworkManager");
                api.navigatePacket = network.getField("NAVIGATE").get(null);
                api.nearestPacket = network.getField("GET_NEAREST_STATION").get(null);
                Class<?> direction = Class.forName("de.mrjulsen.mcdragonlib.network.NetworkDirection");
                api.toServer = direction.getMethod("toServer").invoke(null);
                for (Method m : api.navigatePacket.getClass().getMethods()) {
                    if (m.getName().equals("send") && m.getParameterCount() == 3
                            && m.getParameterTypes()[2] == CompletableFuture.class) {
                        api.send = m;
                    }
                }
                if (api.send == null) {
                    throw new NoSuchMethodException("send(NetworkDirection, data, CompletableFuture)");
                }
                Class<?> navigate = Class.forName("de.mrjulsen.crn.network.packets.NavigatePacketData$Request");
                api.navigateRequest = navigate.getConstructor(String.class, String.class, UUID.class);
                api.navigateData = Class.forName("de.mrjulsen.crn.network.packets.NavigatePacketData$Response")
                        .getMethod("getData");
                api.nearestRequest = Class.forName("de.mrjulsen.crn.network.packets.GetNearestStationPacketData$Request")
                        .getConstructor(BlockPos.class);
                api.nearestResult = Class.forName("de.mrjulsen.crn.network.packets.GetNearestStationPacketData$Response")
                        .getMethod("getResult");
                api.nearestTag = Class.forName("de.mrjulsen.crn.data.NearestTrackStationResult").getField("tagName");
                api.tagNameGet = Class.forName("de.mrjulsen.crn.data.settings.TagName").getMethod("get");
                api.now = Class.forName("de.mrjulsen.crn.util.ModUtils").getMethod("getTransformedWorldTime");

                api.journeyLegs = Class.forName("de.mrjulsen.crn.core.navigator.route.RouteJourney").getMethod("legs");
                Class<?> leg = Class.forName("de.mrjulsen.crn.core.navigator.route.RouteLeg");
                api.legTrainName = leg.getMethod("trainName");
                api.legLine = leg.getMethod("line");
                api.legBoarding = leg.getMethod("boarding");
                api.legAlighting = leg.getMethod("alighting");
                api.lineName = Class.forName("de.mrjulsen.crn.api.core.ref.LineRef").getMethod("name");
                Class<?> call = Class.forName("de.mrjulsen.crn.core.navigator.route.RouteCall");
                api.callStation = call.getMethod("station");
                api.callScheduled = call.getMethod("scheduled");
                api.callRealtime = call.getMethod("realtime");
                Class<?> stationRef = Class.forName("de.mrjulsen.crn.api.core.ref.StationRef");
                api.stationNameOf = stationRef.getMethod("name");
                api.stationDisplayName = stationRef.getMethod("displayName");
                api.stationPlatform = stationRef.getMethod("platform");
                Class<?> times = Class.forName("de.mrjulsen.crn.core.timing.StopTimes");
                api.timesArrival = times.getMethod("arrival");
                api.timesDeparture = times.getMethod("departure");
                api.timesKnown = times.getMethod("isKnown");

                Class<?> client = Class.forName("com.simibubi.create.CreateClient");
                api.railways = client.getField("RAILWAYS");
                api.trackNetworks = api.railways.getType().getField("trackNetworks");
                Class<?> pointType = Class.forName("com.simibubi.create.content.trains.graph.EdgePointType");
                api.stationType = pointType.getField("STATION");
                api.getPoints = Class.forName("com.simibubi.create.content.trains.graph.TrackGraph")
                        .getMethod("getPoints", pointType);
                Class<?> station = Class.forName("com.simibubi.create.content.trains.station.GlobalStation");
                api.stationName = station.getField("name");
                api.blockEntityPos = station.getField("blockEntityPos");
                api.edgeLocation = station.getField("edgeLocation");
                api.coupleFirst = api.edgeLocation.getType().getMethod("getFirst");
                api.nodeDimension = Class.forName("com.simibubi.create.content.trains.graph.TrackNodeLocation")
                        .getField("dimension");
                return api;
            } catch (ReflectiveOperationException | LinkageError e) {
                XaeroNav.LOGGER.warn("XaeroNav: this CRN/Create version does not have what train directions need; "
                        + "they are off ({})", e.toString());
                return null;
            }
        }

        CompletableFuture<Object> send(Object packet, Object request) {
            CompletableFuture<Object> future = new CompletableFuture<>();
            invoke(send, packet, toServer, request, future);
            return future.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).thenApply(response -> {
                Object status = invoke(statusOf(response), response);
                if (!(Boolean) invoke(method(status, "isOK"), status)) {
                    throw new TransitFormatException("CRN answered with status " + status);
                }
                return response;
            });
        }

        CrnRoutes.Journey journey(Object journey) {
            List<CrnRoutes.Leg> legs = new ArrayList<>();
            for (Object leg : (List<?>) invoke(journeyLegs, journey)) {
                legs.add(new CrnRoutes.Leg((String) invoke(legTrainName, leg),
                        (String) invoke(lineName, invoke(legLine, leg)),
                        stop(invoke(legBoarding, leg)), stop(invoke(legAlighting, leg))));
            }
            return new CrnRoutes.Journey(legs);
        }

        private CrnRoutes.Stop stop(Object call) {
            Object station = invoke(callStation, call);
            Object realtime = invoke(callRealtime, call);
            // 実際の時刻（遅れを含む）が分かっていればそれ、無ければ時刻表の値
            Object times = (Boolean) invoke(timesKnown, realtime) ? realtime : invoke(callScheduled, call);
            return new CrnRoutes.Stop((String) invoke(stationNameOf, station), (String) invoke(stationDisplayName, station),
                    (String) invoke(stationPlatform, station), (Long) invoke(timesArrival, times),
                    (Long) invoke(timesDeparture, times));
        }

        private static Method statusOf(Object response) {
            return method(response, "getStatus");
        }

        private static Method method(Object target, String name) {
            try {
                return target.getClass().getMethod(name);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(target.getClass().getName() + "#" + name, e);
            }
        }

        Object newInstance(java.lang.reflect.Constructor<?> constructor, Object... args) {
            try {
                return constructor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("CRN " + constructor.getDeclaringClass().getName(), e);
            }
        }

        Object invoke(Method method, @Nullable Object target, Object... args) {
            try {
                return method.invoke(target, args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("CRN/Create " + method, e);
            }
        }

        Object get(Field field, @Nullable Object target) {
            try {
                return field.get(target);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("CRN/Create " + field, e);
            }
        }
    }
}
