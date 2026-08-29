package net.prason.xaeronav.pathfinding.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathSafetyChecker;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.RunCaps;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.astar.Tolerances;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;

/**
 * ワーカースレッドでA*を実行する。新しいリクエストが来たら
 * 実行中(または未着手)の古いジョブをキャンセルし、常に最新のリクエストだけが結果を返す。
 *
 * <p>{@link CellSource}の構築（メインスレッドでのチャンク参照集め）は呼び出し側の責務。
 * このクラスはA*の実行と、そのキャンセル制御、危険箇所の注釈付けまでを担当する。
 * 注釈付けをここに置くのは、経路を求めたビューと注釈に使うビューを取り違えないようにするため。
 */
public final class PathfindingExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-pathfinding");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 粗い経由地チェーンの中間の経由地を、ゴールとして許す半径（ブロック）。
     *
     * <p>経由地は1セル＝1チャンク(16ブロック)の代表点なので、実際の通り道はその中心から
     * 最大8ブロックずれていて当然。座標ぴったりを要求すると、そのための遠回りが経路に乗る。
     * 半径はセルの半幅に合わせる。
     */
    private static final int COARSE_LEG_GOAL_RADIUS_BLOCKS = 8;

    /**
     * 区間ごとのコストガイドを組む地図の水平マージン（ブロック）。区間の始点・終点を含めば十分——
     * ガイドは幾何学的なHeuristicとのmaxを取って使うだけの補助（{@link CostToGo}のdocを参照）で、
     * 遠くまで見通す必要は無い。
     *
     * <p><b>この値を大きくしない。</b>{@link CoarseRouter#costToGo}は箱の面積に比例した配列を
     * 毎回新規確保してDijkstraを回す（{@code chunksX*chunksZ*MAX_FLOORS}状態）。区間分割全体の
     * 経路計画（{@link CoarseRouter#findRoute}、1回だけ）に使う広い箱をそのままここへ流用すると、
     * 区間の数だけ広い箱ぶんのDijkstraを払うことになる。実機（ジ・エンドの崖ぎわ、2026-08-28）で
     * 箱を4倍(64→256)に広げたところ、1区間の探索が0.7〜0.9秒から0.95〜1.15秒に伸び、
     * `renderRadius`いっぱいまで広げると1.4〜1.6秒まで伸びた——チェーン全体の2秒予算を
     * 区間1つで食い潰し、上限緩和の段が動く時間が無くなった。
     */
    private static final int COARSE_LEG_GUIDE_MARGIN_BLOCKS = 64;

    /**
     * 上限で詰んだときに緩める倍率。最後は{@link RunCaps#NONE}（無制限）で締める。
     *
     * <p>いきなり無制限にすると、初回の詰みで唐突に長大な橋・長時間の潜水が案内に出かねない。
     * 段階を踏むことで、実際に道を作るのに必要な最小限の長さで収まりやすくする。
     */
    private static final int[] RUN_CAP_LOOSEN_MULTIPLIERS = {2, 4};

    private final AtomicReference<PathfindingJob> currentJob = new AtomicReference<>();

    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits) {
        return submit(view, start, goal, limits, true);
    }

    /**
     * {@code costToGoGuideEnabled}を明示的に指定する版。既定（引数無しの{@link #submit}）はtrue——
     * 層1のcost-to-go（{@link #buildCostToGoGuide}）を幾何学的なHeuristicと併用する。設定で
     * 切れるようにする理由は{@code XaeroNavConfig#costToGoGuideEnabled}を参照。
     *
     * <p>この設定値をここで{@code XaeroNavConfig}から直接読まないのは、{@link PathfindingExecutor}が
     * 単体テスト対象（{@code PathfindingExecutorCoarseGuidedTest}）で、NeoForgeの設定システムが
     * ロードされていない環境からも呼べる必要があるため。読み出しは呼び出し側
     * （{@code PathfindingState}）の責務にする。
     */
    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                                 boolean costToGoGuideEnabled) {
        return submit(view, start, goal, limits, costToGoGuideEnabled, 0);
    }

    /**
     * {@code goalRadius}を明示する版。長距離ルートの中間目標のように「向かう方角」でしかない
     * ゴールには半径を与えて、座標ぴったりへ寄せるための遠回りを避ける
     * （{@link AStarPathfinder#search(BlockPos, BlockPos, BooleanSupplier, int)}参照）。
     */
    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                                 boolean costToGoGuideEnabled, int goalRadius) {
        return submit(cancelled -> {
            CostToGo costToGo = costToGoGuideEnabled ? buildCostToGoGuide(view, start, goal, cancelled) : null;
            return search(view, limits, cancelled, costToGo, (pathfinder, c) ->
                    // 立てない座標のまま探索すると経路が1本も伸びない。ブロックを読める場所での
                    // 寄せ直しなので、メインスレッドへ戻さずここで行う
                    pathfinder.search(StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal),
                            c, goalRadius));
        });
    }

    /**
     * {@link LiveCoarseSampler}で組んだ粗い地図から、このゴールへのcost-to-goガイドを作る。
     * {@code view.bounds()}の箱に限れば{@link CoarseRouter}の逆向きDijkstra1回は数msで終わる
     * （描画距離32相当で65×65セル、最大4床）。Xaeroの地図に依存せず読み込み済みチャンクの
     * 生データだけを見るので、ワーカースレッド上で完結できる（メインスレッド境界を動かさない）。
     *
     * <p>ボート所持の有無は見ない（{@code false}固定）。ガイドは{@code AStarPathfinder}側で
     * 幾何学的なヒューリスティックとのmaxを取って使うだけなので、多少粗くても実害が無い——
     * 損をするのは「ボートがあるのに引き締めが甘くなる」程度で、非許容にはならない。
     */
    private static CostToGo buildCostToGoGuide(CellSource view, BlockPos start, BlockPos goal,
                                                BooleanSupplier cancelled) {
        CoarseMap coarseMap = LiveCoarseSampler.sample(view, view.bounds(), start.getY(), cancelled);
        CoarseRouter.BridgePolicy bridgePolicy = view.lavaBridgingEnabled()
                ? CoarseRouter.BridgePolicy.BRIDGE : CoarseRouter.BridgePolicy.ALLOW;
        return CoarseRouter.costToGo(coarseMap, goal, false, bridgePolicy);
    }

    /**
     * {@link #submit}と違い、{@link StanceFinder}による寄せ直しと{@link PathSafetyChecker}による
     * 危険箇所の注釈付けを行わない薄い版。呼び出し側が始点・終点をすでに立てる座標へ解決済みで、
     * 結果を実際に歩く経路としてではなく中間データ（waypoint選定など）として使う場合に使う。
     */
    public CompletableFuture<PathResult> submitRaw(CellSource view, BlockPos start, BlockPos goal,
                                                    SearchLimits limits) {
        return submit(cancelled -> new AStarPathfinder(view, limits).search(start, goal, cancelled));
    }

    /**
     * 地下から地上へ出る経路を、目的地の真下ではなく「y &gt;= surfaceY の空の下」を探して求める
     * （地上優先ナビ。{@link net.prason.xaeronav.client.PathfindingState}参照）。
     *
     * <p>まず{@code onFoot}（掘削を禁じたビュー）で探し、地上まで届かなかったときだけ{@code digging}で
     * 探し直す。掘削を許したまま1度で済ませると、石を含めて分岐が桁違いに増え、展開数の上限が
     * 数十ブロック先で尽きてしまう。そこで返るのは「その場から少し掘り上がる」だけの未到達な経路で、
     * 辿っても地上には出られない。掘削を切れば通れるのは既存の空洞だけになり、同じ展開数で
     * 洞窟や坑道を遥かに遠くまで辿れる。
     */
    public CompletableFuture<PathResult> submitToSurface(CellSource onFoot, CellSource digging, BlockPos start,
                                                          int surfaceY, SearchLimits limits) {
        return submit(cancelled -> {
            PathResult walked = search(onFoot, limits, cancelled, (pathfinder, c) ->
                    pathfinder.searchToSurface(StanceFinder.resolveStart(onFoot, start), surfaceY, c));
            if (walked.complete()) {
                return walked;
            }
            return search(digging, limits, cancelled, (pathfinder, c) ->
                    pathfinder.searchToSurface(StanceFinder.resolveStart(digging, start), surfaceY, c));
        });
    }

    /**
     * 詳細探索が展開ノード数の上限に当たって未到達だったときの再挑戦（層3の局所障害
     * 対策）。読み込み済みチャンクの生データから粗い地図を組み立て（{@link LiveCoarseSampler}）、
     * その上で{@link CoarseRouter}が引いた経由地を1区間ずつ詳細A*で辿る。1回の長い探索より
     * 短い区間の連続の方が、同じ予算でも局所的な崖・湖を迂回しやすい。
     *
     * <p>{@link LiveCoarseSampler}は{@code CellSource}を読むだけ（Xaeroの地図とは無関係）なので、
     * 粗い地図の組み立てから区間ごとの探索まですべてこのワーカースレッド上で完結できる
     * （層2の廊下精緻化と違い、メインスレッドへ戻す必要がない）。
     */
    public CompletableFuture<PathResult> submitCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start,
                                                             BlockPos goal, SearchLimits limits) {
        return submitCoarseGuided(view, bounds, start, goal, limits, true);
    }

    /** {@code costToGoGuideEnabled}を明示的に指定する版。{@link #submit(CellSource, BlockPos, BlockPos,
     * SearchLimits, boolean)}と同じ理由で、設定の読み出しは呼び出し側に委ねる。 */
    public CompletableFuture<PathResult> submitCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start,
                                                             BlockPos goal, SearchLimits limits,
                                                             boolean costToGoGuideEnabled) {
        return submitCoarseGuided(view, bounds, start, goal, limits, costToGoGuideEnabled, 0);
    }

    /** {@code goalRadius}を明示する版。最終ゴールにだけ効く（区間の経由地は元から粗い点なので常に領域）。 */
    public CompletableFuture<PathResult> submitCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start,
                                                             BlockPos goal, SearchLimits limits,
                                                             boolean costToGoGuideEnabled, int goalRadius) {
        return submit(cancelled -> solveCoarseGuided(view, bounds, start, goal, limits, cancelled,
                costToGoGuideEnabled, goalRadius));
    }

    private static PathResult solveCoarseGuided(CellSource view, SearchBounds bounds, BlockPos start, BlockPos goal,
                                                 SearchLimits limits, BooleanSupplier cancelled,
                                                 boolean costToGoGuideEnabled, int goalRadius) {
        CoarseMap coarseMap = LiveCoarseSampler.sample(view, bounds, start.getY(), cancelled);
        // 橋を架けられるなら粗い側でも溶岩を通す。ここを一律ALLOWにすると、溶岩の海の縁では
        // 出発点自身のセルがLAVA＝通行不能になって区間分割が1つも作れず、溶岩の海を1回の探索で
        // 渡ろうとして予算を焼き切る（実機で踏んだ: ステップ数0のまま20万ノード）
        CoarseRouter.BridgePolicy bridgePolicy = view.lavaBridgingEnabled()
                ? CoarseRouter.BridgePolicy.BRIDGE : CoarseRouter.BridgePolicy.ALLOW;
        CoarseRouter.Route route = CoarseRouter.findRoute(coarseMap, start, goal, false, bridgePolicy);
        // 粗い地図が空のまま「経路あり」になるのが最悪の失敗（全セルNO_DATAは通行可能なので、
        // 溶岩を無視した直線が引けてしまう）。知られたセル数を出しておかないと、
        // 「区間分割が下手」なのか「そもそも地形が見えていない」のかを切り分けられない
        // 種類の内訳（kindBreakdown）を併記する。既知セル数だけでは「奈落が奈落として見えて
        // いるのか、そもそもデータが無いのか」を切り分けられない——NO_DATAはUNKNOWN_MULTIPLIER
        // (1.6倍)でほぼ最安なので、奈落がそちらへ倒れていれば奈落を突っ切る線が安く見える理由になる。
        // 実際にこの内訳で「奈落は正しく検出されている」を確認し、原因の候補を1つ潰した
        LOGGER.info("XaeroNav: 粗い経由地チェーンの地図 (既知セル={}/{}, {}, 中間目標={}個, 溶岩={})",
                coarseMap.knownCells(), coarseMap.totalCells(), coarseMap.kindBreakdown(),
                route.waypoints().size(), bridgePolicy);
        if (route.waypoints().isEmpty()) {
            // 粗い側でも道が見つからない（孤立した地形等）。直接探索と同じ結果に留める
            CostToGo directCostToGo = costToGoGuideEnabled
                    ? CoarseRouter.costToGo(coarseMap, goal, false, bridgePolicy) : null;
            return search(view, limits, cancelled, directCostToGo, (pathfinder, c) ->
                    pathfinder.search(StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal),
                            c, 0, goalRadius));
        }

        List<BlockPos> rawLegGoals = new ArrayList<>(route.waypoints());
        rawLegGoals.add(goal);
        // 展開数の上限は区間数で割らずに満額渡す。SearchLimitsが言うとおりこれは「届かなかった
        // ときに打ち切る天井」であって払うコストではなく、区間が短いほど実際の展開数は少なく済む。
        // 割ると、届くはずの区間が手前で切れるだけになる（実機で30000÷3区間=10000となり山岳地形の
        // 1区間目すら届かなかった）。
        //
        // 代わりにチェーン全体を、単一探索1回分と同じ時間で縛る。区間ごとに固定の上限を置くと
        // 区間数ぶんまで伸びてしまい、これの代替手段であるはずのチェーンだけが青天井になる。
        // その中で各区間が残り時間を山分けする（下のlegShare）
        long chainDeadline = System.currentTimeMillis() + limits.timeLimitMillis();

        List<PathStep> steps = new ArrayList<>();
        boolean complete = false;
        int totalExpanded = 0;
        int totalDistinct = 0;
        // チェーン全体の打ち切り理由は最後に解いた区間のもの。全区間が「範囲内に道が無い」で
        // 終わったときだけEXHAUSTEDのまま残り、本物の詰みとして呼び出し側に伝わる
        PathResult.Termination termination = PathResult.Termination.EXHAUSTED;
        BlockPos legStart = StanceFinder.resolveStart(view, start);
        for (int i = 0; i < rawLegGoals.size(); i++) {
            long remainingMillis = chainDeadline - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                termination = PathResult.Termination.TIME_LIMIT;
                break;
            }
            // 区間の持ち時間は<b>残りを山分けする</b>。1つの区間がチェーンの予算を使い切ると、
            // 後続の区間が一度も試されない——実機（ジ・エンド、2026-08-28）では区間1が2秒中
            // 1.4〜1.6秒を使って失敗し、区間2・3が時間切れで潰れるのが失敗時の定型だった。
            // 一方で実際に成功した回は「区間2が100,000ノードで失敗 → 区間3を試したら27,340ノードで
            // 到達」という形で、<b>後続を必ず試せること自体が成功率を決めている</b>
            // （届かなかった経由地の次を同じ地点から狙う、という下のフォールバックが本体）。
            //
            // 固定の上限（区間ごと何ms）は使わない。呼び出し側の予算が変われば1区間に割ける時間も
            // 変わるべきで、固定値だと予算を増やしても区間が使えないまま余る。使い切らなかった
            // ぶんは次の区間へ自然に回る（remainingMillisが減らないため）
            int legsLeft = rawLegGoals.size() - i;
            long legShare = Math.max(1, remainingMillis / legsLeft);
            // 持ち時間の半分は上限緩和のために残す。最初の探索が全部使うと緩和が動けず、
            // 奈落越えに必要な「橋の上限を緩めた探索」へ一度も到達しない
            long legDeadline = System.currentTimeMillis() + legShare;
            SearchLimits thisLegLimits = new SearchLimits(limits.maxExpandedNodes(),
                    Math.max(1, legShare / 2), limits.heuristicWeight());
            BlockPos legGoal = StanceFinder.resolveGoal(view, rawLegGoals.get(i));
            BlockPos currentLegStart = legStart;
            // 中間の経由地はチャンク平均から作った代表点でしかない。座標ぴったりへ寄せる意味が
            // 無いどころか、そのための遠回りが生まれる。最後の区間だけは呼び出し側の指定に従う
            boolean lastLegGoal = i == rawLegGoals.size() - 1;
            int legRadius = lastLegGoal ? goalRadius : COARSE_LEG_GOAL_RADIUS_BLOCKS;
            // 区間ごとのゴールに向けたガイド。区間分割の計画（route）には広い箱のcoarseMapが
            // 要るが、ガイドの計算はその区間の始点・終点周りだけで足りる——広い箱をそのまま
            // 使い回すとDijkstraの状態数が箱の面積に比例して膨らみ、区間の数だけ払うことになる
            // （COARSE_LEG_GUIDE_MARGIN_BLOCKS参照）。区間専用の狭い地図を別に組み直す
            CostToGo legCostToGo = costToGoGuideEnabled
                    ? CoarseRouter.costToGo(legCoarseMap(view, currentLegStart, legGoal, bounds, cancelled),
                            legGoal, false, bridgePolicy)
                    : null;
            // 区間の境目で橋の連続長が0に戻らないよう、直前までの末尾の連続長を引き継ぐ
            int carriedBridgeRun = trailingBridgeRun(steps);
            long legBegan = System.currentTimeMillis();
            PathResult legResult = search(view, thisLegLimits, legDeadline, cancelled, legCostToGo,
                    (pathfinder, c) -> pathfinder.search(currentLegStart, legGoal, c, carriedBridgeRun, legRadius));
            // 区間ごとに出す。チェーン全体の合算だけでは「どの区間で詰まったか」「始点から
            // 動けていないのか、最後の区間だけ届かないのか」が切り分けられない——実機の
            // 「展開30万・ステップ数2」がどちらなのかを、合算値からは判断できなかった。
            // チェーンが走るのは通常探索が失敗した後だけとはいえ、1回で区間数ぶんの行が出るので
            // debugに留める（下の集計行はINFOのまま残る）
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("XaeroNav: 区間{}/{} {} → {} (到達={}, {}, 展開ノード数={}, ステップ数={}, {}ms)",
                        i + 1, rawLegGoals.size(), currentLegStart.toShortString(), legGoal.toShortString(),
                        legResult.complete(), legResult.termination(), legResult.expandedNodes(),
                        legResult.steps().size(), System.currentTimeMillis() - legBegan);
            }
            totalExpanded += legResult.expandedNodes();
            totalDistinct += legResult.distinctNodes();
            boolean lastLeg = i == rawLegGoals.size() - 1;
            if (!legResult.complete()) {
                termination = legResult.termination();
            }

            if (legResult.complete()) {
                steps.addAll(legResult.steps());
                if (!legResult.steps().isEmpty()) {
                    legStart = legResult.steps().get(legResult.steps().size() - 1).pos();
                }
                if (lastLeg) {
                    complete = true;
                }
            } else if (lastLeg) {
                // 最後の区間は本来の目的地。届かなくても拾えた分はそのまま使う（暫定経路の思想）
                steps.addAll(legResult.steps());
            }
            // 中間の経由地に届かなかったときは、そこで諦めずに同じ地点から次の経由地を狙う。
            // 粗い地図は溶岩以外に「通行不能」を表現できず、チャンクを埋める垂直な壁は起伏0の
            // 平坦な台地に見えるため、経由地が壁の天面のような到達不能な点に落ちることがある。
            // 1つ届かないだけでチェーンごと捨てると、そういう地形で直接探索より悪くなる——
            // 最後の区間は必ず本来の目的地なので、経由地が全滅しても直接探索と同じ結果に落ち着く。
            // 部分経路を継ぎ足さないのは、次の区間を同じ地点から引き直す以上そこで経路が飛ぶため
        }
        return new PathResult(steps, complete ? PathResult.Termination.REACHED_GOAL : termination,
                totalExpanded, totalDistinct);
    }

    /**
     * 区間専用の狭いコース地図を組む。{@link #COARSE_LEG_GUIDE_MARGIN_BLOCKS}参照。
     *
     * <p>チャンクの読み取り自体は{@code view}（区間分割全体で共有する広いChunkView）を使い回す
     * ので追加の読み込みは発生しない——{@link LiveCoarseSampler#sample}に渡す{@code bounds}を
     * 狭くするだけで、走査する列の数そのものを絞る。
     */
    private static CoarseMap legCoarseMap(CellSource view, BlockPos legStart, BlockPos legGoal,
                                           SearchBounds outer, BooleanSupplier cancelled) {
        int minX = Math.max(outer.minX(), Math.min(legStart.getX(), legGoal.getX()) - COARSE_LEG_GUIDE_MARGIN_BLOCKS);
        int maxX = Math.min(outer.maxX(), Math.max(legStart.getX(), legGoal.getX()) + COARSE_LEG_GUIDE_MARGIN_BLOCKS);
        int minZ = Math.max(outer.minZ(), Math.min(legStart.getZ(), legGoal.getZ()) - COARSE_LEG_GUIDE_MARGIN_BLOCKS);
        int maxZ = Math.min(outer.maxZ(), Math.max(legStart.getZ(), legGoal.getZ()) + COARSE_LEG_GUIDE_MARGIN_BLOCKS);
        SearchBounds legBounds = new SearchBounds(minX, outer.minY(), minZ, maxX, outer.maxY(), maxZ);
        return LiveCoarseSampler.sample(view, legBounds, legStart.getY(), cancelled);
    }

    /** 経路の末尾で連続している橋のブロック数。 */
    private static int trailingBridgeRun(List<PathStep> steps) {
        int run = 0;
        for (int i = steps.size() - 1; i >= 0 && steps.get(i).bridging(); i--) {
            run++;
        }
        return run;
    }

    private static PathResult search(CellSource view, SearchLimits limits, BooleanSupplier cancelled, SearchCall run) {
        return search(view, limits, cancelled, null, run);
    }

    private static PathResult search(CellSource view, SearchLimits limits, BooleanSupplier cancelled,
                                     CostToGo costToGo, SearchCall run) {
        return search(view, limits, System.currentTimeMillis() + limits.timeLimitMillis(), cancelled, costToGo, run);
    }

    /**
     * 上限緩和の段に使ってよい期限（絶対時刻）を明示する版。
     *
     * <p>緩和には<b>必ず出番を残す</b>。最初の探索と同じ期限を渡すと、最初の探索がそれを使い切った
     * 時点で緩和が動けない——実機（ジ・エンドの島渡り）で走る探索は全部{@link #solveCoarseGuided}の
     * 区間探索なので、緩和は事実上一度も動けていなかった（探索の総時間だけが伸びて結果は変わらず）。
     * 呼び出し側は最初の探索へ持ち時間の半分だけを渡し、この期限には全体を渡すことで
     * 「最初の探索の取り分が余れば緩和がそのぶん長く走る」形にしてある。
     */
    private static PathResult search(CellSource view, SearchLimits limits, long looseningDeadline,
                                     BooleanSupplier cancelled, CostToGo costToGo, SearchCall run) {
        AStarPathfinder pathfinder = new AStarPathfinder(view, limits, costToGo);
        PathResult result = run.search(pathfinder, cancelled);
        boolean capBlocked = pathfinder.bridgeRunCapBlocked() || pathfinder.submergedRunCapBlocked()
                || pathfinder.fallDamageCapBlocked() || pathfinder.riskyJumpBlocked();
        if (!result.complete() && result.termination() != PathResult.Termination.CANCELLED && capBlocked) {
            // 上限のせいで捨てた移動がある。詰むよりは長い橋・息継ぎの要る潜水の方がマシ、という
            // 優先順で上限を段階的に緩めて試す。片方だけ緩めても、もう片方で詰んでいれば同じ結果を
            // もう一度払うだけになるので両方まとめて緩める。
            //
            // <b>予算切れ（NODE_BUDGET/TIME_LIMIT）でも緩める。</b>以前はEXHAUSTED限定だったが、
            // それだと「探索範囲の中の到達可能セルを全部舐め切れる」ほど狭い地形でしか緩和が
            // 発動しない。実機（ジ・エンドの崖ぎわ）で踏んだのはその裏返しで、島が大きいと
            // 到達可能セルだけで予算を使い切り、45マスの奈落を上限30のまま渡ろうとして
            // <b>一度も緩まないまま失敗し続けた</b>（合成地形: 島半径60まではEXHAUSTED＝緩和あり、
            // 半径80でNODE_BUDGET＝緩和なし）。同じ島の途中まで橋を架けた地点からだと到達可能
            // セルが減ってEXHAUSTEDに届くので、「崖ぎわからだけ経路が出ない」という形で表れる。
            //
            // 資源不足を理由に同じ探索を払い直すわけではない——{@code capBlocked}は「上限が実際に
            // 移動を捨てた」という探索自身の報告で、緩めた探索は別の探索になる。総時間は
            // looseningDeadlineが縛るので、緩和の段は残り時間ぶんしか走らない
            for (Tolerances tolerances : loosenedTolerances(view)) {
                long remainingMillis = looseningDeadline - System.currentTimeMillis();
                if (remainingMillis <= 0) {
                    // 上限は疑われた（capBlocked）が、緩和を1回も試す前に持ち時間が尽きた。
                    // 上限ではなく予算の問題だという手がかりなので残す——ただし予算が厳しい地形では
                    // 毎回出るのでdebugに留める（実機の既定ではdebugは出ない）
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("XaeroNav: 上限を疑ったが緩和の時間が残っていなかった ({})",
                                result.termination());
                    }
                    break;
                }
                SearchLimits stageLimits = new SearchLimits(limits.maxExpandedNodes(), remainingMillis,
                        limits.heuristicWeight());
                PathResult attempt =
                        run.search(new AStarPathfinder(view, stageLimits, costToGo, tolerances), cancelled);
                if (attempt.complete()) {
                    result = attempt;
                    break;
                }
                // EXHAUSTED以外（予算切れ・キャンセル）は、更に緩めても同じ壁に当たるだけ
                if (attempt.termination() != PathResult.Termination.EXHAUSTED) {
                    break;
                }
            }
        }
        return PathSafetyChecker.annotate(view, result);
    }

    /**
     * 緩める順に並べた許容量。{@link RunCaps}側は{@link #RUN_CAP_LOOSEN_MULTIPLIERS}倍したものの後に
     * 無制限、落下ダメージ側は全段で{@link #loosenedFallDamagePoints}の1段だけ。
     *
     * <p>まとめて1本の梯子に載せるのは、片方だけ緩めてももう片方で詰んでいれば同じ探索をもう一度
     * 払うだけになるから。<b>落下ダメージと危険な跳躍を1段目から開けるのが要点</b>——直前に失敗した
     * 探索が既定の許容量そのもので走っているので、1段目に同じ値を置くと、そちらだけが原因だったときに
     * 何も変えない探索を1回まるごと捨てることになる。
     *
     * <p>危険な跳躍（奈落・致死落差の上）を全段で許すのは、ここへ来ている時点で<b>回り込む道が
     * 一本も見つからなかった</b>ことが確定しているため。ユーザーの意図は「同じ島の中なら外周を
     * 回れ、島と島の間なら跳べ」で、その使い分けは「他に道があるか」そのもの——梯子の発動条件と
     * 一致する。跳ぶことになった区間には{@code PathRisk.VOID_BELOW}で警告色が付く。
     */
    private static List<Tolerances> loosenedTolerances(CellSource view) {
        RunCaps base = RunCaps.of(view);
        int fallPoints = loosenedFallDamagePoints(view);
        List<Tolerances> stages = new ArrayList<>(RUN_CAP_LOOSEN_MULTIPLIERS.length + 1);
        for (int multiplier : RUN_CAP_LOOSEN_MULTIPLIERS) {
            stages.add(new Tolerances(scaleCaps(base, multiplier), fallPoints, true));
        }
        stages.add(new Tolerances(RunCaps.NONE, fallPoints, true));
        return stages;
    }

    /**
     * 詰み回避で開ける落下ダメージの許容量（0.5ハート単位）。
     *
     * <p><b>無制限の段は作らない。</b>橋の長さや潜水と違って、上限を外すと即死する落下が案内に
     * 出る——「詰みよりはマシ」が成り立たない唯一の項目なので、体力から決まる上限で止める。
     * 既定値が体力の1/3なので、その1.5倍＝体力の1/2まで開ける（体力満タンなら落差13マス）。
     * エリトラを持たないプレイヤーがジ・エンドの低い島へ降りる、という本来の用途にはこれで足りる。
     *
     * <p>{@code fallDamageToleranceEnabled}がoffなら0のまま——設定で明示的に断られている以上、
     * 詰み回避であっても勝手に痛い落下を提示しない。
     */
    private static int loosenedFallDamagePoints(CellSource view) {
        int configured = view.maxFallDamagePoints();
        return configured <= 0 ? 0 : configured * 3 / 2;
    }

    private static RunCaps scaleCaps(RunCaps base, int multiplier) {
        return new RunCaps(scaleCap(base.maxBridgeRunBlocks(), multiplier),
                scaleCap(base.maxLavaBridgeRunBlocks(), multiplier),
                scaleCap(base.maxVoidBridgeRunBlocks(), multiplier),
                scaleCap(base.maxSubmergedTicks(), multiplier));
    }

    /** {@code 0}は既に無制限なので、乗じてもそのまま無制限に留まる。 */
    private static int scaleCap(int cap, int multiplier) {
        return cap == 0 ? 0 : cap * multiplier;
    }

    private CompletableFuture<PathResult> submit(Function<BooleanSupplier, PathResult> work) {
        PathfindingJob job = new PathfindingJob();
        PathfindingJob previous = currentJob.getAndSet(job);
        if (previous != null) {
            previous.cancel();
        }

        CompletableFuture<PathResult> future = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                PathResult result = work.apply(job::isCancelled);
                if (job.isCancelled()) {
                    future.cancel(false);
                } else {
                    future.complete(result);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @FunctionalInterface
    private interface SearchCall {
        PathResult search(AStarPathfinder pathfinder, BooleanSupplier cancelled);
    }
}
