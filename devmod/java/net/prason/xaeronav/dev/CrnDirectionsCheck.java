package net.prason.xaeronav.dev;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.Create;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.client.TransitGuide;
import net.prason.xaeronav.pathfinding.astar.PathResult;

/**
 * {@code -Pxaeronav.crnDirectionsCheck=<名前> -Pwith_create=true}で、Createの試験路線（直線の線路・駅2つ・両運転台の列車・
 * 2駅を往復する運行予定）をサーバー側のAPIで組み、CRN（Create Railways Navigator）の経路検索を測ってクライアントを閉じる。
 * <ul>
 * <li>CRNの{@code NAVIGATE}・{@code GET_NEAREST_STATION}へリフレクションで送れるか、答えの形と応答時間（本体で使う呼び方と同じ）</li>
 * <li>CRNが時刻を覚えるまでの時間（サーバーを早送りして待つ）</li>
 * <li>クライアントの{@code CreateClient.RAILWAYS}から駅名→座標・次元が引けるか</li>
 * <li>座席に座ったときの{@code player.getVehicle()}</li>
 * </ul>
 * 結果はログの「crn directions:」の行と{@code run/crn-directions-<名前>.txt}。ワールドを書き換えるので複製して使うこと。
 *
 * <p>線路は原点から+Xへz=0を走る。駅Aはx=10、駅Bはx=10+{@link #SPAN}、どちらも線路の+Z側の隣に置く。列車はAで組む。
 */
final class CrnDirectionsCheck {

    private static final int Y_OFFSET = 40;
    private static final int SPAN = 400;
    private static final int TRACK_END = SPAN + 40;
    private static final String STATION_A = "Alpha";
    private static final String STATION_B = "Bravo";
    private static final int SPRINT_TICKS = 200_000;
    private static final int PROBE_EVERY = 100;
    private static final int LEARN_TIMEOUT_TICKS = 12_000;

    private final String name;
    private final List<String> out = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger pending = new AtomicInteger();
    private int stage;
    private int ticks;
    private int ox;
    private int oy;
    private int oz;
    private volatile boolean journeysFound;
    private volatile boolean trainAssembled;
    private UUID trainId;
    private volatile boolean headingToA;
    private BlockPos rideGoal;
    private boolean walkingShownWhileRiding;
    private int probes;
    private int rideTicks;
    private int stillTicks;
    private double lastX;
    private double boardX;

    private CrnDirectionsCheck(String name) {
        this.name = name;
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.crnDirectionsCheck");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new CrnDirectionsCheck(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
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
                log("origin {} {} {}", ox, oy, oz);
                server.execute(() -> buildTrack(server));
                next();
            }
            case 1 -> {
                if (ticks < 40) {
                    return;
                }
                server.execute(() -> placeStations(server));
                next();
            }
            case 2 -> {
                if (ticks < 40) {
                    return;
                }
                server.execute(() -> assembleTrain(server));
                next();
            }
            case 3 -> {
                if (ticks < 40) {
                    return;
                }
                if (!trainAssembled) {
                    finish(mc, "train was not assembled");
                    return;
                }
                // 組み立てた直後は運転手がまだ数えられていない（Carriage#updateConductorsはtickで走る）ので、運行予定は少し後に渡す
                server.execute(() -> {
                    Train train = Create.RAILWAYS.trains.get(trainId);
                    Schedule schedule = new Schedule();
                    schedule.cyclic = true;
                    schedule.entries.add(entry(STATION_B));
                    schedule.entries.add(entry(STATION_A));
                    train.runtime.setSchedule(schedule, true);
                    log("schedule set, conductors={}/{}", train.hasForwardConductor(), train.hasBackwardConductor());
                });
                logClientStations();
                log("client trains: {}", CreateClient.RAILWAYS.trains.size());
                server.execute(() -> log("sprint requested: {}", server.tickRateManager().requestGameToSprint(SPRINT_TICKS)));
                next();
            }
            case 4 -> {
                if (journeysFound) {
                    server.execute(() -> log("sprint stopped: {}", server.tickRateManager().stopSprinting()));
                    if (name.startsWith("ride")) {
                        stage = 20;
                        ticks = 0;
                        return;
                    }
                    next();
                    return;
                }
                if (ticks > LEARN_TIMEOUT_TICKS) {
                    finish(mc, "CRN never returned a journey");
                    return;
                }
                if (ticks % PROBE_EVERY == 0 && pending.get() == 0) {
                    probe(mc);
                }
                if (ticks % 200 == 20) {
                    server.execute(() -> logTrainStatus(server));
                }
            }
            case 5 -> {
                // 早送りを止めた後、Aに列車が止まるのを待って座らせる
                if (ticks % 20 != 0) {
                    return;
                }
                if (ticks > 6000) {
                    finish(mc, "train never stood at A");
                    return;
                }
                server.execute(() -> trySeatAtA(server));
                if (mc.player.getVehicle() != null) {
                    boardX = mc.player.getX();
                    lastX = boardX;
                    log("client vehicle after seating: {} at x={}", mc.player.getVehicle().getClass().getName(),
                            String.format("%.1f", boardX));
                    Screenshot.grab(mc.gameDirectory, name + "-seated.png", mc.getMainRenderTarget(), message -> { });
                    next();
                }
            }
            case 6 -> {
                rideTicks++;
                if (rideTicks % 20 == 0) {
                    Entity vehicle = mc.player.getVehicle();
                    log("ride t={} x={} vehicle={}", rideTicks, String.format("%.1f", mc.player.getX()),
                            vehicle == null ? "none" : vehicle.getClass().getSimpleName());
                    if (Math.abs(mc.player.getX() - lastX) < 0.05 && Math.abs(mc.player.getX() - boardX) > SPAN / 2.0) {
                        stillTicks += 20;
                    } else {
                        stillTicks = 0;
                    }
                    lastX = mc.player.getX();
                }
                if (stillTicks >= 60) {
                    server.execute(() -> {
                        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        player.stopRiding();
                    });
                    next();
                } else if (rideTicks > 4000) {
                    finish(mc, "train never reached B while seated");
                }
            }
            case 7 -> {
                if (ticks == 20) {
                    log("after dismount: vehicle={} x={}", mc.player.getVehicle(), String.format("%.1f", mc.player.getX()));
                    probes = 0;
                }
                // 温まった後の応答時間を数回見る
                if (ticks > 20 && ticks % 40 == 0 && pending.get() == 0) {
                    if (probes++ >= 5) {
                        finish(mc, "done");
                        return;
                    }
                    probe(mc);
                }
            }
            case 20 -> {
                // 列車がAへ向かっている間に目的地を置く（Aを発つまで待たされる時間を短くし、歩きより速い答えにする）
                if (ticks % 20 != 0) {
                    return;
                }
                if (ticks > 6000) {
                    finish(mc, "ride: FAIL the train never headed to A");
                    return;
                }
                if (!headingToA) {
                    server.execute(() -> {
                        Train train = Create.RAILWAYS.trains.get(trainId);
                        Entity carriage = train.carriages.get(0).anyAvailableEntity();
                        // Aに止まっている間も行き先はAのまま。まだBの側を走っているうちに置く
                        headingToA = train.navigation.destination != null
                                && STATION_A.equals(train.navigation.destination.name)
                                && carriage != null && carriage.getX() - ox > SPAN / 2.0;
                    });
                    return;
                }
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                    // 飛んだままだと自動操縦が宙に浮いて歩けない（MTRの確認で乗れなかった原因）
                    player.getAbilities().flying = false;
                    player.onUpdateAbilities();
                    player.teleportTo(server.overworld(), ox - 10.5, oy, oz + 2.5, -90f, 10f);
                });
                next();
            }
            case 21 -> {
                if (ticks < 20) {
                    return;
                }
                rideGoal = PathfindingState.INSTANCE.setGoal(new BlockPos(ox + SPAN + 30, oy, oz + 2));
                log("ride: goal {} from {}", rideGoal, mc.player.blockPosition());
                next();
            }
            case 22 -> {
                if (ticks % 20 != 0) {
                    return;
                }
                if (TransitGuide.INSTANCE.accept()) {
                    log("ride: PASS offer accepted after {} ticks", ticks);
                    next();
                } else if (ticks > 600) {
                    finish(mc, "ride: FAIL no offer within 600 ticks");
                }
            }
            case 23 -> {
                PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
                if (view.arrived()) {
                    release(mc);
                    log("ride: PASS reached the boarding spot {} (station block {} {} {}) after {} ticks, standing on {}",
                            view.goal(), ox + 10, oy, oz + 1, ticks,
                            mc.level.getBlockState(mc.player.blockPosition().below()).getBlock());
                    Screenshot.grab(mc.gameDirectory, name + "-at-station.png", mc.getMainRenderTarget(), message -> { });
                    next();
                } else if (ticks > 1200) {
                    release(mc);
                    finish(mc, "ride: FAIL did not reach the station, at " + mc.player.blockPosition() + " goal " + view.goal());
                } else {
                    follow(mc, view);
                }
            }
            case 24 -> {
                // 人が座席を右クリックするのと同じく、止まっている列車の座席に座らせる
                if (mc.player.getVehicle() != null) {
                    log("ride: PASS boarded after {} ticks", ticks);
                    lastX = mc.player.getX();
                    next();
                    return;
                }
                if (ticks > 3000) {
                    finish(mc, "ride: FAIL never boarded, at " + mc.player.position());
                    return;
                }
                if (ticks % 20 == 0) {
                    server.execute(() -> trySeatAtA(server));
                }
            }
            case 25 -> {
                rideTicks++;
                if (PathfindingState.INSTANCE.navigationView().currentResult() != null && !walkingShownWhileRiding) {
                    walkingShownWhileRiding = true;
                    log("ride: FAIL a walking route is shown while riding (tick {})", rideTicks);
                }
                if (rideTicks == 200) {
                    Screenshot.grab(mc.gameDirectory, name + "-riding.png", mc.getMainRenderTarget(), message -> { });
                }
                if (rideTicks % 20 == 0) {
                    double x = mc.player.getX();
                    stillTicks = Math.abs(x - lastX) < 0.05 && x - ox > SPAN / 2.0 ? stillTicks + 20 : 0;
                    lastX = x;
                }
                if (stillTicks >= 60) {
                    log("ride: stopped at x={} after {} ticks, getting off", String.format("%.1f", mc.player.getX() - ox),
                            rideTicks);
                    server.execute(() -> server.getPlayerList().getPlayers().get(0).stopRiding());
                    next();
                } else if (rideTicks > 4000) {
                    finish(mc, "ride: FAIL still riding after 4000 ticks");
                }
            }
            case 26 -> {
                if (ticks == 5) {
                    // 座席から降りると列車の上に立つ。人がホームへ降りるのと同じく、駅の隣の床へ出す
                    server.execute(() -> {
                        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        player.teleportTo(server.overworld(), player.getX(), oy, oz + 2.5, -90f, 10f);
                    });
                }
                if (ticks < 20) {
                    return;
                }
                PathfindingState.NavigationView view = PathfindingState.INSTANCE.navigationView();
                if (view.goal() == null || (view.arrived() && view.goal().equals(rideGoal))) {
                    release(mc);
                    boolean atGoal = mc.player.blockPosition().closerThan(rideGoal, 4.0);
                    Screenshot.grab(mc.gameDirectory, name + "-arrived.png", mc.getMainRenderTarget(), message -> { });
                    finish(mc, "ride: " + (atGoal && !walkingShownWhileRiding ? "PASS" : "FAIL") + " arrived at "
                            + mc.player.blockPosition() + " (goal " + rideGoal + ") " + ticks + " ticks after getting off");
                } else if (ticks > 1200) {
                    release(mc);
                    finish(mc, "ride: FAIL did not reach the goal, at " + mc.player.blockPosition() + " leg goal " + view.goal());
                } else {
                    if (ticks % 100 == 21) {
                        log("ride: after the ride, leg goal {} at {}", view.goal(), mc.player.blockPosition());
                    }
                    follow(mc, view);
                }
            }
            default -> {
            }
        }
    }

    private static void follow(Minecraft mc, PathfindingState.NavigationView view) {
        PathResult result = view.currentResult();
        if (result == null || result.steps().isEmpty()) {
            release(mc);
            return;
        }
        double[][] line = EscalatorCheck.drawnLine(mc, result);
        double[] aim = EscalatorCheck.pursue(line[0], line[1], mc.player.getX(), mc.player.getZ(), 0.6);
        double dx = aim[0] - mc.player.getX();
        double dz = aim[1] - mc.player.getZ();
        mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.options.keyUp.setDown(Math.hypot(dx, dz) > 0.2);
        mc.options.keySprint.setDown(true);
    }

    private static void release(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    // ---- サーバー側の組み立て ----

    private void buildTrack(IntegratedServer server) {
        ServerLevel level = server.overworld();
        // 駅のブロックエンティティがtickしないと駅が線路の網に登録されない。描画距離の外の駅Bのために線路沿いを読み込んだままにする
        for (int cx = (ox - 20) >> 4; cx <= (ox + TRACK_END + 5) >> 4; cx++) {
            level.setChunkForced(cx, oz >> 4, true);
        }
        for (int x = -20; x <= TRACK_END + 5; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlockAndUpdate(new BlockPos(ox + x, oy - 1, oz + z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 4; y++) {
                    level.setBlockAndUpdate(new BlockPos(ox + x, oy + y, oz + z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int x = 0; x <= TRACK_END; x++) {
            level.setBlockAndUpdate(new BlockPos(ox + x, oy, oz),
                    AllBlocks.TRACK.getDefaultState().setValue(TrackBlock.SHAPE, TrackShape.XO));
        }
        // CRNの時計はワールドの時刻（試験ワールドは昼夜の進行が止めてある）。止まったままだと時刻表が作れない見込み
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(true, server);
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(level, ox + 5.5, oy, oz + 2.5, -90f, 10f);
        log("track placed x=0..{}", TRACK_END);
    }

    private void placeStations(IntegratedServer server) {
        ServerLevel level = server.overworld();
        for (int stationX : new int[] {10, 10 + SPAN}) {
            BlockPos pos = new BlockPos(ox + stationX, oy, oz + 1);
            level.setBlockAndUpdate(pos, AllBlocks.TRACK_STATION.getDefaultState());
            StationBlockEntity be = (StationBlockEntity) level.getBlockEntity(pos);
            CompoundTag tag = new CompoundTag();
            be.edgePoint.write(tag, level.registryAccess(), false);
            tag.put("TargetTrack", NbtUtils.writeBlockPos(new BlockPos(0, 0, -1)));
            // 駅は片側から来た列車しか止めない（GlobalStation#canNavigateTo）。Aは-X向きに入る列車、Bは+X向きに入る列車用
            tag.putBoolean("TargetDirection", stationX == 10);
            be.edgePoint.read(tag, level.registryAccess(), false);
            be.notifyUpdate();
        }
        Create.RAILWAYS.trackNetworks.values().forEach(g -> log("server graph {} nodes={} stations={}", g.id,
                g.getNodes().size(), g.getPoints(EdgePointType.STATION).size()));
    }

    private void assembleTrain(IntegratedServer server) {
        ServerLevel level = server.overworld();
        StationBlockEntity a = station(level, 10);
        StationBlockEntity b = station(level, 10 + SPAN);
        log("edge points: A={} B={}", a.getStation(), b.getStation());
        if (a.getStation() == null || b.getStation() == null) {
            return;
        }
        a.updateName(STATION_A);
        b.updateName(STATION_B);

        // 駅Aの組み立て向きは+X。先頭の台車は駅の線路の1つ先の上（x=11）でなければならない
        int y = oy + 1;
        level.setBlockAndUpdate(new BlockPos(ox + 11, y, oz),
                AllBlocks.SMALL_BOGEY.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
        // 運転台2つの間にブレイズバーナー＝両向きの運転手（CarriageContraption#inControl）。座席は人が乗る用
        level.setBlockAndUpdate(new BlockPos(ox + 11, y + 1, oz),
                AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(new BlockPos(ox + 12, y + 1, oz),
                AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
        level.setBlockAndUpdate(new BlockPos(ox + 13, y + 1, oz),
                AllBlocks.TRAIN_CONTROLS.getDefaultState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
        level.setBlockAndUpdate(new BlockPos(ox + 14, y + 1, oz), AllBlocks.SEATS.get(DyeColor.WHITE).getDefaultState());
        level.addFreshEntity(new SuperGlueEntity(level,
                SuperGlueEntity.span(new BlockPos(ox + 11, y, oz), new BlockPos(ox + 14, y + 1, oz))));

        boolean entered = a.enterAssemblyMode(null);
        a.assemble(server.getPlayerList().getPlayers().get(0).getUUID());
        Train train = a.getStation().getPresentTrain();
        log("assembly mode entered={} train={}", entered, train == null ? "none" : train.name.getString());
        if (train == null) {
            return;
        }
        a.exitAssemblyMode();

        trainId = train.id;
        log("train {} doubleEnded={} fwd={} back={} assembled", train.id, train.doubleEnded,
                train.hasForwardConductor(), train.hasBackwardConductor());
        trainAssembled = true;
    }

    private static ScheduleEntry entry(String station) {
        DestinationInstruction go = new DestinationInstruction();
        go.getData().putString("Text", station);
        ScheduledDelay wait = new ScheduledDelay();
        List<List<ScheduleWaitCondition>> conditions = new ArrayList<>();
        conditions.add(new ArrayList<>(List.of(wait)));
        return new ScheduleEntry(go, conditions);
    }

    private StationBlockEntity station(ServerLevel level, int x) {
        return (StationBlockEntity) level.getBlockEntity(new BlockPos(ox + x, oy, oz + 1));
    }

    private void logTrainStatus(IntegratedServer server) {
        for (Train train : Create.RAILWAYS.trains.values()) {
            Entity carriage = train.carriages.get(0).anyAvailableEntity();
            log("server train speed={} x={} dest={} current={} paused={} state={} entry={} derailed={} conductors={}/{} blockConductors={}",
                    String.format("%.3f", train.speed),
                    carriage == null ? "n/a" : String.format("%.1f", carriage.getX() - ox),
                    train.navigation.destination == null ? "none" : train.navigation.destination.name,
                    train.getCurrentStation() == null ? "none" : train.getCurrentStation().name,
                    train.runtime.paused, train.runtime.state, train.runtime.currentEntry, train.derailed,
                    train.hasForwardConductor(), train.hasBackwardConductor(),
                    train.carriages.get(0).anyAvailableEntity() instanceof CarriageContraptionEntity cce
                            && cce.getContraption() instanceof com.simibubi.create.content.trains.entity.CarriageContraption cc
                            ? cc.blockConductors.getFirst() + "/" + cc.blockConductors.getSecond() : "n/a");
        }
        try {
            Object trains = Class.forName("de.mrjulsen.crn.api.core.RailwayBackendApi").getMethod("getAllTrains").invoke(null);
            log("CRN tracked trains: {}", ((List<?>) trains).size());
        } catch (ReflectiveOperationException e) {
            log("CRN getAllTrains failed: {}", e.toString());
        }
    }

    private void trySeatAtA(IntegratedServer server) {
        ServerLevel level = server.overworld();
        GlobalStation a = station(level, 10).getStation();
        Train present = a == null ? null : a.getPresentTrain();
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        if (present == null || present.speed != 0 || player.getVehicle() != null) {
            return;
        }
        List<CarriageContraptionEntity> carriages = level.getEntitiesOfClass(CarriageContraptionEntity.class,
                new AABB(ox, oy - 2, oz - 3, ox + 30, oy + 5, oz + 3));
        if (carriages.isEmpty()) {
            log("train at A but no carriage entity nearby");
            return;
        }
        player.teleportTo(level, ox + 14.5, oy + 3, oz + 1.5, -90f, 10f);
        carriages.get(0).addSittingPassenger(player, 0);
        log("server: seated, vehicle={}", player.getVehicle() == null ? "none" : player.getVehicle().getClass().getName());
    }

    // ---- クライアント側の計測 ----

    private void logClientStations() {
        for (TrackGraph graph : CreateClient.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                log("client station name='{}' blockEntityPos={} dimension={} assembling={}", station.name,
                        station.blockEntityPos, station.edgeLocation.getFirst().dimension.location(), station.assembling);
            }
        }
    }

    private void probe(Minecraft mc) {
        probes++;
        UUID player = mc.player.getUUID();
        pending.incrementAndGet();
        long navStart = System.nanoTime();
        send("NAVIGATE", newInstance("de.mrjulsen.crn.network.packets.NavigatePacketData$Request",
                new Class<?>[] {String.class, String.class, UUID.class}, STATION_A, STATION_B, player)).whenComplete((response, error) -> {
                    pending.decrementAndGet();
                    long ms = (System.nanoTime() - navStart) / 1_000_000;
                    if (error != null) {
                        log("navigate #{} failed after {} ms: {}", probes, ms, error.toString());
                        return;
                    }
                    List<?> journeys = (List<?>) call(response, "getData");
                    log("navigate #{} {} ms: {} journeys, crnNow={}", probes, ms, journeys.size(), crnNow());
                    for (int i = 0; i < Math.min(3, journeys.size()); i++) {
                        logJourney(i, journeys.get(i));
                    }
                    if (!journeys.isEmpty()) {
                        journeysFound = true;
                    }
                });
        if (probes == 1 || journeysFound) {
            nearest(new BlockPos(ox + 5, oy, oz + 2), "player-ish (x=5)");
            nearest(new BlockPos(ox + SPAN + 60, oy, oz + 2), "past B (x=" + (SPAN + 60) + ")");
            nearest(new BlockPos(ox + SPAN / 2, oy, oz + 200), "far side (z=200)");
        }
    }

    private void nearest(BlockPos pos, String label) {
        long start = System.nanoTime();
        send("GET_NEAREST_STATION", newInstance("de.mrjulsen.crn.network.packets.GetNearestStationPacketData$Request",
                new Class<?>[] {BlockPos.class}, pos)).whenComplete((response, error) -> {
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    if (error != null) {
                        log("nearest {} failed: {}", label, error.toString());
                        return;
                    }
                    Object result = call(response, "getResult");
                    Object distance = field(result, "distance");
                    Optional<?> tag = (Optional<?>) field(result, "tagName");
                    log("nearest {} {} ms: tag={} distance={}", label, ms, tag.map(t -> call(t, "get")).orElse("none"), distance);
                });
    }

    private void logJourney(int index, Object journey) {
        log("  journey {}: departure={} arrival={} duration={} transfers={}", index, call(journey, "departure"),
                call(journey, "arrival"), call(journey, "duration"), call(journey, "transferCount"));
        for (Object leg : (List<?>) call(journey, "legs")) {
            log("    leg train='{}' line={} dest='{}' cancelled={}", call(leg, "trainName"), call(leg, "line"),
                    call(leg, "destinationText"), call(leg, "cancelled"));
            for (Object c : (List<?>) call(leg, "calls")) {
                Object station = call(c, "station");
                log("      call station name='{}' tag='{}' platform='{}' scheduled={} realtime={}", call(station, "name"),
                        call(station, "tagName"), call(station, "platform"), call(c, "scheduled"), call(c, "realtime"));
            }
        }
    }

    private Object crnNow() {
        try {
            return Class.forName("de.mrjulsen.crn.util.ModUtils").getMethod("getTransformedWorldTime").invoke(null);
        } catch (ReflectiveOperationException e) {
            return "n/a (" + e + ")";
        }
    }

    // ---- 本体で使う予定のリフレクションの呼び方 ----

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> send(String packetField, Object request) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        try {
            Object type = Class.forName("de.mrjulsen.crn.registry.ModNetworkManager").getField(packetField).get(null);
            Object toServer = Class.forName("de.mrjulsen.mcdragonlib.network.NetworkDirection").getMethod("toServer").invoke(null);
            Method send = null;
            for (Method m : type.getClass().getMethods()) {
                if (m.getName().equals("send") && m.getParameterCount() == 3
                        && m.getParameterTypes()[2] == CompletableFuture.class) {
                    send = m;
                }
            }
            if (send == null) {
                throw new NoSuchMethodException("send(NetworkDirection, data, CompletableFuture) on " + type.getClass());
            }
            send.invoke(type, toServer, request, future);
        } catch (ReflectiveOperationException e) {
            future.completeExceptionally(e);
        }
        return future.orTimeout(15, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static Object newInstance(String className, Class<?>[] types, Object... args) {
        try {
            return Class.forName(className).getConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(className, e);
        }
    }

    private static Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(target.getClass().getName() + "#" + method, e);
        }
    }

    private static Object field(Object target, String name) {
        try {
            return target.getClass().getField(name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(target.getClass().getName() + "." + name, e);
        }
    }

    private void next() {
        stage++;
        ticks = 0;
    }

    private void log(String format, Object... args) {
        XaeroNav.LOGGER.info("crn directions: " + format, args);
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
            Files.write(Path.of("crn-directions-" + name + ".txt"), new ArrayList<>(out));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mc.stop();
    }
}
