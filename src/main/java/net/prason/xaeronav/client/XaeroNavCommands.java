package net.prason.xaeronav.client;

import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.platform.ModPresence;

/**
 * {@code /xaeronav} のクライアントコマンド。
 *
 * <p>案内そのものに使うのは{@code goto} / {@code clear} / {@code version}の3つだけ。{@code debug}は
 * 不具合報告用の状態の一覧（{@link DebugReport}）、{@code debug probe}はいまの目的地に向けて探索を
 * 測り直す計測用（{@link DiagnosticProbe}）。どちらも座標を取らない——報告する人に「どの座標で打てばいいか」を考えさせない。
 */
public final class XaeroNavCommands {

    private XaeroNavCommands() {
    }

    /**
     * ローダーが持つdispatcherへ載せるコマンドツリー。
     *
     * <p>ツリーの中身はローダーに依存しないが、brigadierのsource型は依存する
     * （NeoForgeは{@code CommandSourceStack}、Fabricは{@code FabricClientCommandSource}）。
     * source型を型引数にし、実際にsourceへ触る2つの操作——応答の宛先と座標引数の解決——だけを
     * 呼び出し側から受け取る。
     */
    public static <S> LiteralArgumentBuilder<S> tree(Function<CommandContext<S>, NavCommandSink> sink,
            BlockPosReader<S> blockPos) {
        return XaeroNavCommands.<S>literal("xaeronav")
                .then(XaeroNavCommands.<S>literal("goto")
                        .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> {
                                    reportGoal(sink.apply(ctx),
                                            PathfindingState.INSTANCE.setGoal(blockPos.read(ctx, "pos")));
                                    return 1;
                                })))
                .then(XaeroNavCommands.<S>literal("via")
                        .then(XaeroNavCommands.<S>literal("add")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportStop(sink.apply(ctx),
                                                PathfindingState.INSTANCE.addStop(blockPos.read(ctx, "pos"))))))
                        .then(XaeroNavCommands.<S>literal("append")
                                .then(XaeroNavCommands.<S, Coordinates>argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> reportStop(sink.apply(ctx),
                                                PathfindingState.INSTANCE.appendStop(blockPos.read(ctx, "pos"))))))
                        .then(XaeroNavCommands.<S>literal("list")
                                .executes(ctx -> listStops(sink.apply(ctx))))
                        .then(XaeroNavCommands.<S>literal("remove")
                                .then(XaeroNavCommands.<S, Integer>argument("number",
                                                IntegerArgumentType.integer(1, RouteStops.MAX_STOPS))
                                        .executes(ctx -> removeStop(sink.apply(ctx),
                                                IntegerArgumentType.getInteger(ctx, "number"))))))
                .then(XaeroNavCommands.<S>literal("clear")
                        .executes(ctx -> {
                            PathfindingState.INSTANCE.clear();
                            sink.apply(ctx).success(TextCompat.translatable("commands.xaeronav.cleared"));
                            return 1;
                        }))
                .then(XaeroNavCommands.<S>literal("version")
                        .executes(ctx -> {
                            sink.apply(ctx).success(
                                     TextCompat.translatable("commands.xaeronav.version", modVersion()));
                            return 1;
                        }))
                .then(XaeroNavCommands.<S>literal("debug")
                        .executes(ctx -> {
                            DebugReport.send(sink.apply(ctx));
                            return 1;
                        })
                        .then(XaeroNavCommands.<S>literal("probe")
                                .executes(ctx -> DiagnosticProbe.run(sink.apply(ctx)))));
    }

    static void reportGoal(NavCommandSink out, @Nullable BlockPos resolved) {
        // 指定座標ではなく解決後の目的地を出す。Yはその列で実際に立てる高さへ
        // 寄せられるので、指定したままを表示すると案内先と食い違って見える
        if (resolved != null) {
            out.success(TextCompat.translatable("commands.xaeronav.goal_walk", resolved.toShortString()));
        }
    }

    static int reportStop(NavCommandSink out, PathfindingState.StopResult result) {
        switch (result) {
            case STARTED -> out.success(TextCompat.translatable("commands.xaeronav.goal_walk",
                    PathfindingState.INSTANCE.goal().toShortString()));
            case ADDED -> listStops(out);
            // 上限に当たったことはPathfindingStateがチャットへ出している
            case FULL, NO_WORLD -> {
            }
        }
        return result == PathfindingState.StopResult.STARTED || result == PathfindingState.StopResult.ADDED ? 1 : 0;
    }

    private static int listStops(NavCommandSink out) {
        BlockPos finalGoal = PathfindingState.INSTANCE.finalGoal();
        if (finalGoal == null) {
            out.failure(TextCompat.translatable("commands.xaeronav.probe_no_goal"));
            return 0;
        }
        List<BlockPos> stops = PathfindingState.INSTANCE.stopsInOrder();
        if (stops.isEmpty()) {
            out.success(TextCompat.translatable("commands.xaeronav.stops_none"));
        }
        for (int i = 0; i < stops.size(); i++) {
            out.success(TextCompat.translatable("commands.xaeronav.stop_entry", i + 1, stops.get(i).toShortString()));
        }
        out.success(TextCompat.translatable("commands.xaeronav.stop_final", finalGoal.toShortString()));
        return 1;
    }

    private static int removeStop(NavCommandSink out, int number) {
        List<BlockPos> stops = PathfindingState.INSTANCE.stopsInOrder();
        if (number > stops.size()) {
            out.failure(TextCompat.translatable("commands.xaeronav.stop_not_found", number));
            return 0;
        }
        PathfindingState.INSTANCE.removeStop(stops.get(number - 1));
        return listStops(out);
    }

    /**
     * {@code pos}引数からブロック座標を取り出す。
     *
     * <p>引数型（{@link BlockPosArgument}）自体はsource型を問わないが、`~`相対座標の解決には
     * {@code CommandSourceStack}が要るので、そこだけローダー側に任せる。
     */
    @FunctionalInterface
    public interface BlockPosReader<S> {
        BlockPos read(CommandContext<S> ctx, String name);
    }

    private static <S> LiteralArgumentBuilder<S> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <S, T> RequiredArgumentBuilder<S, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    /** 実機デバッグ用: 今読み込まれているビルドがどのgitコミットかを確認する（ビルド時にmod_versionへ埋め込み済み）。 */
    private static String modVersion() {
        return ModPresence.version(XaeroNav.MOD_ID);
    }
}
