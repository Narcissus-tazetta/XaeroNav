package net.prason.xaeronav.client;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.pathfinding.world.BlockRegistryCompat;
import net.prason.xaeronav.transit.CrnLink;
import net.prason.xaeronav.transit.CrnRoutes;
import net.prason.xaeronav.transit.Itinerary;
import net.prason.xaeronav.transit.TransitOutcome;
import org.jspecify.annotations.Nullable;

/**
 * CRN（Create Railways Navigator）の経路検索で、Createの列車の乗り換え案内を引く。
 *
 * <p>CRNの検索は駅の名前どうしなので、出発地と目的地それぞれの最寄り駅をCRNに聞いてから検索する。最寄り駅は直線で
 * 一番近い駅で、歩いて行ける距離かは見ていない（遠すぎれば、歩きより遅い答えとして提案されないだけ）。
 */
final class CrnTransit implements TransitProvider {

    /** 駅のブロックの周りで立つ場所を探す水平の範囲。駅のブロックは線路の隣に置くので、ホームの幅を覆う値で、実測ではない。 */
    private static final int REACH = 3;
    private static final int BELOW = 2;
    private static final int ABOVE = 2;

    @Override
    public String name() {
        return "CRN";
    }

    @Override
    public boolean present() {
        return CrnLink.present();
    }

    @Override
    public boolean riding(Player player) {
        return CrnLink.riding(player);
    }

    @Override
    public @Nullable String ask(Minecraft mc, BlockPos from, BlockPos to, Consumer<TransitOutcome> result) {
        java.util.UUID player = mc.player.getUUID();
        ResourceKey<Level> dimension = mc.level.dimension();
        CompletableFuture<Optional<String>> fromStation = CrnLink.nearestStation(from);
        CompletableFuture<Optional<String>> toStation = CrnLink.nearestStation(to);
        fromStation.thenCombine(toStation, (a, b) -> new String[] {a.orElse(null), b.orElse(null)})
                .thenCompose(names -> {
                    if (names[0] == null || names[1] == null || names[0].equals(names[1])) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return CrnLink.navigate(names[0], names[1], player);
                })
                // 駅の座標はクライアントの線路の網から引くので、クライアントスレッドで読む
                .thenAcceptAsync(journeys -> {
                    if (journeys == null) {
                        result.accept(new TransitOutcome(null, null));
                        return;
                    }
                    Itinerary itinerary = CrnRoutes.choose(journeys, CrnLink.now(), from, to,
                            (name, near) -> CrnLink.station(dimension, name, near));
                    result.accept(new TransitOutcome(itinerary, null));
                }, mc)
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    XaeroNav.LOGGER.warn("XaeroNav: CRN directions request failed: {}", cause.toString());
                    result.accept(new TransitOutcome(null, cause.toString()));
                    return null;
                });
        return null;
    }

    @Override
    public boolean spotLoaded(Level level, BlockPos board) {
        return BoardingSpot.loaded(level, board);
    }

    /** 駅のブロックの近くで、線路の上ではない立てる所。無ければ駅のブロックの位置のまま。 */
    @Override
    public BlockPos boardingSpot(Level level, BlockPos station) {
        BlockPos best = station;
        long bestRank = Long.MAX_VALUE;
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                for (int dy = -BELOW; dy <= ABOVE; dy++) {
                    int x = station.getX() + dx;
                    int y = station.getY() + dy;
                    int z = station.getZ() + dz;
                    if (!PathfindingState.standableAt(level, x, y, z) || createTrack(level, x, y, z)
                            || createTrack(level, x, y - 1, z)) {
                        continue;
                    }
                    long rank = ((long) (dx * dx + dz * dz) << 8) | Math.abs(dy);
                    if (rank < bestRank) {
                        bestRank = rank;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    private static boolean createTrack(Level level, int x, int y, int z) {
        String id = BlockRegistryCompat.keyOf(level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
        // 線路（create:track）と、線路の上に置く駅・信号などの目印（create:track_station 等）
        return id.startsWith("create:track");
    }

    @Override
    public String unavailableMessageKey() {
        return "commands.xaeronav.transit_unavailable_crn";
    }
}
