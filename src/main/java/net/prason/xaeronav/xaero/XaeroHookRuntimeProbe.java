package net.prason.xaeronav.xaero;

import java.lang.reflect.Field;
import java.util.ArrayList;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.client.XaeroNavKeys;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.map.WorldMapSession;
import xaero.map.controls.ControlsRegister;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.WaypointReader;

/**
 * {@code mc-runtime-test}専用のUI driver。{@link XaeroHookProbe#PROPERTY}が明示された起動でだけロードする。
 *
 * <p>Xaero自身のmap-open処理と、変換後の公開メソッドを通してprobeを駆動する。mixin callbackを直接
 * 呼ばないので、注入点が外れた場合は成功マーカーが出ない。
 */
public final class XaeroHookRuntimeProbe {

    private static final int START_TICK = 40;
    private static final int TIMEOUT_TICK = 90;

    private static Phase phase = Phase.WAITING_FOR_MINIMAP;
    private static boolean finished;

    private XaeroHookRuntimeProbe() {
    }

    public static void onClientTick() {
        if (finished || !XaeroHookProbe.enabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.player.tickCount < START_TICK) {
            return;
        }

        try {
            if (minecraft.player.tickCount >= TIMEOUT_TICK) {
                fail(minecraft, "timeout; missing=" + XaeroHookProbe.missing());
                return;
            }

            switch (phase) {
                case WAITING_FOR_MINIMAP -> waitForMinimap(minecraft);
                case WAITING_FOR_WORLD_MAP -> waitForWorldMap(minecraft);
            }
        } catch (Throwable error) {
            fail(minecraft, error.getClass().getSimpleName() + ": " + error.getMessage());
            XaeroNav.LOGGER.error("XAERONAV_RUNTIME_HOOK_PROBE_EXCEPTION", error);
        }
    }

    private static void waitForMinimap(Minecraft minecraft) {
        if (!XaeroHookProbe.ran(XaeroHookProbe.Point.MINIMAP_RENDER)) {
            return;
        }

        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable()) {
            return;
        }
        session.getControlsHandler().keyDown(ControlsRegister.keyOpenMap, false, false);
        phase = Phase.WAITING_FOR_WORLD_MAP;
    }

    private static void waitForWorldMap(Minecraft minecraft) throws ReflectiveOperationException {
        if (!(minecraft.screen instanceof GuiMap map)
                || !XaeroHookProbe.ran(XaeroHookProbe.Point.WORLD_MAP_RENDER)) {
            return;
        }

        setCoordinateFields(map, minecraft);
        verifyWorldMapMenu(map);
        verifyWorldMapKey(map);
        verifyWaypointMenu(map, minecraft);

        if (!XaeroHookProbe.missing().isEmpty()) {
            fail(minecraft, "target methods returned; missing=" + XaeroHookProbe.missing());
            return;
        }

        for (XaeroHookProbe.Point point : XaeroHookProbe.Point.values()) {
            XaeroNav.LOGGER.info("XAERONAV_HOOK_EXECUTED {}", point.name());
        }
        XaeroNav.LOGGER.info("XAERONAV_RUNTIME_HOOK_PROBE_SUCCESS");
        finished = true;
        PathfindingState.INSTANCE.clear();
        minecraft.setScreen(null);
    }

    private static void setCoordinateFields(GuiMap map, Minecraft minecraft) throws ReflectiveOperationException {
        int x = minecraft.player.getBlockX();
        int y = minecraft.player.getBlockY();
        int z = minecraft.player.getBlockZ();
        setField(map, "mouseBlockPosX", x);
        setField(map, "mouseBlockPosY", y);
        setField(map, "mouseBlockPosZ", z);
        setField(map, "mouseBlockDim", minecraft.level.dimension());
        setField(map, "rightClickX", x);
        setField(map, "rightClickY", y);
        setField(map, "rightClickZ", z);
        setField(map, "rightClickDim", minecraft.level.dimension());
    }

    private static void verifyWorldMapMenu(GuiMap map) {
        ArrayList<RightClickOption> options = map.getRightClickOptions();
        requireOption(options, "gui.xaeronav_goto_here");
        requireOption(options, "gui.xaeronav_clear_route");
    }

    private static void verifyWorldMapKey(GuiMap map) {
        KeyMapping mapping = XaeroNavKeys.GOTO_MAP_CURSOR;
        InputConstants.Key probeKey = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_G);
        try {
            mapping.setKey(probeKey);
            if (!map.keyPressed(GLFW.GLFW_KEY_G, 0, 0)) {
                throw new IllegalStateException("world-map key hook did not consume its key");
            }
            if (PathfindingState.INSTANCE.goal() == null) {
                throw new IllegalStateException("world-map key hook did not set a goal");
            }
        } finally {
            // このdriverが動くのはCIだけで、GOTO_MAP_CURSORの既定値は未割り当て。
            // FabricではKeyMapping#getKeyが公開されていないため、既定値を明示して戻す。
            mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_UNKNOWN));
            KeyMapping.resetMapping();
        }
    }

    private static void verifyWaypointMenu(GuiMap map, Minecraft minecraft) {
        Waypoint source = new Waypoint(minecraft.player.getBlockX(), minecraft.player.getBlockY(),
                minecraft.player.getBlockZ(), "XaeroNav runtime probe", "X", WaypointColor.BLUE,
                WaypointPurpose.NORMAL);
        xaero.map.mods.gui.Waypoint element = new xaero.map.mods.gui.Waypoint(
                source, true, "runtime-probe", 1.0);
        ArrayList<RightClickOption> options = new WaypointReader().getRightClickOptions(element, map);
        requireOption(options, "gui.xaeronav_goto_waypoint");
        requireOption(options, "gui.xaeronav_clear_route");
    }

    private static void requireOption(ArrayList<RightClickOption> options, String key) {
        if (options == null || options.stream().noneMatch(option ->
                option.getDisplayName().getContents() instanceof TranslatableContents translatable
                        && key.equals(translatable.getKey()))) {
            throw new IllegalStateException("missing menu option " + key);
        }
    }

    private static void setField(GuiMap map, String name, Object value) throws ReflectiveOperationException {
        Field field = GuiMap.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(map, value);
    }

    private static void fail(Minecraft minecraft, String reason) {
        if (finished) {
            return;
        }
        finished = true;
        XaeroNav.LOGGER.error("XAERONAV_RUNTIME_HOOK_PROBE_FAILED {}", reason);
        PathfindingState.INSTANCE.clear();
        minecraft.setScreen(null);
    }

    private enum Phase {
        WAITING_FOR_MINIMAP,
        WAITING_FOR_WORLD_MAP
    }
}
