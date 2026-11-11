package net.prason.xaeronav.dev;

import java.lang.reflect.Field;
import java.util.List;

import dev.diorama.MapMenu;
import dev.diorama.MapScreen;
import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;

/**
 * {@code -Pxaeronav.dioramaCheck=<名前>}（{@code -Pwith_diorama=true}と一緒に）で、Diorama連携を通しで確かめて
 * クライアントを閉じる。世界地図を開き、実際のマウス操作と同じ経路で右クリックメニューを出して「ここへ案内」を押し、
 * 目的地・ピン・経由地の項目・案内の終了を見る。結果はログの「diorama check:」の行、見た目は
 * {@code run/screenshots/<名前>-*.png}（メニュー・世界地図の線・回した地図・四角と丸のミニマップ）。
 */
final class DioramaCheck {

    private static final int WAIT_TICKS = 60;
    /** 画面の中心（＝プレイヤー）から右クリックする位置までのずれ（GUIピクセル）。 */
    private static final int CLICK_DX = 70;
    private static final int CLICK_DY = -45;
    private static final int MENU_ROW = 12;

    private final String name;
    private int stage;
    private int ticks;
    private MapScreen screen;

    private DioramaCheck(String name) {
        this.name = name;
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.dioramaCheck");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new DioramaCheck(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || ++ticks < WAIT_TICKS) {
            return;
        }
        ticks = 0;
        PathfindingState state = PathfindingState.INSTANCE;
        if (stage >= 3 && stage <= 11 && mc.screen != screen) {
            // 確認中に人の操作（Escなど）がゲームへ入ると地図が閉じる。開き直して同じ段をやり直す
            XaeroNav.LOGGER.info("diorama check: map screen was closed from outside at stage {}, reopening", stage);
            screen = new MapScreen();
            mc.setScreen(screen);
            return;
        }
        switch (stage++) {
            case 0 -> {
                mc.options.pauseOnLostFocus = false;
                state.clear();
                toSurface(mc);
            }
            // 地図が周りを焼き終えるのを待つ
            case 1 -> ticks = -WAIT_TICKS * 2;
            case 2 -> {
                screen = new MapScreen();
                mc.setScreen(screen);
            }
            case 3 -> rightClick(screen.width / 2.0 + CLICK_DX, screen.height / 2.0 + CLICK_DY);
            case 4 -> {
                MapMenu menu = screen.menu();
                check("right-click opened a menu", menu != null);
                check("menu has Navigate Here", rowOf(menu, "gui.xaeronav_goto_here") >= 0);
                check("no stop items before guidance", rowOf(menu, "gui.xaeronav_add_stop") < 0);
                shot("menu");
            }
            case 5 -> {
                clickRow(screen.menu(), "gui.xaeronav_goto_here");
                check("clicking Navigate Here set a goal", state.goal() != null);
                XaeroNav.LOGGER.info("diorama check: goal {} player {}", state.goal(), mc.player.blockPosition());
            }
            // 経路が引けるのを待つ
            case 6 -> ticks = -WAIT_TICKS;
            case 7 -> {
                check("goal is shown as a Diorama temporary waypoint", temporaryAt(state.goal()) != null);
                shot("map");
                rightClick(screen.width / 2.0 - CLICK_DX, screen.height / 2.0 - CLICK_DY);
            }
            case 8 -> {
                MapMenu menu = screen.menu();
                check("menu has stop items while guiding", rowOf(menu, "gui.xaeronav_add_stop") >= 0
                        && rowOf(menu, "gui.xaeronav_append_stop") >= 0);
                shot("menu-guiding");
                clickRow(menu, "gui.xaeronav_add_stop");
                check("clicking Add as Stop added a stop", !state.stopsInOrder().isEmpty());
            }
            case 9 -> {
                shot("map-stop");
                screen.turn(1);
            }
            case 10 -> ticks = -WAIT_TICKS * 2;
            case 11 -> {
                shot("map-turned");
                screen.turn(-1);
            }
            case 12 -> mc.setScreen(null);
            case 13 -> {
                Settings.get().minimapShape = Settings.Shape.SQUARE;
                shot("minimap-square");
            }
            case 14 -> Settings.get().minimapShape = Settings.Shape.ROUND;
            case 15 -> {
                shot("minimap-round");
                Settings.get().minimapShape = Settings.Shape.SQUARE;
            }
            default -> {
                BlockPos goal = state.goal();
                state.clear();
                // ピンの片付けは次のtickの同期で行われるので、消えたことは閉じる直前に見る
                if (stage < 18) {
                    XaeroNav.LOGGER.info("diorama check: cleared guidance (was {})", goal);
                    return;
                }
                check("temporary waypoints removed after clearing", temporaryCount() == 0);
                mc.stop();
            }
        }
    }

    private void rightClick(double x, double y) {
        screen.mouseClicked(x, y, 1);
        screen.mouseReleased(x, y, 1);
    }

    /** メニューのその行を左クリックする。行が無ければ何もしない（その場合は直後のcheckがFAILになる）。 */
    private void clickRow(MapMenu menu, String key) {
        int row = rowOf(menu, key);
        if (row < 0) {
            return;
        }
        double x = intField(menu, "x") + 6;
        double y = intField(menu, "y") + 1 + row * MENU_ROW + MENU_ROW / 2.0;
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
    }

    @SuppressWarnings("unchecked")
    private static int rowOf(MapMenu menu, String key) {
        if (menu == null) {
            return -1;
        }
        String wanted = Component.translatable(key).getString();
        List<MapMenu.Item> items = (List<MapMenu.Item>) field(menu, "items");
        for (int i = 0; i < items.size(); i++) {
            if (((Component) field(items.get(i), "label")).getString().equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static Waypoint temporaryAt(BlockPos pos) {
        WaypointStore store = WaypointStore.get();
        if (store == null || pos == null) {
            return null;
        }
        for (Waypoint waypoint : store.in(Waypoints.dimension())) {
            if (waypoint.kind == Waypoint.Kind.TEMPORARY && waypoint.x == pos.getX() && waypoint.z == pos.getZ()) {
                return waypoint;
            }
        }
        return null;
    }

    private static long temporaryCount() {
        WaypointStore store = WaypointStore.get();
        return store == null ? 0
                : store.in(Waypoints.dimension()).stream().filter(w -> w.kind == Waypoint.Kind.TEMPORARY).count();
    }

    private void shot(String what) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name + "-" + what + ".png", mc.getMainRenderTarget(),
                message -> XaeroNav.LOGGER.info("diorama check: {}", message.getString()));
    }

    private static int intField(Object target, String name) {
        return (Integer) field(target, name);
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(target.getClass().getName() + "." + name + " not readable", e);
        }
    }

    private static void check(String what, boolean ok) {
        XaeroNav.LOGGER.info("diorama check: {} {}", ok ? "PASS" : "FAIL", what);
    }

    /** いまいる列の地表へ置く。地中や空中から始まるワールドでも、地図に地形が写る場所で確かめるため。 */
    private static void toSurface(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        BlockPos at = mc.player.blockPosition();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(mc.player.getUUID());
            ServerLevel level = player.serverLevel();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
            player.setInvulnerable(true);
            player.teleportTo(level, at.getX() + 0.5, y, at.getZ() + 0.5, player.getYRot(), player.getXRot());
        });
    }
}
