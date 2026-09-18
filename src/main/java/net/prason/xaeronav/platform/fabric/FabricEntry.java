package net.prason.xaeronav.platform.fabric;

//? fabric {
/*import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.NavCommandSink;
import net.prason.xaeronav.client.XaeroNavClient;
import net.prason.xaeronav.client.XaeroNavCommands;
import net.prason.xaeronav.client.XaeroNavKeys;

/^* Fabricのイベントを、ローダー非依存の処理へ繋ぐだけの層。 ^/
public final class FabricEntry implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        XaeroNav.LOGGER.info("XaeroNav initialized");
        XaeroNavClient.reloadBlockLists();

        XaeroNavKeys.register(KeyBindingHelper::registerKeyBinding);

        ClientTickEvents.END_CLIENT_TICK.register(client -> XaeroNavClient.TICK_HANDLER.onClientTick());
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> XaeroNavClient.TICK_HANDLER.onLoggingIn(client.player));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> XaeroNavClient.TICK_HANDLER.onLoggingOut());

        WorldRenderEvents.AFTER_TRANSLUCENT.register(
                context -> XaeroNavClient.PATH_RENDERER.render(context.matrixStack(), context.camera()));
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> XaeroNavClient.HUD.render(graphics));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(XaeroNavCommands.<FabricClientCommandSource>tree(
                        ctx -> sink(ctx.getSource()), FabricEntry::blockPos)));
    }

    /^*
     * `~`相対座標の解決には{@code CommandSourceStack}が要るが、Fabricのクライアントコマンドの
     * sourceはそれではない。プレイヤーから作った{@code CommandSourceStack}で代用する
     * ——{@code WorldCoordinates}が見るのは位置と向きだけで、ワールドやサーバーには触らない。
     ^/
    private static BlockPos blockPos(CommandContext<FabricClientCommandSource> ctx, String name) {
        return ctx.getArgument(name, Coordinates.class)
                .getBlockPos(ctx.getSource().getPlayer().createCommandSourceStack());
    }

    private static NavCommandSink sink(FabricClientCommandSource source) {
        return new NavCommandSink() {
            @Override
            public void success(Component message) {
                source.sendFeedback(message);
            }

            @Override
            public void failure(Component message) {
                source.sendError(message);
            }
        };
    }
}
*///?}
