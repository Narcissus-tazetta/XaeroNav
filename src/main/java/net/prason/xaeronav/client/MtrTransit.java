package net.prason.xaeronav.client;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.transit.MtrLink;
import net.prason.xaeronav.transit.TransitOutcome;
import net.prason.xaeronav.transit.TransitService;
import net.prason.xaeronav.util.GameCompat;
import org.jspecify.annotations.Nullable;

/** MTRの地図サーバーの乗り換え案内（{@link TransitService}）。 */
final class MtrTransit implements TransitProvider {

    private final TransitService service = new TransitService();

    @Override
    public String name() {
        return "MTR";
    }

    @Override
    public boolean present() {
        return MtrLink.present();
    }

    @Override
    public boolean riding(Player player) {
        return MtrLink.riding();
    }

    @Override
    public @Nullable String ask(Minecraft mc, BlockPos from, BlockPos to, Consumer<TransitOutcome> result) {
        int port = MtrLink.port();
        if (port <= 0) {
            return "MTR's map web server is not running";
        }
        String worldId = GameCompat.dimensionId(mc.level.dimension()).replace(':', '/');
        // シングル（LANで開いている側を含む）はシミュレーターの地図サーバーそのもの、それ以外はサーバーへの中継
        boolean relayed = mc.getSingleplayerServer() == null;
        service.ask(port, relayed, worldId, from, to, result);
        return null;
    }

    @Override
    public boolean spotLoaded(Level level, BlockPos board) {
        return BoardingSpot.loaded(level, board);
    }

    @Override
    public BlockPos boardingSpot(Level level, BlockPos board) {
        return BoardingSpot.find(level, board);
    }

    @Override
    public String unavailableMessageKey() {
        return "commands.xaeronav.transit_unavailable";
    }
}
