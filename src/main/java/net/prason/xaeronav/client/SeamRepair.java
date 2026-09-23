package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathLoops;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.GenerationGate;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.AvoidedCellSource;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.PlannedCellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.util.ChangeGate;

/**
 * <b>繋ぎ目をまたぐ区間だけを解き直す。</b>安くなったならその区間だけ差し替える。
 *
 * <p>継ぎ足しも合流も、後の区間は<b>前の区間がどこを通ったかを知らないまま</b>解かれる。
 * 区間Aは人工的な中間目標へ最適に着くよう解かれるので、「そこへどう着くか」と
 * 「そこからどう出るか」が食い違い、繋ぎ目にだけ角が残る（{@link #SPAN_BLOCKS}）。
 * 両側が揃ったここで初めて、その角を丸められる。
 *
 * <p><b>全部引き直すのでは代わりにならない。</b>オフライン実測（{@code SeamDetourTest}）では、
 * 引き直しても繋ぎ目の遠回りは半分しか消えず（引き直した先にも新しい繋ぎ目ができる）、
 * 足元の線が4〜12回描き変わった。ここは1〜3回で、しかも足元ではなく先の方が変わる。
 *
 * <p>{@link PathfindingState}の目的地/経路/計算中フラグは{@link Host}経由でしか触らない
 * （{@link FlightNavState}が{@code stillFlyingTo}/{@code onChanged}で同じことをしている）。
 * 非同期完了時に読むのは<b>呼んだ時点でキャプチャした値ではなく、その時点の最新の状態</b>——
 * 目的地の変更や経路の差し替えが完了までの間に起きていれば、それを見逃さないため。
 */
final class SeamRepair {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 繋ぎ目をまたいで解き直す長さ（繋ぎ目の手前・先それぞれ何ブロックか）。
     *
     * <p><b>繋ぎ目にだけ遠回りが溜まる</b>のは、区間Aが「{@code detailHorizon}先の人工的な
     * 中間目標へ最適に着く」よう解かれるため——そこへ<b>どう着くか</b>と、そこから<b>どう出るか</b>は
     * 別問題で、両側が揃うまで最適化のしようがない。オフライン実測（{@code SeamDetourTest}）では
     * 繋ぎ目を含む64ブロックの窓が平均1.02〜1.21倍・最悪1.795倍で、繋ぎ目を含まない窓（1.00〜1.05倍）
     * とはっきり分かれた。
     *
     * <p>48は「片側1区間ぶんの半分」。これより短いと角を丸める余地が無く、長くすると
     * <b>まだ歩いていない線が大きく描き変わる</b>方の代償が勝つ。
     */
    private static final double SPAN_BLOCKS = 48.0;

    /**
     * プレイヤーの前方これだけは解き直さない（ブロック）。
     *
     * <p>足元の線が描き変わるのは一度直した症状（「歩いているだけで案内が変わる」）。繋ぎ目は
     * 継ぎ足しの根元＝ふつうは数十ブロック先にあるので、ここを残しても修復の効き目は落ちない。
     */
    private static final double KEEP_BLOCKS = 16.0;

    /** 解き直した区間がこの割合より安くならないなら、線を描き変えない。 */
    private static final double MIN_GAIN = 0.98;

    /**
     * 解き直す区間の最小のステップ数。これを割るなら繋ぎ目の両側が揃っていない
     * （経路の端に寄りすぎている）ので、解き直しても丸める角が無い。
     */
    private static final int MIN_STEPS = 4;

    /**
     * 繋ぎ目の修復に使う重み。<b>ここだけ1.0</b>——修復は「今より確実に安い線」が見つかったときだけ
     * 採るもので、貪欲な重みで別の線を引き当てても交換する意味が無い。区間が96ブロックと短いので
     * 重み1.0でも上の予算に収まる。
     */
    private static final double HEURISTIC_WEIGHT = 1.0;

    /**
     * 解き直し待ちの繋ぎ目を覚えておく数。溢れたら古い方から捨てる。
     *
     * <p>捨てて構わないのは、古い繋ぎ目ほど<b>プレイヤーが既に歩き終えている</b>から——
     * 直しても案内は変わらない。覚え続けると、経路が伸びるほど列だけが伸びていく。
     */
    private static final int QUEUE_LIMIT = 4;

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
    }

    private final PathfindingExecutor executor;
    private final AtomicLong generation;
    private final GenerationGate generationGate;
    private final Runnable onChanged;
    private final Host host;
    private final RecentFailures recentFailures;

    /**
     * まだ解き直していない繋ぎ目の座標（継ぎ足しの根元、または合流点）。
     *
     * <p><b>列で持つ。</b>深い先読みでは継ぎ足しが数tick続けて走るので、1つしか覚えないと
     * 最後の繋ぎ目以外が取りこぼされる。書くのはワーカースレッド（継ぎ足し・合流の完了）、
     * 読むのはtick——{@link #QUEUE_LIMIT}で頭打ちにして古い方から捨てる。
     *
     * <p>添字ではなく座標で持つ。修復が走るまでに合流や迂回で添字がずれうるうえ、
     * 見つからなければ「その繋ぎ目はもう無い」と分かって黙って捨てられる。
     */
    private final Queue<BlockPos> pending = new ConcurrentLinkedQueue<>();

    /**
     * 継ぎ足しが経路の手前へ並走して戻ってきた輪の両端（{@code Extend#noteLoop}）。1つだけ覚える——
     * 新しい輪が見つかる頃には、古い輪は解き直したか、プレイヤーが通り過ぎている。
     *
     * <p>繋ぎ目の列とは別に持つ。{@link PathLoops}が畳めるのは同じ座標を2度踏む輪だけで、1〜2ブロック横を
     * 並走して戻る輪は残る。繋ぎ目の前後{@link #SPAN_BLOCKS}を解き直す通常の修復では、輪の入口が
     * {@link #KEEP_BLOCKS}の内側（足元）にあると入口ごと残ってしまう。輪の両端をそのまま区間にすれば、
     * 入口より手前の線は1ブロックも変わらない。
     */
    private final AtomicReference<Loop> pendingLoop = new AtomicReference<>();

    /** 輪の入口（経路側）と、戻ってきた所（継ぎ足し側）。 */
    record Loop(BlockPos entry, BlockPos rejoin) {
    }

    /** 直近に報告した繋ぎ目の解き直し見送りの理由。同じ理由を毎回出さないための重複除去。 */
    private final ChangeGate<String> refusalGate = new ChangeGate<>();

    SeamRepair(PathfindingExecutor executor, AtomicLong generation, GenerationGate generationGate,
               Runnable onChanged, Host host, RecentFailures recentFailures) {
        this.executor = executor;
        this.generation = generation;
        this.generationGate = generationGate;
        this.onChanged = onChanged;
        this.host = host;
        this.recentFailures = recentFailures;
    }

    /** 解き直し待ちの繋ぎ目が1つも無いか。 */
    boolean isEmpty() {
        return pending.isEmpty() && pendingLoop.get() == null;
    }

    /** 並走して戻る輪を覚える。次の{@link #tryRepair}で、繋ぎ目より先に解き直す。 */
    void queueLoop(Loop loop) {
        pendingLoop.set(loop);
    }

    /** 解き直し待ちの繋ぎ目を覚える。溢れたら古い方から捨てる。 */
    void queue(BlockPos seam) {
        pending.add(seam);
        while (pending.size() > QUEUE_LIMIT) {
            pending.poll();
        }
    }

    /** 目的地の変更で、解き直し待ちの列と直近の見送り理由を捨てる。 */
    void clear() {
        pending.clear();
        pendingLoop.set(null);
        refusalGate.reset();
    }

    /** 全部引き直すとき、手前の経路ごと消える繋ぎ目だけを捨てる。見送り理由はまだ有効なので残す。 */
    void dropPending() {
        pending.clear();
        pendingLoop.set(null);
    }

    /** 直近に報告した見送りの理由（診断用）。 */
    @Nullable String currentRefusal() {
        return refusalGate.current();
    }

    /**
     * 1つの繋ぎ目につき一度だけ試す。断られた繋ぎ目をtickごとに測り直しても、地形も経路も
     * 変わっていないので同じ答えしか返らない。
     *
     * @return 解き直しを投げたか（投げたなら、結果は非同期で反映される）
     */
    boolean tryRepair(Level level, Player player, PathfindingState.DisplayedPath shown, int renderRadius) {
        Loop loop = pendingLoop.getAndSet(null);
        if (loop != null) {
            return tryCutLoop(level, player, shown, renderRadius, loop);
        }
        BlockPos seam = pending.poll();
        if (seam == null) {
            return false;
        }
        PathResult result = shown.result();
        List<PathStep> steps = result.steps();
        int walkedTo = PathProgress.INSTANCE.indexFor(result);
        int seamIndex = stepIndexOf(steps, seam, walkedTo);
        if (seamIndex < 0) {
            // 合流や迂回でその繋ぎ目ごと消えていた。直すものが無い
            noteSeamRepairRefused("繋ぎ目が経路上に無い");
            return false;
        }
        // 足元は残す。ここを削ると「歩いているだけで案内が変わる」に戻る
        int first = walkedTo + 1;
        while (first < steps.size()
                && pathLengthBetween(steps, walkedTo, first) < KEEP_BLOCKS) {
            first++;
        }
        int from = seamIndex;
        while (from > first && pathLengthBetween(steps, from - 1, seamIndex) < SPAN_BLOCKS) {
            from--;
        }
        int to = seamIndex;
        while (to < steps.size() - 1 && pathLengthBetween(steps, seamIndex, to + 1) <= SPAN_BLOCKS) {
            to++;
        }
        if (from < 1 || from >= seamIndex || to <= seamIndex || to - from < MIN_STEPS) {
            // 繋ぎ目の両側が揃っていない（経路の端か、足元に寄りすぎている）
            noteSeamRepairRefused("繋ぎ目の両側が揃っていない (手前=" + (seamIndex - from)
                    + "ステップ, 先=" + (to - seamIndex) + "ステップ)");
            return false;
        }

        solve(level, player, shown, renderRadius, from, to, first, "繋ぎ目=" + seam.toShortString());
        return true;
    }

    /**
     * 輪の入口から戻ってきた所までを解き直す。入口より手前は変えない。
     *
     * <p>輪の中に設置・掘削があれば解き直さない。輪の先のステップが、輪の中で置いたブロックや掘った穴を
     * 前提に繋がっていることがあり、輪ごと消すと足場ごと消える（{@link PathLoops}と同じ理由）。
     */
    private boolean tryCutLoop(Level level, Player player, PathfindingState.DisplayedPath shown, int renderRadius,
            Loop loop) {
        List<PathStep> steps = shown.result().steps();
        int walkedTo = PathProgress.INSTANCE.indexFor(shown.result());
        int entry = stepIndexOf(steps, loop.entry(), walkedTo);
        int rejoin = entry < 0 ? -1 : stepIndexOf(steps, loop.rejoin(), entry + 1);
        if (rejoin < 0) {
            // 通り過ぎたか、合流や引き直しで輪ごと消えていた
            noteSeamRepairRefused("輪が経路上に無い");
            return false;
        }
        for (int i = entry + 1; i <= rejoin; i++) {
            if (steps.get(i).bridging() || steps.get(i).digging()) {
                noteSeamRepairRefused("輪の中に設置・掘削がある");
                return false;
            }
        }
        solve(level, player, shown, renderRadius, entry + 1, rejoin, walkedTo + 1,
                "輪=" + loop.entry().toShortString() + "→" + loop.rejoin().toShortString());
        return true;
    }

    /**
     * 経路の{@code sectionFrom}から{@code sectionTo}まで（両端を含む）を、{@code sectionFrom - 1}から
     * {@code sectionTo}へ引き直した線で置き換える。安くなるときだけ。
     *
     * @param first 置く枚数を数え始める添字（プレイヤーの少し先）
     */
    private void solve(Level level, Player player, PathfindingState.DisplayedPath shown, int renderRadius,
            int sectionFrom, int sectionTo, int first, String label) {
        List<PathStep> steps = shown.result().steps();
        int walkedTo = PathProgress.INSTANCE.indexFor(shown.result());
        BlockPos fromPos = steps.get(sectionFrom - 1).pos();
        BlockPos toPos = steps.get(sectionTo).pos();
        double current = stepsCost(steps, sectionFrom, sectionTo);
        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        SearchBounds bounds = SearchBounds.around(level, fromPos, toPos,
                tuning.searchHorizontalMargin(), PathfindingState.verticalSearchMargin(level, false),
                renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, tuning.movementOptions());
        SearchLimits full = tuning.searchLimits();
        // 予算は1区間と同じ。<b>頭打ちにしてはいけない</b>——6万で切ったところ、実機ログに
        // 「解き直しが繋ぎ目の先へ届かなかった (NODE_BUDGET)」が出て、ネザーの橋だらけの繋ぎ目が
        // 直らないまま残った（オフラインでも局所の遠回りが最悪1.059倍→1.927倍に戻る）。
        // 待ち時間を縛っているのは元々ノード数ではなく壁時計（既定2秒）の方
        SearchLimits limits = new SearchLimits(full.maxExpandedNodes(), full.timeLimitMillis(),
                HEURISTIC_WEIGHT);
        // 差し替えない区間で置くと決まっているぶんは、この区間には使えない
        Carryover carried = new Carryover(Carryover.trailingBridgeRun(steps.subList(0, sectionFrom)),
                Carryover.placements(steps.subList(0, sectionFrom), first)
                        + Carryover.placements(steps, sectionTo + 1));

        BlockPos currentGoal = host.goal();
        long myGeneration = generation.incrementAndGet();
        host.setComputing(true);
        // 層1のガイドは掛けない。大局はこの区間が差し替える経路の側が既に決めていて、ここで要るのは
        // <b>その両端を結ぶいちばん安い線</b>だけ。<b>掛けても効かないことは実測済み</b>——96ブロックの
        // 区間では16ブロック解像度のガイドが幾何Heuristicを下回り、maxで常に負けるので展開ノード数が
        // 1つも変わらなかった（5地形すべてで完全一致）
        PlannedCellSource repairTerrain = new PlannedCellSource(view, steps.subList(0, sectionFrom),
                walkedTo + 1);
        CompletableFuture<PathResult> repairFuture = executor.submit(
                AvoidedCellSource.wrap(repairTerrain, recentFailures.avoided()),
                fromPos, toPos, limits, false, 0, carried);
        generationGate.whenStillCurrent(repairFuture, myGeneration, (repaired, error) -> {
            try {
                host.setComputing(false);
                if (error != null) {
                    if (!(error instanceof CancellationException)) {
                        LOGGER.error("XaeroNav: 繋ぎ目の解き直しに失敗しました", error);
                    }
                    return;
                }
                if (host.displayed() != shown || currentGoal == null || !currentGoal.equals(host.goal())) {
                    return;
                }
                if (!repaired.complete() || repaired.steps().isEmpty()
                        || !PathfindingState.endOf(repaired, fromPos).equals(toPos)) {
                    noteSeamRepairRefused("解き直しが繋ぎ目の先へ届かなかった (" + repaired.termination() + ")");
                    return;
                }
                double replacement = stepsCost(repaired.steps(), 0, repaired.steps().size() - 1);
                if (replacement >= current * MIN_GAIN) {
                    noteSeamRepairRefused("解き直しても安くならない (" + Math.round(current) + "→"
                            + Math.round(replacement) + "tick)");
                    return;
                }
                refusalGate.reset();
                host.setDisplayed(withSection(shown, repaired.steps(), sectionFrom, sectionTo));
                LOGGER.info("XaeroNav: 繋ぎ目を解き直しました ({}, {}→{}tick, {}→{}ステップ, 展開ノード数={})",
                        label, Math.round(current), Math.round(replacement),
                        sectionTo - sectionFrom + 1, repaired.steps().size(), repaired.expandedNodes());
            } finally {
                onChanged.run();
            }
        });
    }

    /**
     * 繋ぎ目を直せなかった理由を残す（診断）。ここが黙っていると、実機で
     * 「繋ぎ目を解き直しました」が出ないときに<b>断っているのか、そもそも走っていないのか</b>が
     * 分からない。同じ理由を毎回出さないよう、直前と違うときだけ出す。
     */
    private void noteSeamRepairRefused(String reason) {
        if (!refusalGate.changed(reason)) {
            return;
        }
        LOGGER.info("XaeroNav: 繋ぎ目の解き直しを見送りました ({})", reason);
    }

    /** {@code from}以降で、この座標を踏んでいるステップの添字。無ければ{@code -1}。 */
    private static int stepIndexOf(List<PathStep> steps, BlockPos pos, int from) {
        for (int i = Math.max(0, from); i < steps.size(); i++) {
            if (steps.get(i).pos().equals(pos)) {
                return i;
            }
        }
        return -1;
    }

    /** 経路に沿った{@code from}から{@code to}までの長さ（ブロック）。 */
    private static double pathLengthBetween(List<PathStep> steps, int from, int to) {
        double length = 0;
        for (int i = Math.max(1, from + 1); i <= to && i < steps.size(); i++) {
            length += Math.sqrt(steps.get(i).pos().distSqr(steps.get(i - 1).pos()));
        }
        return length;
    }

    /** {@code from}から{@code to}まで（両端を含む）のコストの合計。 */
    private static double stepsCost(List<PathStep> steps, int from, int to) {
        double total = 0;
        for (int i = from; i <= to && i < steps.size(); i++) {
            total += steps.get(i).cost();
        }
        return total;
    }

    /**
     * 経路の{@code from}から{@code to}までを差し替えた経路を組み立てる。前後はそのまま残る。
     *
     * <p>差し替えた中にあった区間の切れ目は落とす——その繋ぎ目はもう無い。落としたぶんの
     * 中間目標の番号は後ろの区間が引き取る（HUDのカウンタも地図の点線もそちらを見る）。
     */
    static PathfindingState.DisplayedPath withSection(PathfindingState.DisplayedPath shown,
                                                        List<PathStep> section, int from, int to) {
        List<PathStep> steps = shown.result().steps();
        List<PathStep> merged = new ArrayList<>(steps.subList(0, from));
        merged.addAll(section);
        merged.addAll(steps.subList(to + 1, steps.size()));
        // 差し替えた区間は前後がどこを通るかを知らないので、繋ぎ目で同じ位置を踏み直しうる
        PathLoops.Folded folded = PathLoops.fold(merged);
        int shift = section.size() - (to - from + 1);
        List<PathfindingState.PathSegment> segments = new ArrayList<>();
        int tailWaypointIndex = shown.waypointIndex();
        for (PathfindingState.PathSegment segment : shown.segments()) {
            int endStep = segment.endStep();
            if (endStep < from) {
                segments.add(new PathfindingState.PathSegment(folded.newIndex()[endStep], segment.waypointIndex()));
            } else if (endStep > to) {
                segments.add(new PathfindingState.PathSegment(folded.newIndex()[endStep + shift],
                        segment.waypointIndex()));
            } else {
                tailWaypointIndex = segment.waypointIndex();
            }
        }
        int last = folded.steps().size() - 1;
        if (segments.isEmpty() || segments.get(segments.size() - 1).endStep() < last) {
            segments.add(new PathfindingState.PathSegment(last, tailWaypointIndex));
        }
        PathResult combined = new PathResult(List.copyOf(folded.steps()), shown.result().termination(),
                shown.result().expandedNodes(), shown.result().distinctNodes());
        // 差し替えたのは歩いた先だけなので、いま指している位置はそのまま通用する
        PathProgress.INSTANCE.carryOver(combined);
        return new PathfindingState.DisplayedPath(combined, shown.mode(), shown.waypointIndex(),
                List.copyOf(segments));
    }
}
