package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRides;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.corridor.CorridorLegSolver;
import net.prason.xaeronav.pathfinding.corridor.CorridorWaypoints;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.DaemonThreads;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.xaero.XaeroMapReader;

/**
 * 長距離ルート（層1の中間目標列と、それを層2廊下で精緻化した版）を目的地ごとに持つ。
 * {@link PathfindingState}から切り出したもので、引き直す・裏で解く・精緻化する・地図の読み込みを待って
 * 引き直す、の判断と、そのための状態をここに閉じ込める。
 *
 * <p>スレッドの約束は{@link PathfindingState}と同じ。地図を読む処理（{@link #freshRoute}・
 * {@link #freshRouteInBackground}・{@link #prepareCoarseRoute}・{@link #cachedOrFreshRoute}）は
 * <b>メインスレッド専用</b>で、解きと精緻化の完了は{@code onMainThread}を通してメインスレッドへ戻ってくる。
 * volatileなフィールドは描画側（{@link PathfindingState.NavigationView}の発行）からも読まれる。
 */
final class CoarseRoutes {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * 地図が欠けたまま引いた長距離ルートを、引き直すまでの間隔（ミリ秒）。
     *
     * <p>読み込みは非同期なので、要求した直後に引き直しても同じ地図しか読めない。一方で
     * {@code XaeroMapReader#readSurface}はメインスレッドで重いので、毎tick投げてはいけない。
     * 引き直すたびに層2の精緻化もやり直されるため、短くすると黄色い線が生の層1へ戻る瞬間が増える。
     */
    private static final long COARSE_MAP_RETRY_INTERVAL_MILLIS = 3_000;

    /**
     * 地図の読み込みを待つ引き直しを、1つの目的地について諦めるまでの回数。
     *
     * <p>{@code pendingRegions}はXaeroが読み込みを終えるたびに減る（検出情報が捨てられる）ので、
     * 普通は数回で0になる。減らないまま回り続ける事態——要求がキューで捨てられる、リージョンが
     * 壊れていて読み込みが完了しない——に備えて上限を置く。ここが無いと、3秒ごとの全引き直しが
     * 目的地に着くまで止まらない。
     */
    private static final int COARSE_MAP_RETRY_LIMIT = 10;

    /** 長距離ルートの地図読み取りが「遅い」とみなす所要時間。1tick(20TPS)相当。 */
    private static final long SLOW_MAP_READ_THRESHOLD_MILLIS = 50L;

    /** 遅い読み取りが続く間、警告を再度出すまでの間隔。毎回だとログが洪水になる。 */
    private static final long SLOW_MAP_READ_LOG_INTERVAL_MILLIS = 5_000L;

    private static final ChangeGate<Boolean> slowMapReadGate = new ChangeGate<>();

    /**
     * 層2廊下で精緻化したwaypoint列を間引く最小間隔（ブロック）。層2はブロック単位の点列を返すため、
     * 間引かないとHUDの「長距離ルート N/M」やwaypoint数が層1の頃と比べて桁違いに増える。
     */
    static final int REFINED_WAYPOINT_MIN_SPACING_BLOCKS = 24;

    // 層2廊下の精緻化専用。PathfindingStateの詳細探索用executorと共用すると、詳細探索の頻繁な再投入（逸脱・末端接近のたびに
    // 走る）のたびにsubmitが「前のジョブ」を打ち切ってしまい、廊下探索が終わる前に必ず潰れる
    private final PathfindingExecutor corridorExecutor = new PathfindingExecutor();
    // 長距離ルート（層1）の解き専用。executor/corridorExecutorはsubmitが前のジョブを打ち切るので相乗りできない
    private final ExecutorService coarseExecutor = DaemonThreads.singleThread("xaeronav-coarse-route");
    // 長距離ルートの中間目標。地形は不変なので、目的地が変わらない限り引き直さない
    private volatile @Nullable CoarseRoute coarseRoute;
    // coarseRouteの各区間を層2廊下（ブロック解像度）で解決し直した精緻版。層1はチャンク平均でしか
    // 地形を見ないため、waypointが実際には崖の上や湖の中を指すことがある。用意でき次第
    // cachedOrFreshRoute/NavigationView#coarseRouteWaypointsがこちらへ差し替わる
    // （発動条件が一つでも欠けたら層1のcoarseRouteへフォールバックする、という既存の考え方の延長）
    private volatile @Nullable RefinedRoute refinedRoute;
    // 精緻化がバックグラウンドで完了したことを示す、次tickで拾うためのフラグ（{@link PendingEscalations}と
    // 同じ構造）。whenComplete（ワーカースレッド）で立て、onClientTick（クライアントスレッド）で読む
    /** 完了した精緻化そのもの。由来元を照合してから現行routeへ公開する。 */
    private volatile @Nullable RefinedRoute pendingRefinedRouteReady;
    // 精緻化が進行中のcoarseRoute。進行中に長距離ルートを引き直すと、その結果は由来元の不一致で
    // 捨てられる——引き直しの間隔（最短0.5秒）は精緻化（区間ごとに最大300ms）より短くなりうるので、
    // 素通しにすると精緻版が一度も完成しないまま、メインスレッドの地図読みだけを回し続けることになる
    private volatile @Nullable CoarseRoute refiningRoute;
    // バックグラウンドで解いている長距離ルートの要求。同じ目的地へ要求を重ねないためと、完了したときに
    // まだ最新の要求かを見分けるための目印（同一インスタンスで照合する）。クライアントスレッドだけが触る
    private @Nullable CoarseSolve solvingCoarse;
    // 最後に読んだ長距離ルートの地図。窓の外の推定（航法グラフ）はルートを解き終わるのを待たずにこれを使う——
    // 待つと、目的地を決めた直後に組む航法グラフが窓の外を幾何下限で見積もってしまう
    private volatile @Nullable CoarseMapForGoal latestCoarseMap;
    // 地図の読み込み待ちで引き直す前の長距離ルート。次に採用したときに前後を比べてログに出す。クライアントスレッドだけが触る
    private @Nullable CoarseRoute mapRetryBefore;
    // 地図の読み込み待ちで引き直す番（COARSE_MAP_RETRY_INTERVAL_MILLIS）。クライアントスレッドだけが触る
    private long coarseMapRetryAfterMillis;
    // 地図の読み込み待ちで引き直した回数（COARSE_MAP_RETRY_LIMIT）。クライアントスレッドだけが触る
    private int coarseMapRetries;
    private final MapGrowthWatch mapGrowth = new MapGrowthWatch();
    // 解いた長距離ルートに振る番号（CoarseRoute#lineage）。採用はメインスレッドだけが行う
    private long lineages;

    private final Consumer<Runnable> onMainThread;
    /** 中間目標の列を入れ替えたとき。添字の意味が変わるので、通過済みの数を数え直させる。 */
    private final Runnable onRouteReplaced;
    /** 裏で解いたルートを採用したとき。表示のsnapshotを出し直させる。 */
    private final Runnable onChanged;

    CoarseRoutes(Consumer<Runnable> onMainThread, Runnable onRouteReplaced, Runnable onChanged) {
        this.onMainThread = onMainThread;
        this.onRouteReplaced = onRouteReplaced;
        this.onChanged = onChanged;
    }

    /** 今の長距離ルート。目的地の照合は呼び出し側で行う。 */
    @Nullable CoarseRoute route() {
        return coarseRoute;
    }

    /** 今の精緻版。{@link #refines}が{@link #route()}について真のときだけ有効。 */
    @Nullable RefinedRoute refined() {
        return refinedRoute;
    }

    /** {@code refined}が{@code route}を解いた回の精緻版か。違う回のもの（引き直す前のルートの精緻版）は使わない。 */
    static boolean refines(@Nullable RefinedRoute refined, @Nullable CoarseRoute route) {
        return refined != null && route != null && refined.source().lineage() == route.lineage();
    }

    /** {@code route}の中間目標の列。{@code refined}がその精緻版ならそちら（{@link #refines}）。 */
    static List<BlockPos> waypointsOf(CoarseRoute route, @Nullable RefinedRoute refined) {
        return refines(refined, route) ? refined.waypoints() : route.waypoints();
    }

    /** {@code goal}に対して層2の精緻化が進行中なら、その元のルート。 */
    @Nullable CoarseRoute refiningFor(BlockPos goal) {
        CoarseRoute inFlight = refiningRoute;
        return inFlight != null && inFlight.goal().equals(goal) ? inFlight : null;
    }

    /**
     * {@code goal}への中間目標の列。精緻版があればそちら。{@code goal}のルートが無ければ空。
     * {@link PathfindingState.NavigationView#coarseRouteWaypoints}と同じ順序で選ぶ。
     */
    List<BlockPos> waypointsFor(BlockPos goal) {
        CoarseRoute route = coarseRoute;
        RefinedRoute refined = refinedRoute;
        return route == null || !route.goal().equals(goal) ? List.of() : waypointsOf(route, refined);
    }

    /** 最後に読んだ{@code goal}向けの長距離ルートの地図。読んでいなければ{@code null}。 */
    @Nullable CoarseMap mapFor(BlockPos goal) {
        CoarseMapForGoal latestMap = latestCoarseMap;
        return latestMap != null && latestMap.goal().equals(goal) ? latestMap.map() : null;
    }

    /**
     * {@code goal}に対する長距離ルートが、地図の読み込み待ちで欠けたままか。一致する長距離ルートが無ければ
     * {@code -1}、あれば未読み込みリージョン数（0なら読み込み待ちではない）。
     */
    int pendingRegionsFor(BlockPos goal) {
        CoarseRoute route = coarseRoute;
        return route != null && route.goal().equals(goal) ? route.pendingRegions() : -1;
    }

    /** {@code goal}への長距離ルートがあり、粗い地図の上で目的地まで届いていないか（詰みの理由の材料）。 */
    boolean unmappedFor(BlockPos goal) {
        CoarseRoute route = coarseRoute;
        return route != null && route.goal().equals(goal) && !route.reachedGoal();
    }

    /** いま{@link #cachedOrFreshRoute}が層2の精緻版を返しているか。 */
    boolean refinedRouteInUse() {
        CoarseRoute cached = coarseRoute;
        RefinedRoute refined = refinedRoute;
        return refines(refined, cached);
    }

    /**
     * 裏で終わった層2の精緻化を、今のルートのものなら採用する。採用したら{@code true}——呼び出し側は
     * 精緻版へ切り替えるために引き直す。完了後に粗経路が差し替わった古い結果は、ここで黙って捨てる。
     */
    boolean adoptRefinedIfReady() {
        RefinedRoute pendingRefined = pendingRefinedRouteReady;
        if (pendingRefined == null) {
            return false;
        }
        pendingRefinedRouteReady = null;
        if (!refines(pendingRefined, coarseRoute)) {
            return false;
        }
        refinedRoute = pendingRefined;
        return true;
    }

    /**
     * 地図が欠けたまま引いた{@code goal}への長距離ルートを、いま引き直す番か。番なら回数を数え、
     * 採用したときに前後を比べてログに出せるよう引き直す前のルートを覚えて{@code true}を返す。
     * 引き直しそのもの（経路ごとか、裏で長距離ルートだけか）は呼び出し側が選ぶ。
     */
    boolean beginMapRetry(BlockPos goal) {
        CoarseRoute before = coarseRoute;
        if (before == null || !before.goal().equals(goal) || before.pendingRegions() == 0
                || coarseMapRetries >= COARSE_MAP_RETRY_LIMIT) {
            return false;
        }
        // 精緻化の最中に引き直すと、その結果は由来元の不一致で捨てられる（cachedOrFreshRouteと同じ条件）
        if (refiningRoute != null || solvingCoarse != null || MonotonicTime.millis() < coarseMapRetryAfterMillis) {
            return false;
        }
        coarseMapRetries++;
        // 引き直しの結果は同期でも裏で解いても採用のとき（adoptCoarseRoute）にログへ出す
        mapRetryBefore = before;
        return true;
    }

    /**
     * 目的地を同じ列の別の高さへ差し替えたとき（{@code PathfindingState#retargetGoal}）。長距離ルートと地図は
     * 目的地の座標で持ち主を見分けるので、そのままだと捨てられてHUDの点線が消える。
     */
    void retarget(BlockPos resolved) {
        CoarseRoute route = coarseRoute;
        if (route != null && !route.waypoints().isEmpty()) {
            List<BlockPos> waypoints = route.reachedGoal() ? replaceLast(route.waypoints(), resolved) : route.waypoints();
            CoarseRoute moved = new CoarseRoute(resolved, route.computedFrom(), route.reachedGoal(),
                    route.pendingRegions(), waypoints, route.lineage());
            coarseRoute = moved;
            // 進行中の精緻化も同じ回のもの。目的地で照合する側（refiningFor）から見えなくなると、精緻化を待たずに
            // 長距離ルートを引き直してしまう
            CoarseRoute refining = refiningRoute;
            if (refining != null && refining.lineage() == route.lineage()) {
                refiningRoute = moved;
            }
        }
        CoarseMapForGoal latestMap = latestCoarseMap;
        if (latestMap != null) {
            latestCoarseMap = new CoarseMapForGoal(resolved, latestMap.map());
        }
        // 解いている最中の長距離ルートは古い目的地のもの。次の再計算で引き直す
        solvingCoarse = null;
    }

    /** 目的地を捨てたとき。走っている精緻化も止める。 */
    void reset() {
        corridorExecutor.cancelAll();
        coarseRoute = null;
        refinedRoute = null;
        refiningRoute = null;
        pendingRefinedRouteReady = null;
        solvingCoarse = null;
        latestCoarseMap = null;
        mapRetryBefore = null;
        coarseMapRetryAfterMillis = 0L;
        coarseMapRetries = 0;
        mapGrowth.reset();
    }

    /**
     * 層2の精緻版があればそちらを優先する（{@link PathfindingState.NavigationView#coarseRouteWaypoints}と同じ順序）。層1は
     * チャンク解像度で中間目標が100ブロック近く離れることがあり、描画距離を下げた環境では
     * {@code DetailTargets#reachableWaypointTarget}の「renderRadius以内」を1つも満たせず長距離ルートごと
     * 空振りする。精緻版は{@link #REFINED_WAYPOINT_MIN_SPACING_BLOCKS}間隔なのでここを埋められる。
     */
    List<BlockPos> cachedOrFreshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                               boolean playerAnchored, boolean ceilingDimension) {
        if (coarseRouteStale(currentGoal, playerAnchored)) {
            return freshRoute(start, currentGoal, boatAvailable, ceilingDimension);
        }
        CoarseRoute cached = coarseRoute;
        RefinedRoute refined = refinedRoute;
        return waypointsOf(cached, refined);
    }

    /**
     * {@link #cachedOrFreshRoute}の、結果をその場で使わない呼び出し元用。引き直すなら地図だけ読んで、
     * 解くのはバックグラウンドに回す（HUDと地図の点線・窓の外の推定のためだけに引く場面で、
     * メインスレッドを解きで止めない）。
     */
    void prepareCoarseRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                    boolean playerAnchored, boolean ceilingDimension) {
        if (coarseRouteStale(currentGoal, playerAnchored)) {
            freshRouteInBackground(start, currentGoal, boatAvailable, ceilingDimension);
        }
    }

    boolean coarseRouteStale(BlockPos currentGoal, boolean playerAnchored) {
        CoarseRoute cached = coarseRoute;
        if (cached == null || !cached.goal().equals(currentGoal)) {
            return true;
        }
        // 地図が欠けたまま引いたルートは、届いてから引き直す。未知セルはほぼ最安なので、まだ
        // 読み込まれていない溶岩の海があるとそこを直進するルートが引かれる。しかも「届く中間目標が
        // 1つでもあれば引き直さない」ので、歩いても直らないままになる（ユーザー報告
        // 「黄色い線がずっとマグマを直線で進もうとしている」）。引き直せば層2の精緻化も
        // やり直されるので、黄色い線は歩くほど詳細になる。
        //
        // 継ぎ足しの側からは引き直さない。あちらの始点は経路の末端なので、そこを起点に長距離ルートを
        // 引き直すと手前の案内まで入れ替わる（下のgoalOrPointTowardの分岐と同じ理由）
        return playerAnchored && cached.pendingRegions() > 0 && refiningRoute == null
                && MonotonicTime.millis() >= coarseMapRetryAfterMillis;
    }

    List<BlockPos> freshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                       boolean ceilingDimension) {
        CoarseRides rides = PathfindingState.longRouteRides();
        CoarseAttempt attempt = TickLaps.measure("long route",
                () -> solveCoarseRoute(readCoarseMapFor(start, currentGoal), start, currentGoal, boatAvailable, rides));
        // 裏で解いている要求より、いま同期で引いた方が新しい
        solvingCoarse = null;
        return adoptCoarseRoute(start, currentGoal, attempt, ceilingDimension).waypoints();
    }

    /**
     * 地図だけをここで読み、解くのは{@link #coarseExecutor}に回す。解き終わったらメインスレッドで
     * {@link #adoptCoarseRoute}する。同じ目的地をまだ解いている間は重ねて投げない。
     */
    void freshRouteInBackground(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                        boolean ceilingDimension) {
        CoarseSolve pending = solvingCoarse;
        if (pending != null && pending.goal().equals(currentGoal)) {
            return;
        }
        CoarseRead read = TickLaps.measure("long route map read", () -> readCoarseMapFor(start, currentGoal));
        CoarseSolve solve = new CoarseSolve(currentGoal);
        solvingCoarse = solve;
        CoarseRides rides = PathfindingState.longRouteRides();
        CompletableFuture.supplyAsync(() -> solveCoarseRoute(read, start, currentGoal, boatAvailable, rides),
                        coarseExecutor)
                .whenComplete((attempt, error) -> onMainThread.accept(() -> {
                    if (solvingCoarse != solve) {
                        return;
                    }
                    solvingCoarse = null;
                    if (error != null) {
                        LOGGER.error("XaeroNav: failed to compute the long route", error);
                        return;
                    }
                    adoptCoarseRoute(start, currentGoal, attempt, ceilingDimension);
                    onChanged.run();
                }));
    }

    /** 長距離ルートの地図を読み、窓の外の推定がすぐ使えるように{@link #latestCoarseMap}へ置く。 */
    private CoarseRead readCoarseMapFor(BlockPos start, BlockPos currentGoal) {
        CoarseRead read = readCoarseMap(start, currentGoal);
        latestCoarseMap = read.map() == null ? null : new CoarseMapForGoal(currentGoal, read.map());
        coarseMapRetryAfterMillis = MonotonicTime.millis() + COARSE_MAP_RETRY_INTERVAL_MILLIS;
        return read;
    }

    /** 解いたルートを今のルートにする。メインスレッド専用。地図を読まずに状態だけを確かめるテストからも呼ぶ。 */
    CoarseRoute adoptCoarseRoute(BlockPos start, BlockPos currentGoal, CoarseAttempt attempt,
                                 boolean ceilingDimension) {
        CoarseRouter.Route route = attempt.route();
        List<BlockPos> waypoints = route.waypoints();
        if (!waypoints.isEmpty() && route.reachedGoal()) {
            // 粗い終点はチャンク中心±8ブロックで高さも代表値なので、そのままでは到着できない。
            // 最後だけ本来の目的地に差し替える
            waypoints = replaceLast(waypoints, currentGoal);
        }
        CoarseRoute thisRoute = new CoarseRoute(currentGoal, start, route.reachedGoal(),
                attempt.pendingRegions(), waypoints, ++lineages);
        CoarseRoute before = mapRetryBefore;
        coarseRoute = thisRoute;
        // 新しい列では添字の意味が変わる。引き直しは今の位置を始点にするので、先頭が通過済みに
        // なることはない
        onRouteReplaced.run();
        // 古い世代の精緻化結果はここでnullにしない。読み出し側がRefinedRoute.source()で
        // 由来元を検査するので、世代が違えば自動的に無視される——ここで消すと、まだ有効な
        // 精緻版まで一緒に落ちる
        // 経路が引けなかった世代でも必ず入れ替える。ここを条件付きにすると、前の世代の目印が
        // 残ったままになって「精緻化が進行中」の判定が永久に真になる
        // 天井のある次元では層2の精緻化を掛けない。あそこの中間目標は探索の目標として使わず
        // （selectDetailTarget）、地図とHUDの点線を細かくするためだけにメインスレッドで
        // Xaeroを読み直すことになる。層2も2.5Dなので、ネザーの縦に積まれた通路は直せない
        boolean refinable = !waypoints.isEmpty() && !ceilingDimension;
        refiningRoute = refinable ? thisRoute : null;
        if (refinable) {
            refineRouteAsync(start, currentGoal, waypoints, thisRoute);
        }
        if (before != null) {
            mapRetryBefore = null;
            // 引き直したこと自体より「地図が埋まって大局が変わったか」が知りたい。変わらないなら、
            // 遠回りの原因は読み込み待ちではなく別にある
            LOGGER.debug("XaeroNav: replanned the long route after more map loaded"
                            + " (pending regions={}->{}, waypoints={}->{}, {}, attempt {}/{})",
                    before.pendingRegions(), thisRoute.pendingRegions(),
                    before.waypoints().size(), waypoints.size(),
                    waypoints.equals(before.waypoints()) ? "same route" : "changed",
                    coarseMapRetries, COARSE_MAP_RETRY_LIMIT);
        }
        return thisRoute;
    }

    /**
     * {@link #coarseRoute}の各区間を層2廊下で解決し直す（長距離ルート層2の
     * waypoint精緻化）。{@link CorridorLegSolver#prepare}はXaeroのデータ構造を触るため
     * メインスレッド専用——全区間分をここで（このメソッドの呼び出しスレッド＝クライアントスレッドで）
     * 先に済ませてしまい、後段の{@code thenCompose}チェーンには不変な{@link CorridorLegSolver.PreparedLeg}
     * だけを渡す。区間ごとに逐次{@code prepare}を呼ぶと、2区間目以降は前区間の{@link CompletableFuture}を
     * 完了させたワーカースレッド上で実行されてしまい、メインスレッド専用の制約に違反する。
     *
     * <p>重くなりうるA*探索は{@link #corridorExecutor}へ区間ごとに順番に（{@code thenCompose}で
     * 連結して）投げる。{@link PathfindingExecutor#submit}は呼ぶたびに「前のジョブ」を打ち切る仕様
     * なので、全区間をまとめて投げると2区間目以降が1区間目を即座にキャンセルしてしまう——
     * 前の区間の完了を待ってから次を投げることで、これを避ける。
     *
     * <p>区間ごとに地表データが無ければ、その区間だけ生のwaypoint1点にフォールバックする
     * （区間単位の段階的劣化——1区間のデータ欠如で経路全体の精緻化を諦めない）。
     *
     * <p>{@code forRoute}は「この精緻化がどの{@link #coarseRoute}世代に属すか」の目印。
     * {@code goal}の一致だけでは、同じ目的地へ引き直した（{@code clear()}後に同じ座標へ
     * 再度向かった等）場合に、古い世代の精緻化が新しい{@link #coarseRoute}を追い越して完了して
     * 上書きするのを検出できない——目的地の座標は変わっていないので一致判定を素通りしてしまう。
     * {@code coarseRoute}フィールドが今も{@code forRoute}と同一インスタンスかを見ることで、
     * 世代を問わず正しく検出する（{@link #freshRoute}が呼ばれるたびに新しいインスタンスを作るため）。
     */
    private void refineRouteAsync(BlockPos start, BlockPos currentGoal, List<BlockPos> waypoints,
                                  CoarseRoute forRoute) {
        List<BlockPos> legs = new ArrayList<>();
        legs.add(start);
        legs.addAll(waypoints);

        List<CorridorLegSolver.PreparedLeg> prepared = new ArrayList<>();
        for (int i = 0; i < legs.size() - 1; i++) {
            prepared.add(CorridorLegSolver.prepare(legs.get(i), legs.get(i + 1)));
        }

        CompletableFuture<List<List<BlockPos>>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (int i = 0; i < prepared.size(); i++) {
            CorridorLegSolver.PreparedLeg leg = prepared.get(i);
            BlockPos rawTarget = waypoints.get(i);
            chain = chain.thenCompose(soFar -> solveLeg(leg, rawTarget).thenApply(points -> {
                soFar.add(points);
                return soFar;
            }));
        }
        chain.whenComplete((legPoints, error) -> {
            List<BlockPos> downsampled = error == null
                    ? CorridorWaypoints.downsample(CorridorWaypoints.stitch(legPoints),
                            REFINED_WAYPOINT_MIN_SPACING_BLOCKS)
                    : null;
            onMainThread.accept(() -> refinementDone(currentGoal, forRoute, downsampled));
        });
    }

    /**
     * {@code forRoute}の精緻化が終わった（メインスレッド）。{@code waypoints}は失敗したら{@code null}。
     * 採用するのは次のtickの{@link #adoptRefinedIfReady}。テストからも呼ぶ。
     */
    void refinementDone(BlockPos goal, CoarseRoute forRoute, @Nullable List<BlockPos> waypoints) {
        CoarseRoute refining = refiningRoute;
        if (refining != null && refining.lineage() == forRoute.lineage()) {
            refiningRoute = null;
        }
        if (waypoints == null) {
            return;
        }
        // 世代の検査はここではなく読み出し側（RefinedRoute.source()）で行う。ここで
        // 「検査してから書き込む」形にすると、その間に世代が進んだ場合に古い精緻版が
        // 素通りする（stitch/downsampleは点列全体を走査するので、その隙間は実時間で開く）
        pendingRefinedRouteReady = new RefinedRoute(goal, forRoute, waypoints);
    }

    private CompletableFuture<List<BlockPos>> solveLeg(CorridorLegSolver.PreparedLeg leg, BlockPos rawTarget) {
        if (leg.view() == null) {
            // 層2で解けなかった区間。生のwaypointは層1のチャンク中心で、そこに立てる保証が無い
            // （実機ジ・エンドのLANDセルの25%は中心に立てない）。層3はここへgoalRadiusで向かい
            // 橋を架けてでも寄るので、prepareが終点だけでも寄せられていたならそちらを使う
            return CompletableFuture.completedFuture(List.of(leg.to() != null ? leg.to() : rawTarget));
        }
        return corridorExecutor.submitRaw(leg.view(), leg.from(), leg.to(), CorridorLegSolver.SEARCH_LIMITS)
                .thenApply(result -> result.steps().stream().map(PathStep::pos).toList());
    }

    /** 長距離ルートの地図を読み、読めた量をログに残す。<b>メインスレッド専用</b>（{@link XaeroMapReader#readSurface}）。 */
    private CoarseRead readCoarseMap(BlockPos start, BlockPos goal) {
        CoarseMapWindow.Window window = CoarseMapWindow.read(start, goal, CoarseMap.MAX_FLOORS);
        CoarseMap map = window.map();
        if (map == null) {
            return new CoarseRead(null, 0);
        }
        mapGrowth.note(start, window, map);
        // 地図がどれだけ見えていたかを残す。「溶岩をLAVAとして見たうえで通した」のか「まだ
        // NO_DATAで見えていなかった」のかは、ここが黙っていると実機ログから区別できない——
        // 未知セルはCoarseRouterでほぼ最安なので、見えていなければ溶岩の海を直進するルートが引かれる
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("XaeroNav: long route map (known cells={}/{}, {}, per layer={}, pending regions={}, read={}ms)",
                    map.knownCells(), map.totalCells(), map.kindBreakdown(), window.layerBreakdown(),
                    window.pendingRegions(), window.readMillis());
        }
        // 実機のカクつきがメインスレッドの地図読み取り側かを継続的に見張る。1tick(20TPS)相当の
        // 50msを超えたら、閾値以下に戻るまでの間も5秒おきに知らせる（毎回だと洪水になる）
        if (window.readMillis() > SLOW_MAP_READ_THRESHOLD_MILLIS
                && slowMapReadGate.changed(true, MonotonicTime.millis(), SLOW_MAP_READ_LOG_INTERVAL_MILLIS)) {
            LOGGER.warn("XaeroNav: slow long route map read ({}ms > {}ms)",
                    window.readMillis(), SLOW_MAP_READ_THRESHOLD_MILLIS);
        }
        return new CoarseRead(map, window.pendingRegions());
    }

    /**
     * 読んだ地図（{@link #readCoarseMap}）から、{@link CoarseRouter}で中間目標列を引く。Minecraft・Xaeroの状態を
     * 読まないので、どのスレッドからでも呼べる（時間の大半は地図読みではなくここ）。
     *
     * <p>溶岩の扱いを2段階でエスカレーションする。層1が溶岩地帯を突っ切ると決めると、そのwaypointへは
     * 詳細探索が原理的に到達できない（溶岩の上は歩けない）ため、ネザーの溶岩の海の縁で詰む:
     *
     * <ol>
     *   <li>{@link CoarseRouter.BridgePolicy#AVOID} — 溶岩を完全に避ける。大きく迂回・後戻りする道が
     *       あればA*が見つける</li>
     *   <li>{@link CoarseRouter.BridgePolicy#BRIDGE} — 橋を架けて渡る前提で通す。最後の手段だが詰むよりはマシ</li>
     * </ol>
     *
     * <p>地図は1回だけ読んで{@code AVOID}と{@code BRIDGE}で使い回す（{@link XaeroMapReader#readSurface}が
     * メインスレッドで重いため）。この梯子が走るのは{@link #freshRoute}・{@link #freshRouteInBackground}
     * （目的地ごとにキャッシュ）のときだけで毎tickではない。
     *
     * <p>以前はここに参照Yを変えて読み直す3段の梯子もあった（{@link CoarseMap}が1セル1階層
     * しか持てず、天井のある次元で見える地形が参照Y次第で変わっていたため）。{@link CoarseMap}が
     * 複数の床を同時に持てるようになったので、1回の{@code readSurface}で参照Y付近の全レイヤーが
     * 床として揃い、梯子は不要になった。
     */
    static CoarseAttempt solveCoarseRoute(CoarseRead read, BlockPos start, BlockPos goal,
                                                  boolean boatAvailable, CoarseRides rides) {
        CoarseMap map = read.map();
        if (map == null) {
            return new CoarseAttempt(new CoarseRouter.Route(List.of(), false), 0);
        }
        CoarseRouter.Route avoided = CoarseRouter.findRoute(map, start, goal, boatAvailable,
                CoarseRouter.BridgePolicy.AVOID, rides);
        if (avoided.reachedGoal()) {
            return new CoarseAttempt(avoided, read.pendingRegions());
        }

        // <b>ALLOWを飛ばしてはいけない。</b>奈落は{@link CoarseRouter.BridgePolicy#ALLOW}で開き、
        // 溶岩の海が開くのはその1段先の{@code BRIDGE}——という段差が
        // {@code BridgePolicy}のjavadocと{@code CoarseRouterTest#voidOpensOneStepEarlierThanLava}に
        // 書いてあるのに、ここはAVOIDから直接BRIDGEへ飛んでいた。ジ・エンドは島を渡るたびに
        // 奈落でAVOIDが失敗するので<b>常にBRIDGE</b>で走っており、ネザーでも「奈落や溶岩混じりを
        // 避けきれない」だけで溶岩の海を突っ切るルートまで一緒に開いていた
        CoarseRouter.Route allowed = CoarseRouter.findRoute(map, start, goal, boatAvailable,
                CoarseRouter.BridgePolicy.ALLOW, rides);
        if (allowed.reachedGoal()) {
            LOGGER.info("XaeroNav: no route avoids void/lava-mixed cells, switched to a long route through them");
            return new CoarseAttempt(allowed, read.pendingRegions());
        }

        CoarseRouter.Route bridged = CoarseRouter.findRoute(map, start, goal, boatAvailable,
                CoarseRouter.BridgePolicy.BRIDGE, rides);
        if (bridged.reachedGoal()) {
            LOGGER.info("XaeroNav: no route avoids lava, switched to a long route that bridges over it");
            return new CoarseAttempt(bridged, read.pendingRegions());
        }
        return new CoarseAttempt(furtherRoute(furtherRoute(avoided, allowed), bridged), read.pendingRegions());
    }

    /**
     * {@link #solveCoarseRoute}の結果と、それを引いたときに<b>まだ読み込まれていなかった</b>
     * リージョンの数。0より大きければ、待って引き直すと違うルートになりうる。
     */
    record CoarseAttempt(CoarseRouter.Route route, int pendingRegions) {
    }

    /** メインスレッドで読んだ長距離ルートの地図。{@code map}はXaeroの地図が無ければnull。 */
    record CoarseRead(@Nullable CoarseMap map, int pendingRegions) {
    }

    /** 目的地まで届かなかったルート同士の比較。中間目標が多い方＝より遠くまで進めた方を採る。 */
    private static CoarseRouter.Route furtherRoute(CoarseRouter.Route a, CoarseRouter.Route b) {
        return b.waypoints().size() > a.waypoints().size() ? b : a;
    }

    static List<BlockPos> replaceLast(List<BlockPos> waypoints, BlockPos replacement) {
        List<BlockPos> copy = new ArrayList<>(waypoints);
        copy.set(copy.size() - 1, replacement);
        return List.copyOf(copy);
    }

    /**
     * 長距離ルートの中間目標のキャッシュ。地形は不変なので、目的地が変わらない限り引き直さない。
     *
     * @param computedFrom このルートを引いたときの始点。同じ場所からの引き直しは同じ結果になるので、
     *         それを避けるための照合に使う（{@code DetailTargets#COARSE_ROUTE_RETRY_MOVE_BLOCKS}）
     * @param reachedGoal 粗い地図の上で目的地まで届いたか。届いていないなら、詳細探索を
     *         いくら回しても届かない（粗い地図で通行不能になるのは溶岩だけで、未探索セルは
     *         通行可能として扱われる）ので、詰みの理由を言い当てる材料になる
     * @param pendingRegions このルートを引いたとき、ディスクにはあるのにまだメモリへ載って
     *         いなかったリージョンの数。0より大きければ<b>このルートは欠けた地図の上で引かれた</b>
     *         ということで、読み込みを待って引き直す（{@link #cachedOrFreshRoute}）
     * @param lineage 解いた1回ごとの番号。目的地を同じ列の別の高さへ寄せ直したとき（{@link #retarget}）は
     *         作り直したルートへ引き継ぐ——引き直したわけではないので、精緻版もそのまま通用する
     */
    record CoarseRoute(BlockPos goal, BlockPos computedFrom, boolean reachedGoal, int pendingRegions,
                       List<BlockPos> waypoints, long lineage) {
    }

    /** 長距離ルートの地図と、それを読んだときの目的地。 */
    private record CoarseMapForGoal(BlockPos goal, CoarseMap map) {
    }

    /** バックグラウンドで解いている長距離ルートの要求（{@link #solvingCoarse}）。 */
    private record CoarseSolve(BlockPos goal) {
    }

    /**
     * 層2で精緻化したwaypoint列と、その元になった{@link CoarseRoute}。
     *
     * <p>由来元を持つのが要点。同じ目的地への再navigateでは{@code goal}が変わらないので、
     * 座標の一致だけでは古い世代の精緻化を弾けない。書き込み側で検査すると
     * 「検査してから書くまでの間に世代が進む」競合が残るので、<b>読み出し時に</b>
     * 今の{@code coarseRoute}と同じ回に解いたもの（{@link CoarseRoute#lineage}）かを見る（順序に依存しない、
     * {@link #refines}）。
     */
    record RefinedRoute(BlockPos goal, CoarseRoute source, List<BlockPos> waypoints) {
    }
}
