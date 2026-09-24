package net.prason.xaeronav.platform.fabric;

//? fabric {
/*import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
//? if >=1.17 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//?}
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//? if >=1.21.11 {
/^import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
^///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//?}
import net.minecraft.commands.arguments.coordinates.Coordinates;
//? if >=1.21.11 {
/^import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.PermissionSet;
^///?}
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

        //? if >=1.21.11 {
        /^// 半透明の地形まで描き終えた後。以前のAFTER_TRANSLUCENTに当たる
        WorldRenderEvents.END_MAIN.register(context -> XaeroNavClient.PATH_RENDERER.render(
                context.matrices(), Minecraft.getInstance().gameRenderer.getMainCamera()));
        HudElementRegistry.addLast(ResourceLocation.fromNamespaceAndPath(XaeroNav.MOD_ID, "hud"),
                (graphics, tickCounter) -> XaeroNavClient.HUD.render(graphics));
        ^///?} else {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(
                context -> XaeroNavClient.PATH_RENDERER.render(context.matrixStack(), context.camera()));
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> XaeroNavClient.HUD.render(graphics));
        //?}

        //? if >=1.17 {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(XaeroNavCommands.<FabricClientCommandSource>tree(
                        ctx -> sink(ctx.getSource()), FabricEntry::blockPos)));
        //?}
    }

    /^*
     * `~`相対座標の解決には{@code CommandSourceStack}が要るが、Fabricのクライアントコマンドの
     * sourceはそれではない。プレイヤーから作った{@code CommandSourceStack}で代用する
     * ——{@code WorldCoordinates}が見るのは位置と向きだけで、ワールドやサーバーには触らない。
     ^/
    //? if >=1.17 {
    private static BlockPos blockPos(CommandContext<FabricClientCommandSource> ctx, String name) {
        //? if >=1.21.11 {
        /^// プレイヤーからCommandSourceStackを作る口がサーバー側（ServerLevelを要る）にしか無くなった。
        // 座標の解決が読むのは位置・向き・エンティティだけなので、それだけを持たせて組み立てる
        FabricClientCommandSource source = ctx.getSource();
        CommandSourceStack stack = new CommandSourceStack(CommandSource.NULL, source.getPosition(), source.getRotation(),
                null, PermissionSet.NO_PERMISSIONS, "", Component.empty(), null, source.getPlayer());
        return ctx.getArgument(name, Coordinates.class).getBlockPos(stack);
        ^///?} else {
        return ctx.getArgument(name, Coordinates.class)
                .getBlockPos(ctx.getSource().getPlayer().createCommandSourceStack());
        //?}
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
    //?}
}
*///?}
