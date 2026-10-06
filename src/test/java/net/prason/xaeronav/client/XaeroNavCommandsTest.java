package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;

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
    void everySubcommandParsesWithoutThrowing() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav clear", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav version", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav goto 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug probe", source));
    }
}
