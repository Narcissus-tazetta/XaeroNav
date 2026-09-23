package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.platform.ModPresence;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.xaero.XaeroHookHealth;
import net.prason.xaeronav.xaero.XaeroHookProbe;
import net.prason.xaeronav.xaero.XaeroHookRuntimeProbe;
import net.prason.xaeronav.xaero.XaeroHooks;

/** 再計算トリガー（逸脱検知・定期実行）と、案内表示用の実測速度を毎tick駆動する。 */
public final class ClientTickHandler {

    private static final boolean RUNTIME_HOOK_PROBE = Boolean.getBoolean(XaeroHookProbe.PROPERTY);

    /** tickが「遅い」とみなす所要時間。1tick(20TPS)相当。 */
    private static final long SLOW_TICK_THRESHOLD_MILLIS = 50L;
    /** 遅いtickが続く間、警告を再度出すまでの間隔。毎回だとログが洪水になる。 */
    private static final long SLOW_TICK_LOG_INTERVAL_MILLIS = 5_000L;

    /** 連携の欠落を知らせたか。ワールドへ入るたびに繰り返すと、直しようが無い警告を毎回読ませることになる。 */
    private boolean hookNoticeShown;

    private final ChangeGate<Boolean> slowTickGate = new ChangeGate<>();

    public void onClientTick() {
        long startMillis = MonotonicTime.millis();
        XaeroNavKeys.handleInput();
        PathfindingState.INSTANCE.onClientTick();
        NavPace.INSTANCE.onClientTick();
        XaeroHookHealth.onClientTick();
        // XaeroHookRuntimeProbeはXaero型を直接参照するため、通常起動ではクラス自体をloadしない。
        if (RUNTIME_HOOK_PROBE) {
            XaeroHookRuntimeProbe.onClientTick();
        }
        long nowMillis = MonotonicTime.millis();
        long elapsedMillis = nowMillis - startMillis;
        if (elapsedMillis > SLOW_TICK_THRESHOLD_MILLIS
                && slowTickGate.changed(true, nowMillis, SLOW_TICK_LOG_INTERVAL_MILLIS)) {
            XaeroNav.LOGGER.warn("XaeroNav: tick処理が遅い ({}ms > {}ms)", elapsedMillis, SLOW_TICK_THRESHOLD_MILLIS);
        }
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
    public void onLoggingOut() {
        PathfindingState.INSTANCE.clear();
    }

    public void onLoggingIn(LocalPlayer player) {
        reportMissingXaeroHooks(player);
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
        for (XaeroHooks.Hook hook : XaeroHooks.Hook.values()) {
            if (ModPresence.isLoaded(hook.modId()) && XaeroHooks.applied(hook)) {
                // CIが「失敗文字列が無い」だけでなく、各hookの実適用をpositiveに検査するマーカー。
                XaeroNav.LOGGER.info("XAERONAV_HOOK_APPLIED {}", hook.name());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        MutableComponent features = TextCompat.empty();
        for (XaeroHooks.Hook hook : missing) {
            if (!features.getSiblings().isEmpty()) {
                features.append(" / ");
            }
            features.append(TextCompat.translatable(hook.nameKey()));
            XaeroNav.LOGGER.warn("XaeroNav: Xaero連携のmixinが当たっていない ({} / {})。"
                    + "Xaeroの版が対応範囲の外にある可能性がある", hook.modId(), hook.className());
        }
        player.displayClientMessage(TextCompat.translatable("hud.xaeronav.hook_missing", features), false);
    }
}
