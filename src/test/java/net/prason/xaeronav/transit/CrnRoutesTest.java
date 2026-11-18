package net.prason.xaeronav.transit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * CRN 0.10.0-C6が実際に返した便（devmodの試験路線: 駅Alpha(9,116,13)→駅Bravo(409,116,13)、約1,105 tickおきの往復。
 * CRNの時計の今が8130のときの答え）から、乗る便を選べること。
 */
class CrnRoutesTest {

    private static final BlockPos ALPHA = new BlockPos(9, 116, 13);
    private static final BlockPos BRAVO = new BlockPos(409, 116, 13);
    private static final long NOW = 8130;

    private static final CrnRoutes.Stations STATIONS = (name, near) -> switch (name) {
        case "Alpha" -> ALPHA;
        case "Bravo" -> BRAVO;
        default -> null;
    };

    private static CrnRoutes.Journey run(long depart, long arrive) {
        return new CrnRoutes.Journey(List.of(new CrnRoutes.Leg("無名の列車", "",
                new CrnRoutes.Stop("Alpha", "Alpha", "", depart, depart),
                new CrnRoutes.Stop("Bravo", "Bravo", "", arrive, arrive))));
    }

    private static final List<CrnRoutes.Journey> ANSWER = List.of(run(8344, 8797), run(9449, 9902), run(10554, 11007));

    @Test
    void takesTheFirstTrainWhenTheStationIsClose() {
        Itinerary itinerary = CrnRoutes.choose(ANSWER, NOW, new BlockPos(5, 116, 14), new BlockPos(460, 116, 13), STATIONS);

        assertNotNull(itinerary);
        Itinerary.Ride ride = itinerary.first();
        assertEquals("無名の列車", ride.routeName());
        assertEquals("Alpha", ride.fromStation());
        assertEquals("Bravo", ride.toStation());
        assertEquals((8344 - NOW) * 50, ride.departInMillis());
        assertEquals((8797 - NOW) * 50, ride.arriveInMillis());
        assertEquals(ALPHA, ride.board());
        assertEquals(BRAVO, ride.alight());
        // 降りてから目的地まで51ブロックを4 m/sで歩く（255 tick）
        assertEquals((8797 + 255 - NOW) * 50, itinerary.totalMillis());
    }

    @Test
    void skipsTrainsThatLeaveBeforeTheStationCanBeReached() {
        // 駅Alphaまで209ブロック＝1045 tick。8344発には間に合わず、9449発に乗る
        Itinerary itinerary = CrnRoutes.choose(ANSWER, NOW, new BlockPos(-200, 116, 13), new BlockPos(460, 116, 13), STATIONS);

        assertNotNull(itinerary);
        assertEquals((9449 - NOW) * 50, itinerary.first().departInMillis());
    }

    @Test
    void noTrainCanBeCaughtMeansNoItinerary() {
        assertNull(CrnRoutes.choose(ANSWER, NOW, new BlockPos(-2000, 116, 13), new BlockPos(460, 116, 13), STATIONS));
        assertNull(CrnRoutes.choose(List.of(), NOW, new BlockPos(5, 116, 14), new BlockPos(460, 116, 14), STATIONS));
    }

    @Test
    void prefersTheLineNameAndKeepsTransfers() {
        CrnRoutes.Journey transfer = new CrnRoutes.Journey(List.of(
                new CrnRoutes.Leg("Train 1", "Red Line", new CrnRoutes.Stop("Alpha", "Alpha", "1", 8300, 8300),
                        new CrnRoutes.Stop("Bravo", "Central", "2", 8700, 8720)),
                new CrnRoutes.Leg("Train 2", "", new CrnRoutes.Stop("Bravo", "Central", "3", 8800, 8800),
                        new CrnRoutes.Stop("Alpha", "Alpha", "1", 9200, 9200))));

        Itinerary itinerary = CrnRoutes.choose(List.of(transfer), NOW, new BlockPos(5, 116, 14), ALPHA, STATIONS);

        assertNotNull(itinerary);
        assertEquals(2, itinerary.rides().size());
        assertEquals("Red Line", itinerary.first().routeName());
        assertEquals("Central", itinerary.first().toStation());
        assertEquals("2", itinerary.first().toPlatform());
        assertEquals("Train 2", itinerary.rides().get(1).routeName());
    }

    @Test
    void aStationMissingFromTheClientNetworkIsAnError() {
        CrnRoutes.Journey unknown = new CrnRoutes.Journey(List.of(new CrnRoutes.Leg("T", "",
                new CrnRoutes.Stop("Alpha", "Alpha", "", 8344, 8344), new CrnRoutes.Stop("Nowhere", "Nowhere", "", 8797, 8797))));

        assertThrows(TransitFormatException.class,
                () -> CrnRoutes.choose(List.of(unknown), NOW, new BlockPos(5, 116, 14), new BlockPos(460, 116, 14), STATIONS));
    }
}
