package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.BlockDistance;
import net.prason.xaeronav.util.DaemonThreads;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.xaero.XaeroPresence;

/**
 * 経由地より先の区間を、そこへ着く前から見せる。
 *
 * <ul>
 *   <li>地図: 今の目的地→次の地点→…→最終目的地の各区間に長距離ルート（Xaeroの地図）を引き、点線で結ぶ</li>
 *   <li>ワールド内: 今の区間の経路が経由地まで届いたら、経由地から次の地点への詳細経路を先に解いておく。
 *       経由地に着いて次の区間の探索が終わるまでの間もこれを出すので、線が途切れない</li>
 * </ul>
 *
 * <p>どちらも見せるためだけのもので、案内の判断（到着・逸脱・引き直し）には使わない。本番の探索・長距離ルートとは
 * 別のスレッドで解く——同じ実行器へ投げると{@link PathfindingExecutor#submit}が走っている本番の探索を打ち切る。
 * <b>メインスレッド専用</b>（描画からは{@link #mapLegs}・{@link #preview}の不変な値だけを読む）。
 */
final class LegPreview {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 地図がまだ読み込まれていなかった区間を引き直す間隔と回数。未訪問の場所は待っても増えないので回数で切る。 */
    private static final long COARSE_RETRY_MILLIS = 15_000L;
    private static final int COARSE_RETRY_LIMIT = 4;
    /** 経由地の高さを寄せ直す（木の上→根元など）と始点が少し動く。この範囲なら同じ区間の長距離ルートを使い回す。 */
    private static final int SAME_FROM_BLOCKS = 8;
    /** 先の経路が途中までだったとき、プレイヤーがこれだけ動いたら読み込みが進んだと見て解き直す。 */
    private static final int PREVIEW_RETRY_MOVE_BLOCKS = 64;

    private final Consumer<Runnable> onMainThread;
    private final ExecutorService coarseExecutor = DaemonThreads.singleThread("xaeronav-leg-preview");
    private final PathfindingExecutor detailExecutor = new PathfindingExecutor();

    /** 区間の行き先（指定された座標）ごとの長距離ルート。 */
    private final Map<BlockPos, CoarseLeg> coarse = new HashMap<>();
    private @Nullable BlockPos solvingCoarseTo;

    private volatile List<List<BlockPos>> mapLegs = List.of();
    private volatile @Nullable Preview preview;
    private @Nullable PreviewRequest previewRequest;
    /** 古い要求の結果を捨てるための世代。長距離ルートと詳細経路で別々に持つ（片方の要求で他方の結果を捨てないため）。 */
    private long coarseGeneration;
    private long detailGeneration;

    /** 長距離ルートの1区間。{@code waypoints}は目的地まで届いたときだけ中身があり、最後が行き先そのもの。 */
    private record CoarseLeg(BlockPos from, List<BlockPos> waypoints, int pendingRegions, long solvedAtMillis,
                             int attempts) {
    }

    /**
     * 経由地から次の地点への詳細経路。{@code from}は区間の始点（立てる高さへ寄せた経由地）、{@code to}は次の地点の
     * 指定された座標。
     */
    record Preview(BlockPos from, BlockPos to, PathResult result) {
    }

    private record PreviewRequest(BlockPos from, BlockPos to, BlockPos playerAt) {
    }

    LegPreview(Consumer<Runnable> onMainThread) {
        this.onMainThread = onMainThread;
    }

    /** 地図に描く区間の折れ線。各要素の先頭が区間の始点で、最後が行き先。 */
    List<List<BlockPos>> mapLegs() {
        return mapLegs;
    }

    @Nullable Preview preview() {
        return preview;
    }

    void clear() {
        coarseGeneration++;
        detailGeneration++;
        coarse.clear();
        solvingCoarseTo = null;
        mapLegs = List.of();
        preview = null;
        previewRequest = null;
        detailExecutor.cancelAll();
    }

    /**
     * 毎tick呼ぶ。
     *
     * @param legFrom       今の区間の終点（立てる高さへ寄せた今の目的地）
     * @param legRequested  今の区間の終点として指定された座標
     * @param legHasPath    今の区間の経路が出ているか。出たら、経由地を通る前に解いておいた先の経路は要らない
     * @param ahead         その先の地点（指定された座標）。最後が最終目的地
     * @param legComplete   今の区間の経路が終点まで届いているか。届いていなければ先の詳細経路は解かない
     * @param flying        滑空中。先の詳細経路は地上の線なので解かない
     */
    void tick(Level level, Player player, @Nullable BlockPos legFrom, @Nullable BlockPos legRequested,
              boolean legHasPath, List<BlockPos> ahead, boolean legComplete, boolean flying) {
        dropStalePreview(legRequested, legHasPath, ahead);
        if (legFrom == null || ahead.isEmpty()) {
            if (!coarse.isEmpty() || previewRequest != null || !mapLegs.isEmpty()) {
                coarseGeneration++;
                coarse.clear();
                solvingCoarseTo = null;
                mapLegs = List.of();
                previewRequest = null;
            }
            return;
        }
        tickCoarse(legFrom, ahead);
        tickDetail(level, player, legFrom, ahead.get(0), legComplete && !flying);
    }

    private void tickCoarse(BlockPos legFrom, List<BlockPos> ahead) {
        List<BlockPos> froms = new ArrayList<>(ahead.size());
        froms.add(legFrom);
        froms.addAll(ahead.subList(0, ahead.size() - 1));
        coarse.keySet().retainAll(ahead);
        List<List<BlockPos>> legs = new ArrayList<>(ahead.size());
        BlockPos toSolve = null;
        BlockPos toSolveFrom = null;
        long now = MonotonicTime.millis();
        for (int i = 0; i < ahead.size(); i++) {
            BlockPos from = froms.get(i);
            BlockPos to = ahead.get(i);
            CoarseLeg leg = coarse.get(to);
            if (leg != null && BlockDistance.horizontal(leg.from(), from) > SAME_FROM_BLOCKS) {
                coarse.remove(to);
                leg = null;
            }
            boolean stale = leg != null && leg.pendingRegions() > 0 && leg.attempts() < COARSE_RETRY_LIMIT
                    && now - leg.solvedAtMillis() >= COARSE_RETRY_MILLIS;
            if ((leg == null || stale) && toSolve == null) {
                toSolve = to;
                toSolveFrom = from;
            }
            List<BlockPos> line = new ArrayList<>();
            line.add(from);
            if (leg != null && !leg.waypoints().isEmpty()) {
                line.addAll(leg.waypoints());
            } else {
                line.add(to);
            }
            legs.add(List.copyOf(line));
        }
        mapLegs = List.copyOf(legs);
        if (toSolve != null && solvingCoarseTo == null && XaeroPresence.mapPresent()) {
            solveCoarse(toSolveFrom, toSolve);
        }
    }

    private void solveCoarse(BlockPos from, BlockPos to) {
        CoarseLeg before = coarse.get(to);
        int attempts = before == null ? 1 : before.attempts() + 1;
        CoarseMapWindow.Window window = CoarseMapWindow.read(from, to, CoarseMap.MAX_FLOORS);
        PathfindingState.CoarseRead read = new PathfindingState.CoarseRead(window.map(), window.pendingRegions());
        boolean boatAvailable = ChunkView.boatAvailable(Minecraft.getInstance().player);
        var rides = PathfindingState.longRouteRides();
        long myGeneration = coarseGeneration;
        solvingCoarseTo = to;
        CompletableFuture.supplyAsync(() -> PathfindingState.solveCoarseRoute(read, from, to, boatAvailable, rides),
                        coarseExecutor)
                .whenComplete((attempt, error) -> onMainThread.accept(() -> {
                    if (myGeneration != coarseGeneration) {
                        return;
                    }
                    solvingCoarseTo = null;
                    if (error != null) {
                        LOGGER.error("XaeroNav: failed to compute the long route of a later leg", error);
                        return;
                    }
                    List<BlockPos> waypoints = attempt.route().reachedGoal() && !attempt.route().waypoints().isEmpty()
                            ? PathfindingState.replaceLast(attempt.route().waypoints(), to) : List.of();
                    coarse.put(to, new CoarseLeg(from, waypoints, attempt.pendingRegions(), MonotonicTime.millis(),
                            attempts));
                }));
    }

    /**
     * 先の経路は、経由地を通って区間が進んだ後も、その区間の経路が出るまでは出し続ける（線を途切れさせないため）。
     * 区間の経路が出たか、行き先がもう今の区間でも次の区間でもなくなったら捨てる。
     */
    private void dropStalePreview(@Nullable BlockPos legRequested, boolean legHasPath, List<BlockPos> ahead) {
        Preview shown = preview;
        if (shown == null) {
            return;
        }
        boolean forCurrentLeg = shown.to().equals(legRequested);
        boolean forNextLeg = !ahead.isEmpty() && shown.to().equals(ahead.get(0));
        if (forCurrentLeg ? legHasPath : !forNextLeg) {
            preview = null;
        }
    }

    private void tickDetail(Level level, Player player, BlockPos legFrom, BlockPos next, boolean solve) {
        if (!solve) {
            return;
        }
        PreviewRequest request = previewRequest;
        if (request != null && request.from().equals(legFrom) && request.to().equals(next)) {
            Preview done = preview;
            boolean partial = done != null && done.from().equals(legFrom) && done.to().equals(next)
                    && !done.result().complete();
            if (!partial || BlockDistance.horizontal(request.playerAt(), player.blockPosition())
                    < PREVIEW_RETRY_MOVE_BLOCKS) {
                return;
            }
        }
        requestDetail(level, player, legFrom, next);
    }

    private void requestDetail(Level level, Player player, BlockPos from, BlockPos next) {
        previewRequest = new PreviewRequest(from, next, player.blockPosition());
        int renderRadius = ClientCompat.renderDistance(Minecraft.getInstance().options) * 16;
        BlockPos target = level.getChunkSource().getChunkNow(next.getX() >> 4, next.getZ() >> 4) != null
                ? PathfindingState.resolveGoalStandable(level, next) : next;
        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        SearchBounds bounds = SearchBounds.around(level, from, target, tuning.searchHorizontalMargin(),
                PathfindingState.verticalSearchMargin(level, false), renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, tuning.movementOptions());
        long myGeneration = ++detailGeneration;
        detailExecutor.submit(view, from, target, tuning.searchLimits(), tuning.costToGoGuideEnabled())
                .whenComplete((result, error) -> onMainThread.accept(() -> {
                    // 新しい要求・clearで打ち切られた探索は世代が進んでいるのでここで落ちる
                    if (myGeneration != detailGeneration) {
                        return;
                    }
                    if (error != null) {
                        LOGGER.error("XaeroNav: failed to compute the path of the next leg", error);
                        return;
                    }
                    if (!result.steps().isEmpty()) {
                        preview = new Preview(from, next, result);
                    }
                }));
    }
}
