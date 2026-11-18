package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.transit.Itinerary;
import net.prason.xaeronav.transit.TransitOutcome;
import net.prason.xaeronav.util.GameCompat;
import net.prason.xaeronav.util.MonotonicTime;
import org.jspecify.annotations.Nullable;

/**
 * 電車を使う案内。歩きの経路（{@link PathfindingState}）の上に乗る層で、経路探索そのものには手を入れない。
 * 案内そのものは電車のMOD（{@link TransitProvider}）から引く。複数入っていれば全部に聞き、一番早く着く答えを使う。
 *
 * <p>流れ: 区間の終点が決まると乗り換え案内を問い合わせ、歩くより速ければ提案する。承認されたら区間の終点を
 * 乗るホームへ差し替える（{@link PathfindingState#detour}）。乗っている間は歩きの経路を止め、降りたら今いる所から
 * 問い合わせ直す——乗り換え・乗り過ごし・違う電車に乗った場合も、同じ問い合わせ直しで拾う。
 *
 * <p>電車の区間は経路探索の辺にしない。時刻表に依存し、データはサーバーにしか無い。
 */
public final class TransitGuide {

    public static final TransitGuide INSTANCE = new TransitGuide();

    static final String ACCEPT_COMMAND = "/xaeronav transit";

    /** これより近い区間では問い合わせない。駅まで歩いて待つ手間が勝つと見なす足切りで、実測した値ではない。 */
    static final int MIN_BLOCKS = 200;
    /** 歩きの見積もりのこの割合より短いときだけ提案する。どちらの見積もりも粗いための余裕で、実測した値ではない。 */
    static final double FASTER_RATIO = 0.9;
    /** 区間の終点が決まってから問い合わせるまで。地図の上で目的地を置き直している間は問い合わせない。 */
    private static final int SETTLE_TICKS = 40;
    /** 乗車の状態がこれだけ続けて切れたら降りたと見なす。車両の間を歩く一瞬で切れることがある。 */
    private static final int OFF_TICKS = 10;
    /** 提案をそのまま使える古さ。超えていたら承認のときに問い合わせ直す（発車時刻が過ぎているかもしれない）。 */
    private static final long OFFER_FRESH_MILLIS = 20_000L;
    private static final long OFFER_SHOWN_MILLIS = 60_000L;
    /** ホームで待っている間、発車時刻を過ぎたらこの間隔で問い合わせ直す。 */
    private static final long WAIT_REFRESH_MILLIS = 30_000L;
    /** ホームに着いた後、これより離れたらホームへの案内へ戻す。 */
    private static final double LEFT_PLATFORM_BLOCKS = 24.0;

    private enum Phase {
        IDLE, ASKING, OFFERED, TO_PLATFORM, WAITING, RIDING, AFTER_RIDE
    }

    private enum Purpose {
        OFFER, ACCEPT, CONTINUE, REFRESH
    }

    private record Answer(int sequence, TransitProvider from, TransitOutcome outcome) {
    }

    /** HUDの行の受け口。{@code primary}は次の行動を表す行。 */
    interface HudSink {
        void add(Component line, boolean primary);
    }

    private final List<TransitProvider> providers = List.of(new MtrTransit(), new CrnTransit());
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();
    /** 今の問い合わせ（{@link #sequence}）で返事を待っている数と、届いた返事。 */
    private int awaiting;
    private final List<Answer> received = new ArrayList<>();
    private final GoalEta eta = new GoalEta();

    private Phase phase = Phase.IDLE;
    private @Nullable Purpose pending;
    private int sequence;
    /** 問い合わせるかを最後に決めた区間の終点。同じ区間で繰り返し問い合わせないために持つ。 */
    private @Nullable BlockPos askedFor;
    private int settleTicks;
    private int offTicks;
    private @Nullable Itinerary offer;
    private long offerMillis;
    /** 承認された案内。乗る区間は常に先頭（降りるたびに問い合わせ直して入れ替える）。 */
    private @Nullable Itinerary active;
    private long activeMillis;
    private @Nullable BlockPos spot;
    private boolean spotResolved;
    private long lastRefreshMillis;
    /** このクライアントで使える案内の元。ワールドに入ってから初めてのtickで決める。 */
    private @Nullable List<TransitProvider> available;
    /** 今の提案・案内を出した案内の元（乗る場所の探し方が違う）。乗っている間は、乗っている乗り物の元。 */
    private @Nullable TransitProvider provider;
    /** この接続の間に返事を読めなかった案内の元。直るまで待たせ続けないよう、入り直すまで問い合わせない。 */
    private final Set<TransitProvider> unavailable = new HashSet<>();

    private TransitGuide() {
    }

    /** 電車が運んでいる間（と、降りた直後に続きを問い合わせている間）は、歩きの経路を引かせない。 */
    boolean holdsRoute() {
        return phase == Phase.RIDING || phase == Phase.AFTER_RIDE;
    }

    public void onLoggingOut() {
        toIdle();
        askedFor = null;
        unavailable.clear();
        available = null;
        provider = null;
    }

    public void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (available == null) {
            List<TransitProvider> present = new ArrayList<>();
            for (TransitProvider candidate : providers) {
                if (candidate.present()) {
                    present.add(candidate);
                }
            }
            available = List.copyOf(present);
        }
        if (available.isEmpty()) {
            return;
        }
        PathfindingState state = PathfindingState.INSTANCE;
        TransitProvider vehicle = ridingOn(mc.player);
        boolean riding = vehicle != null;
        offTicks = riding ? 0 : offTicks + 1;
        if (state.goal() == null) {
            toIdle();
            askedFor = null;
            return;
        }
        for (Answer answer = answers.poll(); answer != null; answer = answers.poll()) {
            if (answer.sequence() == sequence && pending != null) {
                received.add(answer);
                if (received.size() == awaiting) {
                    Purpose purpose = pending;
                    pending = null;
                    handle(mc, state, purpose, best(mc.player, purpose));
                }
            }
        }
        if (riding && phase != Phase.RIDING) {
            board(state, vehicle);
            return;
        }
        BlockPos leg = state.legDestination();
        switch (phase) {
            case IDLE -> considerAsking(mc, state, leg);
            case ASKING, OFFERED -> {
                if (!leg.equals(askedFor) || state.inDetour()) {
                    toIdle();
                }
            }
            case TO_PLATFORM -> walkToPlatform(mc.level, state);
            case WAITING -> waitOnPlatform(mc, state);
            case RIDING -> {
                if (offTicks >= OFF_TICKS) {
                    alight(mc, state);
                }
            }
            case AFTER_RIDE -> {
                // 返事待ち。返事が来なければ案内の元の時間切れが失敗として返る
            }
        }
    }

    private void considerAsking(Minecraft mc, PathfindingState state, BlockPos leg) {
        if (!XaeroNavConfig.INSTANCE.transitSuggestionsEnabled() || leg.equals(askedFor) || state.flying()
                || state.arrived() || state.inDetour()) {
            settleTicks = 0;
            return;
        }
        if (++settleTicks < SETTLE_TICKS) {
            return;
        }
        settleTicks = 0;
        askedFor = leg;
        if (horizontalDistance(mc.player, leg) < MIN_BLOCKS) {
            return;
        }
        if (ask(mc, Purpose.OFFER, leg)) {
            phase = Phase.ASKING;
        }
    }

    private void walkToPlatform(Level level, PathfindingState state) {
        if (!state.inDetour()) {
            // 目的地や経由地が変えられた
            toIdle();
            return;
        }
        BlockPos railMiddle = active.first().board();
        if (!spotResolved && provider.spotLoaded(level, railMiddle)) {
            spotResolved = true;
            BlockPos found = provider.boardingSpot(level, railMiddle);
            if (!found.equals(spot)) {
                spot = found;
                state.detour(found);
                return;
            }
        }
        if (state.arrived()) {
            phase = Phase.WAITING;
        }
    }

    private void waitOnPlatform(Minecraft mc, PathfindingState state) {
        if (!state.inDetour()) {
            toIdle();
            return;
        }
        if (horizontalDistance(mc.player, spot) > LEFT_PLATFORM_BLOCKS) {
            state.detour(spot);
            phase = Phase.TO_PLATFORM;
            return;
        }
        long now = MonotonicTime.millis();
        boolean departed = now - activeMillis > active.first().departInMillis() + WAIT_REFRESH_MILLIS;
        if (departed && pending == null && now - lastRefreshMillis > WAIT_REFRESH_MILLIS
                && XaeroNavConfig.INSTANCE.transitSuggestionsEnabled()) {
            lastRefreshMillis = now;
            ask(mc, Purpose.REFRESH, state.legDestination());
        }
    }

    private @Nullable TransitProvider ridingOn(Player player) {
        for (TransitProvider candidate : available) {
            if (candidate.riding(player)) {
                return candidate;
            }
        }
        return null;
    }

    private void board(PathfindingState state, TransitProvider vehicle) {
        // 乗る前に投げた問い合わせの答えは、乗った後では使えない
        sequence++;
        pending = null;
        offer = null;
        phase = Phase.RIDING;
        provider = vehicle;
        state.suspendForRide();
        XaeroNav.LOGGER.info("XaeroNav: boarded an {} vehicle, holding the walking route", provider.name());
    }

    private void alight(Minecraft mc, PathfindingState state) {
        XaeroNav.LOGGER.info("XaeroNav: got off the {} vehicle", provider.name());
        if (state.inDetour() && XaeroNavConfig.INSTANCE.transitSuggestionsEnabled()
                && ask(mc, Purpose.CONTINUE, state.legDestination())) {
            phase = Phase.AFTER_RIDE;
            return;
        }
        boolean planned = state.inDetour();
        toIdle();
        if (planned) {
            state.endDetour();
        } else {
            // 案内なしで乗っていた。降りた所から、電車を使う案内が要るかをもう一度見る
            askedFor = null;
            state.resumeAfterRide();
        }
    }

    /**
     * 使える案内の元すべてへ問い合わせを投げたなら{@code true}。どれも使えなければ（使えないものは一度だけ知らせて）
     * {@code false}。
     */
    private boolean ask(Minecraft mc, Purpose purpose, BlockPos to) {
        int asked = sequence + 1;
        int sent = 0;
        for (TransitProvider candidate : available) {
            if (unavailable.contains(candidate)) {
                continue;
            }
            XaeroNav.LOGGER.debug("XaeroNav: asking {} directions ({}) from {} to {}", candidate.name(), purpose,
                    mc.player.blockPosition(), to);
            String refused = candidate.ask(mc, mc.player.blockPosition(), to,
                    outcome -> answers.add(new Answer(asked, candidate, outcome)));
            if (refused != null) {
                noteUnavailable(mc.player, candidate, refused);
            } else {
                sent++;
            }
        }
        if (sent == 0) {
            return false;
        }
        sequence = asked;
        pending = purpose;
        awaiting = sent;
        received.clear();
        return true;
    }

    /**
     * 届いた返事のうち一番早く着く答え。その答えを出した案内の元を{@link #provider}にする。電車の答えが1つも無く、
     * どれかが返事を読めなかったなら、失敗として返す（全部が「電車なし」なら失敗ではない）。
     */
    private TransitOutcome best(Player player, Purpose purpose) {
        Answer best = null;
        String failure = null;
        for (Answer answer : received) {
            TransitOutcome outcome = answer.outcome();
            if (outcome.failure() != null) {
                noteUnavailable(player, answer.from(), outcome.failure());
                failure = outcome.failure();
            } else if (outcome.itinerary() == null) {
                XaeroNav.LOGGER.info("XaeroNav: {} directions ({}) have no train ride, walking only",
                        answer.from().name(), purpose);
            } else if (best == null || outcome.itinerary().totalMillis() < best.outcome().itinerary().totalMillis()) {
                best = answer;
            }
        }
        received.clear();
        if (best == null) {
            return new TransitOutcome(null, failure);
        }
        provider = best.from();
        return best.outcome();
    }

    private void handle(Minecraft mc, PathfindingState state, Purpose purpose, TransitOutcome outcome) {
        Itinerary itinerary = outcome.itinerary();
        switch (purpose) {
            case OFFER -> {
                phase = Phase.IDLE;
                if (itinerary != null && fasterThanWalking(mc, state, itinerary)) {
                    offer = itinerary;
                    offerMillis = MonotonicTime.millis();
                    phase = Phase.OFFERED;
                    GameCompat.tell(mc.player, offerMessage(itinerary), false);
                }
            }
            case ACCEPT -> {
                if (itinerary == null) {
                    toIdle();
                    if (outcome.failure() == null) {
                        GameCompat.tell(mc.player, TextCompat.translatable("commands.xaeronav.transit_gone"), false);
                    }
                } else {
                    activate(mc, state, itinerary);
                }
            }
            case CONTINUE -> {
                if (itinerary == null) {
                    toIdle();
                    state.endDetour();
                } else {
                    activate(mc, state, itinerary);
                }
            }
            case REFRESH -> {
                if (outcome.failure() != null) {
                    // 返事が読めないだけで、電車が無くなったとは限らない。待たせたままにする
                    return;
                }
                if (itinerary == null) {
                    toIdle();
                    state.endDetour();
                    GameCompat.tell(mc.player, TextCompat.translatable("commands.xaeronav.transit_gone"), false);
                } else if (itinerary.first().board().equals(active.first().board())) {
                    active = itinerary;
                    activeMillis = MonotonicTime.millis();
                } else {
                    activate(mc, state, itinerary);
                }
            }
        }
    }

    private boolean fasterThanWalking(Minecraft mc, PathfindingState state, Itinerary itinerary) {
        double walkTicks = eta.ticks(mc.player.blockPosition(), state.goal(),
                state.navigationView().coarseRouteWaypoints());
        int walkSeconds = NavGuidance.estimateSeconds(walkTicks);
        long trainSeconds = itinerary.totalMillis() / 1000L;
        XaeroNav.LOGGER.info("XaeroNav: {} directions take {} s with {} ride(s), walking is estimated at {} s",
                provider.name(), trainSeconds, itinerary.rides().size(), walkSeconds);
        return trainSeconds < walkSeconds * FASTER_RATIO;
    }

    /** 提案を受ける（{@code /xaeronav transit}とチャットの提案のクリック）。結果はチャットへ出す。 */
    public boolean accept() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return false;
        }
        if (pending == Purpose.ACCEPT) {
            // 古い提案を承認して問い合わせ直している最中に、もう一度押された
            return true;
        }
        Itinerary offered = offer;
        if (phase != Phase.OFFERED || offered == null) {
            GameCompat.tell(player, TextCompat.translatable("commands.xaeronav.transit_no_offer"), false);
            return false;
        }
        PathfindingState state = PathfindingState.INSTANCE;
        if (MonotonicTime.millis() - offerMillis <= OFFER_FRESH_MILLIS) {
            activate(mc, state, offered);
        } else if (ask(mc, Purpose.ACCEPT, state.legDestination())) {
            phase = Phase.ASKING;
        } else {
            toIdle();
            return false;
        }
        return true;
    }

    private void activate(Minecraft mc, PathfindingState state, Itinerary itinerary) {
        active = itinerary;
        activeMillis = MonotonicTime.millis();
        offer = null;
        BlockPos railMiddle = itinerary.first().board();
        spotResolved = provider.spotLoaded(mc.level, railMiddle);
        // 読み込み前は案内が教える位置へ向かい、読み込まれたら床へ寄せ直す
        spot = spotResolved ? provider.boardingSpot(mc.level, railMiddle) : railMiddle;
        phase = Phase.TO_PLATFORM;
        state.detour(spot);
        Itinerary.Ride ride = itinerary.first();
        GameCompat.tell(mc.player, TextCompat.translatable("commands.xaeronav.transit_started", ride.routeName(),
                boardingAt(ride), ride.toStation()), false);
    }

    private void toIdle() {
        phase = Phase.IDLE;
        sequence++;
        pending = null;
        offer = null;
        active = null;
        spot = null;
        settleTicks = 0;
    }

    private void noteUnavailable(Player player, TransitProvider from, String reason) {
        XaeroNav.LOGGER.warn("XaeroNav: {} train directions are unavailable for this session: {}", from.name(), reason);
        if (unavailable.add(from)) {
            GameCompat.tell(player, TextCompat.translatable(from.unavailableMessageKey()), false);
        }
    }

    private static Component offerMessage(Itinerary itinerary) {
        Itinerary.Ride first = itinerary.first();
        Itinerary.Ride last = itinerary.rides().get(itinerary.rides().size() - 1);
        Component time = NavHud.time((int) (itinerary.totalMillis() / 1000L));
        Component text = itinerary.rides().size() == 1
                ? TextCompat.translatable("commands.xaeronav.transit_offer", first.routeName(), boardingAt(first),
                        last.toStation(), time)
                : TextCompat.translatable("commands.xaeronav.transit_offer_transfers", first.routeName(),
                        boardingAt(first), last.toStation(), time, itinerary.rides().size() - 1);
        Component button = TextCompat.translatable("commands.xaeronav.transit_offer_accept").setStyle(Style.EMPTY
                .withClickEvent(ChatCoordinateLinks.runCommand(ACCEPT_COMMAND))
                .withHoverEvent(ChatCoordinateLinks.showText(TextCompat.translatable("gui.xaeronav.transit_link.hover")))
                .withUnderlined(true)
                .withColor(ChatFormatting.AQUA));
        return TextCompat.empty().append(text).append(" ").append(button);
    }

    /**
     * HUDへこの層の行を足す。歩きの経路の行を出さずにこの行だけにするなら{@code true}
     * （乗車中・ホームで待っている間は、歩きの経路が無いか、着いたホームのもの）。
     */
    boolean addHud(Minecraft mc, HudSink out) {
        long now = MonotonicTime.millis();
        switch (phase) {
            case OFFERED -> {
                if (now - offerMillis < OFFER_SHOWN_MILLIS) {
                    out.add(TextCompat.translatable("hud.xaeronav.transit_offer", offer.first().routeName(),
                            NavHud.time((int) (offer.totalMillis() / 1000L))), false);
                }
                return false;
            }
            case TO_PLATFORM -> {
                Itinerary.Ride ride = active.first();
                out.add(TextCompat.translatable("hud.xaeronav.transit_to_platform", boardingAt(ride), ride.routeName()),
                        false);
                addDeparture(out, ride, now);
                return false;
            }
            case WAITING -> {
                Itinerary.Ride ride = active.first();
                out.add(TextCompat.translatable("hud.xaeronav.transit_wait", ride.routeName(), ride.toStation()), true);
                addDeparture(out, ride, now);
                return true;
            }
            case RIDING -> {
                Itinerary planned = active;
                if (planned == null) {
                    out.add(TextCompat.translatable("hud.xaeronav.transit_riding_plain"), false);
                    return true;
                }
                Itinerary.Ride ride = planned.first();
                out.add(TextCompat.translatable("hud.xaeronav.transit_riding", ride.routeName(), ride.toStation()), true);
                out.add(TextCompat.translatable("hud.xaeronav.transit_alight_distance",
                        (int) Math.round(horizontalDistance(mc.player, ride.alight()))), false);
                if (planned.rides().size() > 1) {
                    out.add(TextCompat.translatable("hud.xaeronav.transit_then",
                            planned.rides().get(1).routeName()), false);
                }
                return true;
            }
            case AFTER_RIDE -> {
                out.add(TextCompat.translatable("hud.xaeronav.transit_checking"), false);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 乗る駅とホーム。ホームの名前が無い駅（CRNでホームを設定していない駅など）は駅名だけ。 */
    private static Component boardingAt(Itinerary.Ride ride) {
        return ride.fromPlatform().isEmpty() ? TextCompat.literal(ride.fromStation())
                : TextCompat.translatable("hud.xaeronav.transit_station_platform", ride.fromStation(), ride.fromPlatform());
    }

    private void addDeparture(HudSink out, Itinerary.Ride ride, long now) {
        long seconds = (activeMillis + ride.departInMillis() - now) / 1000L;
        if (seconds > 0) {
            out.add(TextCompat.translatable("hud.xaeronav.transit_departs_in", NavHud.time((int) seconds)), false);
        }
    }

    private static double horizontalDistance(Player player, BlockPos pos) {
        return Math.hypot(pos.getX() + 0.5 - player.getX(), pos.getZ() + 0.5 - player.getZ());
    }
}
