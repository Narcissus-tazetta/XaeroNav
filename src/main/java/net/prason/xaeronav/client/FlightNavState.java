package net.prason.xaeronav.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import org.apache.logging.log4j.LogManager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;
import net.prason.xaeronav.pathfinding.flight.AirGrid;
import net.prason.xaeronav.pathfinding.flight.CoarseAirMap;
import net.prason.xaeronav.pathfinding.flight.CoarseFlightField;
import net.prason.xaeronav.pathfinding.flight.CoarseFlightRouter;
import net.prason.xaeronav.pathfinding.flight.FlightHorizon;
import net.prason.xaeronav.pathfinding.flight.FlightLineRouter;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.flight.FlightRouter;
import net.prason.xaeronav.pathfinding.flight.FlightTuning;
import net.prason.xaeronav.pathfinding.flight.TurnBack;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.GameCompat;
import net.prason.xaeronav.util.DaemonThreads;

/**
 * エリトラで滑空している間の案内。3D空中経路（太線）と、その先を繋ぐ中間目標の点線を持つ。
 *
 * <p>歩行（{@link PathfindingState}）とは<b>別のパイプライン</b>。探索の打ち切り方も鮮度の
 * 確認方法も違うので、状態を混ぜない。滑空しているかどうかの判断と目的地そのものは
 * {@link PathfindingState}が持ち、ここは「その目的地へ向かう空中経路」だけを受け持つ。
 *
 * <p>{@code volatile}が付いているものはワーカースレッドが書いてクライアントスレッドが読む。
 * それ以外はクライアントスレッド専用。
 */
final class FlightNavState {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * 空中経路を投げ直す下限間隔（tick）。逸脱と移動のきっかけは高速で飛んでいる間ほぼ毎tick
     * 成立しうるので、これが無いとワーカースレッドへ探索を積み続ける。
     */
    private static final int MIN_RECALC_INTERVAL_TICKS = 10;

    /**
     * 空中の長距離ルートから、プレイヤーがこれだけ離れたら引き直す（ブロック）。
     *
     * <p>移動距離ではなく<b>ルートから外れた距離</b>で見るのが要点。{@code XaeroMapReader.readSurface}は
     * <b>メインスレッド専用で重く</b>、移動距離で引くとエリトラの速度では数秒おきに走る——実機で
     * サーバースレッドが「Can't keep up (7250ms behind)」を出していた原因がこれ。長距離ルートは
     * 目的地まで通しで引いてあるので、<b>それに沿って飛んでいる限り引き直す理由が無い</b>。
     */
    private static final double COARSE_OFF_ROUTE_BLOCKS = 192.0;

    /** 残りの中間目標がこれを下回ったら、先を作るために引き直す。 */
    private static final int COARSE_MIN_REMAINING_WAYPOINTS = 4;

    /**
     * 空中経路が一度に狙う最大の水平距離（ブロック）。
     *
     * <p><b>地形によらない固定値であることが要点</b>——歩行の{@code detailHorizonBlocks}とまったく
     * 同じ理由で、「届く距離」を描画距離から見積もると必ず外れる。描画距離の75%(384)を狙わせていた
     * 頃は、探索がそこまで届かず<b>予算が尽きた場所で経路が切れる</b>ため、プレイヤーが少し進むたびに
     * 切れる場所が変わって太線の末端が飛び回っていた（ユーザー報告「めっちゃ目的地変わる」）。
     * 実測の到達距離（ネザーで280前後）の内側に固定して、狙った先まで引き切れるようにする。
     */
    private static final int DETAIL_HORIZON_BLOCKS = 256;

    /**
     * 経路の末端がこれより近づいたら、末端から先を継ぎ足す（ブロック）。
     *
     * <p>1.5ブロック/tickで飛ぶので160ブロックは約5秒。探索1回が1〜2秒かかるので、これくらいの
     * 余裕が無いと<b>末端まで飛び切ってから次の経路が出てくる</b>（ユーザー報告
     * 「それ以上経路がないところまで行ってから経路探索していた」）。
     */
    private static final int EXTEND_LEAD_BLOCKS = 160;

    /** これより短い継ぎ足しは投げない（ブロック）。探索1回に見合わない。 */
    private static final int MIN_EXTENSION_BLOCKS = 64;

    /**
     * 継ぎ足しに失敗した末端を、プレイヤーがこれだけ動いたら再挑戦する（ブロック）。
     * 失敗はたいてい一時的（その先がまだ未ロード）で、進めば成功しうる。
     */
    private static final double EXTEND_RETRY_MOVE_BLOCKS = 48.0;

    /**
     * 読み込み済みと当てにしてよい描画半径の割合。描画距離まで必ず読めているわけではない
     * （実測でネザーは半径173ブロック相当しか載っていなかった）ので、縁は当てにしない。
     */
    private static final double LOADED_MARGIN = 0.9;

    /**
     * 岩盤天井の下に取る余白（ブロック）。天井は不透明なのでXaeroの洞窟レイヤーには床として
     * 記録されない——ここで頭打ちにしないと、最上段の高度帯が岩の中まで伸びる。
     */
    private static final int CEILING_MARGIN_BLOCKS = 10;

    /**
     * 先を伸ばすとき、これまでに引き直した地点の中で目的地に最も近い所より、さらにこれだけ近づいていれば
     * 末端から継ぎ足す代わりに引き直す（ブロック、水平）。
     *
     * <p>末端からの継ぎ足しは、末端を決めた時点の読める範囲で選んだ出口に縛られる。進んで新しく読めた
     * 範囲で引き直す方が経路が短い（benchで最適比 現世1.072→1.044・ネザー1.335→1.200）。比べる相手が
     * 「前回」ではなく「最も近づいた所」なのは、読める範囲の外の見積もりが外れる地形で、出口が2つの
     * 行き止まりの間で入れ替わって往復しうるため——最も近づいた所を基準にすれば往復では条件を満たせない。
     */
    private static final double REROUTE_PROGRESS_BLOCKS = 64.0;

    /**
     * 引き直しても、プレイヤーのこれだけ先までは今の線を残す（ブロック）。手前の線は今まさに辿っている
     * ところで、そこが描き変わると案内が揺れて見える（歩行で繋ぎ目の前後だけ解き直すのと同じ考え方）。
     */
    private static final double REROUTE_KEEP_BLOCKS = 48.0;

    /**
     * 非同期の結果を適用してよいかを所有者に問い合わせる。
     *
     * <p>鮮度の確認は目的地と次元の一致で行い、歩行A*と共有の{@code generation}は使わない——
     * こちらは別のパイプラインで、あちらの打ち切りや世代進行に巻き込まれる理由が無い。
     */
    @FunctionalInterface
    interface Current {
        /** まだ滑空していて、目的地も次元も計算した時点から変わっていないか。 */
        boolean stillFlyingTo(BlockPos goal, ResourceKey<Level> dimension);
    }

    /**
     * 空中の長距離ルート。歩行版と同じく、目的地と計算地点を一緒に覚えて「今の目的地に対するものか」
     * 「同じ場所から引き直していないか」を読み出し側で照合する。
     */
    private record CoarseRoute(BlockPos goal, BlockPos computedFrom, List<BlockPos> waypoints,
                               @Nullable CoarseFlightField field) {
    }

    /** 長距離ルートの中間目標と、同じ地図から求めた目的地までの残りコストの場。 */
    private record CoarseSolution(List<BlockPos> waypoints, @Nullable CoarseFlightField field) {
        static final CoarseSolution NONE = new CoarseSolution(List.of(), null);
    }

    /**
     * 空中経路と、その代わりに使う曲がり点線。どちらを使うかは計算した側が決める。
     * {@code coarse}はこのjobで長距離ルートを引き直したときだけ非null。
     */
    private record Guidance(FlightRoute route, List<Vec3> bend, BlockPos from, @Nullable CoarseSolution coarse) {
    }

    /**
     * 継ぎ足しの結果。{@code cut}は手前の経路へ戻ってきたときの繋ぎ直し、{@code turnsBack}は
     * 引き返す向きだったか（{@link TurnBack}）。
     */
    private record Extension(FlightRoute route, int segmentAtStart, TurnBack.@Nullable Cut cut) {
    }

    /** 滑空中にこのtickで何をするか。 */
    private enum Action {
        /** 全部引き直す。手前の案内も描き変わる。 */
        RECOMPUTE,
        /** 末端から先だけを継ぎ足す。手前は定義上そのまま残る。 */
        EXTEND,
        NOTHING
    }

    private final Current current;

    /**
     * 空中経路の非同期完了ごとに呼ぶ通知。所有者（{@link PathfindingState}）が描画用の
     * atomic snapshotを再発行するためのもの。{@code tick}/{@code recalculate}
     * 等の同期呼び出し経由の変更は呼び出し元が自分でtick終端に発行するので、ここでは
     * ワーカー完了callback（メインスレッドへ戻った後）でのみ呼べば足りる。
     */
    private final Runnable onChanged;

    /**
     * 滑空中の点線を曲げる計算専用。A*とはライフサイクルも打ち切り方も関係が無いので、
     * {@code PathfindingExecutor}（呼ぶたび前のジョブを打ち切る）ではなく素のスレッドを1本持つ。
     */
    private final ThreadPoolExecutor executor = DaemonThreads.singleThread("xaeronav-flight-line");

    /** 空中経路（太線で描く本体）。引けなければ空。 */
    private volatile FlightRoute route = FlightRoute.NONE;

    /**
     * 探索がワーカースレッドで走っている最中か。{@link #executor}は前のジョブを打ち切らず
     * <b>キューに積む</b>ので、これが無いと1回2秒かかる探索を1秒ごとに投げてキューが際限なく
     * 伸びる（実機ログ: ネザーで10万ノード・2.1秒）。表示される経路は遅れる一方になり、
     * CPUは焼き続ける。
     */
    private volatile boolean computing;

    /**
     * {@link #computing}を所有するjobの世代。古いcallbackが新しいjobの実行中フラグを下ろさないための
     * identityで、投入・resetはクライアントスレッドからだけ行う。
     */
    private volatile long jobGeneration;

    /** {@link #route}を計算したときのプレイヤー位置。ここから離れた＝新しいチャンクが読めている。 */
    private volatile BlockPos computedFrom;

    /**
     * 継ぎ足しが失敗した末端と、そのときのプレイヤー位置。同じ場所から投げ直しても読み込み済み
     * チャンクも地形も変わっていないので同じ結果になる——歯止めが無いと、行き止まりの末端で
     * 予算いっぱいの探索を延々と回し続ける。
     */
    private volatile Vec3 extendBlockedAt;
    private volatile BlockPos extendBlockedFrom;

    /**
     * 描画距離の外までの中間目標（Xaeroの地図由来、天井のある次元のみ）。読み込み済みチャンクを
     * 見る空中経路はレンダー距離で必ず頭打ちになるので、その先を繋ぐのはこれしかない。
     */
    private volatile CoarseRoute coarseRoute;

    /** これまでに引き直した地点の、目的地までの水平距離の最小（{@link #REROUTE_PROGRESS_BLOCKS}）。 */
    private double bestRerouteDistance = Double.POSITIVE_INFINITY;

    /**
     * 通過済みとみなす中間目標の数。地図・ワールドの点線をどこから描くかにだけ使う。
     * 単調に進める——経路が予算切れで短くなって末端が後退しても、通過済みが戻らないようにする。
     */
    private volatile int passedWaypoints;

    /** 空中経路が引けなかったときの代替。目的地への点線を山の上・横へ曲げた2〜3点。 */
    private volatile List<Vec3> guideWaypoints;

    /** クライアントスレッド専用。 */
    private int ticksSinceRecalc;

    FlightNavState(Current current, Runnable onChanged) {
        this.current = current;
        this.onChanged = onChanged;
    }

    /**
     * 空中経路。先頭の点は<b>計算した時点</b>のプレイヤー位置なので、届く頃には最大で再計算間隔ぶん
     * 古い。描画側は先頭を捨てて今の位置から引き直すこと。
     */
    FlightRoute route() {
        return route;
    }

    /**
     * 折れ線をどの点から描き始めるか。通り過ぎた区間を描かないための添字で、ワールド内描画と地図で
     * 必ず共有すること（片方だけ切り詰めると、地図にだけ自分の後ろへ伸びた線が残る）。
     */
    int routeFrom() {
        return FlightProgress.INSTANCE.segmentFor(route) + 1;
    }

    /**
     * 点線が辿るべき中間点。<b>始点も目的地も含まない</b>——描画側はどちらも自分で持っている
     * （始点は太線の末端か現在地、終点は目的地）ので、端を含めると必ず添字をずらす処理が要る。
     *
     * <p>長距離ルートがあればその中間目標を返し、無ければ曲がり点線へ落ちる。呼び出し側から見て
     * 「点線をどこで折るか」という1つの問いなので、2つの供給元をここで1本にまとめる。
     */
    List<Vec3> dashWaypoints(boolean airborne, boolean done, BlockPos currentGoal) {
        if (!airborne) {
            return List.of();
        }
        List<BlockPos> coarse = coarseWaypoints(airborne, done, currentGoal);
        if (!coarse.isEmpty()) {
            return coarse.stream().map(Vec3::atCenterOf).toList();
        }
        List<Vec3> bend = guideWaypoints;
        // findGuideLineは[始点, 曲がり点, 終点]を返すので、両端を落とす
        return bend == null || bend.size() < 3 ? List.of() : List.copyOf(bend.subList(1, bend.size() - 1));
    }

    /** 引いてある経路だけを捨てる。着地・到着・スペクテイターのように、長距離ルートは生きている場面用。 */
    void dropRoute() {
        route = FlightRoute.NONE;
        guideWaypoints = null;
        computedFrom = null;
    }

    /** 目的地ごと捨て、実行中jobを論理的にキャンセルする。 */
    void reset() {
        jobGeneration++;
        computing = false;
        // 実行中のsupplierは安全な中断点まで走り得るが、待機中の旧jobは全て捨てて最新だけを残す。
        executor.getQueue().clear();
        dropRoute();
        coarseRoute = null;
        bestRerouteDistance = Double.POSITIVE_INFINITY;
        passedWaypoints = 0;
        extendBlockedAt = null;
        extendBlockedFrom = null;
    }

    /** 滑空中の1tick。進捗の更新と、必要なら引き直し・継ぎ足しの投入。 */
    void tick(Level level, Player player, BlockPos currentGoal) {
        FlightProgress.INSTANCE.update(route, player.position());
        advancePassedWaypoints(player, currentGoal);
        ticksSinceRecalc++;
        switch (action(player)) {
            case RECOMPUTE -> recalculate(currentGoal);
            case EXTEND -> extend(level, player, currentGoal);
            case NOTHING -> {
            }
        }
    }

    /**
     * 空中経路を一から引き直す。
     *
     * <p>長距離ルート（Xaeroの地図読み）はメインスレッドで先に済ませ、ワーカーへは不変の結果だけを
     * 渡す。{@code XaeroMapReader.readSurface}がメインスレッド専用のため。
     */
    void recalculate(BlockPos currentGoal) {
        ticksSinceRecalc = 0;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null || currentGoal == null) {
            return;
        }

        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            // スペクテイターはブロックをすり抜ける（Player#isSpectator → noPhysics）。避ける必要の
            // 無い地形のために線を曲げると、まっすぐ飛べばいい所を遠回りに見せるだけになる。
            // 判定にPlayer#isSpectator()を使わないのは、あれがタブリストのPlayerInfo経由で、
            // 未受信なら黙ってfalseに落ちるため（AbstractClientPlayer#isSpectator）
            dropRoute();
            return;
        }

        Vec3 start = player.position();
        BlockPos from = player.blockPosition();
        Vec3 goalVec = Vec3.atCenterOf(currentGoal);
        ResourceKey<Level> dimension = level.dimension();
        boolean rockets = hasRockets(player);
        boolean routing = XaeroNavConfig.INSTANCE.flightRoutingEnabled();
        FlightTuning tuning = tuning();
        int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
        // 水平マージンを描画距離に揃えて、読み込み済みの正方形をまるごと探索範囲に入れる。
        // 壁を回り込む経路は始点と目的地を結ぶ帯の外へ出るので、狭いマージンでは回り込めない
        SearchBounds bounds = SearchBounds.around(level, player.blockPosition(), currentGoal,
                routing ? renderRadius : FlightLineRouter.HORIZONTAL_MARGIN_BLOCKS,
                FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        // 飛行判定に掘削・ブロック設置・隙間跳び・落下ダメージはどれも無関係なので全てfalse
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        // 長距離ルートは地図読みだけをここ（メインスレッド）で済ませ、解くのはワーカーで行う。
        // 解きは地図読みの数倍かかり、同期で解くと目標を切り替えるたびに描画が止まる
        CoarseRequest coarseRequest = routing ? coarseRequest(level, player, currentGoal) : CoarseRequest.NONE;
        int minAirY = GameCompat.minBuildHeight(level) + CEILING_MARGIN_BLOCKS;
        int maxAirY = GameCompat.maxBuildHeight(level) - 1 - CEILING_MARGIN_BLOCKS;
        FlightHorizon horizon = loadedHorizon(start, renderRadius);
        long myJob = ++jobGeneration;
        computing = true;

        CompletableFuture
                .supplyAsync(() -> {
                    CoarseSolution fresh = coarseRequest.fresh()
                            ? solveCoarse(coarseRequest.map(), minAirY, maxAirY, from, currentGoal, rockets)
                            : null;
                    CoarseFlightField field = fresh != null ? fresh.field() : coarseRequest.field();
                    // 狙うのは目的地そのもの。読める範囲の外にあっても縁（horizon）で打ち切られ、どの縁から
                    // 出るかは粗い地図の残りコストの場が回り道ごと見積もる。手前の中間目標を狙う形は、
                    // 目標の点が岩の中に落ちるたびに予算を焼き、引き直しと組み合わせると往復した
                    FlightRoute solved = routing
                            ? FlightRouter.route(view, start, goalVec, rockets, tuning, horizon, field,
                                    () -> jobGeneration != myJob)
                            : FlightRoute.NONE;
                    // 曲がり点線は経路が引けなかったときだけ要る。引けているときに重ねると、
                    // 末端から目的地へ伸ばす点線が遠くの山を避けて曲がってしまう
                    List<Vec3> bend = solved.isEmpty()
                            ? new FlightLineRouter(view).findGuideLine(start, goalVec)
                            : null;
                    return new Guidance(solved, bend, from, fresh);
                }, executor)
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (jobGeneration != myJob) {
                        return;
                    }
                    try {
                        computing = false;
                        if (error != null) {
                            LOGGER.error("XaeroNav: 滑空中の経路の計算に失敗しました", error);
                            return;
                        }
                        if (result.coarse() != null) {
                            // 列を作り直したので添字の意味が変わる
                            coarseRoute = new CoarseRoute(currentGoal, from, result.coarse().waypoints(),
                                    result.coarse().field());
                            passedWaypoints = 0;
                        }
                        if (current.stillFlyingTo(currentGoal, dimension)) {
                            route = result.route();
                            guideWaypoints = result.bend();
                            computedFrom = result.from();
                        }
                    } finally {
                        onChanged.run();
                    }
                }));
    }

    /**
     * このtickで何をするか。歩行側と同じ優先順——<b>引き直しは経路が間違っているときだけ</b>で、
     * 前へ伸ばすのは継ぎ足しの仕事。
     *
     * <p>以前は「48ブロック動いたら引き直す」で伸ばそうとしていたが、目標を固定した（狙いを
     * 安定させるための歯止め）とたんに、引き直しても<b>同じ目標へ向かう短い経路</b>が出るだけになった。
     * 進むほど線が短くなり、末端に着いてから次が出る。目標を安定させることと線を前へ伸ばすことは、
     * 全置換では両立しない——これが継ぎ足しが要る理由。
     */
    private Action action(Player player) {
        if (computing) {
            // まだ前の探索が終わっていない。積んでも古い結果を先に反映するだけになる
            return Action.NOTHING;
        }
        if (ticksSinceRecalc < MIN_RECALC_INTERVAL_TICKS) {
            // きっかけが立て続けに成立しても、探索の投入間隔はここで頭打ちにする
            return Action.NOTHING;
        }
        if (FlightProgress.INSTANCE.deviated(XaeroNavConfig.INSTANCE.flightDeviationThresholdBlocks())) {
            return Action.RECOMPUTE;
        }
        Vec3 tail = route.tail();
        if (tail == null) {
            // 経路がまだ無い。周期で投げ直すが、同じ場所からでは結果が変わらないので動いたときだけ
            if (ticksSinceRecalc < XaeroNavConfig.INSTANCE.flightRecalcIntervalTicks()) {
                return Action.NOTHING;
            }
            return computedFrom == null || !computedFrom.equals(player.blockPosition())
                    ? Action.RECOMPUTE : Action.NOTHING;
        }
        if (player.position().distanceTo(tail) > EXTEND_LEAD_BLOCKS) {
            return Action.NOTHING;
        }
        if (tail.equals(extendBlockedAt) && extendBlockedFrom != null
                && Math.sqrt(extendBlockedFrom.distSqr(player.blockPosition()))
                        < EXTEND_RETRY_MOVE_BLOCKS) {
            // この末端からは伸ばせなかった。プレイヤーが動いて新しいチャンクが読めるまで待つ
            return Action.NOTHING;
        }
        return Action.EXTEND;
    }

    /**
     * 設定から空中経路の調整値を組む。診断コマンドが本番とまったく同じ条件で測れるように、
     * 組み立てはここ1箇所に置く（別々に組むと、測った数字が実際の案内と食い違う）。
     */
    static FlightTuning tuning() {
        return tuning(XaeroNavConfig.INSTANCE.flightMaxExpandedNodes());
    }

    private static FlightTuning tuning(int maxExpandedNodes) {
        XaeroNavConfig config = XaeroNavConfig.INSTANCE;
        return new FlightTuning(config.flightCellBlocks(),
                config.flightClearanceDetourBlocks() * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK,
                new SearchLimits(maxExpandedNodes, AStarPathfinder.DEFAULT_TIME_LIMIT_MILLIS,
                        config.flightHeuristicWeight()));
    }

    /**
     * 長距離ルートの中間目標——<b>まだ通っていない分だけ</b>。無ければ空リスト。
     *
     * <p>点線はこれを辿る。太線（読み込み済みチャンクを見る空中経路）が届く所までは確実な経路で、
     * その先は「どちらへ向かうか」しか言えない、という区別をそのまま見た目にしてある。
     */
    private List<BlockPos> coarseWaypoints(boolean airborne, boolean done, BlockPos currentGoal) {
        if (!airborne || done) {
            return List.of();
        }
        CoarseRoute existing = coarseRoute;
        if (existing == null || !existing.goal().equals(currentGoal)) {
            return List.of();
        }
        int from = passedWaypoints;
        return from >= existing.waypoints().size() ? List.of()
                : existing.waypoints().subList(from, existing.waypoints().size());
    }

    /**
     * 点線をどこから描き始めるかを進める。
     *
     * <p><b>基準はプレイヤーではなく太線の末端</b>。点線は末端から続けて引くのに、切り詰めを
     * プレイヤー基準でやっていたため、末端より手前の中間目標が残っていた——点線が末端から
     * いったん自分の近くまで戻ってから改めて先へ伸び、<b>2本の経路があるように見えていた</b>
     * （ユーザー報告）。歩行側で既に「地図の点線は経路の末端、HUDはプレイヤーがいる区間」と
     * 分けてあるのと同じ話。
     *
     * <p>単調にしか進めないのは、予算切れで経路が短くなって末端が後退したときに通過済みが
     * 戻らないようにするため。長距離ルートを引き直したときは{@link #recalculate}の完了callbackが0へ戻す。
     */
    private void advancePassedWaypoints(Player player, BlockPos currentGoal) {
        CoarseRoute existing = coarseRoute;
        if (existing == null || !existing.goal().equals(currentGoal)) {
            return;
        }
        Vec3 tail = route.tail();
        Vec3 from = tail == null ? player.position() : tail;
        passedWaypoints = Math.max(passedWaypoints,
                nearestWaypointIndex(existing.waypoints(), from) + 1);
    }

    /** {@code position}に最も近い中間目標の添字。空リストなら-1。 */
    private static int nearestWaypointIndex(List<BlockPos> waypoints, Vec3 position) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            double distance = centerDistanceSq(waypoints.get(i), position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * 長距離ルートを引き直すなら地図を読み、引き直さないならキャッシュを返す。<b>メインスレッド専用</b>
     * （{@code XaeroMapReader.readSurface}がXaeroの書き込みスレッドと同じ構造を触るため）。
     *
     * <p>天井のある次元だけで作る。地上・エンドは高く上がって直線で飛べるので、チャンク解像度の
     * 粗い層が足せる情報がほとんど無い——水平の迂回を強いるのは岩盤天井だけ。
     */
    private CoarseRequest coarseRequest(Level level, Player player, BlockPos currentGoal) {
        if (!level.dimensionType().hasCeiling()) {
            coarseRoute = null;
            return CoarseRequest.NONE;
        }
        CoarseRoute existing = coarseRoute;
        if (existing != null && existing.goal().equals(currentGoal) && stillFollowing(existing, player)) {
            return new CoarseRequest(false, null, existing.field());
        }
        return new CoarseRequest(true, readCoarseMap(player.blockPosition(), currentGoal), null);
    }

    /**
     * 長距離ルートをどう用意するか。{@code fresh}なら{@code map}（読んだ地図、無ければnull）から解き直し、
     * そうでなければ前回の場（{@code field}）をそのまま使う。
     */
    private record CoarseRequest(boolean fresh, @Nullable CoarseMap map, @Nullable CoarseFlightField field) {
        static final CoarseRequest NONE = new CoarseRequest(false, null, null);
    }

    /**
     * Xaeroの地図から空中の長距離ルートを1本解く。<b>メインスレッド専用</b>
     * （{@code XaeroMapReader.readSurface}がXaeroの書き込みスレッドと同じ構造を触るため）。
     *
     * <p>診断コマンド（{@code /xaeronav debug flight}）もここを通すこと。範囲やマージンを別々に組むと、
     * 測った数字が実際の案内と食い違う——実際に、診断側の独自実装はチャンク範囲が常に1つ狭く、
     * 目的地が地図の外に落ちると「中間目標0本」と報告していた（{@link #tuning()}を1箇所に
     * 置いてあるのと同じ理由）。
     */
    static CoarseRouter.Route solveCoarseRoute(Level level, BlockPos from, BlockPos goal, boolean rockets) {
        return solveCoarseRoute(readCoarseMap(from, goal), GameCompat.minBuildHeight(level) + CEILING_MARGIN_BLOCKS,
                GameCompat.maxBuildHeight(level) - 1 - CEILING_MARGIN_BLOCKS, from, goal, rockets);
    }

    /** 長距離ルートの地図を読む。<b>メインスレッド専用</b>。Xaeroの地図が無ければnull。 */
    private static @Nullable CoarseMap readCoarseMap(BlockPos from, BlockPos goal) {
        return CoarseMapWindow.read(from, goal, CoarseAirMap.MAX_BANDS).map();
    }

    /** 読んだ地図から長距離ルートを解く。Minecraft・Xaeroの状態を読まないので、どのスレッドからでも呼べる。 */
    private static CoarseRouter.Route solveCoarseRoute(@Nullable CoarseMap map, int minAirY, int maxAirY,
                                                       BlockPos from, BlockPos goal, boolean rockets) {
        if (map == null) {
            return new CoarseRouter.Route(List.of(), false);
        }
        return CoarseFlightRouter.findRoute(CoarseAirMap.from(map, minAirY, maxAirY), from, goal, rockets);
    }

    /** {@link #solveCoarseRoute}に加えて、同じ地図から目的地までの残りコストの場も作る。どのスレッドからでも呼べる。 */
    private static CoarseSolution solveCoarse(@Nullable CoarseMap map, int minAirY, int maxAirY, BlockPos from,
                                              BlockPos goal, boolean rockets) {
        if (map == null) {
            return CoarseSolution.NONE;
        }
        CoarseAirMap air = CoarseAirMap.from(map, minAirY, maxAirY);
        return new CoarseSolution(CoarseFlightRouter.findRoute(air, from, goal, rockets).waypoints(),
                CoarseFlightField.toward(air, goal, rockets));
    }

    /**
     * その長距離ルートをまだ辿れているか。辿れている限り引き直さない——同じ地図から同じ結果が
     * 出るだけで、メインスレッドの地図読みを1回焼くことにしかならない。
     */
    private boolean stillFollowing(CoarseRoute existing, Player player) {
        List<BlockPos> waypoints = existing.waypoints();
        if (waypoints.size() - passedWaypoints < COARSE_MIN_REMAINING_WAYPOINTS) {
            // 残りが尽きかけている。この先を作るには引き直すしかない
            return false;
        }
        int nearest = nearestWaypointIndex(waypoints, player.position());
        return nearest >= 0 && Math.sqrt(centerDistanceSq(waypoints.get(nearest), player.position()))
                <= COARSE_OFF_ROUTE_BLOCKS;
    }

    /**
     * 経路の末端から先を継ぎ足す。<b>手前は一切触らない</b>ので、伸びても案内はちらつかない。
     *
     * <p><b>継ぎ足しの目標はプレイヤー中心の読み込み済み正方形の中に置くこと。</b>末端から
     * 一定距離という決め方にすると、目標はプレイヤーから最大「描画半径＋その距離」＝<b>必ず
     * 未ロードチャンクの中</b>に落ちる。未ロードは飛行不可なので探索は毎回失敗し、継ぎ足しが
     * 一度も成功しない——歩行側で実際に踏んだ穴（{@code extendLead}）と同じ形。
     */
    private void extend(Level level, Player player, BlockPos currentGoal) {
        FlightRoute source = route;
        Vec3 tail = source.tail();
        if (tail == null || currentGoal == null) {
            return;
        }
        int renderRadius = level == null ? 0 : ClientCompat.renderDistance(Minecraft.getInstance().options) * 16;
        // 末端から先に残っている「読み込み済みの余地」。ここを超える目標は未ロードの中に落ちる。
        //
        // <b>探索の地平でも頭打ちにする</b>。読み込み済みの余地は最大460ブロックにもなるが、
        // 入り組んだ地形で1回の予算にそれを渡すと届かず、上限まで焼いてから数十ブロックの
        // 部分経路を返す——実機ログで60,000ノード×1秒を8回連続、伸びは12〜80ブロックだった
        // （届いた回はどれも6〜107msで約290ブロック伸びている）。狙う先が届く範囲にあるかどうかが
        // 速さと伸びの両方を決める
        double lead = Math.min(DETAIL_HORIZON_BLOCKS,
                renderRadius * LOADED_MARGIN - player.position().distanceTo(tail));
        if (lead < MIN_EXTENSION_BLOCKS) {
            // まだ伸ばせるだけの余地が無い。プレイヤーが進めば自然に開く
            extendBlockedAt = tail;
            extendBlockedFrom = player.blockPosition();
            return;
        }

        if (horizontalDistance(player.position(), currentGoal) < bestRerouteDistance - REROUTE_PROGRESS_BLOCKS) {
            reroute(level, player, currentGoal, source, renderRadius);
            return;
        }
        // 狙うのは目的地そのもの（recalculateと同じ理由）
        Vec3 target = Vec3.atCenterOf(currentGoal);
        CoarseRoute existing = coarseRoute;
        CoarseFlightField field = existing != null && existing.goal().equals(currentGoal) ? existing.field() : null;
        if (tail.distanceTo(target) < MIN_EXTENSION_BLOCKS) {
            // 末端がもう目的地のすぐ手前。伸ばす先が無い
            extendBlockedAt = tail;
            extendBlockedFrom = player.blockPosition();
            return;
        }

        boolean rockets = hasRockets(player);
        // 箱はプレイヤー中心。末端を始点にしたまま末端中心の箱を作ると、上と同じ理由で外へはみ出す
        SearchBounds bounds = SearchBounds.around(level, player.blockPosition(), new BlockPos(
                        Mth.floor(target.x), Mth.floor(target.y), Mth.floor(target.z)),
                renderRadius, FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        // 継ぎ足しは短い区間を何度も繋ぐので、1回の予算を絞って回数で稼ぐ。満額を許すと
        // 地形が詰まったときに毎回2秒かけて少ししか伸びず、飛ぶ速度に追いつかない
        FlightTuning tuning = tuning(XaeroNavConfig.INSTANCE.flightExtendMaxExpandedNodes());
        BlockPos from = player.blockPosition();
        ResourceKey<Level> dimension = level.dimension();
        ticksSinceRecalc = 0;
        long myJob = ++jobGeneration;
        computing = true;

        long startedAt = System.nanoTime();
        // 出口はプレイヤー中心の読める範囲の縁。末端中心の円にすると、末端の<b>後ろ側</b>の縁も
        // 数十ブロック先にあることになり、前が塞がった途端に後ろから出て線が引き返す
        FlightHorizon horizon = loadedHorizon(player.position(), renderRadius);
        // 引き返しの判定に使う「プレイヤーから末端まで」。投げた時点のもので測る
        int segmentAtStart = FlightProgress.INSTANCE.segmentFor(source);
        List<Vec3> ahead = ahead(source, segmentAtStart, player.position());
        CompletableFuture
                .supplyAsync(() -> {
                    FlightRoute grown = FlightRouter.route(view, tail, target, rockets, tuning, horizon, field,
                            () -> jobGeneration != myJob);
                    if (grown.isEmpty()) {
                        return new Extension(grown, segmentAtStart, null);
                    }
                    return new Extension(grown, segmentAtStart, TurnBack.cut(ahead, grown.points(),
                            new AirGrid(view, grown.cellBlocks())::clearLine));
                }, executor)
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    if (jobGeneration != myJob) {
                        return;
                    }
                    try {
                        computing = false;
                        if (error != null) {
                            LOGGER.error("XaeroNav: 空中経路の継ぎ足しに失敗しました", error);
                            return;
                        }
                        FlightRoute extension = result.route();
                        Vec3 grown = extension.tail();
                        LOGGER.debug("XaeroNav: 空中経路の継ぎ足し ({}, 展開={}, {}ms, 伸び={}ブロック, 格子={})",
                                extension.termination(), extension.expandedNodes(),
                                (System.nanoTime() - startedAt) / 1_000_000L,
                                grown == null ? 0 : Mth.floor(tail.distanceTo(grown)), extension.cellBlocks());
                        if (!current.stillFlyingTo(currentGoal, dimension)) {
                            return;
                        }
                        // 継ぎ足す先が入れ替わっていたら捨てる（引き直しが挟まった場合）
                        if (route != source) {
                            return;
                        }
                        if (extension.isEmpty()) {
                            extendBlockedAt = tail;
                            extendBlockedFrom = from;
                            return;
                        }
                        if (!extension.complete() && tail.distanceTo(grown) < MIN_EXTENSION_BLOCKS) {
                            // 予算を焼き切るか、先が無いと分かって数十ブロックしか伸びなかった。この末端から
                            // 投げ直しても同じことの繰り返しになるので、プレイヤーが進んで地形が変わるまで待つ。
                            // 先が無いときに待たないと、閉じた空間の中で2点の間を10tickごとに引き直し続ける。
                            // 伸びたぶんは捨てずに繋ぐ
                            extendBlockedAt = extension.tail();
                            extendBlockedFrom = from;
                        } else {
                            extendBlockedAt = null;
                            extendBlockedFrom = null;
                        }
                        FlightRoute extended = result.cut() != null
                                ? spliced(source, result.segmentAtStart(), result.cut(), extension)
                                : source.append(extension);
                        if (result.cut() != null) {
                            LOGGER.debug("XaeroNav: 空中経路の継ぎ足しが手前へ戻ってきたので、行って戻る区間を切り落としました");
                        }
                        // 対応づけを引き継がないと、伸ばした瞬間だけ通過済みの区間が描き直される
                        FlightProgress.INSTANCE.carryOver(extended);
                        route = extended;
                        computedFrom = from;
                    } finally {
                        onChanged.run();
                    }
                }));
    }

    /**
     * 末端から継ぎ足す代わりに、プレイヤーの{@link #REROUTE_KEEP_BLOCKS}先から目的地まで引き直す
     * （{@link #REROUTE_PROGRESS_BLOCKS}参照）。そこまでの線は残すので、いま辿っている手前は描き変わらない。
     */
    private void reroute(Level level, Player player, BlockPos currentGoal, FlightRoute source, int renderRadius) {
        bestRerouteDistance = horizontalDistance(player.position(), currentGoal);
        int segment = FlightProgress.INSTANCE.segmentFor(source);
        List<Vec3> kept = keptAhead(source, segment, player.position(), REROUTE_KEEP_BLOCKS);
        Vec3 start = kept.get(kept.size() - 1);
        Vec3 goalVec = Vec3.atCenterOf(currentGoal);
        boolean rockets = hasRockets(player);
        SearchBounds bounds = SearchBounds.around(level, player.blockPosition(), currentGoal, renderRadius,
                FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        FlightTuning tuning = tuning();
        FlightHorizon horizon = loadedHorizon(player.position(), renderRadius);
        CoarseRoute existing = coarseRoute;
        CoarseFlightField field = existing != null && existing.goal().equals(currentGoal) ? existing.field() : null;
        BlockPos from = player.blockPosition();
        ResourceKey<Level> dimension = level.dimension();
        ticksSinceRecalc = 0;
        long myJob = ++jobGeneration;
        computing = true;
        long startedAt = System.nanoTime();
        CompletableFuture
                .supplyAsync(() -> FlightRouter.route(view, start, goalVec, rockets, tuning, horizon, field,
                        () -> jobGeneration != myJob), executor)
                .whenComplete((solved, error) -> Minecraft.getInstance().execute(() -> {
                    if (jobGeneration != myJob) {
                        return;
                    }
                    try {
                        computing = false;
                        if (error != null) {
                            LOGGER.error("XaeroNav: 空中経路の引き直しに失敗しました", error);
                            return;
                        }
                        LOGGER.debug("XaeroNav: 空中経路を{}ブロック先から引き直し ({}, 展開={}, {}ms)",
                                (int) REROUTE_KEEP_BLOCKS, solved.termination(), solved.expandedNodes(),
                                (System.nanoTime() - startedAt) / 1_000_000L);
                        if (!current.stillFlyingTo(currentGoal, dimension) || route != source || solved.isEmpty()) {
                            // 引けなかったら今の線のまま。次の機会は末端からの継ぎ足しになる
                            return;
                        }
                        List<Vec3> points = new java.util.ArrayList<>(source.points().subList(0, segment + 1));
                        points.addAll(kept);
                        points.addAll(solved.points().subList(1, solved.points().size()));
                        FlightRoute rerouted = new FlightRoute(points, solved.termination(),
                                source.expandedNodes() + solved.expandedNodes(), solved.cellBlocks());
                        FlightProgress.INSTANCE.carryOver(rerouted);
                        route = rerouted;
                        computedFrom = from;
                        extendBlockedAt = null;
                        extendBlockedFrom = null;
                    } finally {
                        onChanged.run();
                    }
                }));
    }

    /** {@code route}のうち、プレイヤーがいる区間から先（先頭はプレイヤーの位置）。 */
    private static List<Vec3> ahead(FlightRoute route, int segment, Vec3 player) {
        List<Vec3> points = route.points();
        List<Vec3> result = new java.util.ArrayList<>();
        result.add(player);
        for (int i = segment + 1; i < points.size(); i++) {
            result.add(points.get(i));
        }
        return result;
    }

    /**
     * 継ぎ足しが手前へ戻ってきたときの繋ぎ直し。プレイヤーがいる区間までは元の経路のまま残すので、
     * 進捗の対応づけ（{@link FlightProgress}）はそのまま引き継げる。
     */
    private static FlightRoute spliced(FlightRoute source, int segment, TurnBack.Cut cut, FlightRoute extension) {
        List<Vec3> points = new java.util.ArrayList<>(source.points().subList(0, segment + 1));
        points.addAll(cut.aheadKept());
        points.addAll(cut.rest());
        return new FlightRoute(points, extension.termination(), source.expandedNodes() + extension.expandedNodes(),
                source.cellBlocks());
    }

    /**
     * {@code route}のうち、プレイヤーの真横（いる区間への射影）から{@code blocks}先までの点。末尾がその地点。
     * 経路がそれより短ければ末端まで。
     */
    private static List<Vec3> keptAhead(FlightRoute route, int segment, Vec3 player, double blocks) {
        List<Vec3> points = route.points();
        Vec3 anchor = FlightProgress.INSTANCE.nearestOnRoute(route, player);
        List<Vec3> result = new java.util.ArrayList<>();
        Vec3 previous = anchor == null ? points.get(segment) : anchor;
        result.add(previous);
        double left = blocks;
        for (int i = segment + 1; i < points.size(); i++) {
            Vec3 next = points.get(i);
            double length = previous.distanceTo(next);
            if (length >= left) {
                result.add(length < 1.0e-6 ? next : previous.add(next.subtract(previous).scale(left / length)));
                return result;
            }
            result.add(next);
            left -= length;
            previous = next;
        }
        return result;
    }

    private static double horizontalDistance(Vec3 point, BlockPos goal) {
        return Math.hypot(goal.getX() + 0.5 - point.x, goal.getZ() + 0.5 - point.z);
    }

    /**
     * 空中経路の探索の出口。プレイヤー中心の、読み込み済みと当てにしてよい円
     * （{@link FlightHorizon}参照）。診断コマンドもここを通す。
     */
    static FlightHorizon loadedHorizon(Vec3 player, int renderRadius) {
        return new FlightHorizon(player.x, player.z, renderRadius * LOADED_MARGIN);
    }

    /**
     * ロケット花火を持っているか。上昇コストがこれで切り替わる（ボートの有無で水のコストが
     * 変わるのと同じ形）。
     *
     * <p>持っていないエリトラは定常状態で高度を保てない＝水平飛行そのものが「登り」になるので、
     * ここの真偽で経路の高度の取り方がはっきり変わる。
     */
    private static boolean hasRockets(Player player) {
        return ChunkView.hasItem(GameCompat.inventory(player), stack -> stack.getItem() instanceof FireworkRocketItem);
    }
    private static double centerDistanceSq(BlockPos pos, Vec3 point) {
        double x = pos.getX() + 0.5 - point.x;
        double y = pos.getY() + 0.5 - point.y;
        double z = pos.getZ() + 0.5 - point.z;
        return x * x + y * y + z * z;
    }
}
