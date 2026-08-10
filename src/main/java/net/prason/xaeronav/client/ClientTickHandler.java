package net.prason.xaeronav.client;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** design doc §4-6の再計算トリガー（逸脱検知・定期実行）を毎tick駆動する。 */
public final class ClientTickHandler {

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        PathfindingState.INSTANCE.onClientTick();
    }
}
