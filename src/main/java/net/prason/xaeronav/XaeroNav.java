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
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.client.ClientTickHandler;
import net.prason.xaeronav.client.PathRenderer;
import net.prason.xaeronav.client.XaeroNavCommands;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.cost.ForbiddenBlocks;

@Mod(XaeroNav.MOD_ID)
public final class XaeroNav {
    public static final String MOD_ID = "xaeronav";
    public static final Logger LOGGER = LogUtils.getLogger();

    public XaeroNav(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("XaeroNav initialized");
        modContainer.registerConfig(ModConfig.Type.CLIENT, XaeroNavConfig.SPEC);
    }

    // クライアント専用クラス（Minecraft/RenderLevelStageEvent等）への参照はFMLClientSetupEvent内に
    // 閉じ込める。dist=CLIENTでガードすることで、専用サーバー上でもこのクラス自体がロードされない。
    @EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static final class ClientSetup {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            ForbiddenBlocks.reloadFromConfig(XaeroNavConfig.INSTANCE.additionalForbiddenBlocks());
            NeoForge.EVENT_BUS.register(new PathRenderer());
            NeoForge.EVENT_BUS.register(new ClientTickHandler());
            NeoForge.EVENT_BUS.register(new XaeroNavCommands());
        }
    }
}
