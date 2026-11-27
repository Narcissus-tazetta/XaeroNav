package net.prason.xaeronav.dev;

import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.sable.SableRide;
import net.prason.xaeronav.sable.SableVehicles;

/**
 * {@code -Pxaeronav.aeronauticsCheck=<名前> -Pwith_aeronautics=true}で、Sableの構造物を組んで乗り、XaeroNavの案内を確かめて閉じる。
 * <ol>
 * <li>幅5の車（Offroadの車輪4つ）に座って、壁の向こうの目的地を置く。壁には2マスの狭い口と9マスの広い口がある。
 *     車で広い口を回る経路が出るか、始点が地面か、止まっている間に引き直しが続かないか</li>
 * <li>車のすぐ横（地面）を目的地にして、座ったまま到着になるか</li>
 * <li>車の上に立って、地面からの経路が出るか</li>
 * <li>幅8の車に座って、歩きの経路とHUDの幅超過の通知</li>
 * <li>車輪の無い台を止めたまま空中で組んで座り、空中の案内になるか。物理を戻して落とし、着地で地上の案内へ戻るか</li>
 * </ol>
 * 結果はログの「aeronautics:」の行・{@code run/aeronautics-<名前>.txt}・スクリーンショット。ワールドを書き換えるので複製して使うこと。
 */
final class AeronauticsCheck {

    private static final int CAR_DX = 12;
    private static final int WALL_DX = CAR_DX + 30;
    private static final int GOAL_DX = CAR_DX + 55;
    private static final int WIDE_DZ = -14;
    private static final int FLOAT_DX = CAR_DX + 8;
    private static final int FLOAT_DZ = -9;
    private static final int FLOAT_HEIGHT = 20;
    private static final int ROUTE_TIMEOUT_TICKS = 600;

    private final String name;
    private final List<String> out = Collections.synchronizedList(new ArrayList<>());
    private int stage;
    private int ticks;
    private int ox;
    private int gy;
    private int oz;
    private BlockPos goal;
    private PathResult watched;
    private int resultChanges;

    private AeronauticsCheck(String name) {
        this.name = name;
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.aeronauticsCheck");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new AeronauticsCheck(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) {
            return;
        }
        ticks++;
        PathfindingState state = PathfindingState.INSTANCE;
        switch (stage) {
            case 0 -> {
                if (ticks < 100) {
                    return;
                }
                mc.options.pauseOnLostFocus = false;
                ox = mc.player.getBlockX();
                oz = mc.player.getBlockZ();
                server.execute(() -> build(server));
                next();
            }
            case 1 -> {
                if (ticks < 60) {
                    return;
                }
                command(server, String.format("sable assemble area %d %d %d %d %d %d", ox + CAR_DX, gy + 2, oz + 1,
                        ox + CAR_DX + 5, gy + 3, oz + 3));
                command(server, String.format("sable assemble area %d %d %d %d %d %d", ox + CAR_DX, gy + 2, oz + WIDE_DZ + 1,
                        ox + CAR_DX + 5, gy + 3, oz + WIDE_DZ + 6));
                next();
            }
            case 2 -> {
                // 物理で車が車輪に落ち着くのを待つ
                if (ticks < 120) {
                    return;
                }
                server.execute(() -> sit(server, new Vec3(ox + CAR_DX + 3, gy + 3, oz + 2)));
                next();
            }
            case 3 -> {
                if (ticks == 40) {
                    logRide(mc, "car seated");
                    goal = new BlockPos(ox + GOAL_DX, gy + 1, oz + 2);
                    state.setGoal(goal);
                    log("goal {}", goal);
                }
                if (ticks > 40 && routeReady(state)) {
                    describeRoute(mc, state, "car seated");
                    watched = state.currentResult();
                    resultChanges = 0;
                    next();
                } else if (ticks > ROUTE_TIMEOUT_TICKS) {
                    log("car seated: no route after {} ticks (computing={})", ticks, state.navigationView().computing());
                    next();
                }
            }
            case 4 -> {
                // 止まったまま200tick見て、経路が作り直され続けないこと
                PathResult now = state.currentResult();
                if (now != watched) {
                    resultChanges++;
                    watched = now;
                }
                if (ticks == 60) {
                    shot(mc, "car-seated");
                }
                if (ticks < 200) {
                    return;
                }
                log("car seated: result changed {} times in 200 ticks while parked", resultChanges);
                // 車の真下の地面のすぐ横を目的地にする（座ったまま着くか）
                goal = BlockPos.containing(mc.player.getX() + 2, gy + 1, mc.player.getZ());
                state.setGoal(goal);
                log("near goal {}", goal);
                next();
            }
            case 5 -> {
                if (state.navigationView().arrived()) {
                    log("car seated: arrived at the near goal after {} ticks", ticks);
                    next();
                } else if (ticks > 200) {
                    log("car seated: NOT arrived at the near goal (goal={} view goal={})", goal, state.navigationView().goal());
                    next();
                }
            }
            case 6 -> {
                if (ticks == 1) {
                    server.execute(() -> {
                        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        player.stopRiding();
                        SubLevelAccess car = subLevelNear(player.serverLevel(), new Vec3(ox + CAR_DX + 3, gy + 3, oz + 2));
                        BoundingBox3dc box = car.boundingBox();
                        player.teleportTo((box.minX() + box.maxX()) / 2, box.maxY() + 0.1, (box.minZ() + box.maxZ()) / 2);
                    });
                }
                if (ticks == 40) {
                    logRide(mc, "car standing");
                    goal = new BlockPos(ox + GOAL_DX, gy + 1, oz + 2);
                    state.setGoal(goal);
                }
                if (ticks > 40 && routeReady(state)) {
                    describeRoute(mc, state, "car standing");
                    shot(mc, "car-standing");
                    next();
                } else if (ticks > ROUTE_TIMEOUT_TICKS) {
                    log("car standing: no route after {} ticks", ticks);
                    next();
                }
            }
            case 7 -> {
                if (ticks == 1) {
                    server.execute(() -> sit(server, new Vec3(ox + CAR_DX + 3, gy + 3, oz + WIDE_DZ + 3.5)));
                }
                if (ticks == 40) {
                    logRide(mc, "wide car seated");
                    state.setGoal(new BlockPos(ox + GOAL_DX, gy + 1, oz + 2));
                }
                if (ticks > 40 && routeReady(state)) {
                    describeRoute(mc, state, "wide car seated");
                    shot(mc, "wide-car");
                    next();
                } else if (ticks > ROUTE_TIMEOUT_TICKS) {
                    log("wide car: no route after {} ticks", ticks);
                    next();
                }
            }
            case 8 -> {
                if (ticks == 1) {
                    server.execute(() -> server.getPlayerList().getPlayers().get(0).stopRiding());
                    command(server, "sable paused true");
                    command(server, String.format("sable assemble area %d %d %d %d %d %d", ox + FLOAT_DX, gy + FLOAT_HEIGHT,
                            oz + FLOAT_DZ, ox + FLOAT_DX + 2, gy + FLOAT_HEIGHT + 1, oz + FLOAT_DZ + 2));
                }
                // 組んだ構造物は次のtick以降でないと一覧に出ない
                if (ticks == 20) {
                    server.execute(() -> sit(server, new Vec3(ox + FLOAT_DX + 1.5, gy + FLOAT_HEIGHT + 1, oz + FLOAT_DZ + 1.5)));
                }
                if (ticks == 60) {
                    logRide(mc, "float seated");
                    state.setGoal(new BlockPos(ox + GOAL_DX, gy + 1, oz + 2));
                }
                if (ticks == 120) {
                    log("float seated paused: flying={} flightRoute={}", state.navigationView().flying(),
                            state.navigationView().flightRoute().isEmpty() ? "empty" : "present");
                    shot(mc, "float-flying");
                    command(server, "sable paused false");
                    next();
                }
            }
            case 9 -> {
                if (ticks % 10 == 0) {
                    SableRide ride = SableVehicles.ride(mc.player);
                    log("float falling t={} clearance={} flying={}", ticks,
                            ride == null ? "-" : String.format("%.2f", ride.clearance()), state.navigationView().flying());
                }
                if (ticks < 120) {
                    return;
                }
                if (routeReady(state)) {
                    describeRoute(mc, state, "float landed");
                } else {
                    log("float landed: no ground route yet (flying={} computing={})", state.navigationView().flying(),
                            state.navigationView().computing());
                }
                shot(mc, "float-landed");
                finish(mc);
            }
            default -> { }
        }
    }

    private static boolean routeReady(PathfindingState state) {
        PathfindingState.NavigationView view = state.navigationView();
        return !view.computing() && state.currentResult() != null && !state.currentResult().steps().isEmpty();
    }

    private void describeRoute(Minecraft mc, PathfindingState state, String label) {
        PathResult result = state.currentResult();
        Map<MovementType, Integer> counts = new EnumMap<>(MovementType.class);
        boolean wide = false;
        boolean narrow = false;
        BlockPos dismount = null;
        for (PathStep step : result.steps()) {
            counts.merge(step.movement(), 1, Integer::sum);
            if (step.movement() == MovementType.DISMOUNT && dismount == null) {
                dismount = step.pos();
            }
            for (BlockPos cell : step.bodyCells()) {
                if (cell.getX() == ox + WALL_DX) {
                    wide |= cell.getZ() >= oz + 6;
                    narrow |= cell.getZ() >= oz + 1 && cell.getZ() <= oz + 2;
                }
            }
        }
        PathStep first = result.steps().get(0);
        PathStep last = result.steps().get(result.steps().size() - 1);
        log("{}: mount={} complete={} steps={} moves={} first={} {} last={} wideGap={} narrowGap={} dismount={} playerY={}",
                label, ChunkView.mount(mc.player), result.complete(), result.steps().size(), counts, first.pos().toShortString(),
                first.movement(), last.pos().toShortString(), wide, narrow, dismount, String.format("%.2f", mc.player.getY()));
    }

    private void logRide(Minecraft mc, String label) {
        SableRide ride = SableVehicles.ride(mc.player);
        log("{}: ride={} mount={} player={} flying={}", label, ride, ChunkView.mount(mc.player), mc.player.position(),
                PathfindingState.INSTANCE.navigationView().flying());
    }

    private void build(IntegratedServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        player.setNoGravity(false);
        gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ox + CAR_DX + 2, oz + 2) - 1;
        log("origin {} {} {}", ox, gy, oz);
        // 平らな石の広場。上は空ける
        for (int x = ox + CAR_DX - 8; x <= ox + GOAL_DX + 8; x++) {
            for (int z = oz + WIDE_DZ - 4; z <= oz + 18; z++) {
                level.setBlock(new BlockPos(x, gy, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = gy + 1; y <= gy + FLOAT_HEIGHT + 6; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        // 壁: z=1..2が2マスの狭い口、z=7..15が9マスの広い口
        for (int z = oz + WIDE_DZ - 4; z <= oz + 18; z++) {
            boolean open = (z >= oz + 1 && z <= oz + 2) || (z >= oz + 7 && z <= oz + 15);
            for (int y = gy + 1; y <= gy + 6; y++) {
                level.setBlock(new BlockPos(ox + WALL_DX, y, z), open ? Blocks.AIR.defaultBlockState()
                        : Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        // 幅5の車: 台x0..5・z1..3。x=1・4のz=1は北向き、z=3は南向きの車輪（タイヤはz=0とz=4）
        buildCar(level, player, oz + 1, oz + 3);
        // 幅8の車: 台x0..5・z(W+1)..(W+6)。タイヤはz=W..W+7
        buildCar(level, player, oz + WIDE_DZ + 1, oz + WIDE_DZ + 6);
        // 車輪の無い台
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                level.setBlock(new BlockPos(ox + FLOAT_DX + x, gy + FLOAT_HEIGHT, oz + FLOAT_DZ + z),
                        Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        }
        level.setBlock(new BlockPos(ox + FLOAT_DX + 1, gy + FLOAT_HEIGHT + 1, oz + FLOAT_DZ + 1),
                block("create:white_seat").defaultBlockState(), 3);
        player.teleportTo(ox + CAR_DX - 4 + 0.5, gy + 1, oz - 4 + 0.5);
    }

    private void buildCar(ServerLevel level, ServerPlayer player, int z0, int z1) {
        Block mount = block("offroad:wheel_mount");
        ItemStack tire = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("offroad:tire")));
        for (int x = 0; x <= 5; x++) {
            for (int z = z0; z <= z1; z++) {
                BlockPos at = new BlockPos(ox + CAR_DX + x, gy + 2, z);
                if ((x == 1 || x == 4) && (z == z0 || z == z1)) {
                    Direction facing = z == z0 ? Direction.NORTH : Direction.SOUTH;
                    level.setBlock(at, mount.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing), 3);
                    // 車輪の向きの面を叩いたときだけタイヤが付く
                    player.setItemInHand(InteractionHand.MAIN_HAND, tire.copy());
                    level.getBlockState(at).useItemOn(player.getItemInHand(InteractionHand.MAIN_HAND), level, player,
                            InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(at), facing, at, false));
                } else {
                    level.setBlock(at, Blocks.OAK_PLANKS.defaultBlockState(), 3);
                }
            }
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(new BlockPos(ox + CAR_DX + 2, gy + 3, (z0 + z1) / 2), block("create:white_seat").defaultBlockState(), 3);
    }

    /** 構造物は組んだあと動くので、plot側の箱の中から座席を探して座らせる。 */
    private void sit(IntegratedServer server, Vec3 near) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();
        player.stopRiding();
        SubLevelAccess sub = subLevelNear(level, near);
        if (sub == null) {
            log("no sub-level near {}", near);
            return;
        }
        BoundingBox3d local = sub.boundingBox().transformInverse(sub.logicalPose(), new BoundingBox3d());
        for (BlockPos at : BlockPos.betweenClosed((int) Math.floor(local.minX()), (int) Math.floor(local.minY()),
                (int) Math.floor(local.minZ()), (int) Math.floor(local.maxX()), (int) Math.floor(local.maxY()),
                (int) Math.floor(local.maxZ()))) {
            if (level.getBlockState(at).getBlock() instanceof SeatBlock) {
                SeatBlock.sitDown(level, at.immutable(), player);
                return;
            }
        }
        log("no seat in sub-level {}", sub.getName());
    }

    private static SubLevelAccess subLevelNear(Level level, Vec3 at) {
        for (SubLevelAccess sub : SableCompanion.INSTANCE.getAllIntersecting(level,
                new BoundingBox3d(at.x - 3, at.y - 4, at.z - 3, at.x + 3, at.y + 4, at.z + 3))) {
            return sub;
        }
        return null;
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    private void command(IntegratedServer server, String command) {
        server.execute(() -> {
            log("/{}", command);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4), command);
        });
    }

    private void shot(Minecraft mc, String label) {
        Screenshot.grab(mc.gameDirectory, "aeronautics-" + name + "-" + label + ".png", mc.getMainRenderTarget(), m -> { });
    }

    private void next() {
        stage++;
        ticks = 0;
    }

    private void log(String format, Object... args) {
        XaeroNav.LOGGER.info("aeronautics: " + format, args);
        String line = format;
        for (Object arg : args) {
            line = line.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(String.valueOf(arg)));
        }
        out.add(line);
    }

    private void finish(Minecraft mc) {
        try {
            Files.write(mc.gameDirectory.toPath().resolve("aeronautics-" + name + ".txt"), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mc.stop();
    }
}
