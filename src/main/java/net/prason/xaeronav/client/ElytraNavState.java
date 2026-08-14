package net.prason.xaeronav.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.elytra.ElytraPath;
import net.prason.xaeronav.pathfinding.elytra.ElytraPathfinder;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * design doc §5。徒歩用の{@link PathfindingState}とは別系統（連続座標・単発計算）。
 * MVPでは§5-3の通りユーザーが明示的に{@code /xaeronav flyto}で選択する方式とし、
 * 徒歩系のような逸脱検知・定期再計算（§4-6）は行わない。
 */
final class ElytraNavState {

    static final ElytraNavState INSTANCE = new ElytraNavState();

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int HORIZONTAL_MARGIN_BLOCKS = 32;

    /**
     * 垂直方向のマージン。飛行経路に必要な高さは出発点・目的地のYではなく、途中の山の高さで決まる。
     * 水平と同じ32マスにすると尾根を越える高度が探索箱の外になり、越えられるはずの山を
     * 「越えられないので迂回」と判断してしまう。上限はビルド高度で頭打ちになる。
     */
    private static final int VERTICAL_MARGIN_BLOCKS = 192;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-elytra");
        thread.setDaemon(true);
        return thread;
    });

    // clear()・新規requestPath()のたびに増分し、非同期結果の適用直前に照合する
    // （clear後に古い計算結果がcurrentPathを復活させてしまう競合を防ぐ。PathfindingStateと同じ仕組み）。
    private final AtomicLong generation = new AtomicLong();

    private volatile ElytraPath currentPath;
    // 経路を出した次元。座標だけを覚えていると、次元をまたいだあとも同じ線を描き続けてしまう
    private volatile ResourceKey<Level> pathDimension;

    private ElytraNavState() {
    }

    ElytraPath currentPath() {
        return currentPath;
    }

    void clear() {
        generation.incrementAndGet();
        currentPath = null;
        pathDimension = null;
    }

    void onClientTick() {
        Level level = Minecraft.getInstance().level;
        if (currentPath != null && (level == null || level.dimension() != pathDimension)) {
            clear();
        }
    }

    void requestPath(BlockPos goalBlock) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return;
        }

        // 徒歩経路が出たままだと、行き先の違う2本の線が同時に描かれる（PathfindingState側と対）
        PathfindingState.INSTANCE.clear();

        Vec3 start = player.position();
        Vec3 goal = Vec3.atCenterOf(goalBlock);
        BlockPos startBlock = player.blockPosition();

        SearchBounds bounds = SearchBounds.around(level, startBlock, goalBlock,
                HORIZONTAL_MARGIN_BLOCKS, VERTICAL_MARGIN_BLOCKS,
                mc.options.getEffectiveRenderDistance() * 16);
        // 飛行判定に掘削・ブロック設置・隙間跳びはどれも無関係なので全てfalseにしておく
        ChunkView view = ChunkView.capture(level, player, bounds, false, false, false);

        long myGeneration = generation.incrementAndGet();
        pathDimension = level.dimension();
        CompletableFuture.supplyAsync(() -> new ElytraPathfinder(view).findPath(start, goal), executor)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        LOGGER.error("XaeroNav: エリトラ経路の計算に失敗しました", error);
                        return;
                    }
                    if (generation.get() == myGeneration) {
                        currentPath = result;
                    }
                });
    }
}
