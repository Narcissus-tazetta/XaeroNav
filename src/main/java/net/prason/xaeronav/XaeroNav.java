package net.prason.xaeronav;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.client.ClientTickHandler;
import net.prason.xaeronav.client.NavHud;
import net.prason.xaeronav.client.PathRenderer;
import net.prason.xaeronav.client.XaeroNavCommands;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.cost.ForbiddenBlocks;

@Mod(XaeroNav.MOD_ID)
public final class XaeroNav {
    public static final String MOD_ID = "xaeronav";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 設定画面の登録はクライアント側で行うので、そこまでコンテナを持ち越す。 */
    private static ModContainer container;

    public XaeroNav(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("XaeroNav initialized");
        container = modContainer;
        modContainer.registerConfig(ModConfig.Type.CLIENT, XaeroNavConfig.SPEC);
        modEventBus.addListener(XaeroNav::onConfigReloaded);
    }

    /**
     * 掘削禁止ブロックだけはIDからBlockへの解決結果を保持するので、設定ファイルの再読み込みに
     * 自分で追随する必要がある（他の設定値は参照のたびに読むので何もしなくてよい）。
     */
    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == XaeroNavConfig.SPEC) {
            ForbiddenBlocks.reloadFromConfig(XaeroNavConfig.INSTANCE.additionalForbiddenBlocks());
        }
    }

    // クライアント専用クラス（Minecraft/RenderLevelStageEvent等）への参照はFMLClientSetupEvent内に
    // 閉じ込める。dist=CLIENTでガードすることで、専用サーバー上でもこのクラス自体がロードされない。
    @EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static final class ClientSetup {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            ForbiddenBlocks.reloadFromConfig(XaeroNavConfig.INSTANCE.additionalForbiddenBlocks());
            NeoForge.EVENT_BUS.register(new PathRenderer());
            NeoForge.EVENT_BUS.register(new NavHud());
            NeoForge.EVENT_BUS.register(new ClientTickHandler());
            NeoForge.EVENT_BUS.register(new XaeroNavCommands());

            // Modsの一覧からもキーバインド（XaeroNavKeys.OPEN_CONFIG_SCREEN）と同じ画面を開けるようにする
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (modContainer, parent) -> new XaeroNavConfigScreen(parent));
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            XaeroNavKeys.register(event);
        }
    }
}
