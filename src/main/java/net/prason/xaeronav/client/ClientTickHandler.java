package net.prason.xaeronav.client;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** design doc §4-6の再計算トリガー（逸脱検知・定期実行）と、案内表示用の実測速度を毎tick駆動する。 */
public final class ClientTickHandler {

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        PathfindingState.INSTANCE.onClientTick();
        ElytraNavState.INSTANCE.onClientTick();
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
        ElytraNavState.INSTANCE.clear();
    }
}
