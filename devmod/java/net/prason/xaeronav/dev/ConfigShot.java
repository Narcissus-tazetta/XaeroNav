package net.prason.xaeronav.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;

/**
 * {@code -Pxaeronav.configShot=<名前>}で、タイトル画面から設定画面を開いてスクリーンショット（{@code run/screenshots/<名前>.png}）を
 * 撮り、クライアントを閉じる。設定画面の見た目を手で開かずに確かめるためのもの。
 */
final class ConfigShot {

    /** 開いてから撮るまで。1フレーム目は描き終わっていないことがある。 */
    private static final int SETTLE_TICKS = 40;

    private final String name;
    private int openedTicks = -1;

    private ConfigShot(String name) {
        this.name = name;
    }

    static void armIfRequested() {
        String name = System.getProperty("xaeronav.configShot");
        if (name != null) {
            NeoForge.EVENT_BUS.addListener(new ConfigShot(name)::onTick);
        }
    }

    private void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (openedTicks < 0) {
            if (mc.screen instanceof TitleScreen title) {
                mc.setScreen(new XaeroNavConfigScreen(title));
                openedTicks = 0;
            }
            return;
        }
        if (++openedTicks == SETTLE_TICKS) {
            Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
                    message -> XaeroNav.LOGGER.info("XaeroNav config shot: {}", message.getString()));
            mc.stop();
        }
    }
}
