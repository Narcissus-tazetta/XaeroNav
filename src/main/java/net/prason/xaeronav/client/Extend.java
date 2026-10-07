package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.Logger;

import org.apache.logging.log4j.LogManager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathLoops;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.GenerationGate;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.AvoidedCellSource;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.PlannedCellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.BlockDistance;

/**
 * いま表示している経路を、その<b>末端から</b>次の区間ぶん伸ばす（プレイヤーからではない）継ぎ足し。
 *
 * <p>{@link PathfindingState#recalculate}との違いはそこだけだが、結果は大きく変わる。プレイヤーから
 * 引き直すとすでに歩いている手前側まで毎回作り直され、目標が少し動くだけで案内全体が描き変わる。
 * 末端から継ぎ足せば手前は定義上そのまま残り、探索は必ず新しい土地だけを見る。
 *
 * <p>{@link PathfindingState}の目的地/経路/計算中フラグは{@link Host}経由でしか触らない
 * （{@link FlightNavState}/{@link SeamRepair}/{@link Splice}と同じ構成）。長距離ルート選定
 * （{@code selectDetailTarget}・{@code preparedVoxelGuide}・詰み判定への反映）はPathfindingState
 * 本体に残っている状態機械の中核なので、こちらもHost経由で問い合わせる。
 */
final class Extend {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * 末端から伸ばせなかったあと、同じ末端でもう一度試すまでにプレイヤーが動く距離（ブロック）。
     *
     * <p>継ぎ足しの失敗はたいてい一時的で、その先のチャンクがまだ読み込まれていないだけ。
     * 歩けば読み込まれて成功しうるのに、失敗を末端の座標だけで覚えると経路が差し替わるまで
     * 二度と試さない——他の再計算トリガー（逸脱・末端への到達・地形変化）はどれも成立しないので、
     * 実際には末端まで歩き切るまで探索が一切走らなくなる。
     */
    private static final double EXTEND_RETRY_MOVE_BLOCKS = 16.0;

    /**
     * {@link #noteLoop}が「戻ってきた」とみなす水平の距離（各軸）。窓の縁の先の行き止まりから引き返す線は、来た線と十数ブロック
     * 離れて並ぶことがある（実機のエンドのV字）。
     */
    private static final int LOOP_NEAR_BLOCKS = 16;

    /** {@link #noteLoop}が「戻ってきた」とみなす高さの差。 */
    private static final int LOOP_NEAR_Y = 6;

    /** 両端が1ブロック離れるごとに要求する、経路に沿った遠回りのステップ数。離れた2点の間を普通に歩いた線を輪とみなさない。 */
    private static final int LOOP_GAP_PER_BLOCK = 3;

    /**
     * {@link #noteLoop}が輪とみなす、経路に沿った最小のステップ数。末端の近くで向きを変えるだけの
     * 継ぎ足しは、末端の数ステップ手前に必ず近づくので、それでは鳴らない長さにする。
     */
    private static final int LOOP_MIN_GAP_STEPS = 20;

    /**
     * {@link #noteRetreatingTail}が「遠ざかった」とみなす、目的地までの水平距離の増え幅。回り込みで数ブロック
     * 遠ざかるのは普通なので、それでは鳴らない幅にする。
     */
    private static final double RETREATING_TAIL_LOG_BLOCKS = 16.0;

    /** {@link PathfindingState}が持つ、非同期完了時に読み書きする必要のある可変状態と長距離ルート選定。 */
    interface Host {
        /** 現在の目的地。 */
        @Nullable BlockPos goal();

        /** 現在表示中の経路。 */
        PathfindingState.DisplayedPath displayed();

        /** 表示中の経路を差し替える。 */
        void setDisplayed(PathfindingState.DisplayedPath path);

        /** 探索中フラグを立て下げする。 */
        void setComputing(boolean computing);

        /**
         * この目的地に対する長距離ルートが、地図の読み込み待ちで欠けたままか。
         * 一致する長距離ルートが無ければ{@code -1}、あれば未読み込みリージョン数
         * （0なら読み込み待ちではない）。
         */
        int coarseRoutePendingRegions(BlockPos currentGoal);

        /** 詳細探索のゴールを決める（{@code PathfindingState#selectDetailTarget}への委譲）。 */
        PathfindingState.DetailTarget selectDetailTarget(BlockPos start, BlockPos currentGoal, int renderRadius,
                                                           int reach, boolean boatAvailable, boolean playerAnchored,
                                                           int minWaypointIndex, boolean ceilingDimension,
                                                           boolean navGraphGuided);

        /** 目的地をそのまま狙う探索のガイド（{@code PathfindingState#goalGuide}への委譲）。 */
        PathfindingState.@Nullable GoalGuide goalGuide(Level level, Player player, BlockPos from,
                                                       BlockPos currentGoal, int renderRadius);

        /** この探索の結果を詰みの判定へ反映する（{@code PathfindingState#noteSearchOutcome}への委譲）。 */
        void noteSearchOutcome(BlockPos start, BlockPos planEnd, PathResult result);
    }

    private final PathfindingExecutor executor;
    private final AtomicLong generation;
    private final GenerationGate generationGate;
    private final Runnable onChanged;
    private final Host host;
    private final SeamRepair seamRepair;
    private final RecentFailures recentFailures;

    /**
     * 経路の末端から先へ伸ばせなかった地点。同じ末端で延長を試み続けないための歯止めで、
     * 経路が差し替わる（＝別の末端になる）と自然に外れる。
     */
    private volatile BlockPos blockedAt;

    /** {@link #blockedAt}を立てたときのプレイヤー位置。{@link #EXTEND_RETRY_MOVE_BLOCKS}参照。 */
    private volatile BlockPos blockedFrom;

    // goto直後、地図の読み込み待ちで継ぎ足しの先を1レグに留めている間だけ真。ログを1回だけ出す印
    private boolean heldForStreaming;

    Extend(PathfindingExecutor executor, AtomicLong generation, GenerationGate generationGate,
           Runnable onChanged, Host host, SeamRepair seamRepair, RecentFailures recentFailures) {
        this.executor = executor;
        this.generation = generation;
        this.generationGate = generationGate;
        this.onChanged = onChanged;
        this.host = host;
        this.seamRepair = seamRepair;
        this.recentFailures = recentFailures;
    }

    /** 目的地の変更・全引き直しで、継ぎ足しに関する歯止めを全て捨てる。 */
    void clear() {
        blockedAt = null;
        blockedFrom = null;
        heldForStreaming = false;
    }

    /**
     * いま経路を末端から継ぎ足すべきか。
     *
     * <p>深い先読み（{@code deepLookAheadEnabled}）では<b>末端が読み込み済みチャンクの縁に届くまで</b>
     * 伸ばし続ける。マジックナンバーを置かずに済むうえ、歩けば新しいチャンクが読まれてまた伸びるので、
     * そのまま「進むほど先が見える」になる。伸ばし切ったら自然に止まる。
     *
     * <p>浅い先読みでは従来どおり{@link PathfindingState#EXTEND_DISTANCE_BLOCKS}手前から。ただしこの値は
     * 経路が数百ブロックある地上世界を前提にしており、{@code detailReach}が縮む次元（ネザーの実測で24）
     * では経路長より長くなって「常に手前」＝先読みとして機能しない。経路長そのものを下限に使う。
     */
    boolean shouldExtend(Player player, PathfindingState.DisplayedPath shown, int renderRadius) {
        PathResult result = shown.result();
        if (!extendableTail(result)) {
            return false;
        }
        List<PathStep> steps = result.steps();
        BlockPos end = steps.get(steps.size() - 1).pos();
        BlockPos currentGoal = host.goal();
        if (end.equals(currentGoal) || extendBlocked(player, end)) {
            return false;
        }
        int pendingRegions = host.coarseRoutePendingRegions(currentGoal);
        boolean streaming = pendingRegions > 0;
        if (heldForStreaming && !streaming) {
            heldForStreaming = false;
            LOGGER.debug("XaeroNav: map is complete, back to normal extension");
        }
        if (streaming
                && BlockDistance.horizontal(player.blockPosition(), end)
                        > PathfindingState.detailHorizon(renderRadius)) {
            // goto直後、地図がまだストリーミングで届いている間は末端を1レグ先までに留める。
            // extendLeadは読み込み済みの余地しか見ないので、放っておくとチャンクが届くたびに継ぎ足しが
            // 連鎖し、末端が数百手先まで伸びて繋ぎ目が毎tick動く（実機ログ「101→334→467手」・
            // 「完走した経路を手放しました」）。プレイヤーは常にdetailHorizonぶんの案内を持っているので
            // 途切れない。pendingRegionsが0になれば通常の先読みへ戻る
            if (!heldForStreaming) {
                heldForStreaming = true;
                LOGGER.debug("XaeroNav: limiting extension to {} blocks while the map loads"
                                + " (pending regions={}, {} blocks to end, {} steps)",
                        PathfindingState.detailHorizon(renderRadius), pendingRegions,
                        Math.round(BlockDistance.horizontal(player.blockPosition(), end)), steps.size());
            }
            return false;
        }
        if (XaeroNavConfig.INSTANCE.deepLookAheadEnabled()) {
            // 案内として意味のある長さぶん読み込み済みの土地が残っているときだけ伸ばす。
            // renderRadiusぎりぎりまで許すと、目標が読み込み済み正方形の外へ出る（extendLeadを参照）
            return extendLead(player, end, renderRadius) >= PathfindingState.MIN_DETAIL_REACH_BLOCKS;
        }
        double lead = Math.min(PathfindingState.EXTEND_DISTANCE_BLOCKS, pathLength(steps));
        return PathfindingState.distanceTo(player.position(), end) <= lead;
    }

    /**
     * {@link #shouldExtend}が断った理由。末端まで歩いてしまった原因を実機ログから追うためだけに使う。
     * 判定の順序は{@link #shouldExtend}と揃えること——ずれると、実際に効いた条件と違う理由が出る。
     */
    String extendRefusal(Player player, PathfindingState.DisplayedPath shown, int renderRadius) {
        PathResult result = shown.result();
        if (!extendableTail(result)) {
            return "terminated by " + result.termination();
        }
        List<PathStep> steps = result.steps();
        BlockPos end = steps.get(steps.size() - 1).pos();
        BlockPos currentGoal = host.goal();
        if (end.equals(currentGoal)) {
            return "the end is the goal itself";
        }
        if (extendBlocked(player, end)) {
            return "the previous extension failed at this end";
        }
        int pendingRegions = host.coarseRoutePendingRegions(currentGoal);
        if (pendingRegions > 0
                && BlockDistance.horizontal(player.blockPosition(), end)
                        > PathfindingState.detailHorizon(renderRadius)) {
            return "waiting for map load (pending regions " + pendingRegions + ")";
        }
        if (XaeroNavConfig.INSTANCE.deepLookAheadEnabled()) {
            return "not enough loaded room (left " + extendLead(player, end, renderRadius)
                    + " blocks, need " + PathfindingState.MIN_DETAIL_REACH_BLOCKS + ")";
        }
        return Math.round(PathfindingState.distanceTo(player.position(), end)) + " blocks to end (extends from "
                + Math.round(Math.min(PathfindingState.EXTEND_DISTANCE_BLOCKS, pathLength(steps))) + " blocks before it)";
    }

    /**
     * この末端から先へ伸ばしてよいか。
     *
     * <p>「到達した経路だけ」ではない。<b>予算切れで打ち切った末端は正当なフロンティア</b>——
     * そこまでは実際に歩ける経路が引けていて（{@code buildResult}は先行ノードの鎖を辿るだけ）、
     * 続きを解くのに必要なのは資源であって別の場所ではない。目標を固定の地平で切る以上、
     * 遠い目的地では予算切れが常態になるので、ここで止めると継ぎ足しが一度も起きない。
     *
     * <p>{@code EXHAUSTED}（範囲内のオープンセットが尽きた＝行き止まりが証明済み）と
     * {@code CANCELLED}（結果自体を捨てる）だけは別。前者から伸ばすのは同じ袋小路を掘り続けること
     * になるので、既存の再挑戦（範囲拡大・粗い経由地チェーン）に任せる。
     */
    private static boolean extendableTail(PathResult result) {
        return switch (result.termination()) {
            case REACHED_GOAL, NODE_BUDGET, TIME_LIMIT -> true;
            case EXHAUSTED, CANCELLED -> false;
        };
    }

    /**
     * 経路の末端から更に先へ探索してよい水平距離（ブロック）。
     *
     * <p><b>読み込み済みチャンクはプレイヤー中心の正方形</b>なので、末端を始点にする継ぎ足しでは
     * その半径から「プレイヤーから末端までの距離」を引いた残りしか使えない。ここを引かずに
     * {@code renderRadius}や{@code detailReach}をそのまま末端基準の上限として渡すと、目標は
     * プレイヤーから最大{@code renderRadius + reach}の位置＝<b>必ず未ロードチャンクの中</b>に落ちる。
     * 未ロードのセルは{@code CellData.ABSENT}＝進入不可なので、探索はオープンセットを尽くして
     * {@code EXHAUSTED}で終わり、{@code complete()}は決して真にならない。
     */
    private static int extendLead(Player player, BlockPos end, int renderRadius) {
        return renderRadius - (int) Math.round(BlockDistance.horizontal(player.blockPosition(), end));
    }

    /**
     * この末端は「伸ばせなかった」印が立っていて、まだ失効していないか。
     * {@link #EXTEND_RETRY_MOVE_BLOCKS}ぶん歩けば新しいチャンクが読まれるので、そこで印を捨てる。
     */
    private boolean extendBlocked(Player player, BlockPos end) {
        if (!end.equals(blockedAt)) {
            return false;
        }
        BlockPos from = blockedFrom;
        if (from != null && from.distSqr(player.blockPosition())
                > EXTEND_RETRY_MOVE_BLOCKS * EXTEND_RETRY_MOVE_BLOCKS) {
            blockedAt = null;
            blockedFrom = null;
            return false;
        }
        return true;
    }

    /** 経路の端から端までの直線距離。先読みの余裕を経路長より長く取らないための目安。 */
    private static double pathLength(List<PathStep> steps) {
        BlockPos first = steps.get(0).pos();
        BlockPos last = steps.get(steps.size() - 1).pos();
        return Math.sqrt(first.distSqr(last));
    }

    /**
     * いま表示している経路を、その<b>末端から</b>次の区間ぶん伸ばす（プレイヤーからではない）。
     *
     * <p>区間ごとに解くこと自体は元々そうで、大局的な最適性は層1の粗いルートが持っている。
     * だから継ぎ足しで失うものは無い——むしろ「毎回プレイヤーから、動く目標へ」引き直す方が
     * ジグザグを生む。
     *
     * <p>継ぎ足しの<b>元</b>になれるのは末端が目標に到達した経路だけ（{@link #shouldExtend}）。
     * 未到達の末端から更に伸ばすのは行き止まりの続きを掘ることになるので、既存の再挑戦
     * （範囲拡大・粗い経由地チェーン）に任せる。一方で継ぎ足した<b>結果</b>が未到達だった場合は、
     * そこまで引けたぶんを繋ぐ——{@link PathfindingState#recalculate}が暫定経路をそのまま見せるのと
     * 同じ扱いで、合成後の{@code complete}がfalseになることで次からは自然に上のトリガーへ引き継がれる。
     */
    void extendPath(PathfindingState.DisplayedPath shown) {
        TickLaps.measure("extend", () -> extendPathNow(shown));
    }

    private void extendPathNow(PathfindingState.DisplayedPath shown) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        BlockPos currentGoal = host.goal();
        if (level == null || player == null || currentGoal == null) {
            return;
        }
        List<PathStep> steps = shown.result().steps();
        BlockPos from = steps.get(steps.size() - 1).pos();
        boolean boatAvailable = ChunkView.boatAvailable(player);
        int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
        // 継続はワーカースレッドで走るので、プレイヤーの位置はここで写し取ってから渡す
        BlockPos playerAt = player.blockPosition();
        boolean ceilingDimension = level.dimensionType().hasCeiling();

        // 末端基準の上限は、プレイヤー中心の読み込み済み正方形の「残り」で切る（extendLead参照）
        int lead = extendLead(player, from, renderRadius);
        // 探索の地平と、読み込み済みチャンクの残りの小さい方。どちらも地形の実測ではないので、
        // かつての detailReach のように成功／失敗で振動することがない
        int reach = Math.min(PathfindingState.detailHorizon(renderRadius), lead);
        PathfindingState.GoalGuide goalGuide = host.goalGuide(level, player, from, currentGoal, renderRadius);
        boolean navGraphGuided = goalGuide != null && goalGuide.navGraph();
        if (navGraphGuided && extendLead(player, from, NavGraphGuide.window(renderRadius))
                < PathfindingState.MIN_DETAIL_REACH_BLOCKS) {
            // 航法グラフで探す箱は描画距離ではなく窓で切られる（navGraphBounds）。描画距離で測った余地のまま投げると、
            // 末端が箱の縁にある継ぎ足しが10万ノードを焼いて1歩も進まない（実機: 描画距離15のネザー・エンドで数秒おきに繰り返した）
            blockExtend(from, playerAt);
            return;
        }
        PathfindingState.DetailTarget detail = PathfindingState.landingOr(goalGuide, currentGoal,
                host.selectDetailTarget(from, currentGoal, lead, reach, boatAvailable, false, shown.waypointIndex(),
                        ceilingDimension, navGraphGuided));
        BlockPos target = detail.target();
        // 目的地をそのまま狙っているときは、遠くても止めない（箱が切るので探索は有限）。
        // 中間目標を狙うときだけ「伸ばす先が読み込み済みチャンクの外」を歯止めにする。
        // 着地点は窓のグラフのノード＝読み込み済みなので止めない
        boolean aimingAtGoal = target.equals(currentGoal);
        boolean landing = goalGuide != null && goalGuide.landing() != null
                && target.equals(goalGuide.landing().target());
        if (target.equals(from)
                || (!aimingAtGoal && !landing && BlockDistance.horizontal(from, target) > lead)) {
            // これ以上伸ばす先が無いか、伸ばす先が読み込み済みチャンクの外（中間目標が1つも
            // 残りの中に無いとselectDetailTargetは本来の目的地へフォールバックする）。
            // 歯止めを立てないと、shouldExtendが毎tick真を返し続け、そのたびに
            // selectDetailTarget（＝メインスレッドの地図読み）を回すことになる
            blockExtend(from, playerAt);
            return;
        }

        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        SearchBounds bounds = navGraphGuided
                ? PathfindingState.navGraphBounds(level, from, target, playerAt, renderRadius,
                        tuning.searchHorizontalMargin(), landing, goalGuide.costToGo())
                : SearchBounds.around(level, from, target, tuning.searchHorizontalMargin(),
                        PathfindingState.verticalSearchMargin(level, false), renderRadius);
        ChunkView view = TickLaps.measure("chunk capture",
                () -> ChunkView.capture(level, player, bounds, tuning.movementOptions()));
        SearchLimits limits = navGraphGuided ? PathfindingState.navGraphLimits(tuning.searchLimits())
                : tuning.searchLimits();

        long myGeneration = generation.incrementAndGet();
        host.setComputing(true);
        boolean reachesGoal = aimingAtGoal;
        int newWaypointIndex = reachesGoal ? -1 : detail.waypointIndex();
        boolean costToGoGuideEnabled = tuning.costToGoGuideEnabled();
        // 手前の経路がこれから使うぶんを差し引いた資源で続きを解く。数えるのは<b>いる場所から先</b>
        // だけ——通り過ぎたぶんは既に置き終わっていて、手持ちの枚数からも減っている
        Carryover carried = new Carryover(Carryover.trailingBridgeRun(steps),
                Carryover.placements(steps, PathProgress.INSTANCE.indexFor(shown.result()) + 1),
                Carryover.trailingRide(steps));
        PlannedCellSource futureTerrain = new PlannedCellSource(view, steps,
                PathProgress.INSTANCE.indexFor(shown.result()) + 1);
        CostToGo prepared = PathfindingState.preparedGuide(goalGuide, currentGoal, target);
        CompletableFuture<PathResult> extendFuture = executor.submit(
                AvoidedCellSource.wrap(futureTerrain, recentFailures.avoided()), from, target, limits,
                costToGoGuideEnabled, detail.goalRadius(), carried, prepared);
        generationGate.whenStillCurrent(extendFuture, myGeneration, TickLaps.timed("receive/extend", (result, error) -> {
            try {
                host.setComputing(false);
                if (error != null) {
                    if (!(error instanceof CancellationException)) {
                        LOGGER.error("XaeroNav: failed to extend the path", error);
                    }
                    return;
                }
                // 継ぎ足す先が入れ替わっていたら捨てる。世代が同じでも、目的地の変更や逸脱で
                // displayedごと差し替わっていることがある
                PathfindingState.DisplayedPath current = host.displayed();
                if (current != shown || !currentGoal.equals(host.goal())) {
                    return;
                }
                PathfindingState.logSearchReach(from, target, result);
                List<PathStep> tail = result.steps();
                if (result.complete()) {
                    // 継ぎ足しが狙った先まで届いた＝前へ出られている。詰みの目印はここで落とす。
                    // 届かなかったことは逆に詰みの根拠にしない——継ぎ足しの失敗はたいていその先が
                    // まだ未ロードなだけで、表示中の経路はそのまま歩ける。「ここから目的地へ行けるか」
                    // に答えているのはプレイヤーから引き直す側（recalculate）だけ
                    host.noteSearchOutcome(playerAt, PathfindingState.endOf(result, from), result);
                }
                if (tail.isEmpty()) {
                    // 1歩も進めなかった。手前の経路はそのまま残し、通常の再計算に委ねる
                    blockExtend(from, playerAt);
                    return;
                }
                if (!result.complete()
                        && BlockDistance.horizontal(from, tail.get(tail.size() - 1).pos())
                                < PathfindingState.MIN_EXTEND_PROGRESS_BLOCKS) {
                    // 予算切れの末端からは伸ばしてよいが、ほとんど前へ出ていないならそれ以上は無駄。
                    // selectFallbackは「始点から5ブロック以上離れた最良点」を返すので、行き止まりの
                    // 袋小路でも毎回わずかに進んだ経路が返る——歯止めが無いと数ブロックずつ這い続ける
                    //
                    // 繋がずに捨てる。completeは末尾の区間のものなので、這うだけの尻尾を繋ぐと
                    // 完走していた経路まで未到達扱いになり、「打ち切られた末端に近づいたら引き直す」に
                    // 落ちて経路全体が作り直される。数ブロックの得のために証明済みの経路を失う
                    blockExtend(from, playerAt);
                    return;
                }
                // 未到達でも引けたぶんは繋ぐ。recalculate側は元々そうしている（暫定経路）。
                // 捨ててしまうと、読み込み済みの縁まで引けていた経路を毎回無駄にすることになる
                // 繋ぎ目はここ（手前の末端）。落ち着いてから解き直す（{@link SeamRepair}）
                SeamRepair.Loop loop = TickLaps.measure("loop detection",
                        () -> noteLoop(steps, tail, PathProgress.INSTANCE.indexFor(current.result()) + 1,
                                target, result, navGraphGuided));
                TickLaps.measure("retreat check", () -> noteRetreatingTail(from, tail.get(tail.size() - 1).pos(),
                        currentGoal, target, result, goalGuide));
                seamRepair.queue(from);
                if (loop != null) {
                    seamRepair.queueLoop(loop);
                }
                RouteExplain.log("extend", level, from, target, currentGoal, result, prepared,
                        goalGuide == null ? null : goalGuide.costToGo(), view,
                        tuning.movementOptions(), renderRadius);
                TickLaps.measure("extend append",
                        () -> host.setDisplayed(append(current, result, newWaypointIndex, reachesGoal)));
                blockedAt = null;
                blockedFrom = null;
            } finally {
                onChanged.run();
            }
        }));
    }

    /**
     * 継ぎ足した区間の末端が、継ぎ足す前の末端より目的地から遠いなら、そのときガイドが両端をどう見ていたかを1行残す。
     *
     * <p>継ぎ足しの終点選びはガイドの上で必ず目的地へ近づく点を選ぶので、遠ざかる向きへ伸びたなら
     * 「ガイドが遠回りの方を近いと評価した」のか「窓の外の推定で比べていた」のかのどちらか。
     * 両端の値と、それが窓の中で実際に辿った値か（{@link WindowField#measuredInWindow}）を並べると1行で割れる。
     */
    private static void noteRetreatingTail(BlockPos from, BlockPos end, BlockPos currentGoal, BlockPos target,
            PathResult result, PathfindingState.@Nullable GoalGuide goalGuide) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        double fromLeft = BlockDistance.horizontal(from, currentGoal);
        double endLeft = BlockDistance.horizontal(end, currentGoal);
        if (endLeft <= fromLeft + RETREATING_TAIL_LOG_BLOCKS) {
            return;
        }
        NavGraphGuide.logOffThread(() -> {
            String guide = "none";
            if (goalGuide != null) {
                CostToGo costToGo = goalGuide.costToGo();
                guide = "%s before=%d%s after=%d%s, value origin before=%s, value origin after=%s".formatted(
                        goalGuide.navGraph() ? "nav graph" : "3D coarse layer etc.",
                        Math.round(costToGo.estimate(from.getX(), from.getY(), from.getZ())), windowNote(costToGo, from),
                        Math.round(costToGo.estimate(end.getX(), end.getY(), end.getZ())), windowNote(costToGo, end),
                        NavGraphGuide.origin(costToGo, from), NavGraphGuide.origin(costToGo, end));
            }
            LOGGER.debug("XaeroNav: extension moved away from the goal (end before={} with {} to goal, end after={} with {}, "
                            + "{} steps/{}, aimed at={}, guide={})",
                    from.toShortString(), Math.round(fromLeft), end.toShortString(), Math.round(endLeft),
                    result.steps().size(), result.termination(), target.toShortString(), guide);
        });
    }

    private static String windowNote(CostToGo costToGo, BlockPos pos) {
        if (!(costToGo instanceof WindowField field)) {
            return "";
        }
        return field.measuredInWindow(pos.getX(), pos.getZ()) ? "(inside window)" : "(outside-window estimate)";
    }

    /**
     * 継ぎ足す区間が既存の経路の手前の近くへ戻ってくるなら1行残す。継ぎ足しは末端から先だけを解くので、
     * 戻ってきても手前の経路は見直されず、線が輪を描いたまま表示される。輪は後で繋ぎ目の解き直しが
     * 切ることもあるが、「なぜ継ぎ足しが戻る向きへ伸びたか」はそこからは分からない。
     * 経路に沿って最も多くのステップを遠回りしている組を出す。
     *
     * @param fromIndex プレイヤーの次に踏むステップの添字。歩き終えた所へ戻る輪は切り落とせない
     * @return 輪の両端（{@link SeamRepair#queueLoop}へ渡して切り落とす）。輪が無ければ{@code null}
     */
    private static SeamRepair.@Nullable Loop noteLoop(List<PathStep> route, List<PathStep> tail, int fromIndex,
            BlockPos target, PathResult result, boolean navGraphGuided) {
        PathLoops.Return found = PathLoops.widestReturn(route, tail, fromIndex, LOOP_NEAR_BLOCKS, LOOP_NEAR_Y,
                LOOP_MIN_GAP_STEPS, LOOP_GAP_PER_BLOCK);
        if (found == null) {
            return null;
        }
        LOGGER.debug("XaeroNav: extension came back to an earlier part of the path (extension step {}={}, near path step {}={}, "
                        + "loop of {} steps along the path, path={} steps, extension={} steps/{}, end={}, aimed at={}, "
                        + "nav graph={})",
                found.rejoin(), tail.get(found.rejoin()).pos().toShortString(), found.entry(),
                route.get(found.entry()).pos().toShortString(), found.gap(), route.size(), tail.size(),
                result.termination(), route.get(route.size() - 1).pos().toShortString(), target.toShortString(),
                navGraphGuided);
        return new SeamRepair.Loop(route.get(found.entry()).pos(), tail.get(found.rejoin()).pos());
    }

    /**
     * この末端からは伸ばせなかった、と記録する。{@link #EXTEND_RETRY_MOVE_BLOCKS}ぶん歩けば失効する。
     *
     * <p>ここで「経路を引き直しました」の通知は出さない。手前の経路は1ブロックも変わっておらず、
     * ユーザーから見て変化が無いのに警告だけ点滅することになる。
     */
    private void blockExtend(BlockPos end, BlockPos playerAt) {
        blockedAt = end;
        blockedFrom = playerAt;
    }

    /**
     * 継ぎ足した経路を組み立てる。ステップ列は連結し、区間の境目を記録する。
     *
     * <p>{@link PathProgress}へ引き継ぎを伝えるのはここ。継ぎ足しは手前の添字を変えないので
     * 対応づけはそのまま通用するが、伝えないと別経路とみなされて全体走査に落ちる。
     */
    private static PathfindingState.DisplayedPath append(PathfindingState.DisplayedPath current, PathResult tail,
                                                           int tailWaypointIndex, boolean reachesGoal) {
        List<PathStep> merged = new ArrayList<>(current.result().steps());
        merged.addAll(tail.steps());
        // 継ぎ足す区間は手前がどこを通ったかを知らないので、繋ぎ目で同じ位置を踏み直しうる
        PathLoops.Folded folded = PathLoops.fold(merged);
        // completeは「この経路が狙った先まで届いたか」であって「最終目的地に着いたか」ではない
        // （中間目標へ向かう経路も、その中間目標に届いていればcomplete）。ここを reachesGoal に
        // すると、継ぎ足した瞬間に未到達扱いになってshouldExtendが止まり、1回しか伸びなくなる
        PathResult combined = new PathResult(List.copyOf(folded.steps()), tail.termination(),
                tail.expandedNodes(), tail.distinctNodes(), tail.limitsHeld());
        List<PathfindingState.PathSegment> segments = new ArrayList<>();
        for (PathfindingState.PathSegment segment : current.segments()) {
            segments.add(new PathfindingState.PathSegment(folded.newIndex()[segment.endStep()],
                    segment.waypointIndex()));
        }
        segments.add(new PathfindingState.PathSegment(folded.steps().size() - 1, tailWaypointIndex));
        PathProgress.INSTANCE.carryOver(combined);
        return new PathfindingState.DisplayedPath(combined,
                reachesGoal ? PathfindingState.PathMode.GOAL : PathfindingState.PathMode.WAYPOINT,
                tailWaypointIndex, List.copyOf(segments));
    }
}
