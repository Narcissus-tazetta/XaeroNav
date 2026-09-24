package net.prason.xaeronav.platform.forge;

//? if forge && >=1.21.11 {
/*import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.config.ForgeConfigSpecStore;
import net.prason.xaeronav.config.XaeroNavConfig;

/^*
 * Forge 61（Minecraft 1.21.11）の入口。EventBus 7でイベントがそれぞれ自分のバスを持つ形に変わったので、
 * 注釈で購読する{@code ForgeEntry}とは別に、各バスへ明示的に登録する。
 ^/
@Mod(XaeroNav.MOD_ID)
public final class ForgeMod {

    public ForgeMod(FMLJavaModLoadingContext context) {
        XaeroNav.LOGGER.info("XaeroNav initialized");
        context.registerConfig(ModConfig.Type.CLIENT, forgeConfigSpec());
        ModConfigEvent.Reloading.getBus(context.getModBusGroup()).addListener(ForgeMod::onConfigReloaded);
        // Forgeの@Modにはdist指定が無く、専用サーバーでもここは呼ばれる。クライアントのクラスを
        // 触る登録はForgeClientSetupへ分け、サーバーではそのクラス自体を読み込ませない
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientSetup.register(context);
        }
    }

    private static ForgeConfigSpec forgeConfigSpec() {
        return ((ForgeConfigSpecStore) XaeroNavConfig.store()).forgeConfigSpec();
    }

    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == forgeConfigSpec()) {
            XaeroNavClient.reloadBlockLists();
        }
    }
}
*///?}
