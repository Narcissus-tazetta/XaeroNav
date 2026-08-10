package net.prason.xaeronav.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.elytra.ElytraPath;
import net.prason.xaeronav.pathfinding.elytra.ElytraPathfinder;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.WorldSnapshot;

/**
 * design doc §5。徒歩用の{@link PathfindingState}とは別系統（連続座標・単発計算）。
 * MVPでは§5-3の通りユーザーが明示的に{@code /xaeronav flyto}で選択する方式とし、
 * 徒歩系のような逸脱検知・定期再計算（§4-6）は行わない。
 */
public final class ElytraNavState {

    public static final ElytraNavState INSTANCE = new ElytraNavState();

    private static final int MARGIN_BLOCKS = 32;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-elytra");
        thread.setDaemon(true);
        return thread;
    });

    // clear()・新規requestPath()のたびに増分し、非同期結果の適用直前に照合する
    // （clear後に古い計算結果がcurrentPathを復活させてしまう競合を防ぐ。PathfindingStateと同じ仕組み）。
    private final AtomicLong generation = new AtomicLong();

    private volatile ElytraPath currentPath;

    private ElytraNavState() {
    }

    public ElytraPath currentPath() {
        return currentPath;
    }

    public void clear() {
        generation.incrementAndGet();
        currentPath = null;
    }

    public void requestPath(BlockPos goalBlock) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return;
        }

        Vec3 start = player.position();
        Vec3 goal = Vec3.atCenterOf(goalBlock);
        BlockPos startBlock = player.blockPosition();

        SearchBounds bounds = SearchBounds.around(startBlock, goalBlock, MARGIN_BLOCKS, MARGIN_BLOCKS);
        // 飛行判定に掘削は無関係なのでdiggingEnabled=falseにして軽量にスナップショットする
        WorldSnapshot snapshot = WorldSnapshot.capture(level, player, bounds, false);

        long myGeneration = generation.incrementAndGet();
        CompletableFuture.supplyAsync(() -> new ElytraPathfinder(snapshot).findPath(start, goal), executor)
                .thenAccept(result -> {
                    if (generation.get() == myGeneration) {
                        currentPath = result;
                    }
                })
                .exceptionally(ex -> null);
    }
}
