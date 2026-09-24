package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathLoops;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.GenerationGate;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.AvoidedCellSource;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.ChangeGate;

/**
 * 経路の帯から外れたときの合流(splice)。<b>経路そのものが生きているなら、全部引き直さずに、
 * いまの位置からその経路へ合流する区間だけを探す。</b>
 *
 * <p>引き直しはこの経路を捨てることを意味する。詳細探索のゴールは再計算のたびに揺れるので、
 * 同じ経路をもう一度引き当てられる保証は無い——実機（エンドの島渡り）では、橋47本を含む
 * 110ステップの完走ルートが逸脱のたびに捨てられ、次の探索は30万ノードを焼いて未到達に
 * 終わっていた。合流区間は{@link #SPLICE_MAX_JOIN_BLOCKS}以内の1点が相手なので桁違いに安く、
 * 成功すれば高い経路（橋・掘削）をそのまま持ち越せる。
 *
 * <p>{@link PathfindingState}の目的地/経路/計算中フラグは{@link Host}経由でしか触らない
 * （{@link FlightNavState}/{@link SeamRepair}と同じ構成）。
 */
final class Splice {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 経路へ合流し直せる最大の距離（ブロック）。これより遠いなら、その経路はもう自分の経路では
     * ないので全部引き直す。
     *
     * <p><b>実測に基づいて64から広げた。</b>実機ログ（2026-08-30、666ステップの経路を維持しながら
     * 20回合流）で、合流区間が実際に使った展開ノード数は<b>2〜116</b>——{@link #SPLICE_MAX_EXPANDED_NODES}
     * (30,000)に対して2〜3桁の余裕があった。一方でユーザー報告は「たまに全部引き直される」で、
     * 64ブロックの壁がその主因（線の先の方にブロックを置くと、合流点が64より遠くなって引き直しに落ちる）。
     *
     * <p>実効値は{@code renderRadius}でも切る（{@link #joinDistanceLimit}）——読み込み済みチャンクの
     * 外にある合流点は{@link SearchBounds}の外なので、いくら許しても原理的に到達しない。
     */
    private static final double SPLICE_MAX_JOIN_BLOCKS = 192.0;

    /**
     * 合流区間の展開ノード数の上限。合流先は{@link #SPLICE_MAX_JOIN_BLOCKS}以内の1点なので、
     * 全体を引き直す探索と同じ予算を与える意味が無い（与えると、合流に失敗したときの待ち時間が
     * 引き直しと同じになり、安く済ませるという目的自体が消える）。
     */
    private static final int SPLICE_MAX_EXPANDED_NODES = 30_000;

    /**
     * 合流点として認める距離の余裕（ブロック）。<b>最も近いステップから</b>これだけの範囲を
     * 同じくらい近いとみなし、その中でいちばん先のステップへ合流する。
     *
     * <p><b>いちばん近い1点を選んではいけない。</b>合流点より手前は捨てるので、
     * <b>先のステップへ合流できるほど残りの道のりが短くなる</b>——距離だけで選ぶと、
     * 経路が曲がっている所で自分より手前のステップが「最も近い」に選ばれ、いま歩いてきた区間を
     * もう一度歩かされる。実測（実機3次元の経路に対し、4/8/16ブロック逸脱した位置から）:
     *
     * <pre>
     *              最も近い           近い中で最後(+8)
     * 地上   平均1.045 最悪1.505 → 平均1.001 最悪1.066
     * 地上2  平均1.030 最悪2.013 → 平均1.003 最悪1.089
     * ネザー 平均1.074 最悪1.782 → 平均1.000 最悪1.048
     * エンド 平均1.091 最悪1.943 → 平均1.002 最悪1.039
     * </pre>
     *
     * <p>「合流までの見積もり＋残りの道のり」で選ぶ方が筋が良さそうに見えるが、<b>実測では
     * 地上で悪化した</b>（平均1.088・最悪1.233）——{@code Heuristic}は幾何学的な下限なので、
     * 川や崖の向こうの点を「近い」と見積もる。余裕を8ブロックに切っておけば、その賭けをせずに
     * 「同じくらい近いなら先の方」だけを取れる。
     */
    private static final double JOIN_SLACK_BLOCKS = 8.0;

    /**
     * 合流のために払ってよい「目的地へ近づかない移動」の上限（tick）。徒歩48ブロック相当。
     *
     * <p>合流区間は障害物を回り込むぶんだけ遠回りになることがあるので、多少の余裕は要る。
     * 止めたいのは<b>回り込みではなく引き返し</b>——崖から飛び降りた直後は、真上の経路が
     * 「最も近い」ままなので、そこへ合流しようとすると崖を登り直す区間が出る。
     */
    private static final double SPLICE_DETOUR_ALLOWANCE_TICKS = 48.0 * ActionCosts.SPRINT_ONE_BLOCK;

    /** 合流に失敗した地点から、これだけ歩けばもう一度試す（ブロック）。 */
    private static final double SPLICE_RETRY_MOVE_BLOCKS = 8.0;

    /** {@link PathfindingState}が持つ、非同期完了時に読み書きする必要のある可変状態。 */
    interface Host {
        /** 現在の目的地。 */
        @Nullable BlockPos goal();

        /** 現在表示中の経路。 */
        PathfindingState.DisplayedPath displayed();

        /** 表示中の経路を差し替える。 */
        void setDisplayed(PathfindingState.DisplayedPath path);

        /** 探索中フラグを立て下げする。 */
        void setComputing(boolean computing);

        /** 目的地までの残りコスト（{@link #spliceWorthTaking}の物差し）。組み上がっていなければ{@code null}。 */
        @Nullable WindowField guide();
    }

    private final PathfindingExecutor executor;
    private final AtomicLong generation;
    private final GenerationGate generationGate;
    private final Runnable onChanged;
    private final Host host;
    private final SeamRepair seamRepair;
    private final RecentFailures recentFailures;

    /** {@link #trySplice}が合流に失敗したときのプレイヤー位置。{@link #SPLICE_RETRY_MOVE_BLOCKS}で失効。 */
    private volatile BlockPos blockedFrom;

    /** 直近に報告した合流拒否の理由。同じ理由を毎tick出さないための重複除去。 */
    private final ChangeGate<String> refusalGate = new ChangeGate<>();

    Splice(PathfindingExecutor executor, AtomicLong generation, GenerationGate generationGate,
           Runnable onChanged, Host host, SeamRepair seamRepair, RecentFailures recentFailures) {
        this.executor = executor;
        this.generation = generation;
        this.generationGate = generationGate;
        this.onChanged = onChanged;
        this.host = host;
        this.seamRepair = seamRepair;
        this.recentFailures = recentFailures;
    }

    /**
     * 新しい経路に対する合流可否は測り直しになる。前の経路で失敗した記録は持ち越さない
     * （目的地の変更・全引き直しの両方で呼ぶ）。
     */
    void clearBlock() {
        blockedFrom = null;
    }

    /** 直近に報告した合流拒否の理由（診断用）。 */
    @Nullable String currentRefusal() {
        return refusalGate.current();
    }

    /**
     * 経路の帯から外れた。<b>経路そのものが生きているなら、全部引き直さずに、いまの位置から
     * その経路へ合流する区間だけを探す。</b>投げたなら{@code true}（結果は非同期で反映される）。
     *
     * <p>合流点は<b>最も近いステップ</b>にして、その手前は捨てる。歩いて先へ進んでいた場合も
     * これで正しく前へ詰む（通り過ぎた区間が残らない）。合流に失敗したときは
     * {@link #SPLICE_RETRY_MOVE_BLOCKS}ぶん歩くまで再挑戦せず、呼び出し側の引き直しに任せる。
     *
     * @param minJoinIndex 合流点として認める最小の添字。塞がった箇所を迂回するときは、そこより
     *                     先へ合流しないと同じ場所へ戻ってしまうので、その次を渡す
     */
    boolean trySplice(Level level, Player player, PathfindingState.DisplayedPath shown, int minJoinIndex) {
        long lap = TickLaps.start();
        try {
            return trySpliceNow(level, player, shown, minJoinIndex);
        } finally {
            TickLaps.add("合流", lap);
        }
    }

    private boolean trySpliceNow(Level level, Player player, PathfindingState.DisplayedPath shown, int minJoinIndex) {
        if (shown.mode() == PathfindingState.PathMode.TO_SURFACE) {
            return false;
        }
        PathResult result = shown.result();
        if (!result.complete() || result.steps().isEmpty()) {
            // 未到達の経路は「その先へ行ける」という保証を持たない。合流しても得るものが無い
            return false;
        }
        BlockPos currentGoal = host.goal();
        BlockPos playerAt = player.blockPosition();
        BlockPos blocked = blockedFrom;
        if (currentGoal == null
                || (blocked != null
                        && blocked.distSqr(playerAt) < SPLICE_RETRY_MOVE_BLOCKS * SPLICE_RETRY_MOVE_BLOCKS)) {
            return false;
        }
        int renderRadius = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        int joinIndex = joinableStepIndex(level, result.steps(), player.position(), minJoinIndex);
        if (joinIndex < 0) {
            // 黙って引き直しへ落ちると、なぜ局所修正できなかったのかがどこにも残らない。
            // 「合流できる素のステップが1つも無い」＝経路が丸ごと橋か、全部塞がっている
            noteSpliceRefused("合流できるステップが無い", result.steps().size(), minJoinIndex, -1);
            return false;
        }
        BlockPos joinPos = result.steps().get(joinIndex).pos();
        double joinDistance = PathfindingState.distanceTo(player.position(), joinPos);
        if (joinDistance > joinDistanceLimit(renderRadius)) {
            noteSpliceRefused("合流点が遠すぎる (" + Math.round(joinDistance) + "ブロック)",
                    result.steps().size(), minJoinIndex, joinIndex);
            return false;
        }
        // 見るのは合流点から先だけ。手前は捨てる区間なので、そこの変化を理由に諦めると、
        // 迂回すれば繋がる経路まで呼び出し側の全引き直しへ落ちる。
        // ここで無効と分かって黙ってfalseを返すと、なぜ合流を諦めたのかがどこにも残らない
        PathValidator.Failure failure = PathValidator.firstFailureFrom(level, result, joinIndex,
                playerAt, renderRadius);
        if (failure != null) {
            LOGGER.debug("XaeroNav: 経路上のセルが変化していたため合流を諦めました ({})", failure.reason());
            return false;
        }

        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        SearchBounds bounds = SearchBounds.around(level, playerAt, joinPos,
                tuning.searchHorizontalMargin(), PathfindingState.verticalSearchMargin(level, false),
                renderRadius);
        long captureLap = TickLaps.start();
        ChunkView view = ChunkView.capture(level, player, bounds, tuning.movementOptions());
        TickLaps.add("チャンク集め", captureLap);
        SearchLimits full = tuning.searchLimits();
        SearchLimits limits = new SearchLimits(Math.min(full.maxExpandedNodes(), SPLICE_MAX_EXPANDED_NODES),
                full.timeLimitMillis(), full.heuristicWeight());

        // ガイドは投げる前に取る。合流区間のコストは今の{@code playerAt}から測ったものなので、
        // 完了時に窓が進んでいると、同じ点が窓の外に出て物差しが幾何下限へ落ちる
        WindowField guide = host.guide();
        long myGeneration = generation.incrementAndGet();
        host.setComputing(true);
        // 合流点は実際に歩けるセル（この経路が通っている）なので、半径を与えずぴったり狙う。
        // 半径で緩めると別のセルに着いてしまい、そこから先の区間が繋がらない
        // 合流点から先はそのまま残るので、そこで置くと決まっているぶんは合流区間には使えない。
        // 引き継がないと、合流のたびに予算が満額に戻って手持ちを超える経路が組み上がる
        Carryover carried = new Carryover(0, Carryover.placements(result.steps(), joinIndex + 1));
        CompletableFuture<PathResult> spliceFuture = executor.submit(
                AvoidedCellSource.wrap(view, recentFailures.avoided()), playerAt, joinPos, limits,
                tuning.costToGoGuideEnabled(), 0, carried);
        generationGate.whenStillCurrent(spliceFuture, myGeneration, TickLaps.timed("受け取り/合流", (splice, error) -> {
            try {
                host.setComputing(false);
                if (error != null) {
                    if (!(error instanceof CancellationException)) {
                        LOGGER.error("XaeroNav: 経路への合流に失敗しました", error);
                    }
                    return;
                }
                if (host.displayed() != shown || !currentGoal.equals(host.goal())) {
                    return;
                }
                if (!splice.complete() || splice.steps().isEmpty()) {
                    blockedFrom = playerAt;
                    LOGGER.debug("XaeroNav: 経路へ合流できませんでした ({}, 合流点={}, 展開ノード数={})",
                            splice.termination(), joinPos.toShortString(), splice.expandedNodes());
                    return;
                }
                double spliceCost = splice.steps().stream().mapToDouble(PathStep::cost).sum();
                if (!spliceWorthTaking(spliceCost, playerAt, joinPos, currentGoal, guide)) {
                    // 合流できるが、そのために元の経路へ引き返すことになる。捨てて全部引き直す
                    // （次のtickでblockedFromが効いて、呼び出し側のrecalculateへ落ちる）
                    //
                    // 断った理由を数字で残す。実機(2026-09-18 23:20)で「経路から5ブロックずれただけで
                    // 完走した233ステップの経路が捨てられる」が出たが、当時のログには合流区間のtickしか
                    // 無く、ガイドが何を言って断ったのかが追えなかった。物差しの3項を並べておけば、
                    // 「ガイドが合流点を過大評価した」のか「本当に引き返しだった」のかが1行で割れる
                    blockedFrom = playerAt;
                    LOGGER.debug("XaeroNav: 合流は引き返しになるので諦めました (合流点={}, 合流区間={}tick, "
                                    + "残り 現在地={} 合流点={}, 経路の実残り={}tick, 物差し={})",
                            joinPos.toShortString(), Math.round(spliceCost),
                            remainingAt(playerAt, currentGoal, guide),
                            remainingAt(joinPos, currentGoal, guide),
                            Math.round(costAlong(result.steps(), joinIndex)),
                            measuredInWindow(playerAt, joinPos, guide) ? "ガイド" : "幾何下限");
                    return;
                }
                blockedFrom = null;
                refusalGate.reset();
                seamRepair.queue(joinPos);
                long spliceLap = TickLaps.start();
                host.setDisplayed(spliced(shown, splice, joinIndex));
                TickLaps.add("合流の差し替え", spliceLap);
                LOGGER.debug("XaeroNav: 経路へ合流しました (合流までの{}ステップ, 引き継いだ{}ステップ, 展開ノード数={})",
                        splice.steps().size(), result.steps().size() - joinIndex - 1, splice.expandedNodes());
            } finally {
                onChanged.run();
            }
        }));
        return true;
    }

    /**
     * 合流点として認める距離の上限。{@link #SPLICE_MAX_JOIN_BLOCKS}と読み込み済み範囲の小さい方。
     *
     * <p>{@code renderRadius}で切るのは、合流区間の探索範囲が{@code SearchBounds.around}で
     * そこまでしか広がらないため——外の合流点を許しても未ロード＝進入不可のセルを舐めるだけで、
     * 予算を捨てて結局引き直しに落ちる。
     */
    private static double joinDistanceLimit(int renderRadius) {
        return Math.min(SPLICE_MAX_JOIN_BLOCKS, renderRadius);
    }

    /**
     * 合流を諦めた理由を残す（診断）。ユーザー報告「局所修正はいいが、たまに全部引き直される」の
     * 残りがどの分岐なのかは、ここが黙っている限り実機ログから分からない。
     *
     * <p>同じ理由を毎tick出さないよう、直前と違うときだけ出す。
     */
    private void noteSpliceRefused(String reason, int steps, int minJoinIndex, int joinIndex) {
        if (!refusalGate.changed(reason)) {
            return;
        }
        LOGGER.debug("XaeroNav: 経路への合流を諦めました ({}, 経路={}ステップ, 最小添字={}, 合流点添字={})",
                reason, steps, minJoinIndex, joinIndex);
    }

    /**
     * 合流区間と、合流点から先の既存の経路を1本に繋ぐ。合流点より手前の区間は捨てる。
     *
     * <p>打ち切り理由は<b>元の経路のもの</b>を引き継ぐ。合流区間は合流点へ届いた（そうでなければ
     * 繋がない）ので、この経路が狙った先まで届くかどうかを決めているのは元の経路の側。
     */
    private static PathfindingState.DisplayedPath spliced(PathfindingState.DisplayedPath shown,
                                                            PathResult splice, int joinIndex) {
        List<PathStep> steps = shown.result().steps();
        List<PathStep> merged = new ArrayList<>(splice.steps());
        merged.addAll(steps.subList(joinIndex + 1, steps.size()));
        // 合流区間は合流点より先がどこを通るかを知らないので、繋ぎ目で同じ位置を踏み直しうる
        PathLoops.Folded folded = PathLoops.fold(merged);
        // 合流点より手前が消えたぶんだけ、区間の境目の添字がずれる
        int shift = splice.steps().size() - (joinIndex + 1);
        List<PathfindingState.PathSegment> segments = new ArrayList<>();
        for (PathfindingState.PathSegment segment : shown.segments()) {
            if (segment.endStep() > joinIndex) {
                segments.add(new PathfindingState.PathSegment(folded.newIndex()[segment.endStep() + shift],
                        segment.waypointIndex()));
            }
        }
        if (segments.isEmpty()) {
            segments.add(new PathfindingState.PathSegment(folded.steps().size() - 1, shown.waypointIndex()));
        }
        PathResult combined = new PathResult(List.copyOf(folded.steps()), shown.result().termination(),
                splice.expandedNodes(), splice.distinctNodes());
        return new PathfindingState.DisplayedPath(combined, shown.mode(), shown.waypointIndex(),
                List.copyOf(segments));
    }

    /**
     * この合流は割に合うか。<b>払ったコストに見合うだけ目的地へ近づいているか</b>で見る。
     *
     * <p>合流点まで実際に掛かるコストと、目的地までの残りがどれだけ縮んだかを比べる。縮んだぶん＋
     * {@link #SPLICE_DETOUR_ALLOWANCE_TICKS}を超えて払っているなら、その合流は前へ進むためではなく
     * <b>元の経路へ戻るため</b>に払っている。
     *
     * <p><b>残りを測る物差しは、払ったコストと同じ単位でなければならない。</b>{@code guide}が無いときの
     * {@link Heuristic}は<b>疾走で進める前提の幾何下限</b>で、地形を触る手間を1つも含まない——溶岩・奈落の上では
     * 1ブロック進むのに橋1本（約35.6tick＝疾走10ブロック相当、{@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}）
     * 掛かるので、前へ進む合流でも実コストが下限の10倍に開き、{@link #SPLICE_DETOUR_ALLOWANCE_TICKS}(171tick)
     * では埋まらない。実機ログ（2026-09-18、ネザーの溶岩の海）では<b>合流8回のうち5回</b>がここで断られ、
     * うち2回はその場で完走ルート（259ステップ・129ステップ）の破棄に直結した。断られた1回は
     * 15ブロック先へ前進する区間で、実コスト807tickに対し幾何下限で縮んだのは40tick。
     * {@link Splice}の冒頭が「捨ててはいけない」と書いているエンドの島渡りと同じ壊れ方を、
     * この判定自身が作っていたことになる。
     *
     * <p>そこで、窓の中の本物の残りコスト（{@link WindowField}）が両端で引けるならそちらで測る——
     * 橋が要る地形なら<b>プレイヤー側の残りにも同じ橋が乗る</b>ので、差を取れば地形の値段が相殺される。
     * 崖のケースは相殺されない（登り直すぶんだけ合流点の残りが縮まない）ので、止めたいものだけが残る。
     *
     * <p><b>探索の後に見るしかない。</b>合流点までの下限（幾何学）で先に判定しようとしても、
     * 崖のケースは下限では引き返しを見抜けない。
     *
     * @param guide 目的地までの残りコスト。{@code null}か、どちらかの点が窓の外なら幾何下限で測る
     */
    static boolean spliceWorthTaking(double spliceCost, BlockPos player, BlockPos joinPos, BlockPos goal,
                                     @Nullable WindowField guide) {
        return spliceCost <= remainingGained(player, joinPos, goal, guide) + SPLICE_DETOUR_ALLOWANCE_TICKS;
    }

    /** 診断用。その点の「目的地までの残り」を、実際に使った物差しで。 */
    private static String remainingAt(BlockPos at, BlockPos goal, @Nullable WindowField guide) {
        if (guide != null && guide.measuredInWindow(at.getX(), at.getZ())) {
            return Math.round(guide.estimate(at.getX(), at.getY(), at.getZ())) + "tick";
        }
        return Math.round(Heuristic.estimate(at.getX(), at.getY(), at.getZ(),
                goal.getX(), goal.getY(), goal.getZ())) + "tick(幾何)";
    }

    /** 診断用。合流点から先を、いま引けている経路どおりに歩いたときの実費。 */
    private static double costAlong(List<PathStep> steps, int from) {
        double total = 0;
        for (int i = from; i < steps.size(); i++) {
            total += steps.get(i).cost();
        }
        return total;
    }

    private static boolean measuredInWindow(BlockPos player, BlockPos joinPos, @Nullable WindowField guide) {
        return guide != null && guide.measuredInWindow(player.getX(), player.getZ())
                && guide.measuredInWindow(joinPos.getX(), joinPos.getZ());
    }

    /** 合流点へ移ることで縮む「目的地までの残り」。 */
    private static double remainingGained(BlockPos player, BlockPos joinPos, BlockPos goal,
                                          @Nullable WindowField guide) {
        if (guide != null && guide.measuredInWindow(player.getX(), player.getZ())
                && guide.measuredInWindow(joinPos.getX(), joinPos.getZ())) {
            return guide.estimate(player.getX(), player.getY(), player.getZ())
                    - guide.estimate(joinPos.getX(), joinPos.getY(), joinPos.getZ());
        }
        return Heuristic.estimate(player.getX(), player.getY(), player.getZ(),
                        goal.getX(), goal.getY(), goal.getZ())
                - Heuristic.estimate(joinPos.getX(), joinPos.getY(), joinPos.getZ(),
                        goal.getX(), goal.getY(), goal.getZ());
    }

    private static int joinableStepIndex(Level level, List<PathStep> steps, Vec3 position, int minIndex) {
        return joinableStepIndex(steps, position, minIndex,
                i -> PathValidator.stepFailure(level, steps.get(i), i) == null);
    }

    /**
     * {@code Level}を切り離した版。合流点選びは経路とプレイヤー位置だけで決まるので、
     * ここだけ取り出せばワールド無しで振る舞いを固定できる（{@code SpliceJoinTest}）。
     *
     * @param usable そのステップが今も通れるか（本番は{@link PathValidator}）
     */
    static int joinableStepIndex(List<PathStep> steps, Vec3 position, int minIndex,
                                  IntPredicate usable) {
        int from = Math.max(0, minIndex);
        // まずは検査を掛けずに測る。経路の検査は重いので、採用しうる候補にだけ掛けたい
        int join = latestWithinSlack(steps, position, from, nearestDistance(steps, position, from, i -> true),
                usable);
        if (join >= 0) {
            return join;
        }
        // 近い一帯が全部塞がっていた。<b>ここで諦めてはいけない</b>——範囲は検査を掛けずに
        // 測った「最も近いステップ」から取るので、その一帯が塞がっていると範囲ごと外れる。
        // 塞がった箇所を迂回する場面（連続してブロックが置かれている）がまさにそれで、
        // 諦めると合流できるのに呼び出し側の全引き直しへ落ちる。通れるステップだけで測り直す
        return latestWithinSlack(steps, position, from, nearestDistance(steps, position, from, usable),
                usable);
    }

    /** {@code from}以降の、橋でなく{@code usable}なステップまでの最短距離。無ければ無限大。 */
    private static double nearestDistance(List<PathStep> steps, Vec3 position, int from,
                                           IntPredicate usable) {
        double nearest = Double.MAX_VALUE;
        for (int i = from; i < steps.size(); i++) {
            if (steps.get(i).bridging()) {
                continue;
            }
            double distance = PathfindingState.distanceTo(position, steps.get(i).pos());
            if (distance < nearest && usable.test(i)) {
                nearest = distance;
            }
        }
        return nearest;
    }

    /**
     * {@code nearest}から{@link #JOIN_SLACK_BLOCKS}以内にある、いちばん先のステップ。
     * 後ろから見るので、最初に見つかったものがそれ。
     */
    private static int latestWithinSlack(List<PathStep> steps, Vec3 position, int from, double nearest,
                                          IntPredicate usable) {
        if (nearest == Double.MAX_VALUE) {
            return -1;
        }
        double limit = nearest + JOIN_SLACK_BLOCKS;
        for (int i = steps.size() - 1; i >= from; i--) {
            PathStep step = steps.get(i);
            if (step.bridging() || PathfindingState.distanceTo(position, step.pos()) > limit) {
                continue;
            }
            if (usable.test(i)) {
                return i;
            }
        }
        return -1;
    }
}
