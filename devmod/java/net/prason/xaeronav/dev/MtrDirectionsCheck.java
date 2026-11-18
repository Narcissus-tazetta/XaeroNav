package net.prason.xaeronav.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.client.TransitGuide;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.transit.MtrLink;
import org.mtr.MTR;
import org.mtr.MTRClient;
import org.mtr.client.MinecraftClientData;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.tool.Utilities;
import org.mtr.packet.PacketForwardClientRequest;
import org.mtr.registry.RegistryClient;

/**
 * {@code -Pxaeronav.mtrDirectionsCheck=<名前> -Pwith_mtr=true}で、MTRのシミュレーター（TSC）へ試験の路線を直接登録し、
 * クライアントから乗り換え案内（{@code /mtr/api/map/directions}）を引けるかを測ってクライアントを閉じる。
 * <ul>
 * <li>手元のHTTP（シングルではTSCのWebサーバーそのもの）へ送ったとき</li>
 * <li>{@link PacketForwardClientRequest}でサーバーへ中継させたとき（マルチの{@code ClientServlet}と同じ道筋）</li>
 * </ul>
 * 線路はブロックを置かずにTSCのデータだけを作る（案内の計算はブロックを見ない）。結果はログの「mtr directions:」の行と
 * {@code run/mtr-directions-<名前>.txt}。ワールドのMTRデータを書き換えるので複製して使うこと。
 *
 * <p>配置はプレイヤーの位置を原点に、z=0を+X向きに走る一方通行の環状線: 車庫の待避線 0〜40、駅A 100〜140、駅B {@link #B}〜+40、
 * その20先から半径20の曲線でz=-40へ回り、z=-40を-X向きに戻って-20から待避線の頭へ入る。路線はA→Bの片道。
 *
 * <p>名前が{@code ride}で始まるときは、問い合わせの計測の代わりにXaeroNavの乗り換え案内を通しで動かす: 両駅のホームと
 * 通路だけブロックで置き、駅Aの手前から駅Bの先へ目的地を置く→提案を承認→ホームへ歩く→乗る→駅Bで降りる→目的地まで歩く。
 * 各段の結果はログの「mtr directions: ride:」の行（PASS/FAIL）。名前が{@code manual}で始まるときは、駅を置いて目的地を
 * 設定した所で止め、あとは人が操作する。
 */
final class MtrDirectionsCheck {

    private static final int Y_OFFSET = 30;
    /**
     * 駅Bのホームの西端（原点からのX）。ホームまで歩く・待つ・停車・降りて歩く手間があるので、駅の間が300ほどだと
     * 電車の方が遅く（73秒対60秒）、提案が出ない。
     */
    private static final int B = 800;
    private static final int GENERATE_WAIT_TICKS = 20 * 60;
    private static final int RESPONSE_WAIT_TICKS = 20 * 30;

    private record Probe(String label, int startDx, int startDz, int endDx, int endDz) {
    }

    private static final List<Probe> PROBES = List.of(
            // 駅Aの手前50から駅Bの先50へ。案内が出るはずの場面
            new Probe("A-50 to B+50", 70, 0, B + 70, 0),
            // 駅Bから駅Aへ（路線は片道なので出ないはず）
            new Probe("B to A (wrong way)", B + 20, 0, 120, 0),
            // 出発地がどのホームからも500ブロックより遠い
            new Probe("start far (>500)", -600, 0, B + 70, 0),
            // 歩いた方が明らかに速い短い距離（電車を使わない答えは返さないはず）
            new Probe("short walk", 110, 0, 130, 0));

    private final String name;
    private final List<String> out = Collections.synchronizedList(new ArrayList<>());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private int stage;
    private int ticks;
    private int ox;
    private int oy;
    private int oz;
    private long stationAId;
    private long stationBId;
    private long depotId;
    private long routeId;
    private volatile JsonObject firstResponse;
    private volatile int dimensionIndex = -1;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private int probeIndex;
    private int rideTicks;
    private int stoppedTicks;
    private double lastRideX;
    private double alightX;
    private BlockPos rideGoal;

    private MtrDirectionsCheck(String name) {
        this.name = name;
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.mtrDirectionsCheck");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new MtrDirectionsCheck(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.level != null && mc.player != null && server == null && name.startsWith("mp")) {
            ticks++;
            multiplayer(mc);
            return;
        }
        if (mc.level == null || mc.player == null || server == null) {
            return;
        }
        ticks++;
        switch (stage) {
            case 0 -> {
                if (ticks < 100) {
                    return;
                }
                mc.options.pauseOnLostFocus = false;
                ox = mc.player.getBlockX();
                oy = mc.player.getBlockY() + Y_OFFSET;
                oz = mc.player.getBlockZ();
                log("origin {} {} {}, MTRClient.getServerPort()={}, worldId={}", ox, oy, oz, MTRClient.getServerPort(),
                        MTR.getWorldId(mc.level));
                server.execute(() -> sendRailsAndStations(server));
                next();
            }
            case 1 -> {
                // 返事（登録で自動にできたホーム・待避線）を待つ
                if (firstResponse == null) {
                    if (ticks > RESPONSE_WAIT_TICKS) {
                        finish(mc, "no response to update_data (rails)");
                    }
                    return;
                }
                server.execute(() -> sendRouteAndDepot(server, firstResponse));
                next();
            }
            case 2 -> {
                if (ticks < 40) {
                    return;
                }
                server.execute(() -> sendOperation(server, OperationProcessor.GENERATE_BY_DEPOT_IDS,
                        obj("depotIds", arr(depotId)), "generate"));
                next();
            }
            case 3 -> {
                // 生成が済むと路線に区間の所要時間（durations）が入る。stations-and-routesで見る
                if (ticks % 40 != 0 || pending.get() > 0) {
                    if (ticks > GENERATE_WAIT_TICKS) {
                        finish(mc, "route durations never appeared");
                    }
                    return;
                }
                pending.incrementAndGet();
                long started = System.nanoTime();
                httpPost("/mtr/api/map/stations-and-routes?dimension=" + Math.max(dimensionIndex, 0), "{}").whenComplete((body, error) -> {
                    pending.decrementAndGet();
                    if (error != null) {
                        log("stations-and-routes failed: {}", error.toString());
                        return;
                    }
                    JsonObject data = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data");
                    if (dimensionIndex < 0) {
                        JsonArray dims = data.getAsJsonArray("dimensions");
                        String worldId = MTR.getWorldId(Minecraft.getInstance().level);
                        for (int i = 0; i < dims.size(); i++) {
                            if (dims.get(i).getAsString().equals(worldId)) {
                                dimensionIndex = i;
                            }
                        }
                        log("dimensions {} -> index {} for {}", dims, dimensionIndex, worldId);
                    }
                    for (JsonElement r : data.getAsJsonArray("routes")) {
                        JsonObject route = r.getAsJsonObject();
                        if (Long.parseUnsignedLong(route.get("id").getAsString(), 16) == routeId) {
                            JsonArray durations = route.getAsJsonArray("durations");
                            if (durations != null && !durations.isEmpty()) {
                                log("route ready after {} ms (http {} ms): {}", ticks * 50, (System.nanoTime() - started) / 1_000_000, route);
                                stage = 4;
                                ticks = 0;
                            }
                        }
                    }
                });
            }
            case 4 -> {
                // 時刻表（到着の一覧）ができるまで少し待つ
                if (ticks < 100 || pending.get() > 0) {
                    return;
                }
                if (name.startsWith("ride") || name.startsWith("manual")) {
                    server.execute(() -> buildStations(server));
                    stage = 20;
                    ticks = 0;
                    return;
                }
                if (name.startsWith("silent") || name.startsWith("refused")) {
                    server.execute(() -> buildStations(server));
                    stage = 40;
                    ticks = 0;
                    return;
                }
                if (probeIndex >= PROBES.size() * 2) {
                    stage = 5;
                    ticks = 0;
                    return;
                }
                Probe probe = PROBES.get(probeIndex / 2);
                boolean relay = probeIndex % 2 == 1;
                probeIndex++;
                directions(probe, relay);
            }
            case 5 -> {
                if (pending.get() > 0 && ticks < RESPONSE_WAIT_TICKS) {
                    return;
                }
                if (pending.get() > 0) {
                    log("{} request(s) never answered", pending.get());
                }
                pending.incrementAndGet();
                httpPost("/mtr/api/map/clients?dimension=" + dimensionIndex, "{}").whenComplete((body, error) -> {
                    log("clients: {}", error != null ? error.toString() : body);
                    pending.decrementAndGet();
                });
                stage = 6;
                ticks = 0;
            }
            case 6 -> {
                if (pending.get() > 0 && ticks < RESPONSE_WAIT_TICKS) {
                    return;
                }
                finish(mc, "done");
            }
            case 20 -> {
                if (ticks < 60) {
                    return;
                }
                rideGoal = PathfindingState.INSTANCE.setGoal(new BlockPos(ox + B + 70, oy + 1, oz + 3));
                log("ride: goal {} from {}", rideGoal, mc.player.blockPosition());
                if (name.startsWith("manual")) {
                    // ここから先は人が操作する。閉じるのも人。見たことだけログとスクリーンショットに残す
                    release(mc);
                    stage = 30;
                    ticks = 0;
                    return;
                }
                next();
            }
            case 21 -> {
                // 提案が出るまで待つ。出ていれば承認が通る
                if (ticks % 20 != 0) {
                    return;
                }
                if (name.startsWith("ridestale")) {
                    // 提案が古くなってから（承認で問い合わせ直す道）、返事が来る前にもう一度押す
                    if (ticks == 700) {
                        boolean first = TransitGuide.INSTANCE.accept();
                        boolean second = TransitGuide.INSTANCE.accept();
                        log("ride: stale offer accepted twice: {} {}", first, second);
                        next();
                    }
                    return;
                }
                if (TransitGuide.INSTANCE.accept()) {
                    log("ride: PASS offer accepted after {} ticks", ticks);
                    next();
                } else if (ticks > 400) {
                    finish(mc, "ride: FAIL no offer within 400 ticks");
                }
            }
            case 22 -> {
                PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
                if (view.arrived()) {
                    release(mc);
                    BlockPos at = mc.player.blockPosition();
                    log("ride: PASS reached the boarding spot {} (rail middle {} {} {}) after {} ticks, standing on {}",
                            view.goal(), ox + 120, oy, oz, ticks, mc.level.getBlockState(at.below()).getBlock());
                    next();
                } else if (ticks > 1200) {
                    release(mc);
                    finish(mc, "ride: FAIL did not reach the platform, at " + mc.player.blockPosition() + " goal " + view.goal());
                } else {
                    follow(mc, view);
                }
            }
            case 23 -> {
                if (MtrLink.riding()) {
                    release(mc);
                    log("ride: PASS boarded after waiting {} ticks at {}", ticks, mc.player.position());
                    lastRideX = mc.player.getX();
                    next();
                } else if (ticks > 3600) {
                    release(mc);
                    finish(mc, "ride: FAIL never boarded, at " + mc.player.position());
                } else {
                    if (ticks % 100 == 0) {
                        StringBuilder vehicles = new StringBuilder();
                        MinecraftClientData.getInstance().vehicles.forEach(vehicle -> vehicles.append(' ')
                                .append(vehicle.getHeadPositionAndTiltAngle().position())
                                .append(vehicle.isMoving() ? " moving" : " stopped"));
                        log("ride: waiting at {} tick {}, vehicles:{}", mc.player.position(), ticks, vehicles);
                        Screenshot.grab(mc.gameDirectory, name + "-wait-" + ticks + ".png", mc.getMainRenderTarget(),
                                message -> { });
                    }
                    // 扉の位置は車両の定義次第なので、車体の側面のすぐ内側をホームに沿って往復して扉をくぐる。
                    // 電車は先頭をホームの進行方向の端（+X側）に合わせて止まる（1両13ブロックなら127〜140）
                    double targetX = ox + ((ticks / 80) % 2 == 0 ? 128.0 : 139.0);
                    steer(mc, targetX, oz + 1.3);
                }
            }
            case 24 -> {
                rideTicks++;
                PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
                if (view.currentResult() != null) {
                    log("ride: FAIL a walking route is shown while riding (tick {})", rideTicks);
                }
                if (!MtrLink.riding()) {
                    release(mc);
                    log("ride: got off after {} ticks at {}", rideTicks, mc.player.position());
                    next();
                    return;
                }
                double moved = Math.abs(mc.player.getX() - lastRideX);
                lastRideX = mc.player.getX();
                stoppedTicks = moved < 0.001 ? stoppedTicks + 1 : 0;
                if (rideTicks % 100 == 0) {
                    log("ride: riding, x={} (station B is {}..{})", String.format("%.1f", mc.player.getX()), ox + B, ox + B + 40);
                }
                if (mc.player.getX() > ox + B - 5 && stoppedTicks > 20) {
                    // 駅Bに止まった。扉を探してホーム側へ出る
                    if (stoppedTicks == 21) {
                        alightX = mc.player.getX();
                    }
                    steer(mc, alightX + ((stoppedTicks / 40) % 2 == 0 ? -5.0 : 5.0), oz + 3.5);
                } else {
                    release(mc);
                }
                if (rideTicks > 4000) {
                    release(mc);
                    finish(mc, "ride: FAIL still riding after 4000 ticks, at " + mc.player.position());
                }
            }
            case 25 -> {
                PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
                if (view.goal() == null || (view.arrived() && view.goal().equals(rideGoal))) {
                    release(mc);
                    boolean atGoal = mc.player.blockPosition().closerThan(rideGoal, 4.0);
                    finish(mc, "ride: " + (atGoal ? "PASS" : "FAIL") + " arrived at " + mc.player.blockPosition()
                            + " (goal " + rideGoal + ") " + ticks + " ticks after getting off");
                } else if (ticks > 1200) {
                    release(mc);
                    finish(mc, "ride: FAIL did not reach the goal, at " + mc.player.blockPosition() + " leg goal " + view.goal());
                } else {
                    if (ticks % 100 == 1) {
                        log("ride: after the ride, leg goal {} at {}", view.goal(), mc.player.blockPosition());
                    }
                    follow(mc, view);
                }
            }
            case 30 -> watchManual(mc);
            case 40 -> {
                if (ticks < 60) {
                    return;
                }
                int port = name.startsWith("silent") ? silentPort() : refusedPort();
                setMtrPort(port);
                rideGoal = PathfindingState.INSTANCE.setGoal(new BlockPos(ox + B + 70, oy + 1, oz + 3));
                log("unavailable: MTR port now {} ({}), goal {} from {}", MtrLink.port(), name, rideGoal,
                        mc.player.blockPosition());
                next();
            }
            case 41 -> checkUnavailable(mc, 600, "first goal");
            case 42 -> {
                BlockPos second = PathfindingState.INSTANCE.setGoal(new BlockPos(ox + B + 70, oy + 1, oz - 300));
                rideGoal = second;
                log("unavailable: second goal {}", second);
                next();
            }
            case 43 -> checkUnavailable(mc, 400, "second goal");
            case 44 -> finish(mc, "unavailable: done");
            default -> {
            }
        }
    }

    private boolean watchedRiding;
    private int watchedRideTicks;
    private boolean watchedLineWhileRiding;
    private BlockPos watchedLegGoal;
    private boolean watchedArrived;

    /** 人が操作している間、乗り降り・乗車中の歩きの線・区間の終点・到着をログに出し、要所でスクリーンショットを撮る。 */
    private void watchManual(Minecraft mc) {
        PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
        boolean riding = MtrLink.riding();
        if (riding != watchedRiding) {
            watchedRiding = riding;
            watchedRideTicks = 0;
            watchedLineWhileRiding = false;
            log("manual: {} at {}", riding ? "boarded" : "got off", mc.player.blockPosition());
        }
        if (riding) {
            watchedRideTicks++;
            if (view.currentResult() != null && !watchedLineWhileRiding) {
                watchedLineWhileRiding = true;
                log("manual: FAIL a walking route is shown while riding (ride tick {})", watchedRideTicks);
            }
            if (watchedRideTicks == 100 || watchedRideTicks == 600) {
                log("manual: riding at {}, walking route shown={}", mc.player.blockPosition(), view.currentResult() != null);
                shot(mc, "riding-" + watchedRideTicks);
            }
        }
        if (view.goal() != null && !view.goal().equals(watchedLegGoal)) {
            watchedLegGoal = view.goal();
            log("manual: leg goal is now {} (final goal {})", watchedLegGoal, rideGoal);
        }
        // 着くと目的地ごと消えることがあるので、最後の区間の終点が元の目的地だったかで見る
        boolean arrived = rideGoal.equals(watchedLegGoal) && (view.goal() == null || view.arrived());
        if (arrived && !watchedArrived) {
            log("manual: {} arrived at {} (goal {})",
                    mc.player.blockPosition().closerThan(rideGoal, 4.0) ? "PASS" : "FAIL", mc.player.blockPosition(), rideGoal);
            shot(mc, "arrived");
        }
        watchedArrived = arrived;
    }

    /**
     * 専用サーバーへつないだとき（名前が{@code mp}で始まる）。サーバーのワールドは、シングルで{@code silent}などを回して
     * 路線と駅を作ったワールドの複製で、原点は{@code xaeronav.mtrOrigin=x,y,z}（そのときのログの{@code origin}）。
     * 駅Aの手前へ移って（要OP）目的地を置き、MTRの中継を通った提案が出て、承認でホームへの案内に切り替わるかを見る。
     */
    private void multiplayer(Minecraft mc) {
        switch (stage) {
            case 0 -> {
                if (ticks < 100) {
                    return;
                }
                mc.options.pauseOnLostFocus = false;
                String[] origin = System.getProperty("xaeronav.mtrOrigin").split(",");
                ox = Integer.parseInt(origin[0]);
                oy = Integer.parseInt(origin[1]);
                oz = Integer.parseInt(origin[2]);
                mc.player.connection.sendCommand(String.format("tp @s %.1f %d %.1f -90 0", ox + 72.5, oy + 1, oz + 3.5));
                log("mp: port {}, teleporting next to station A", MtrLink.port());
                if (name.startsWith("mpprobe")) {
                    probeRelay(MtrLink.port());
                }
                next();
            }
            case 1 -> {
                if (ticks < 60) {
                    return;
                }
                if (name.startsWith("mpsilent")) {
                    // サーバー側の地図サーバーが無効なときと同じく、中継が何も返さない
                    setMtrPort(silentPort());
                }
                rideGoal = PathfindingState.INSTANCE.setGoal(new BlockPos(ox + B + 70, oy + 1, oz + 3));
                log("mp: goal {} from {}", rideGoal, mc.player.blockPosition());
                if (name.startsWith("mpsilent")) {
                    stage = 41;
                    ticks = 0;
                    return;
                }
                next();
            }
            case 2 -> {
                if (ticks % 20 != 0) {
                    return;
                }
                if (TransitGuide.INSTANCE.accept()) {
                    log("mp: PASS offer accepted after {} ticks", ticks);
                    shot(mc, "accepted");
                    next();
                } else if (ticks > 600) {
                    finish(mc, "mp: FAIL no offer within 600 ticks");
                }
            }
            case 3 -> {
                PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
                if (ticks == 100) {
                    boolean toPlatform = view.goal() != null && !view.goal().equals(rideGoal)
                            && Math.abs(view.goal().getX() - (ox + 120)) <= 21;
                    shot(mc, "to-platform");
                    finish(mc, "mp: " + (toPlatform ? "PASS" : "FAIL") + " leg goal " + view.goal()
                            + " (station A is " + (ox + 100) + ".." + (ox + 140) + "), walking line="
                            + (view.currentResult() != null));
                }
            }
            case 41 -> checkUnavailable(mc, 600, "first goal");
            case 42 -> {
                rideGoal = PathfindingState.INSTANCE.setGoal(new BlockPos(ox + B + 70, oy + 1, oz - 300));
                log("unavailable: second goal {}", rideGoal);
                next();
            }
            case 43 -> checkUnavailable(mc, 400, "second goal");
            case 44 -> finish(mc, "unavailable: done");
            default -> {
            }
        }
    }

    /** 中継口へ同じ要求を投げ方を変えて順に送り、どれが返るかを見る。 */
    private void probeRelay(int port) {
        Thread thread = new Thread(() -> {
            String path = "/mtr/api/map/stations-and-routes?dimension=0";
            for (String host : new String[] {"localhost", "127.0.0.1"}) {
                long started = System.nanoTime();
                try {
                    HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path))
                            .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                    log("mp probe: java.net.http {} -> {} in {} ms", host, response.statusCode(), (System.nanoTime() - started) / 1_000_000);
                } catch (IOException | InterruptedException e) {
                    log("mp probe: java.net.http {} -> {} in {} ms", host, e, (System.nanoTime() - started) / 1_000_000);
                }
                for (boolean contentType : new boolean[] {true, false}) {
                    started = System.nanoTime();
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) URI.create("http://" + host + ":" + port + path)
                                .toURL().openConnection();
                        c.setConnectTimeout(3000);
                        c.setReadTimeout(8000);
                        c.setRequestMethod("POST");
                        if (contentType) {
                            c.setRequestProperty("Content-Type", "application/json");
                        }
                        c.setDoOutput(true);
                        try (java.io.OutputStream o = c.getOutputStream()) {
                            o.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                        int status = c.getResponseCode();
                        int length = c.getInputStream().readAllBytes().length;
                        log("mp probe: HttpURLConnection {} contentType={} -> {} ({} bytes) in {} ms", host, contentType, status,
                                length, (System.nanoTime() - started) / 1_000_000);
                    } catch (IOException e) {
                        log("mp probe: HttpURLConnection {} contentType={} -> {} in {} ms", host, contentType, e,
                                (System.nanoTime() - started) / 1_000_000);
                    }
                }
            }
        }, "mtr-relay-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private int noticesSeen;
    private int firstNoticeTick = -1;

    /**
     * 返事が来ないとき: 理由の通知がワールドの間に1回だけ出て、歩きの案内（区間の終点・線）が元の目的地のままかを見る。
     * {@code waitTicks}の終わりに判定する。
     */
    private void checkUnavailable(Minecraft mc, int waitTicks, String label) {
        int notices = countChat(mc, net.minecraft.network.chat.Component.translatable(
                "commands.xaeronav.transit_unavailable").getString());
        if (notices != noticesSeen) {
            noticesSeen = notices;
            if (firstNoticeTick < 0) {
                firstNoticeTick = ticks;
            }
            log("unavailable: notice #{} in chat at tick {} ({})", notices, ticks, label);
            shot(mc, "notice-" + notices);
        }
        if (ticks < waitTicks) {
            return;
        }
        PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
        boolean legIsGoal = rideGoal.equals(view.goal());
        boolean line = view.currentResult() != null;
        log("unavailable: {} {} after {} ticks: notices={} (first at tick {}), leg goal {} (is the goal: {}), walking line={}",
                notices == 1 && legIsGoal && line ? "PASS" : "FAIL", label, ticks, notices, firstNoticeTick,
                view.goal(), legIsGoal, line);
        shot(mc, label.replace(' ', '-'));
        next();
    }

    @SuppressWarnings("unchecked")
    private static int countChat(Minecraft mc, String text) {
        try {
            java.lang.reflect.Field field = net.minecraft.client.gui.components.ChatComponent.class
                    .getDeclaredField("allMessages");
            field.setAccessible(true);
            int count = 0;
            for (net.minecraft.client.GuiMessage message
                    : (List<net.minecraft.client.GuiMessage>) field.get(mc.gui.getChat())) {
                if (message.content().getString().contains(text)) {
                    count++;
                }
            }
            return count;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 接続は受けるが何も返さない口。マルチでサーバー側の地図サーバーが無効なとき、MTRの中継が黙るのと同じ形。 */
    private static int silentPort() {
        try {
            java.net.ServerSocket socket = new java.net.ServerSocket(0);
            List<java.net.Socket> held = Collections.synchronizedList(new ArrayList<>());
            Thread thread = new Thread(() -> {
                while (true) {
                    try {
                        held.add(socket.accept());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }, "mtr-silent-port");
            thread.setDaemon(true);
            thread.start();
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 誰も待っていない口（接続を拒まれる）。 */
    private static int refusedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void setMtrPort(int port) {
        try {
            java.lang.reflect.Field field = MTRClient.class.getDeclaredField("serverPort");
            field.setAccessible(true);
            field.setInt(null, port);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void shot(Minecraft mc, String label) {
        Screenshot.grab(mc.gameDirectory, name + "-" + label + ".png", mc.getMainRenderTarget(), message -> { });
    }

    /** 両駅のホーム（線路側の1列がMTRのホームのブロック）と通路、線路の下の床を置き、プレイヤーを駅Aの手前へ移す。 */
    private void buildStations(IntegratedServer server) {
        ServerLevel level = overworld(server);
        Block platform = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mtr:platform"));
        for (int[] range : new int[][] {{60, 150}, {B - 10, B + 80}}) {
            for (int x = range[0]; x <= range[1]; x++) {
                for (int z = -1; z <= 5; z++) {
                    boolean edge = z == 2 && (x >= 100 && x <= 140 || x >= B && x <= B + 40);
                    level.setBlock(new BlockPos(ox + x, oy, oz + z),
                            (edge ? platform : Blocks.STONE).defaultBlockState(), Block.UPDATE_CLIENTS);
                    for (int y = 1; y <= 3; y++) {
                        level.setBlock(new BlockPos(ox + x, oy + y, oz + z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        player.teleportTo(level, ox + 72.5, oy + 1, oz + 3.5, -90f, 0f);
        log("ride: built the stations, platform block {}", platform);
    }

    /** XaeroNavが描く線をなぞって疾走する。 */
    private static void follow(Minecraft mc, PathfindingState.NavigationView view) {
        PathResult result = view.currentResult();
        if (result == null || result.steps().isEmpty()) {
            release(mc);
            return;
        }
        double[][] line = EscalatorCheck.drawnLine(mc, result);
        double[] aim = EscalatorCheck.pursue(line[0], line[1], mc.player.getX(), mc.player.getZ(), 0.6);
        steer(mc, aim[0], aim[1]);
        mc.options.keySprint.setDown(true);
    }

    private static void steer(Minecraft mc, double x, double z) {
        double dx = x - mc.player.getX();
        double dz = z - mc.player.getZ();
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.options.keyUp.setDown(Math.hypot(dx, dz) > 0.2);
    }

    private static void release(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private void directions(Probe probe, boolean relay) {
        JsonObject body = new JsonObject();
        body.addProperty("startPositionX", ox + probe.startDx());
        body.addProperty("startPositionY", oy);
        body.addProperty("startPositionZ", oz + probe.startDz());
        body.addProperty("endPositionX", ox + probe.endDx());
        body.addProperty("endPositionY", oy);
        body.addProperty("endPositionZ", oz + probe.endDz());
        body.addProperty("startTime", System.currentTimeMillis());
        String endpoint = "/mtr/api/map/directions?dimension=" + dimensionIndex;
        String via = relay ? "relay" : "http";
        long started = System.nanoTime();
        pending.incrementAndGet();
        if (relay) {
            // マルチのClientServletと同じく、クライアントからサーバーへ中継を頼む。返事は描画スレッド外で来ることがある
            RegistryClient.sendPacketToServer(new PacketForwardClientRequest(endpoint, body.toString(), (response, path) -> {
                log("directions [{}] {}: {} ms, path={}, body={}", via, probe.label(), (System.nanoTime() - started) / 1_000_000, path, response);
                pending.decrementAndGet();
            }));
        } else {
            httpPost(endpoint, body.toString()).whenComplete((response, error) -> {
                log("directions [{}] {}: {} ms, body={}", via, probe.label(), (System.nanoTime() - started) / 1_000_000,
                        error != null ? error.toString() : response);
                pending.decrementAndGet();
            });
        }
    }

    private java.util.concurrent.CompletableFuture<String> httpPost(String endpoint, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + MTRClient.getServerPort() + endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(HttpResponse::body);
    }

    private void sendRailsAndStations(IntegratedServer server) {
        stationAId = 0x5A000001L;
        stationBId = 0x5A000002L;
        depotId = 0x5A000003L;
        // 一方通行の環状線。折り返す線路が無いので、何編成走っても正面で鉢合わせない
        JsonArray rails = new JsonArray();
        rails.add(rail(0, 0, "E", 40, 0, "W", false, true));
        rails.add(rail(40, 0, "E", 100, 0, "W", false, false));
        rails.add(rail(100, 0, "E", 140, 0, "W", true, false));
        rails.add(rail(140, 0, "E", B, 0, "W", false, false));
        rails.add(rail(B, 0, "E", B + 40, 0, "W", true, false));
        rails.add(rail(B + 40, 0, "E", B + 60, 0, "W", false, false));
        rails.add(rail(B + 60, 0, "E", B + 80, -20, "S", false, false));
        rails.add(rail(B + 80, -20, "N", B + 60, -40, "E", false, false));
        rails.add(rail(B + 60, -40, "W", -20, -40, "E", false, false));
        rails.add(rail(-20, -40, "W", -40, -20, "N", false, false));
        rails.add(rail(-40, -20, "S", -20, 0, "W", false, false));
        rails.add(rail(-20, 0, "E", 0, 0, "W", false, false));
        JsonArray stations = new JsonArray();
        stations.add(area(stationAId, "Test A", 90, 150));
        stations.add(area(stationBId, "Test B", B - 10, B + 50));
        JsonObject depot = area(depotId, "Test Depot", -10, 50);
        JsonObject request = new JsonObject();
        request.add("rails", rails);
        request.add("stations", stations);
        request.add("depots", arr(depot));
        log("update_data (rails): {}", request);
        MTR.<SerializedDataBase>sendMessageC2S(OperationProcessor.UPDATE_DATA, server, overworld(server), new JsonData(request), response -> {
            JsonObject json = JsonParser.parseString(Utilities.getJsonObjectFromData(response).toString()).getAsJsonObject();
            log("update_data (rails) response: {}", json);
            firstResponse = json;
        }, SerializedDataBase.class);
    }

    private void sendRouteAndDepot(IntegratedServer server, JsonObject response) {
        long platformA = 0;
        long platformB = 0;
        JsonObject siding = null;
        for (JsonElement e : response.getAsJsonArray("platforms")) {
            JsonObject p = e.getAsJsonObject();
            long x1 = p.getAsJsonObject("position1").get("x").getAsLong() - ox;
            long x2 = p.getAsJsonObject("position2").get("x").getAsLong() - ox;
            long minX = Math.min(x1, x2);
            if (minX == 100) {
                platformA = p.get("id").getAsLong();
            } else if (minX == B) {
                platformB = p.get("id").getAsLong();
            }
        }
        for (JsonElement e : response.getAsJsonArray("sidings")) {
            siding = e.getAsJsonObject();
        }
        log("platforms A={} B={}, siding={}", platformA, platformB, siding);
        if (platformA == 0 || platformB == 0 || siding == null) {
            log("platforms or siding were not created; stopping");
            stage = 99;
            Minecraft.getInstance().stop();
            return;
        }
        routeId = 0x5A000004L;
        JsonObject car = new JsonObject();
        // 乗るには実在する車両の定義（床と扉）が要る。値はMTR同梱のmtr_custom_resources.jsonのもの
        car.addProperty("vehicleId", "sp1900_mini_trailer");
        car.addProperty("length", 13.0);
        car.addProperty("width", 2.0);
        car.addProperty("capacity", 100);
        car.addProperty("bogie1Position", -6.5);
        car.addProperty("bogie2Position", 6.5);
        car.addProperty("couplingPadding1", 0.0);
        car.addProperty("couplingPadding2", 0.0);
        siding.add("vehicleCars", arr(car));
        siding.addProperty("maxVehicles", 0);

        JsonObject route = new JsonObject();
        route.addProperty("id", routeId);
        route.addProperty("transportMode", "TRAIN");
        route.addProperty("name", "Test Line");
        route.addProperty("color", 0xFF0000);
        route.addProperty("routeType", "NORMAL");
        route.addProperty("routeNumber", "T1");
        route.addProperty("hidden", false);
        route.addProperty("circularState", "NONE");
        route.add("routePlatformData", arr(obj("platformId", new JsonPrimitive(platformA)), obj("platformId", new JsonPrimitive(platformB))));

        JsonObject depot = area(depotId, "Test Depot", -10, 50);
        depot.add("routeIds", arr(routeId));
        JsonArray frequencies = new JsonArray();
        for (int i = 0; i < 24; i++) {
            // ゲーム内1時間（実時間50秒）あたりの本数。乗る確認では、後続が詰まって駅の手前に並ばない本数にする
            frequencies.add(name.startsWith("ride") || name.startsWith("manual") ? 6 : 32);
        }
        depot.add("frequencies", frequencies);

        JsonObject request = new JsonObject();
        request.add("sidings", arr(siding));
        request.add("routes", arr(route));
        request.add("depots", arr(depot));
        log("update_data (route): {}", request);
        sendOperation(server, OperationProcessor.UPDATE_DATA, request, "update_data (route)");
    }

    private void sendOperation(IntegratedServer server, String key, JsonObject request, String label) {
        MTR.<SerializedDataBase>sendMessageC2S(key, server, overworld(server), new JsonData(request),
                response -> log("{} response: {}", label, Utilities.getJsonObjectFromData(response)), SerializedDataBase.class);
    }

    private static ServerLevel overworld(IntegratedServer server) {
        return server.overworld();
    }

    /**
     * 1→2の向きにだけ走れる線路。角度はそれぞれの端から線路へ出ていく向き（MTRの{@code Angle}、Sが+Z）。
     * 逆向きの速度を0にすると、MTRの待避線からの経路探索はその向きを通らない（{@code SidingPathFinder}）。
     */
    private JsonObject rail(int dx1, int dz1, String angle1, int dx2, int dz2, String angle2, boolean platform,
            boolean siding) {
        JsonObject r = new JsonObject();
        r.add("position1", pos(ox + dx1, oy, oz + dz1));
        r.addProperty("angle1", angle1);
        r.add("position2", pos(ox + dx2, oy, oz + dz2));
        r.addProperty("angle2", angle2);
        r.addProperty("shape", "QUADRATIC");
        r.addProperty("verticalRadius", 0.0);
        r.addProperty("tiltPoints", 0);
        for (String key : new String[] {"tiltAngleDegrees1", "tiltAngleDistance1a", "tiltAngleDegrees1a", "tiltAngleDegrees1b",
                "tiltAngleDistance1b", "tiltAngleDegreesMiddle", "tiltAngleDistance2b", "tiltAngleDegrees2b", "tiltAngleDegrees2a",
                "tiltAngleDistance2a", "tiltAngleDegrees2"}) {
            r.addProperty(key, 0.0);
        }
        r.add("styles", new JsonArray());
        int speed = platform || siding ? 40 : 80;
        r.addProperty("speedLimit1", speed);
        r.addProperty("speedLimit2", 0);
        r.addProperty("isPlatform", platform);
        r.addProperty("isSiding", siding);
        r.addProperty("canAccelerate", true);
        r.addProperty("canTurnBack", false);
        r.addProperty("canConnectRemotely", false);
        r.addProperty("canHaveSignal", true);
        r.addProperty("transportMode", "TRAIN");
        return r;
    }

    private JsonObject area(long id, String name, int x1, int x2) {
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        a.addProperty("transportMode", "TRAIN");
        a.addProperty("name", name);
        a.addProperty("color", 0x3366FF);
        a.add("position1", pos(ox + x1, oy - 10, oz - 10));
        a.add("position2", pos(ox + x2, oy + 10, oz + 10));
        return a;
    }

    private static JsonObject pos(int x, int y, int z) {
        JsonObject p = new JsonObject();
        p.addProperty("x", x);
        p.addProperty("y", y);
        p.addProperty("z", z);
        return p;
    }

    private static JsonObject obj(String key, JsonElement value) {
        JsonObject o = new JsonObject();
        o.add(key, value);
        return o;
    }

    private static JsonArray arr(Object... values) {
        JsonArray a = new JsonArray();
        for (Object v : values) {
            if (v instanceof JsonElement e) {
                a.add(e);
            } else {
                a.add((Long) v);
            }
        }
        return a;
    }

    private void next() {
        stage++;
        ticks = 0;
    }

    private void log(String format, Object... args) {
        XaeroNav.LOGGER.info("mtr directions: " + format, args);
        String line = format;
        for (Object arg : args) {
            line = line.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(String.valueOf(arg)));
        }
        out.add(line);
    }

    private void finish(Minecraft mc, String why) {
        log("finished: {}", why);
        stage = 99;
        try {
            Files.write(Path.of("mtr-directions-" + name + ".txt"), new ArrayList<>(out));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mc.stop();
    }

    /** TSCは受け取ったデータを一度JSONへ書き出して読み直すので、JSONをそのまま書き出すだけで任意の要求になる。 */
    private record JsonData(JsonObject json) implements SerializedDataBase {

        @Override
        public void updateData(ReaderBase readerBase) {
        }

        @Override
        public void serializeData(WriterBase writer) {
            for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                write(writer, e.getKey(), e.getValue());
            }
        }

        private static void write(WriterBase writer, String key, JsonElement value) {
            if (value.isJsonObject()) {
                new JsonData(value.getAsJsonObject()).serializeData(writer.writeChild(key));
            } else if (value.isJsonArray()) {
                WriterBase.Array array = writer.writeArray(key);
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        new JsonData(element.getAsJsonObject()).serializeData(array.writeChild());
                    } else {
                        JsonPrimitive p = element.getAsJsonPrimitive();
                        if (p.isString()) {
                            array.writeString(p.getAsString());
                        } else if (p.isBoolean()) {
                            array.writeBoolean(p.getAsBoolean());
                        } else if (isIntegral(p)) {
                            array.writeLong(p.getAsLong());
                        } else {
                            array.writeDouble(p.getAsDouble());
                        }
                    }
                }
            } else {
                JsonPrimitive p = value.getAsJsonPrimitive();
                if (p.isString()) {
                    writer.writeString(key, p.getAsString());
                } else if (p.isBoolean()) {
                    writer.writeBoolean(key, p.getAsBoolean());
                } else if (isIntegral(p)) {
                    writer.writeLong(key, p.getAsLong());
                } else {
                    writer.writeDouble(key, p.getAsDouble());
                }
            }
        }

        private static boolean isIntegral(JsonPrimitive p) {
            // 返事を読み直した数は型を持たない（LazilyParsedNumber）ので、書き方で整数かを見分ける
            String text = p.getAsString();
            return text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0;
        }
    }
}
