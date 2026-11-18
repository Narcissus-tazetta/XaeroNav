package net.prason.xaeronav.transit;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * CRN（Create Railways Navigator）の経路検索の答え（{@code RouteJourney}の列）から、乗る便を1つ選んで
 * {@link Itinerary}にする。CRNのクラスには触らない（{@link CrnLink}が素の値へ写してから渡す）。
 *
 * <p>時刻はどれもCRNの時計（ゲームtick）。CRNの答えは「駅から駅」なので、出発地から乗る駅まで歩いて間に合う便のうち
 * 一番早く着くものを選び、降りた駅から目的地までの歩きを足して所要時間にする。
 */
public final class CrnRoutes {

    /** CRNの時計の1 tick。 */
    static final long MILLIS_PER_TICK = 50L;
    /**
     * 駅までの歩きの見積もりの速さ（ブロック/tick、直線距離で）。MTRの案内が歩きを4 m/sで見積もるのに揃えた値で、実測ではない。
     * 疾走（約5.6 m/s）より遅めにして、乗り遅れる便を選ばないようにしている。
     */
    static final double WALK_BLOCKS_PER_TICK = 4.0 / 20.0;

    private CrnRoutes() {
    }

    /** 駅1つ。{@code name}は駅そのものの名前（座標を引く鍵）、{@code shown}は表示名（タグがあればタグ名）。 */
    public record Stop(String name, String shown, String platform, long arriveTick, long departTick) {
    }

    /** 1本の列車に乗る区間。 */
    public record Leg(String trainName, String lineName, Stop board, Stop alight) {
    }

    public record Journey(List<Leg> legs) {
    }

    /** 駅の名前から、その駅のブロックの位置（同名の駅が複数あれば{@code near}に一番近いもの）。無ければ{@code null}。 */
    public interface Stations {
        @Nullable BlockPos find(String name, BlockPos near);
    }

    /**
     * 乗る便を選ぶ。出発地から乗る駅まで歩いて間に合う便が無ければ{@code null}。
     *
     * @param now CRNの時計の今
     * @throws TransitFormatException 使える便が無く、答えにクライアントの線路の網に無い駅が含まれる
     */
    public static @Nullable Itinerary choose(List<Journey> journeys, long now, BlockPos from, BlockPos to,
                                             Stations stations) {
        Itinerary best = null;
        TransitFormatException unresolved = null;
        for (Journey journey : journeys) {
            if (journey.legs().isEmpty()) {
                continue;
            }
            Itinerary candidate;
            try {
                candidate = toItinerary(journey, now, from, to, stations);
            } catch (TransitFormatException missingStation) {
                unresolved = missingStation;
                continue;
            }
            if (candidate != null && (best == null || candidate.totalMillis() < best.totalMillis())) {
                best = candidate;
            }
        }
        if (best == null && unresolved != null) {
            throw unresolved;
        }
        return best;
    }

    private static @Nullable Itinerary toItinerary(Journey journey, long now, BlockPos from, BlockPos to,
                                                   Stations stations) {
        List<Itinerary.Ride> rides = new ArrayList<>();
        BlockPos here = from;
        long readyTick = now;
        for (Leg leg : journey.legs()) {
            BlockPos board = position(stations, leg.board().name(), here);
            BlockPos alight = position(stations, leg.alight().name(), board);
            if (rides.isEmpty() && leg.board().departTick() < now + walkTicks(from, board)) {
                return null;
            }
            if (leg.board().departTick() < readyTick) {
                return null;
            }
            String route = leg.lineName().isEmpty() ? leg.trainName() : leg.lineName();
            rides.add(new Itinerary.Ride(route, leg.board().shown(), leg.board().platform(), leg.alight().shown(),
                    leg.alight().platform(), (leg.board().departTick() - now) * MILLIS_PER_TICK,
                    (leg.alight().arriveTick() - now) * MILLIS_PER_TICK, board, alight));
            here = alight;
            readyTick = leg.alight().arriveTick();
        }
        long endTick = readyTick + walkTicks(here, to);
        return new Itinerary(List.copyOf(rides), (endTick - now) * MILLIS_PER_TICK);
    }

    private static BlockPos position(Stations stations, String name, BlockPos near) {
        BlockPos pos = stations.find(name, near);
        if (pos == null) {
            throw new TransitFormatException("CRN station '" + name + "' is not on the client's track network");
        }
        return pos;
    }

    static long walkTicks(BlockPos a, BlockPos b) {
        double blocks = Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
        return (long) Math.ceil(blocks / WALK_BLOCKS_PER_TICK);
    }
}
