package net.prason.xaeronav.platform.forge;

//? forge {
/*//? if >=1.21 {
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
//?} else {
/^import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
^///?}
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;
import net.prason.xaeronav.config.ForgeConfigSpecStore;
import net.prason.xaeronav.config.XaeroNavConfig;

@Mod(XaeroNav.MOD_ID)
public final class ForgeEntry {

    // 設定画面の登録はFMLClientSetupEvent内で行うので、そこまでコンテキストを持ち越す
    private static FMLJavaModLoadingContext context;

    public ForgeEntry(FMLJavaModLoadingContext context) {
        XaeroNav.LOGGER.info("XaeroNav initialized");
        ForgeEntry.context = context;
        context.registerConfig(ModConfig.Type.CLIENT, forgeConfigSpec());
        context.getModEventBus().addListener(ForgeEntry::onConfigReloaded);
    }

    private static ForgeConfigSpec forgeConfigSpec() {
        return ((ForgeConfigSpecStore) XaeroNavConfig.store()).forgeConfigSpec();
    }

    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == forgeConfigSpec()) {
            XaeroNavClient.reloadBlockLists();
        }
    }

    // クライアント専用クラス（Minecraft/RenderLevelStageEvent等）への参照はFMLClientSetupEvent内に
    // 閉じ込める。dist=CLIENTでガードすることで、専用サーバー上でもこのクラス自体がロードされない
    // （NeoForgeEntryと同じ構造。Forgeの@Modにはdist引数が無いのでここでガードする）。
    // bus=MODは明示が要る——NeoForgeと違いForgeの@EventBusSubscriberは既定がFORGE busで、
    // 省略するとFMLClientSetupEvent/RegisterKeyMappingsEvent/AddGuiOverlayLayersEvent
    // （すべてmod event busでしか発火しない）が一切呼ばれない
    @Mod.EventBusSubscriber(modid = XaeroNav.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientSetup {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            XaeroNavClient.reloadBlockLists();
            MinecraftForge.EVENT_BUS.register(new ForgeEvents());

            // Modsの一覧からもキーバインド（XaeroNavKeys.OPEN_CONFIG_SCREEN）と同じ画面を開けるようにする
            context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(
                            parent -> new XaeroNavConfigScreen(parent)));
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            XaeroNavKeys.register(event::register);
        }

        // ForgeにはNeoForgeのRenderGuiEvent.Postが無い。HUD描画をオーバーレイとして登録する形で
        // 差し込む（ForgeとNeoForge/Fabricの構造差はここだけ）。登録イベント自体が1.21.1と1.20.1で
        // 別クラス（AddGuiOverlayLayersEvent / RegisterGuiOverlaysEvent）かつシグネチャも違う
        @SubscribeEvent
        //? if >=1.21 {
        public static void onAddGuiOverlayLayers(AddGuiOverlayLayersEvent event) {
            event.getLayeredDraw().add(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "hud"),
                    (graphics, partialTick) -> XaeroNavClient.HUD.render(graphics));
        }
        //?} else {
        /^public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("hud",
                    (gui, graphics, partialTick, screenWidth, screenHeight) -> XaeroNavClient.HUD.render(graphics));
        }
        ^///?}
    }
}
*///?}
