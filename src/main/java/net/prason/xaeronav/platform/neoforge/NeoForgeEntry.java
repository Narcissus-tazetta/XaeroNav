package net.prason.xaeronav.platform.neoforge;

//? neoforge {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;
import net.prason.xaeronav.config.ModConfigSpecStore;
import net.prason.xaeronav.config.XaeroNavConfig;

// クライアント専用MOD。dist=CLIENTを付けないと、このエントリポイントが専用サーバー上でも走り、
// クライアント側にしか意味の無いCLIENT configを登録しにいく
@Mod(value = XaeroNav.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeEntry {

    /** 設定画面の登録はクライアント側で行うので、そこまでコンテナを持ち越す。 */
    private static ModContainer container;

    public NeoForgeEntry(IEventBus modEventBus, ModContainer modContainer) {
        XaeroNav.LOGGER.info("XaeroNav initialized");
        container = modContainer;
        modContainer.registerConfig(ModConfig.Type.CLIENT, modConfigSpec());
        modEventBus.addListener(NeoForgeEntry::onConfigReloaded);
        modEventBus.addListener(ClientSetup::onClientSetup);
        modEventBus.addListener(ClientSetup::onRegisterKeyMappings);
    }

    private static net.neoforged.neoforge.common.ModConfigSpec modConfigSpec() {
        return ((ModConfigSpecStore) XaeroNavConfig.store()).modConfigSpec();
    }

    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == modConfigSpec()) {
            XaeroNavClient.reloadBlockLists();
        }
    }

    // クライアント専用クラス（Minecraft/RenderLevelStageEvent等）への参照はFMLClientSetupEvent内に
    // 閉じ込める。@Mod(dist=CLIENT)でガードすることで、専用サーバー上でもこのクラス自体がロードされない。
    // 注釈での購読（@EventBusSubscriber）にしないのは、NeoForge 21.0.xが購読先のバスを自動で選ばず
    // MODバスのイベントを拒否するため。addListenerならバージョンによらずバスを明示できる
    public static final class ClientSetup {

        public static void onClientSetup(FMLClientSetupEvent event) {
            XaeroNavClient.reloadBlockLists();
            NeoForge.EVENT_BUS.register(new NeoForgeEvents());

            // Modsの一覧からもキーバインド（XaeroNavKeys.OPEN_CONFIG_SCREEN）と同じ画面を開けるようにする
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (modContainer, parent) -> new XaeroNavConfigScreen(parent));
        }

        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            //? if >=1.21.11 {
            /*event.registerCategory(XaeroNavKeys.CATEGORY);
            *///?}
            XaeroNavKeys.register(event::register);
        }
    }
}
//?}
