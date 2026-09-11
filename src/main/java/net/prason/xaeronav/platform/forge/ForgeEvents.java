package net.prason.xaeronav.platform.forge;

//? forge {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.prason.xaeronav.client.NavCommandSink;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavCommands;

/^* Forgeのゲームイベントを、ローダー非依存の処理へ繋ぐだけの層。 ^/
public final class ForgeEvents {

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // Forge 1.21.1のRenderLevelStageEventはPoseStackではなくMatrix4fを持つ（Mojang側がGUI描画で
        // PoseStackの受け渡しをやめたため）。PathRendererはPoseStackのpush/pop APIに依存しているので、
        // 単体のPoseStackへ積み直して渡す（回転・並進が乗った行列を1回複製するだけ、毎フレームの負荷は軽い）
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(event.getPoseStack());
        XaeroNavClient.PATH_RENDERER.render(poseStack, event.getCamera());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent.Post event) {
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
*///?}
