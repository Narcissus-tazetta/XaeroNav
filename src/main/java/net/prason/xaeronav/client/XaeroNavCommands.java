package net.prason.xaeronav.client;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /xaeronav goto <pos>}（徒歩・掘削） / {@code /xaeronav flyto <pos>}（エリトラ、design doc §5-3の
 * 明示的モード選択） / {@code /xaeronav clear}。
 * Xaeroの右クリックメニュー等からの目的地設定はPhase 2後半（Xaeroアダプタ層）で追加する想定の暫定UI。
 */
public final class XaeroNavCommands {

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("xaeronav")
                .then(Commands.literal("goto")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    PathfindingState.INSTANCE.setGoal(pos);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("XaeroNav: 目的地を設定(徒歩) " + pos.toShortString()), false);
                                    return 1;
                                })))
                .then(Commands.literal("flyto")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                    ElytraNavState.INSTANCE.requestPath(pos);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("XaeroNav: 目的地を設定(エリトラ) " + pos.toShortString()), false);
                                    warnMissingFlightGear(ctx.getSource());
                                    return 1;
                                })))
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            PathfindingState.INSTANCE.clear();
                            ElytraNavState.INSTANCE.clear();
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("XaeroNav: 経路をクリア"), false);
                            return 1;
                        })));
    }

    /**
     * エリトラ経路は「地形の上まで高度を上げて越える」前提で引く。ところがエリトラは
     * ロケット花火が無ければ上昇できず、滑空で下るぶんしか進めない。線だけ引いても
     * 辿れないので、実行できない前提が欠けていることはその場で伝える。
     */
    private static void warnMissingFlightGear(CommandSourceStack source) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !ElytraItem.isFlyEnabled(chest)) {
            source.sendFailure(Component.literal("XaeroNav: 飛べるエリトラを装備していません"));
        }
        if (!player.getInventory().contains(stack -> stack.is(Items.FIREWORK_ROCKET))) {
            source.sendFailure(Component.literal(
                    "XaeroNav: ロケット花火がありません。上昇できないので、滑空で下れる範囲しか辿れません"));
        }
    }
}
