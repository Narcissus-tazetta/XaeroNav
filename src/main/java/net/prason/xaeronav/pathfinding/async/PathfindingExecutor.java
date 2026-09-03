package net.prason.xaeronav.pathfinding.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathSafetyChecker;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.RunCaps;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.astar.Tolerances;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
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
     * {@link #submitWithDeepFallback}が深い予算の探索だけに使う2本目のワーカー。
     *
     * <p>通常予算の探索は{@link #executor}上でそのまま進めつつ、こちらで深い予算の探索を
     * 同時に進める。通常予算が届けば{@link AtomicBoolean}で打ち切るので、実際にCPUを
     * 2コア分使い続けるのは「通常予算が結局失敗するとき」だけに限られる。
     */
    private final ExecutorService deepExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-pathfinding-deep");
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

    /**
     * {@link #refineQuality}が引き直しに使う重み。実機ジ・エンドの保存地形での実測から、
     * <b>改善のほとんどが取れて展開ノードの増分が最小</b>の点を採った（経路コスト{@code -3.0%} /
     * 展開{@code +40%}。1.15まで下げても{@code -5.0%} / {@code +46%}にしかならない）。
     */
    private static final double REFINE_HEURISTIC_WEIGHT = 1.25;

    /**
     * {@link #retryGreedier}が順に試す重み。実測で2.5から解け始めるので、そこを1段目に置く。
     * 3.0は{@code XaeroNavConfig#heuristicWeight}の上限でもあり、これ以上は用意しない——
     * 貪欲さを上げるほど経路は遠回りになるので、届く最小の重みで止めたい。
     */
    private static final double[] GREEDY_RETRY_WEIGHTS = {2.5, 3.0};

    /**
     * 最初の探索へ渡す予算・時間の割合（%）。
     *
     * <p><b>使い切られると緩和も{@link #retryGreedier}も動けない。</b>両者は
     * {@code looseningDeadline}を最初の探索と共有しているので、1段目が枠いっぱいまで走ると
     * 再挑戦に残り時間がゼロになる——実機のジ・エンド（深い探索でも12秒）でまさにそれが起きて、
     * 「重みを上げれば解ける」と分かっていても一度も試されないままだった。
     *
     * <p><b>解ける地形では損をしない。</b>A*はゴールを取り出した時点で返るので、届く経路は
     * 上限に関わらず同じ手数で見つかる（{@code XaeroNavConfig#maxExpandedNodes}の
     * 「払うコストではなく届かなかったときの天井」と同じ理屈）。減るのは<b>届かない探索が
     * 諦めるまでの時間</b>だけで、それはそのまま再挑戦の持ち時間になる。
     */
    private static final int FIRST_PASS_PERCENT = 40;

    /** 最初の探索の取り分。残りは緩和と{@link #retryGreedier}のために空けておく。 */
    private static SearchLimits firstPassLimits(SearchLimits limits) {
        return new SearchLimits(
                Math.max(1, limits.maxExpandedNodes() * FIRST_PASS_PERCENT / 100),
                Math.max(1, limits.timeLimitMillis() * FIRST_PASS_PERCENT / 100),
                limits.heuristicWeight());
    }

    /**
     * {@link #refineQuality}が引き直すのは、最初の探索が予算のこれだけしか使わなかったときに限る。
     *
     * <p><b>「余裕があったときだけ質を問い直す」の余裕をここで測る。</b>引き直しは最初の探索より
     * 4割ほど多く展開するので、既に予算の大半を焼いている探索でもう一度払うと、数%の質のために
     * 待ち時間が倍になる——実機ジ・エンドの<b>島から島への渡り</b>がまさにそれで、
     * {@code RealEndTerrainTest}の地形は60万ノード中53万(89%)を使って到達する。しかもそこは
     * 奈落を渡る以外に道が無いので、引き直しても同じ経路しか出ない。
     *
     * <p>一方で狙っている谷の横断は3万/60万＝5%、実機の既定予算(10万)に置き直しても30%で収まる。
     * 半分に置けば両者を分けられる。
     *
     * <p><b>割合を測る分母は{@link #firstPassLimits}が渡した予算</b>——最初の探索はフル予算では
     * 走らないので、フル予算と比べると条件が常に成立して保護が消える。
     */
    private static final double REFINE_MAX_FIRST_PASS_FRACTION = 0.5;

    /**
     * 経路が持ち物のこの割合を超えて使うとき、{@link #refineQuality}が節約を試みる。
     *
     * <p>半分に置くのは、<b>足りないことより「使い切ること」を問題にしている</b>から——渡り切れても
     * 手元が空になれば、その先の谷や柱で詰む。逆に1〜2割しか使わない経路にまで掛けると、
     * 設置を含む経路が常態のジ・エンドでは毎回2度探索することになる。
     */
    private static final double THRIFT_TRIGGER_FRACTION = 0.5;

    /**
     * 節約の引き直しで、足場1つを置く動作の値段を何倍にするか
     * （{@code ActionCosts#PLACE_BLOCK_AIM_TICKS}）。
     *
     * <p><b>「1個節約するために何マス余計に歩いてよいか」がこの値の意味</b>。倍にすれば
     * 置く動作ぶん（{@code ActionCosts#PLACE_BLOCK_AIM_TICKS}＝16.0）が上乗せされる＝
     * <b>疾走4.5マス相当</b>。3倍なら9マス相当で、それ以上は
     * {@link #THRIFT_MAX_COST_INCREASE}の関門で弾かれるだけの引き直しが増える。
     *
     * <p>掛かるのは置く動作の側だけで、走行を中断するぶん
     * （{@code ActionCosts#TERRAIN_EDIT_INTERRUPTION_TICKS}）には掛からない。減らしたいのは
     * <b>使う枚数</b>なので枚数に比例する成分だけを割り増す——{@link #trueCost}が割増を
     * 差し引いて比べられるのも、全ての設置が同じ額だけ膨らんでいるからこそ。
     */
    private static final double THRIFT_PLACEMENT_COST_SCALE = 2.0;

    /**
     * 節約した経路を採るために、本来の値段での総コストの悪化をどこまで許すか。
     *
     * <p>0にしてはいけない——最初の経路は本来の値段でほぼ最適なので、設置を減らした経路は
     * 定義上それより高くなる。<b>少しの時間でブロックを買っている</b>のがこの引き直しなので、
     * 買値の上限をここで決める。1割は、実機の島渡り（500 tick前後の区間）でおよそ2.5秒。
     */
    private static final double THRIFT_MAX_COST_INCREASE = 0.10;

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
        return submit(view, start, goal, limits, costToGoGuideEnabled, goalRadius, Carryover.NONE);
    }

    /**
     * 手前の区間から累積を引き継ぐ版（{@link Carryover}）。表示中の経路の末端から継ぎ足す探索と、
     * 経路へ合流し直す探索が使う——どちらも<b>1本の経路の続き</b>を解いているので、橋の連続長も
     * 持ち物の予算も、この経路が既に使うと決めているぶんを差し引いた状態から始めなければならない。
     */
    public CompletableFuture<PathResult> submit(CellSource view, BlockPos start, BlockPos goal, SearchLimits limits,
                                                 boolean costToGoGuideEnabled, int goalRadius, Carryover carried) {
        return submit(cancelled -> {
            CostToGo costToGo = costToGoGuideEnabled ? buildCostToGoGuide(view, start, goal, cancelled) : null;
            return search(view, limits, cancelled, costToGo, (pathfinder, c) ->
                    // 立てない座標のまま探索すると経路が1本も伸びない。ブロックを読める場所での
                    // 寄せ直しなので、メインスレッドへ戻さずここで行う
                    pathfinder.search(StanceFinder.resolveStart(view, start), StanceFinder.resolveGoal(view, goal),
                            c, carried, goalRadius));
        });
    }

    /**
     * 通常予算と深い予算を<b>並列に</b>試す。通常予算が届けばそれを採用して深い方は打ち切り、
     * 通常予算が予算切れ・時間切れで終わったときだけ深い方の結果を待つ。
     *
     * <p><b>直列（通常予算の失敗を確認 → 次tickで深い予算）だと2回分の時間が丸ごと足し算になる。</b>
     * 実測（実機ユーザー報告の島渡り地形、{@code PlayerAreaEndReproTest}と同条件）:
     *
     * <pre>
     * 通常予算(10万/2秒)のみ → NODE_BUDGET、1871ms
     * 深い予算(60万/15秒)のみ → 到達、1876ms
     * 直列の合計 ≈ 3747ms（tick境界の待ちを含めると実機ではさらに伸びる）
     * </pre>
     *
     * <p>深い方は通常予算と同時に始めておけば、通常予算が失敗を確定する頃には
     * <b>ほぼ同時に終わっている</b>——上の実測どおり2つの所要時間はほとんど差が無い。
     * 通常予算がすぐ届く（大半のケース）なら深い方は即座に打ち切られるので、
     * 増える負荷は「通常予算と同じだけの時間、もう1コア使う」だけに留まる。
     *
     * <p>費用対効果が悪いのは通常予算がそもそも一瞬で終わる近距離ナビだが、そこでは
     * 深い方も同じくらい一瞬で打ち切られるので実害は小さい。逆に通常予算が最初から
     * 時間切れ確定（{@code plainSearchHopeless}）と分かっている場合は、深い予算だけで
     * 足りるので呼び出し側（{@code PathfindingState}）はこちらを使わず従来どおり
     * {@link #submit}に深い{@link SearchLimits}を渡す。
     *
     * <p><b>ビューを2つ受け取るのは飾りではない。</b>{@link CellSource}は単一のワーカースレッドが
     * 占有する約束（{@code ChunkView}のスレッド契約）で、2つの探索へ同じインスタンスを渡すと
     * セルのキャッシュが並行に書き換わって壊れる。実際そうなっていて、実機で
     * {@code ArrayIndexOutOfBoundsException}が出ていた。呼び出し側に2つ渡させるのは、
     * {@code CellSource}へ複製用のメソッドを生やすと実装側が{@code this}を返して黙って
     * 元に戻せてしまうため——引数で強制すれば取り違えようがない。
     *
     * @param normalView 通常予算の探索が占有するビュー
     * @param deepView   深い予算の探索が占有するビュー。{@code normalView}とは別インスタンスであること
     */
    public CompletableFuture<PathResult> submitWithDeepFallback(CellSource normalView, CellSource deepView,
                                                                  BlockPos start, BlockPos goal,
                                                                  SearchLimits normalLimits, SearchLimits deepLimits,
                                                                  boolean costToGoGuideEnabled, int goalRadius) {
        return submit(cancelled -> {
            CostToGo costToGo = costToGoGuideEnabled ? buildCostToGoGuide(normalView, start, goal, cancelled) : null;
            BlockPos resolvedStart = StanceFinder.resolveStart(normalView, start);
            BlockPos resolvedGoal = StanceFinder.resolveGoal(normalView, goal);

            // 通常予算が先に届いたら、まだ走っている深い方をここで打ち切る。deepExecutor自体は
            // 空けておかないと、次の呼び出しがこのジョブの後ろに並んで無駄に待たされる
            AtomicBoolean normalWon = new AtomicBoolean(false);
            BooleanSupplier deepCancelled = () -> cancelled.getAsBoolean() || normalWon.get();
            CompletableFuture<PathResult> deepFuture = CompletableFuture.supplyAsync(() ->
                    search(deepView, deepLimits, deepCancelled, costToGo, (pathfinder, c) ->
                            pathfinder.search(resolvedStart, resolvedGoal, c, Carryover.NONE, goalRadius)),
                    deepExecutor);

            PathResult normal = search(normalView, normalLimits, cancelled, costToGo, (pathfinder, c) ->
                    pathfinder.search(resolvedStart, resolvedGoal, c, Carryover.NONE, goalRadius));

            if (normal.complete() || cancelled.getAsBoolean()) {
                normalWon.set(true);
                deepFuture.cancel(true);
                return normal;
            }
            // 通常予算は予算切れ・時間切れで終わった。深い方は同時に始めているので、
            // ここではもう終わっているか、残りわずかのはず
            try {
                PathResult deep = deepFuture.get();
                return deep.complete() ? deep : normal;
            } catch (ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return normal;
            }
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
                            c, Carryover.NONE, goalRadius));
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
            // 区間の境目で累積が0に戻らないよう、直前までの分を引き継ぐ（橋の連続長・設置数）
            Carryover carried = Carryover.after(steps);
            long legBegan = System.currentTimeMillis();
            PathResult legResult = search(view, thisLegLimits, legDeadline, cancelled, legCostToGo,
                    (pathfinder, c) -> pathfinder.search(currentLegStart, legGoal, c, carried, legRadius));
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
        AStarPathfinder pathfinder = new AStarPathfinder(view, firstPassLimits(limits), costToGo);
        PathResult result = run.search(pathfinder, cancelled);
        boolean capBlocked = pathfinder.bridgeRunCapBlocked() || pathfinder.submergedRunCapBlocked()
                || pathfinder.fallDamageCapBlocked() || pathfinder.riskyJumpBlocked()
                || pathfinder.placedBudgetBlocked() || pathfinder.placementBlockedByEmptyInventory();
        // 上限の緩和より先に貪欲さを上げる。<b>重みを上げる方が圧倒的に安い</b>——実機ジ・エンドの
        // 島渡りで、緩和の段は毎回フル予算(60万ノード・7秒)を焼くのに対し、重み2.5は19.8万で解ける。
        // 緩和を先に置くと、そこで持ち時間を使い切って再挑戦が一度も走らないまま終わる
        // （実機相当の時間枠4.8秒で実測: 1段目24万＋緩和で使い切り、届かず）。
        // 上限が本当に原因なら重みを上げても解けないので、その場合だけ下の緩和へ進む
        if (!result.complete() && result.termination() != PathResult.Termination.CANCELLED) {
            PathResult greedier = retryGreedier(view, limits, looseningDeadline, cancelled, costToGo, run, result);
            if (greedier.complete()) {
                return PathSafetyChecker.annotate(view, greedier);
            }
        }
        if (!result.complete() && result.termination() != PathResult.Termination.CANCELLED && capBlocked) {
            // 上限のせいで捨てた移動がある。詰むよりは長い橋・息継ぎの要る潜水の方がマシ、という
            // 優先順で上限を段階的に緩めて試す。上限と落下ダメージはまとめて緩める——片方だけ緩めても、
            // もう片方で詰んでいれば同じ結果をもう一度払うだけになるから。
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
            // 危険な跳躍だけは別扱いで、上限を緩める段を全部試し切ってから開ける（{@link #capStages}）。
            // 跳躍を捨てたのが最初の探索とは限らない——上限を緩めて初めて届いた場所に、
            // 跳ぶしかない隙間があることがあるので、1群目の報告も見る
            boolean budgetBlocked = pathfinder.placedBudgetBlocked();
            boolean emptyInventoryBlocked = pathfinder.placementBlockedByEmptyInventory();
            Loosening capsOnly = runStages(view, limits, looseningDeadline, cancelled, costToGo, run,
                    capStages(view, !view.avoidRiskyJumps(), budgetBlocked, emptyInventoryBlocked));
            if (capsOnly.result() != null) {
                result = capsOnly.result();
            } else if (view.avoidRiskyJumps()
                    && (pathfinder.riskyJumpBlocked() || capsOnly.riskyJumpBlocked())) {
                Loosening withJumps = runStages(view, limits, looseningDeadline, cancelled, costToGo, run,
                        capStages(view, true, budgetBlocked, emptyInventoryBlocked));
                if (withJumps.result() != null) {
                    result = withJumps.result();
                }
            }
            // 緩和まで来た経路は「そもそも道が無い」側の話なので、質を問い直さない（下記）
            return PathSafetyChecker.annotate(view, result);
        }
        return refineQuality(view, limits, looseningDeadline, cancelled, costToGo, run,
                PathSafetyChecker.annotate(view, result), pathfinder.carriedPlacedBlocks());
    }

    /**
     * <b>予算を焼き切って届かなかったら、探索の貪欲さを上げてもう一度試す。</b>
     *
     * <p>広い足場の上から長い奈落を渡る地形（ジ・エンドの島渡り）では、
     * <b>島の上でヒューリスティックがほぼ一定になる</b>——どこにいてもゴールは奈落の向こうで、
     * 残りの見積もりは「縁までの距離＋橋の値段」だから差が付きにくい。重み1.5の探索は
     * そこで幅優先に近くなり、<b>橋に手を伸ばす前に島を舐め尽くして予算が尽きる</b>。
     * 橋1マスは徒歩10マス相当なので、100マスの奈落を渡る経路に届くには
     * 「徒歩1000マス分の陸地」を先に展開し終える必要がある。
     *
     * <p>実測（ユーザーが報告した地点、24339列の島の突端から北東99ブロックの島へ）:
     *
     * <pre>
     * 重み1.5 → 60万ノードで未到達    重み2.5 → 19.8万で到達
     * 重み2.0 → 60万ノードで未到達    重み3.0 → 10.7万で到達
     * </pre>
     *
     * <p><b>cost-to-goガイドが無いとどの重みでも解けない</b>（全部60万で未到達）。
     * 島の縁へ導いているのはガイドの方で、重みはそれを信じる度合いを上げているだけ。
     *
     * <p>質は確実に落ちる（{@code refineQuality}が重みを下げているのと正反対のことをする）が、
     * ここへ来るのは<b>経路が1本も出ていない</b>ときだけ——遠回りな案内と案内なしの比較になる。
     * 上限の緩和を試し切った後に置くのも同じ理由で、まず「上限のせいで道が消えていないか」を
     * 確かめてから貪欲さに手を付ける。
     */
    private static PathResult retryGreedier(CellSource view, SearchLimits limits, long deadline,
                                             BooleanSupplier cancelled, CostToGo costToGo, SearchCall run,
                                             PathResult result) {
        if (result.complete() || result.termination() == PathResult.Termination.CANCELLED) {
            return result;
        }
        for (double weight : GREEDY_RETRY_WEIGHTS) {
            if (weight <= limits.heuristicWeight()) {
                continue;
            }
            long remainingMillis = deadline - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                break;
            }
            SearchLimits greedy = new SearchLimits(limits.maxExpandedNodes(), remainingMillis, weight);
            PathResult attempt = run.search(new AStarPathfinder(view, greedy, costToGo), cancelled);
            if (attempt.complete()) {
                return attempt;
            }
        }
        return result;
    }

    /**
     * <b>余裕があるときだけ、経路の質を問い直してもう一度だけ引き直す。</b>引き金は2つあり、
     * どちらか一方でも立てば<b>1回だけ</b>引き直す（両方立てば両方の調整を掛けた1回）。
     *
     * <h4>引き金1: 奈落を渡っている（重みを下げる）</h4>
     *
     * <p>重み付きA*は{@code f = g + w·h}で取り出すので、<b>目的地から一度遠ざかる経路を系統的に嫌う</b>。
     * 実機ジ・エンド(2481,-488)で踏んだのがまさにこれで、谷を挟んだ39ブロック東へ行くのに
     * <b>15マスの橋（うち7マスは奈落の上）を架けて突っ切る</b>経路が出ていた。しかもコストモデルの側は
     * 既に南から回り込む方を安いと言っている——重み1.5の経路が596.3tick、1.3では橋ゼロの522.2tick。
     * 探索が貪欲なだけで、値段付けは間違っていなかった。
     *
     * <p><b>重みを下げるのは全体ではなくここだけ</b>。実機の保存地形で測った平均は割に合わない——
     * 経路コストの改善は{@code -1.9%}(1.35)〜{@code -5.0%}(1.15)しかないのに、展開ノードは
     * {@code +17%}〜{@code +46%}増える（オーバーワールドでは{@code -0.7%}に対し{@code +5%}）。
     * <b>損は平均ではなく一部の経路に集中している</b>ので、その一部だけを狙い撃つ。
     *
     * <p>引き金を「奈落・致死落差の上を通る」に置くのは、そこが<b>貪欲さを許してはいけない唯一の判断</b>
     * だから。{@code VOID_BRIDGE_PENALTY_TICKS}は元々「他に道が無いときの最後の手段」という値段で、
     * 回り込む道があるかどうかを確かめずに払ってよいものではない。橋の無い経路には引き金が掛からないので、
     * 大半の探索は1回で終わる。
     *
     * <h4>引き金2: 持ち物の大半を使い切る（設置の値段を上げる）</h4>
     *
     * <p>予算（{@link Tolerances#placedBlockBudget()}）は<b>実行できるか</b>の線引きでしかない。
     * 手持ち40個で40個置く経路は「実行できる」が、少し回り込めば10個で済むならそちらの方がいい
     * ——<b>置いた先で足りなくなるのは、その経路を歩き終えた後</b>だからだ（経路キャッシュのキーは
     * 目的地だけなので、途中で減っても引き直されない）。
     *
     * <p>そこで{@link #THRIFT_TRIGGER_FRACTION}を超えて使う経路が出たときだけ、設置の手間を
     * {@link #THRIFT_PLACEMENT_COST_SCALE}倍にして引き直す。<b>係数は探索開始時に決まる一律の値</b>
     * ——残り枚数で値段を変えると、同じ辺の値段が到達経路によって変わってA*の前提が崩れる。
     *
     * <p>採るのは<b>設置が減って、本来の値段での総コストが{@link #THRIFT_MAX_COST_INCREASE}以内の
     * 悪化に収まるとき</b>だけ。値段を割り増して解いた以上、そのままの総コストで比べると必ず
     * 「改善した」ことになってしまうので、割増ぶんを差し引いてから比べる（{@link #trueCost}）。
     *
     * <h4>共通</h4>
     *
     * <p>緩和の梯子を通った結果には掛けない——あちらは「上限を外さないと経路が一本も出ない」場所なので、
     * 質を問い直す前提（別の道がある）が成り立たない。
     */
    private static PathResult refineQuality(CellSource view, SearchLimits limits,
                                             long deadline, BooleanSupplier cancelled,
                                             CostToGo costToGo, SearchCall run, PathResult result,
                                             int carriedPlacements) {
        if (!result.complete()) {
            return result;
        }
        // 分母は<b>最初の探索が実際に渡された予算</b>（{@link #firstPassLimits}）。フル予算と
        // 比べると、1段目の上限がそれより小さい以上この条件は常に通り、保護が丸ごと効かなくなる
        if (result.expandedNodes()
                > firstPassLimits(limits).maxExpandedNodes() * REFINE_MAX_FIRST_PASS_FRACTION) {
            return result;
        }
        // 引き金は独立に立つ。両方立てば、両方の調整を掛けた探索を1回だけ走らせる
        boolean lowerWeight = limits.heuristicWeight() > REFINE_HEURISTIC_WEIGHT
                && result.steps().stream().anyMatch(step -> step.risk() == PathRisk.VOID_BELOW);
        boolean thrift = thrifty(view, result, carriedPlacements);
        if (!lowerWeight && !thrift) {
            return result;
        }
        long remainingMillis = deadline - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            return result;
        }
        double scale = thrift ? THRIFT_PLACEMENT_COST_SCALE : 1.0;
        SearchLimits refined = new SearchLimits(limits.maxExpandedNodes(), remainingMillis,
                lowerWeight ? REFINE_HEURISTIC_WEIGHT : limits.heuristicWeight());
        PathResult attempt = PathSafetyChecker.annotate(view,
                run.search(new AStarPathfinder(view, refined, costToGo, Tolerances.of(view), scale), cancelled));
        if (!attempt.complete()) {
            return result;
        }
        // 元の経路は割増していないので、その総コストがそのまま本来の値段
        double before = totalCost(result);
        double after = trueCost(attempt, scale);
        // 「安くなった」か「同じくらいの値段で設置が減った」なら採る。後者を許すのが節約の本体で、
        // 買値の上限が THRIFT_MAX_COST_INCREASE（節約を狙っていない引き直しでは0＝従来どおり）
        boolean worthIt = after < before || placements(attempt) < placements(result);
        if (worthIt && after <= before * (1.0 + (thrift ? THRIFT_MAX_COST_INCREASE : 0.0))) {
            return attempt;
        }
        return result;
    }

    /**
     * この経路は持ち物の大半を使い切るか。予算が無い（クリエイティブ・設定offなど）なら
     * <b>希少さという概念自体が無い</b>ので問わない。
     *
     * <p>数えるのは<b>手前の区間が使うと決めているぶんも含めた合計</b>。この探索が返した経路の
     * 設置数だけで見ると、区間に割って解いたときは<b>いつも余裕があるように見える</b>——
     * 予算そのものは全区間で共通（手持ちの枚数）なので、比べる相手も全区間の合計でなければ
     * 意味が合わない。
     */
    private static boolean thrifty(CellSource view, PathResult result, int carriedPlacements) {
        int budget = view.placedBlockBudget();
        return budget > 0 && carriedPlacements + placements(result) > budget * THRIFT_TRIGGER_FRACTION;
    }

    private static int placements(PathResult result) {
        return Carryover.placements(result.steps(), 0);
    }

    /**
     * 割り増した設置の値段を元に戻した総コスト。<b>2つの経路を同じ値段で比べるためのもの</b>で、
     * 割増したまま比べると「割増した方の探索が割増した目的関数で勝つ」だけの比較になる。
     *
     * <p>水中で置いた足場だけは差し引きが僅かに足りない（{@code relax}が
     * {@code SUBMERGED_TRAVEL_PENALTY}を辺コスト全体に掛けるため）。<b>ずれる向きは安全側</b>
     * ——引き直した経路の見積もりが実際より高くなるので、採用しすぎる方には倒れない。
     *
     * @param placementScale その経路を求めた探索が使っていた設置の値段の倍率。1.0なら素通し
     */
    private static double trueCost(PathResult result, double placementScale) {
        return totalCost(result)
                - (placementScale - 1.0) * ActionCosts.PLACE_BLOCK_AIM_TICKS * placements(result);
    }

    private static double totalCost(PathResult result) {
        double total = 0;
        for (PathStep step : result.steps()) {
            total += step.cost();
        }
        return total;
    }

    /**
     * 緩和の1群を順に試す。到達した段があればその結果を、無ければ{@code null}を{@link Loosening}で返す。
     *
     * <p>{@code riskyJumpBlocked}には、この群のどこかで「危険な跳躍を理由に手を捨てた」ことが
     * 立つ。呼び出し側はそれを見て次の群（跳躍を開ける段）を作るか決める。
     */
    private static Loosening runStages(CellSource view, SearchLimits limits, long looseningDeadline,
                                       BooleanSupplier cancelled, CostToGo costToGo, SearchCall run,
                                       List<Tolerances> stages) {
        boolean riskyJumpBlocked = false;
        for (Tolerances tolerances : stages) {
            long remainingMillis = looseningDeadline - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                // 上限は疑われた（capBlocked）が、緩和を試し切る前に持ち時間が尽きた。上限ではなく
                // 予算の問題だという手がかりなので残す——ただし予算が厳しい地形では毎回出るので
                // debugに留める（実機の既定ではdebugは出ない）
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("XaeroNav: 上限を疑ったが緩和の時間が残っていなかった");
                }
                break;
            }
            SearchLimits stageLimits = new SearchLimits(limits.maxExpandedNodes(), remainingMillis,
                    limits.heuristicWeight());
            AStarPathfinder stage = new AStarPathfinder(view, stageLimits, costToGo, tolerances);
            PathResult attempt = run.search(stage, cancelled);
            if (attempt.complete()) {
                return new Loosening(attempt, riskyJumpBlocked);
            }
            riskyJumpBlocked |= stage.riskyJumpBlocked();
            // EXHAUSTED以外（予算切れ・キャンセル）は、更に緩めても同じ壁に当たるだけ
            if (attempt.termination() != PathResult.Termination.EXHAUSTED) {
                break;
            }
        }
        return new Loosening(null, riskyJumpBlocked);
    }

    /**
     * @param result           到達した経路。この群では到達しなかったなら{@code null}
     * @param riskyJumpBlocked この群のどこかで危険な跳躍を理由に手を捨てたか
     */
    private record Loosening(PathResult result, boolean riskyJumpBlocked) {
    }

    /**
     * 上限を緩める段（{@link #RUN_CAP_LOOSEN_MULTIPLIERS}倍したものの後に無制限）。落下ダメージは
     * 全段で{@link #loosenedFallDamagePoints}の1段だけ。
     *
     * <p><b>落下ダメージを1段目から開けるのが要点</b>——直前に失敗した探索が既定の許容量そのもので
     * 走っているので、1段目に同じ値を置くと、そちらだけが原因だったときに何も変えない探索を
     * 1回まるごと捨てることになる。
     *
     * <p><b>危険な跳躍（奈落・致死落差の上）だけは、この群を全部試し切ってから開ける</b>
     * （呼び出し側が{@code allowRiskyJumps=true}でもう一度この群を作る）。以前は1段目から無条件に
     * 開けていたが、それだと<b>橋の上限で詰まっただけの探索でも、経路のどこであれ奈落を跳ぶ手が
     * 合法になっていた</b>——実機ジ・エンドのように橋が常用される地形では毎回開くので、
     * 回り込める島の内部の亀裂まで跳んでいた（ユーザー報告「エンド島内部で奈落を越えたジャンプ」）。
     * ユーザーの意図は「同じ島の中なら外周を回れ、島と島の間なら跳べ」で、その使い分けは
     * <b>「橋を架けてでも回れるか」まで含めた「他に道があるか」</b>。
     *
     * <p>跳ぶことになった区間には{@code PathRisk.VOID_BELOW}で警告色が付き、
     * {@code ActionCosts#dropRiskPenalty}が隙間の深さぶんの危険料を積む——<b>開けたあとも、
     * 短い回り道があるならそちらが勝つ</b>。
     *
     * <p><b>持ち物のブロックの予算だけは、他の上限と違って真っ先に外す。</b>あちらは「その移動を
     * 作らない」だけで探索の形は変わらないが、<b>予算は前線が進むほど全ての設置の枝を消していく</b>
     * ——{@code PathNode.placedTotal}はノードの同一性に含まれない近似なので、集約されたセルに
     * 残った累積が実際より多いと、そこから先の橋が理由なく消える。結果、予算内で解けない地形では
     * 探索が橋以外の道を延々と探して予算を焼き切る。
     *
     * <p>実測（実機ジ・エンドの島渡り 1233,1142→1288,1080、橋が43本必要）:
     * <b>予算42以上と8以下では到達するのに、16〜40では60万ノードを焼いて6ステップで終わる</b>。
     * 少ない側で通るのは橋が即座に切られて探索が橋を諦めるから。中間の帯だけが壊れる。
     *
     * <p>倍率で緩めないのは枚数が地形の都合で増えないから。外すなら一度に外す。ここまで来た経路は
     * 「手持ちでは足りないが、それ以外に道が無い」ものなので、HUDが不足を伝える。
     *
     * @param budgetBlocked 最初の探索が予算を理由に設置を捨てたか。立っていれば予算を外した段を
     *                      先頭に積む——予算が原因なら、他の上限をいくら緩めても同じ壁に当たる
     */
    // 段の順序そのものが直した中身なので、探索を回さずに直接確かめられるようにpackage-privateにしてある
    static List<Tolerances> capStages(CellSource view, boolean allowRiskyJumps, boolean budgetBlocked,
                                       boolean emptyInventoryBlocked) {
        RunCaps base = RunCaps.of(view);
        int fallPoints = loosenedFallDamagePoints(view);
        int budget = view.placedBlockBudget();
        List<Tolerances> stages = new ArrayList<>(RUN_CAP_LOOSEN_MULTIPLIERS.length + 3);
        if (budgetBlocked && budget > 0) {
            stages.add(new Tolerances(base, fallPoints, allowRiskyJumps, 0, false));
        }
        // 置けるブロックを1つも持っていないせいで設置を捨てた場合も、上限を緩める前にここを開ける。
        // 他の上限をいくら緩めても「橋そのものが生成されない」という壁は動かない
        if (emptyInventoryBlocked) {
            stages.add(new Tolerances(base, fallPoints, allowRiskyJumps, 0, true));
        }
        for (int multiplier : RUN_CAP_LOOSEN_MULTIPLIERS) {
            stages.add(new Tolerances(scaleCaps(base, multiplier), fallPoints, allowRiskyJumps, budget,
                    emptyInventoryBlocked));
        }
        stages.add(new Tolerances(RunCaps.NONE, fallPoints, allowRiskyJumps, 0, emptyInventoryBlocked));
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
