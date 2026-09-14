package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * コマンドツリーの構文境界だけを見る。{@code goto}/{@code version}/{@code hooks}等の実行
 * ({@code execute}まで進める)は{@code PathfindingState.INSTANCE}や{@code ModList}等の実
 * Minecraft/loader状態に触れるため対象外——{@code parse}だけならそれらに触れずに構文だけ検証できる
 * （xaeronav.common.gradle.ktsの「テストはレジストリを起動しなくても動く範囲に留める」方針に沿う）。
 */
class XaeroNavCommandsTest {

    private static final class FakeSink implements NavCommandSink {
        final List<Component> successes = new ArrayList<>();
        final List<Component> failures = new ArrayList<>();

        @Override
        public void success(Component message) {
            successes.add(message);
        }

        @Override
        public void failure(Component message) {
            failures.add(message);
        }
    }

    private static CommandDispatcher<Object> newDispatcher() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(XaeroNavCommands.tree(ctx -> new FakeSink(),
                (ctx, name) -> BlockPos.ZERO));
        return dispatcher;
    }

    @Test
    void mapdataAcceptsTheDefaultAndMaximumRadius() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug mapdata", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug mapdata 128", source));
    }

    @Test
    void mapdataRejectsARadiusBeyondTheUpperBound() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        // IntegerArgumentTypeの範囲チェックはparseの構文解析自体では効かず、実行(execute)して
        // 初めてCommandSyntaxExceptionになる。mapdataの実処理（Xaero地図読み取り）には
        // 到達しない——この境界チェックはコマンドの中身より前で必ず弾かれるため安全に実行できる。
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("xaeronav debug mapdata 129", source));
    }

    @Test
    void mapdataRejectsARadiusOfZero() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("xaeronav debug mapdata 0", source));
    }

    @Test
    void everySubcommandParsesWithoutThrowing() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav clear", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav version", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug hooks", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug summary", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav goto 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug route 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug corridor 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug probe 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug flight 0 64 0", source));
    }
}
