package net.prason.xaeronav.dev;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.ChatCoordinateLinks;
import net.prason.xaeronav.client.PathfindingState;
import net.prason.xaeronav.config.XaeroNavConfig;

/**
 * {@code -Pxaeronav.chatCheck=<名前>}で、チャットの座標リンクを通しで確かめてクライアントを閉じる。
 * システムメッセージ（内蔵サーバーから）とプレイヤーの発言（自分のチャット）を流し、リンクが付くこと・
 * チャット画面のクリック処理で目的地になること・Shift+クリックの設定・設定offで付かないことを見る。
 * 結果はログの「chat check:」の行、リンクの見た目は{@code run/screenshots/<名前>.png}。
 */
final class ChatCheck {

    private static final int WAIT_TICKS = 60;
    private static final String SYSTEM_TEXT = "system: base at 90 64 521";
    private static final String PLAYER_TEXT = "hub -12, 70, -3 please";
    private static final String OFF_TEXT = "off: 300 64 300";

    private final String name;
    private int stage;
    private int ticks;

    private ChatCheck(String name) {
        this.name = name;
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.chatCheck");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new ChatCheck(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || ++ticks < WAIT_TICKS) {
            return;
        }
        ticks = 0;
        XaeroNavConfig cfg = XaeroNavConfig.INSTANCE;
        PathfindingState state = PathfindingState.INSTANCE;
        switch (stage++) {
            case 0 -> {
                mc.options.pauseOnLostFocus = false;
                cfg.setChatCoordinateLinksEnabled(true);
                cfg.setChatCoordinateShiftViaEnabled(false);
                state.clear();
                systemMessage(mc, SYSTEM_TEXT);
                mc.player.connection.sendChat(PLAYER_TEXT);
            }
            case 1 -> {
                Style system = linkIn(mc, "system: base at");
                Style player = linkIn(mc, "hub ");
                check("system message got a link to 90 64 521", "/xaeronav goto 90 64 521".equals(command(system)));
                check("player chat got a link to -12 70 -3", "/xaeronav goto -12 70 -3".equals(command(player)));
                ChatScreen screen = new ChatScreen("");
                mc.setScreen(screen);
                boolean handled = screen.handleComponentClicked(system);
                check("click on the chat screen is handled", handled);
                check("click set the destination", goalAt(state.goal(), 90, 521));
            }
            case 2 -> {
                Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
                        message -> XaeroNav.LOGGER.info("chat check: {}", message.getString()));
                Style player = linkIn(mc, "hub ");
                ChatCoordinateLinks.click(player, true);
                check("shift+click sets the destination while the setting is off", goalAt(state.goal(), -12, -3)
                        && state.stopsInOrder().isEmpty());
                state.setGoal(new BlockPos(90, 64, 521));
                cfg.setChatCoordinateShiftViaEnabled(true);
                ChatCoordinateLinks.click(player, true);
                check("shift+click adds a stop while the setting is on", goalAt(state.finalGoal(), 90, 521)
                        && state.stopsInOrder().stream().anyMatch(stop -> goalAt(stop, -12, -3)));
                cfg.setChatCoordinateShiftViaEnabled(false);
                cfg.setChatCoordinateLinksEnabled(false);
                systemMessage(mc, OFF_TEXT);
            }
            default -> {
                check("no link while the setting is off", linkIn(mc, "off: ") == null);
                cfg.setChatCoordinateLinksEnabled(true);
                state.clear();
                mc.stop();
            }
        }
    }

    private static boolean goalAt(BlockPos pos, int x, int z) {
        return pos != null && pos.getX() == x && pos.getZ() == z;
    }

    private static void systemMessage(Minecraft mc, String text) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> server.getPlayerList().getPlayer(mc.player.getUUID())
                .sendSystemMessage(Component.literal(text)));
    }

    /** {@code marker}を含む最新のメッセージの中で、座標のリンクが付いた部分のStyle（発言者の名前にも別のクリック動作がある）。 */
    private static Style linkIn(Minecraft mc, String marker) {
        for (GuiMessage message : allMessages(mc.gui.getChat())) {
            if (!message.content().getString().contains(marker)) {
                continue;
            }
            AtomicReference<Style> found = new AtomicReference<>();
            message.content().visit((style, text) -> {
                String command = command(style);
                if (command != null && command.startsWith("/xaeronav goto ") && found.get() == null) {
                    found.set(style);
                }
                return Optional.empty();
            }, Style.EMPTY);
            XaeroNav.LOGGER.info("chat check: message \"{}\" link {}", message.content().getString(),
                    found.get() == null ? null : command(found.get()));
            return found.get();
        }
        XaeroNav.LOGGER.info("chat check: no message containing \"{}\"", marker);
        return null;
    }

    private static String command(Style style) {
        if (style == null) {
            return null;
        }
        ClickEvent event = style.getClickEvent();
        return event != null && event.getAction() == ClickEvent.Action.RUN_COMMAND ? event.getValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage> allMessages(ChatComponent chat) {
        try {
            Field field = ChatComponent.class.getDeclaredField("allMessages");
            field.setAccessible(true);
            return (List<GuiMessage>) field.get(chat);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ChatComponent.allMessages not readable", e);
        }
    }

    private static void check(String what, boolean ok) {
        XaeroNav.LOGGER.info("chat check: {} {}", ok ? "PASS" : "FAIL", what);
    }
}
