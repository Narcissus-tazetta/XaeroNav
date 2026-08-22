package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.xaero.XaeroHooks;

/** design doc §4-6の再計算トリガー（逸脱検知・定期実行）と、案内表示用の実測速度を毎tick駆動する。 */
public final class ClientTickHandler {

    /** 連携の欠落を知らせたか。ワールドへ入るたびに繰り返すと、直しようが無い警告を毎回読ませることになる。 */
    private boolean hookNoticeShown;

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        XaeroNavKeys.handleInput();
        PathfindingState.INSTANCE.onClientTick();
        NavPace.INSTANCE.onClientTick();
    }

    /**
     * ワールドから抜けるときに経路と目的地を捨てる。
     *
     * <p>{@link PathfindingState#onClientTick}は{@code level == null}で何もせずに戻るだけなので、
     * 切断してもゴールと経路はそのまま残る。次に別のワールドへ入ると、前のワールドの座標を目指す
     * 案内が復活する（次元の違いは見ているが、同じ次元の別サーバーは見分けられない）。
     *
     * <p>経路が持つ{@code ChunkView}は探索範囲ぶんのチャンク参照を掴んでいるので、
     * ここで捨てることでワールドのアンロードを妨げなくなる意味もある。
     */
    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PathfindingState.INSTANCE.clear();
    }

    @SubscribeEvent
    public void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        reportMissingXaeroHooks(event.getPlayer());
    }

    /**
     * Xaeroは入っているのに連携が当たっていないことを、ゲーム起動につき1度だけ知らせる。
     *
     * <p>当たらなかったmixinは何も言わずに消える（required=false）ので、ユーザーには
     * 「地図に線が出ない」としか見えない。Xaeroが注入先の形を変えた新版でこうなるが、その状態でも
     * ワールド内描画は動いているため、故障だと気付かないまま使い続けることになる。
     *
     * <p>ワールドへ入る時点で出すのは、チャットへ書ける最初の機会がここだから。判定に使う
     * {@code Class.forName}はXaeroのクラスを読み込むので、MODの読み込み中には行わない。
     */
    private void reportMissingXaeroHooks(LocalPlayer player) {
        if (hookNoticeShown) {
            return;
        }
        hookNoticeShown = true;
        List<XaeroHooks.Hook> missing = XaeroHooks.missing();
        if (missing.isEmpty()) {
            return;
        }
        MutableComponent features = Component.empty();
        for (XaeroHooks.Hook hook : missing) {
            if (!features.getSiblings().isEmpty()) {
                features.append(" / ");
            }
            features.append(Component.translatable(hook.nameKey()));
            XaeroNav.LOGGER.warn("XaeroNav: Xaero連携のmixinが当たっていない ({} / {})。"
                    + "Xaeroの版が対応範囲の外にある可能性がある", hook.modId(), hook.className());
        }
        player.displayClientMessage(Component.translatable("hud.xaeronav.hook_missing", features), false);
    }
}
