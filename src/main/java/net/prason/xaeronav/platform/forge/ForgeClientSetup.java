package net.prason.xaeronav.platform.forge;

//? if forge && >=1.21.11 {
/*import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.FramePassManager;
import net.minecraftforge.client.event.AddFramePassEvent;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.NavCommandSink;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavCommands;
import net.prason.xaeronav.client.XaeroNavKeys;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;

/^* Forge 61のイベントを、ローダー非依存の処理へ繋ぐだけの層。クライアントでだけ読み込まれる。 ^/
final class ForgeClientSetup {

    private ForgeClientSetup() {
    }

    static void register(FMLJavaModLoadingContext context) {
        FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(event -> {
            XaeroNavClient.reloadBlockLists();
            // Modsの一覧からもキーバインド（XaeroNavKeys.OPEN_CONFIG_SCREEN）と同じ画面を開けるようにする
            context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory(XaeroNavConfigScreen::new));
        });
        RegisterKeyMappingsEvent.BUS.addListener(event -> XaeroNavKeys.register(event::register));
        AddGuiOverlayLayersEvent.BUS.addListener(event -> event.getLayeredDraw().add(
                ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "hud"),
                (graphics, deltaTracker) -> XaeroNavClient.HUD.render(graphics)));
        // LevelRendererの生成時に1回だけ発火し、FMLClientSetupEventより早い。だからここで登録しておく
        AddFramePassEvent.BUS.addListener(event -> event.addPass(
                ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "path"), new PathPass()));

        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> XaeroNavClient.TICK_HANDLER.onClientTick());
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(
                event -> XaeroNavClient.TICK_HANDLER.onLoggingIn(event.getPlayer()));
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> XaeroNavClient.TICK_HANDLER.onLoggingOut());
        RegisterClientCommandsEvent.BUS.addListener(event -> event.getDispatcher().register(
                XaeroNavCommands.<CommandSourceStack>tree(ctx -> sink(ctx.getSource()), BlockPosArgument::getBlockPos)));
    }

    /^*
     * ワールドの経路を描くパス。Forgeは追加のパスをバニラの全パスの後に置き、その間もmodelViewには
     * 視点の回転が積まれたままなので、渡す行列は単位行列でよい（Fabricの{@code END_MAIN}と同じ）。
     ^/
    private static final class PathPass implements FramePassManager.PassDefinition {

        @Override
        public void extracts(LevelTargetBundle bundle, FramePass pass, DeltaTracker deltaTracker) {
            // Fabulous!以外ではmainしか無い。描く先もmainだけ（NavRenderTypes）
            bundle.main = pass.readsAndWrites(bundle.main);
        }

        @Override
        public void executes(LevelRenderState state) {
            XaeroNavClient.PATH_RENDERER.render(new PoseStack(), Minecraft.getInstance().gameRenderer.getMainCamera());
        }
    }

    private static NavCommandSink sink(CommandSourceStack source) {
        return new NavCommandSink() {
            @Override
            public void success(Component message) {
                source.sendSuccess(() -> message, false);
            }

            @Override
            public void failure(Component message) {
                source.sendFailure(message);
            }
        };
    }
}
*///?}
