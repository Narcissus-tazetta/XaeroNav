package net.prason.xaeronav.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.sable.SableVehicles;

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

    private static void shoot(Minecraft mc, String file) {
        Screenshot.grab(mc.gameDirectory, file + ".png", mc.getMainRenderTarget(),
                message -> XaeroNav.LOGGER.info("XaeroNav config shot: {}", message.getString()));
    }

    private static AbstractWidget vehicleWidthButton(GuiEventListener parent) {
        if (parent instanceof AbstractWidget widget
                && widget.getMessage().getString().startsWith(Component.translatable("gui.xaeronav.config.vehicle_width").getString())) {
            return widget;
        }
        if (parent instanceof ContainerEventHandler container) {
            for (GuiEventListener child : container.children()) {
                AbstractWidget found = vehicleWidthButton(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
        ++openedTicks;
        if (!SableVehicles.present()) {
            if (openedTicks == SETTLE_TICKS) {
                shoot(mc, name);
                mc.stop();
            }
            return;
        }
        // Create Aeronauticsが入っていれば、一番下の車幅の項目まで送り、1回押して値が回るのを撮る
        if (openedTicks == SETTLE_TICKS / 2) {
            mc.screen.mouseScrolled(mc.screen.width / 2.0, mc.screen.height / 2.0, 0, -100);
        } else if (openedTicks == SETTLE_TICKS) {
            shoot(mc, name);
            AbstractWidget button = vehicleWidthButton(mc.screen);
            XaeroNav.LOGGER.info("XaeroNav config shot: vehicle width before={} button={}",
                    XaeroNavConfig.INSTANCE.vehicleWidthBlocks(), button == null ? "missing" : button.getMessage().getString());
            if (button != null) {
                button.mouseClicked(button.getX() + 1, button.getY() + 1, 0);
            }
        } else if (openedTicks == SETTLE_TICKS * 2) {
            AbstractWidget button = vehicleWidthButton(mc.screen);
            XaeroNav.LOGGER.info("XaeroNav config shot: vehicle width after click={} button={}",
                    XaeroNavConfig.INSTANCE.vehicleWidthBlocks(), button == null ? "missing" : button.getMessage().getString());
            shoot(mc, name + "-clicked");
            mc.stop();
        }
    }
}
