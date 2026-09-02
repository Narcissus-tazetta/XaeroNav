package net.prason.xaeronav.platform.neoforge;

//? neoforge {
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.prason.xaeronav.client.NavCommandSink;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavCommands;

/** NeoForgeのゲームイベントを、ローダー非依存の処理へ繋ぐだけの層。 */
public final class NeoForgeEvents {

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        XaeroNavClient.PATH_RENDERER.render(event.getPoseStack(), event.getCamera());
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        XaeroNavClient.HUD.render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        XaeroNavClient.TICK_HANDLER.onClientTick();
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        XaeroNavClient.TICK_HANDLER.onLoggingIn(event.getPlayer());
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        XaeroNavClient.TICK_HANDLER.onLoggingOut();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(XaeroNavCommands.<CommandSourceStack>tree(
                ctx -> sink(ctx.getSource()), BlockPosArgument::getBlockPos));
    }

    private static NavCommandSink sink(CommandSourceStack source) {
        return new NavCommandSink() {
            @Override
            public void success(net.minecraft.network.chat.Component message) {
                source.sendSuccess(() -> message, false);
            }

            @Override
            public void failure(net.minecraft.network.chat.Component message) {
                source.sendFailure(message);
            }
        };
    }
}
//?}
