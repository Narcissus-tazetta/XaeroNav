package net.prason.xaeronav.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** MTR 4.1.0-beta.2が実際に返した応答（試験の路線: 駅A→駅Bの片道）を読めること。 */
class ItineraryTest {

    private static final String NETWORK = """
            {"stations":[{"id":"000000005A000001","name":"Test A|試験A","color":3368703,"zone1":0,"zone2":0,"zone3":0},
            {"id":"000000005A000002","name":"Test B","color":3368703,"zone1":0,"zone2":0,"zone3":0}],
            "routes":[{"id":"000000005A000004","name":"Test Line||hidden","color":16711680,"number":"T1","type":"train_normal",
            "circularState":"NONE","hidden":false,"stations":[{"id":"000000005A000001","x":-38,"y":88,"z":-19,"name":"1",
            "dwellTime":10000},{"id":"000000005A000002","x":262,"y":88,"z":-19,"name":"1","dwellTime":10000}],
            "durations":[18880],"depots":["Test Depot"]}],
            "dimensions":["minecraft/overworld","minecraft/the_nether","minecraft/the_end"]}""";

    private static final String WITH_TRAIN = """
            {"code":200,"currentTime":1794899313590,"text":"OK - directions","version":1,"data":{"connections":[
            {"routeId":"","startStationId":"","endStationId":"000000005A000001","startPlatformName":"","endPlatformName":"1",
            "startTime":1794899312339,"endTime":1794899324839,"walkingDistance":50},
            {"routeId":"000000005A000004","startStationId":"000000005A000001","endStationId":"000000005A000002",
            "startPlatformName":"1","endPlatformName":"1","startTime":1794899330751,"endTime":1794899349631,"walkingDistance":0},
            {"routeId":"","startStationId":"000000005A000002","endStationId":"","startPlatformName":"1","endPlatformName":"",
            "startTime":1794899349631,"endTime":1794899362131,"walkingDistance":50}],"totalRefreshGraphTime":0}}""";

    private static final String WALK_ONLY = """
            {"code":200,"currentTime":1794899316239,"text":"OK - directions","version":1,"data":{"connections":[
            {"routeId":"","startStationId":"","endStationId":"000000005A000002","startPlatformName":"","endPlatformName":"1",
            "startTime":1794899314989,"endTime":1794899314989,"walkingDistance":0},
            {"routeId":"","startStationId":"000000005A000002","endStationId":"","startPlatformName":"1","endPlatformName":"",
            "startTime":1794899314989,"endTime":1794899389989,"walkingDistance":300}]}}""";

    private static final String TOO_FAR = """
            {"code":200,"currentTime":1794899320139,"text":"OK - directions","version":1,"data":{"connections":[]}}""";

    private static TransitNetwork network() {
        return TransitNetwork.parse(json(NETWORK));
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void readsTheRideWithPlatformPositionsAndRelativeTimes() {
        Itinerary itinerary = Itinerary.parse(json(WITH_TRAIN), network());

        assertNotNull(itinerary);
        assertEquals(1, itinerary.rides().size());
        Itinerary.Ride ride = itinerary.first();
        assertEquals("Test Line", ride.routeName());
        assertEquals("Test A 試験A", ride.fromStation());
        assertEquals("1", ride.fromPlatform());
        assertEquals("Test B", ride.toStation());
        assertEquals(new BlockPos(-38, 88, -19), ride.board());
        assertEquals(new BlockPos(262, 88, -19), ride.alight());
        assertEquals(17161L, ride.departInMillis());
        assertEquals(36041L, ride.arriveInMillis());
        // 最後の歩きの区間の終わりまで
        assertEquals(48541L, itinerary.totalMillis());
    }

    @Test
    void walkingOnlyAnswerIsNotAnItinerary() {
        assertNull(Itinerary.parse(json(WALK_ONLY), network()));
    }

    @Test
    void emptyAnswerIsNotAnItinerary() {
        assertNull(Itinerary.parse(json(TOO_FAR), network()));
    }

    @Test
    void rejectsAnAnswerThatIsNotDirections() {
        TransitNetwork network = network();
        assertThrows(TransitFormatException.class, () -> Itinerary.parse(json("{\"code\":404}"), network));
        assertThrows(TransitFormatException.class,
                () -> Itinerary.parse(json("{\"code\":200,\"currentTime\":1,\"data\":{}}"), network));
    }

    @Test
    void rejectsMalformedValuesAsTransitFailures() {
        TransitNetwork network = network();
        assertThrows(TransitFormatException.class,
                () -> Itinerary.parse(json("{\"code\":\"not a number\"}"), network));
        assertThrows(TransitFormatException.class,
                () -> Itinerary.parse(json(TOO_FAR.replace("[]", "[null]")), network));
        assertThrows(TransitFormatException.class,
                () -> TransitNetwork.parse(json("{\"stations\":[null]}")));
        assertThrows(TransitFormatException.class,
                () -> TransitNetwork.parse(json(NETWORK.replace("-38", "4294967296"))));
    }

    @Test
    void rejectsARideOnAPlatformTheNetworkDoesNotKnow() {
        TransitNetwork empty = TransitNetwork.parse(json("{\"dimensions\":[\"minecraft/overworld\"]}"));
        assertThrows(TransitFormatException.class, () -> Itinerary.parse(json(WITH_TRAIN), empty));
    }

    @Test
    void findsTheDimensionIndex() {
        assertEquals(1, network().dimensionIndex("minecraft/the_nether"));
        assertEquals(-1, network().dimensionIndex("mod/other"));
    }
}
