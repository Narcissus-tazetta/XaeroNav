package net.prason.xaeronav.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;

/**
 * {@code -Pxaeronav.hopCheck=<名前>}で、地上⇄ネザーの目的地の持ち越しを通しで確かめてクライアントを閉じる。ポータルは作らず、
 * 内蔵サーバーで次元の間を移す。結果はログの「hop check:」の行、着いたときのHUDは{@code run/screenshots/<名前>.png}。
 */
final class HopCheck {

    private static final int WAIT_TICKS = 100;
    private static final BlockPos STOP_OFFSET = new BlockPos(400, 0, 0);
    private static final BlockPos GOAL_OFFSET = new BlockPos(800, 0, 400);

    private final String name;
    private int stage;
    private int ticks;
    private BlockPos home;
    private BlockPos requestedGoal;

    private HopCheck(String name) {
        this.name = name;
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.hopCheck");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new HopCheck(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || ++ticks < WAIT_TICKS) {
            return;
        }
        ticks = 0;
        PathfindingState state = PathfindingState.INSTANCE;
        PathfindingState.NavigationView view = state.navigationView();
        switch (stage++) {
            case 0 -> {
                mc.options.pauseOnLostFocus = false;
                home = new BlockPos(mc.player.getBlockX(), 0, mc.player.getBlockZ());
                teleport(mc, Level.OVERWORLD, home, true);
            }
            case 1 -> {
                home = mc.player.blockPosition();
                requestedGoal = home.offset(GOAL_OFFSET);
                state.setGoal(home.offset(STOP_OFFSET));
                state.appendStop(requestedGoal);
                report("overworld goal set", mc, state);
            }
            case 2 -> teleport(mc, Level.NETHER, new BlockPos(home.getX() / 8, 64, home.getZ() / 8), false);
            case 3 -> {
                report("in the nether", mc, state);
                check("goal carried to the nether stop", view.goal() != null
                        && view.goal().getX() == Math.floorDiv(home.getX() + STOP_OFFSET.getX(), 8)
                        && view.goal().getZ() == Math.floorDiv(home.getZ() + STOP_OFFSET.getZ(), 8));
                teleport(mc, Level.NETHER, view.goal(), false);
            }
            case 4 -> {
                report("after the nether stop", mc, state);
                teleport(mc, Level.NETHER, view.goal(), false);
            }
            // 着いた表示が消える時間（100tick）を過ぎても残るかを見るため、もう1回待つ
            case 5 -> ticks = -WAIT_TICKS;
            case 6 -> {
                report("arrived in the nether", mc, state);
                check("guidance kept at the converted destination", view.arrived() && view.goal() != null);
                check("hint names the overworld destination", view.hopArrival() != null
                        && view.hopArrival().destination().equals(requestedGoal) && !view.hopArrival().fromNether());
                Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
                        message -> XaeroNav.LOGGER.info("hop check: {}", message.getString()));
                teleport(mc, Level.OVERWORLD, home, false);
            }
            case 7 -> {
                report("back in the overworld", mc, state);
                check("exact overworld destination restored", view.goal() != null
                        && view.goal().getX() == requestedGoal.getX() && view.goal().getZ() == requestedGoal.getZ());
                teleport(mc, Level.END, new BlockPos(100, 49, 0), false);
            }
            default -> {
                report("in the end", mc, state);
                check("cleared in the end", view.goal() == null);
                mc.stop();
            }
        }
    }

    private static void report(String what, Minecraft mc, PathfindingState state) {
        XaeroNav.LOGGER.info("hop check: {} — dimension {}, player {}, goal {}, stops {}, arrived {}", what,
                mc.level.dimension(), mc.player.blockPosition().toShortString(), state.goal(), state.stopsInOrder(),
                state.navigationView().arrived());
    }

    private static void check(String what, boolean ok) {
        XaeroNav.LOGGER.info("hop check: {} {}", ok ? "PASS" : "FAIL", what);
    }

    /** {@code onSurface}なら列の地表へ置く（チャンクはサーバーが読み込む）。落下・窒息で死なないよう無敵にする。 */
    private static void teleport(Minecraft mc, ResourceKey<Level> dimension, BlockPos at, boolean onSurface) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
            ServerLevel level = server.getLevel(dimension);
            int y = onSurface ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) : at.getY();
            player.setInvulnerable(true);
            player.teleportTo(level, at.getX() + 0.5, y, at.getZ() + 0.5, player.getYRot(), player.getXRot());
        });
    }
}
