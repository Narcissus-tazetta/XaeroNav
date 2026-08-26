package net.prason.xaeronav.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

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
import net.prason.xaeronav.pathfinding.flight.CoarseAirMap;
import net.prason.xaeronav.pathfinding.flight.CoarseFlightRouter;
import net.prason.xaeronav.pathfinding.flight.FlightLineRouter;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.flight.FlightRouter;
import net.prason.xaeronav.pathfinding.flight.FlightTuning;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

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

    private static final Logger LOGGER = LogUtils.getLogger();

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
     * 狙っている中間目標をこれだけ手前まで詰めたら次へ進める（ブロック）。
     *
     * <p>この歯止めが無いと、目標は「届く範囲で最も遠い中間目標」なのでプレイヤーが64ブロック進む
     * たびに1つ先へ移る。目標が動けば同じ始点でも別の経路が出るので、太線が数秒おきに描き変わる。
     * 同じ点を狙い続けているあいだは経路も安定する。
     */
    private static final int AIM_ADVANCE_BLOCKS = 96;

    /**
     * 岩盤天井の下に取る余白（ブロック）。天井は不透明なのでXaeroの洞窟レイヤーには床として
     * 記録されない——ここで頭打ちにしないと、最上段の高度帯が岩の中まで伸びる。
     */
    private static final int CEILING_MARGIN_BLOCKS = 10;

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
    private record CoarseRoute(BlockPos goal, BlockPos computedFrom, List<BlockPos> waypoints) {
    }

    /** 空中経路と、その代わりに使う曲がり点線。どちらを使うかは計算した側が決める。 */
    private record Guidance(FlightRoute route, List<Vec3> bend, BlockPos from) {
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
     * 滑空中の点線を曲げる計算専用。A*とはライフサイクルも打ち切り方も関係が無いので、
     * {@code PathfindingExecutor}（呼ぶたび前のジョブを打ち切る）ではなく素のスレッドを1本持つ。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-flight-line");
        thread.setDaemon(true);
        return thread;
    });

    /** 空中経路（太線で描く本体）。引けなければ空。 */
    private volatile FlightRoute route = FlightRoute.NONE;

    /**
     * 探索がワーカースレッドで走っている最中か。{@link #executor}は前のジョブを打ち切らず
     * <b>キューに積む</b>ので、これが無いと1回2秒かかる探索を1秒ごとに投げてキューが際限なく
     * 伸びる（実機ログ: ネザーで10万ノード・2.1秒）。表示される経路は遅れる一方になり、
     * CPUは焼き続ける。
     */
    private volatile boolean computing;

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

    /**
     * いま狙っている中間目標。座標で覚えるのは、長距離ルートを引き直すと添字の意味が変わるため
     * （新しい列に無ければ歯止めは自動的に外れる）。歩行の{@code lastAimedWaypoint}と同じ考え方。
     */
    private volatile BlockPos aimedWaypoint;

    /**
     * 通過済みとみなす中間目標の数。地図・ワールドの点線をどこから描くかにだけ使う。
     * 単調に進める——経路が予算切れで短くなって末端が後退しても、通過済みが戻らないようにする。
     */
    private volatile int passedWaypoints;

    /** 空中経路が引けなかったときの代替。目的地への点線を山の上・横へ曲げた2〜3点。 */
    private volatile List<Vec3> guideWaypoints;

    /** クライアントスレッド専用。 */
    private int ticksSinceRecalc;

    FlightNavState(Current current) {
        this.current = current;
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

    /** 目的地ごと捨てる。{@link #computing}は下ろさない——走っている探索の結果は{@link Current}が弾く。 */
    void reset() {
        dropRoute();
        coarseRoute = null;
        aimedWaypoint = null;
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
        int renderRadius = mc.options.getEffectiveRenderDistance() * 16;
        // 水平マージンを描画距離に揃えて、読み込み済みの正方形をまるごと探索範囲に入れる。
        // 壁を回り込む経路は始点と目的地を結ぶ帯の外へ出るので、狭いマージンでは回り込めない
        SearchBounds bounds = SearchBounds.around(level, player.blockPosition(), currentGoal,
                routing ? renderRadius : FlightLineRouter.HORIZONTAL_MARGIN_BLOCKS,
                FlightLineRouter.VERTICAL_MARGIN_BLOCKS, renderRadius);
        // 飛行判定に掘削・ブロック設置・隙間跳び・落下ダメージはどれも無関係なので全てfalse
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        List<BlockPos> coarse = routing
                ? updateCoarseRoute(level, player, currentGoal, rockets)
                : List.of();
        // 探索が狙う先は、届く範囲で最も遠い中間目標。読み込み済みの縁より少し内側に置く——
        // 縁ちょうどを狙うと、その周りのセルが未ロード＝飛行不可で必ず未到達に終わる
        Vec3 detailTarget = detailTarget(start, goalVec, coarse);
        computing = true;

        CompletableFuture
                .supplyAsync(() -> {
                    FlightRoute solved = routing
                            ? FlightRouter.route(view, start, detailTarget, rockets, tuning)
                            : FlightRoute.NONE;
                    // 曲がり点線は経路が引けなかったときだけ要る。引けているときに重ねると、
                    // 末端から目的地へ伸ばす点線が遠くの山を避けて曲がってしまう
                    List<Vec3> bend = solved.isEmpty()
                            ? new FlightLineRouter(view).findGuideLine(start, goalVec)
                            : null;
                    return new Guidance(solved, bend, from);
                }, executor)
                .whenComplete((result, error) -> {
                    computing = false;
                    if (error != null) {
                        LOGGER.error("XaeroNav: 滑空中の経路の計算に失敗しました", error);
                        return;
                    }
                    if (current.stillFlyingTo(currentGoal, dimension)) {
                        route = result.route();
                        guideWaypoints = result.bend();
                        computedFrom = result.from();
                    }
                });
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
     * 戻らないようにするため。長距離ルートを引き直したときは{@link #updateCoarseRoute}が0へ戻す。
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
            double distance = waypoints.get(i).distToCenterSqr(position);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * 長距離ルートを引き直すべきなら引き直して返す。<b>メインスレッド専用</b>
     * （{@code XaeroMapReader.readSurface}がXaeroの書き込みスレッドと同じ構造を触るため）。
     *
     * <p>天井のある次元だけで作る。地上・エンドは高く上がって直線で飛べるので、チャンク解像度の
     * 粗い層が足せる情報がほとんど無い——水平の迂回を強いるのは岩盤天井だけ。
     */
    private List<BlockPos> updateCoarseRoute(Level level, Player player, BlockPos currentGoal,
                                              boolean rockets) {
        if (!level.dimensionType().hasCeiling()) {
            coarseRoute = null;
            return List.of();
        }
        BlockPos from = player.blockPosition();
        CoarseRoute existing = coarseRoute;
        if (existing != null && existing.goal().equals(currentGoal) && stillFollowing(existing, player)) {
            return existing.waypoints();
        }

        CoarseRouter.Route solved = solveCoarseRoute(level, from, currentGoal, rockets);
        // 列を作り直したので添字の意味が変わる。座標で覚えている狙い（aimedWaypoint）は
        // 新しい列に同じ点があれば生き残り、無ければ自然に外れる
        coarseRoute = new CoarseRoute(currentGoal, from, solved.waypoints());
        passedWaypoints = 0;
        return solved.waypoints();
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
        CoarseMap map = CoarseMapWindow.read(from, goal, CoarseAirMap.MAX_BANDS);
        if (map == null) {
            return new CoarseRouter.Route(List.of(), false);
        }
        CoarseAirMap air = CoarseAirMap.from(map, level.getMinBuildHeight() + CEILING_MARGIN_BLOCKS,
                level.getMaxBuildHeight() - 1 - CEILING_MARGIN_BLOCKS);
        return CoarseFlightRouter.findRoute(air, from, goal, rockets);
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
        return nearest >= 0 && Math.sqrt(waypoints.get(nearest).distToCenterSqr(player.position()))
                <= COARSE_OFF_ROUTE_BLOCKS;
    }

    /**
     * 空中経路が今回狙う先。長距離ルートがあれば<b>届く範囲で最も遠い中間目標</b>、
     * 無ければ本来の目的地。
     *
     * <p>目的地そのものを毎回狙うと、読み込み済みチャンクの外にあるのが常態なので探索は必ず
     * 予算を焼き切る。手前の中間目標に切り替えると、同じ予算で「確実に引ける区間」を引き切れる。
     * 歩行の{@code reachableWaypointTarget}と同じ考え方。
     *
     * <p>探すのは<b>プレイヤーに最も近い中間目標より先</b>だけ。全体から最も遠いものを選ぶと、
     * ルートが自分の近くへ折り返す地形で通り過ぎた点を掴み、案内が後戻りする。
     */
    private Vec3 detailTarget(Vec3 start, Vec3 goalVec, List<BlockPos> waypoints) {
        double reach = DETAIL_HORIZON_BLOCKS;
        if (waypoints.isEmpty() || start.distanceTo(goalVec) <= reach) {
            aimedWaypoint = null;
            return goalVec;
        }
        // 後戻りの歯止め。列の添字はルートの順序なので、これより手前は「もう通った」ことになる
        int from = Math.max(nearestWaypointIndex(waypoints, start) + 1, passedWaypoints);
        // いま狙っている点がまだ列にあって、まだ十分先なら狙い続ける。目標が動くと同じ始点でも
        // 別の経路が出るので、ここを毎回選び直すと太線が数秒おきに描き変わる。
        //
        // <b>距離だけで判断してはいけない</b>——角を曲がりきれずに中間目標の脇を通り過ぎると、
        // 距離は96を超えたままなので歯止めが永久に外れず、経路が<b>後ろの点へ引き返す</b>
        // （ユーザー報告「あるところに行ってから戻らされる」）。歩行側の
        // 「最寄りのwaypointフォールバックが通り過ぎた点を掴んで引き返す」とまったく同じ穴
        BlockPos aimed = aimedWaypoint;
        if (aimed != null) {
            int index = waypoints.indexOf(aimed);
            double distance = Math.sqrt(aimed.distToCenterSqr(start));
            if (index >= from && distance > AIM_ADVANCE_BLOCKS && distance <= reach) {
                return Vec3.atCenterOf(aimed);
            }
        }
        BlockPos target = null;
        for (int i = from; i < waypoints.size(); i++) {
            if (Math.sqrt(waypoints.get(i).distToCenterSqr(start)) > reach) {
                break;
            }
            target = waypoints.get(i);
        }
        aimedWaypoint = target;
        if (target == null) {
            return goalVec;
        }
        LOGGER.info("XaeroNav: 空中経路の目標を切り替えました (目標={}, {}, {}, 中間目標={}本)",
                target.getX(), target.getY(), target.getZ(), waypoints.size());
        return Vec3.atCenterOf(target);
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
        int renderRadius = level == null ? 0 : Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
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

        Vec3 goalVec = Vec3.atCenterOf(currentGoal);
        CoarseRoute existing = coarseRoute;
        List<BlockPos> coarse = existing != null && existing.goal().equals(currentGoal)
                ? existing.waypoints() : List.of();
        Vec3 target = extensionTarget(tail, goalVec, coarse, lead);
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
        computing = true;

        long startedAt = System.nanoTime();
        CompletableFuture
                .supplyAsync(() -> FlightRouter.route(view, tail, target, rockets, tuning), executor)
                .whenComplete((extension, error) -> {
                    computing = false;
                    if (error != null) {
                        LOGGER.error("XaeroNav: 空中経路の継ぎ足しに失敗しました", error);
                        return;
                    }
                    Vec3 grown = extension.tail();
                    LOGGER.info("XaeroNav: 空中経路の継ぎ足し ({}, 展開={}, {}ms, 伸び={}ブロック, 格子={})",
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
                    if (extension.budgetExhausted() && tail.distanceTo(grown) < MIN_EXTENSION_BLOCKS) {
                        // 予算を焼き切って数十ブロックしか伸びなかった。この末端から投げ直しても
                        // 同じことの繰り返しになるので、プレイヤーが進んで地形が変わるまで待つ。
                        // 伸びたぶんは捨てずに繋ぐ
                        extendBlockedAt = extension.tail();
                        extendBlockedFrom = from;
                    } else {
                        extendBlockedAt = null;
                        extendBlockedFrom = null;
                    }
                    FlightRoute extended = source.append(extension);
                    // 対応づけを引き継がないと、伸ばした瞬間だけ通過済みの区間が描き直される
                    FlightProgress.INSTANCE.carryOver(extended);
                    route = extended;
                    computedFrom = from;
                });
    }

    /**
     * 継ぎ足しが狙う先。{@code lead}の内側で最も遠い中間目標、無ければ{@code lead}ぶん目的地へ寄った点。
     *
     * <p><b>「先」は列の添字で決める</b>（＝ルートの順序）。「目的地に近い方」で選ぶと、ルートが
     * 曲がっている所で<b>後ろの中間目標の方が直線距離では目的地に近い</b>ことがあり、経路が
     * 引き返す。歩行側が添字で持っているのと同じ理由。
     */
    static Vec3 extensionTarget(Vec3 tail, Vec3 goalVec, List<BlockPos> waypoints, double lead) {
        if (tail.distanceTo(goalVec) <= lead) {
            return goalVec;
        }
        Vec3 target = null;
        for (int i = nearestWaypointIndex(waypoints, tail) + 1; i < waypoints.size(); i++) {
            if (Math.sqrt(waypoints.get(i).distToCenterSqr(tail)) > lead) {
                break;
            }
            target = Vec3.atCenterOf(waypoints.get(i));
        }
        if (target != null) {
            return target;
        }
        // 中間目標が無い（未訪問領域）。目的地へ向かう直線上のleadぶん先を狙う
        return tail.add(goalVec.subtract(tail).normalize().scale(lead));
    }

    /**
     * ロケット花火を持っているか。上昇コストがこれで切り替わる（ボートの有無で水のコストが
     * 変わるのと同じ形）。
     *
     * <p>持っていないエリトラは定常状態で高度を保てない＝水平飛行そのものが「登り」になるので、
     * ここの真偽で経路の高度の取り方がはっきり変わる。
     */
    private static boolean hasRockets(Player player) {
        return player.getInventory().contains(stack -> stack.getItem() instanceof FireworkRocketItem);
    }
}
