package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.apache.logging.log4j.Logger;

import org.apache.logging.log4j.LogManager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.MonotonicTime;
import net.prason.xaeronav.pathfinding.astar.Carryover;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.NavigationTuning;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.GenerationGate;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.corridor.CorridorLegSolver;
import net.prason.xaeronav.pathfinding.corridor.CorridorWaypoints;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.navgraph.FarField;
import net.prason.xaeronav.pathfinding.navgraph.RouteReview;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.pathfinding.world.AvoidedCellSource;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.StanceFinder;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;
import net.prason.xaeronav.util.GameCompat;

/**
 * クライアント側の経路探索状態。
 *
 * <p>{@link #setGoal}/{@link #onClientTick}はクライアントスレッド（メインスレッド）から呼ぶこと。
 * メインスレッドで行うのは{@link ChunkView}の構築（読み込み済みチャンクへの参照集め）だけで、
 * ブロックの読み取りとA*探索はどちらも{@link PathfindingExecutor}のワーカースレッドで行う。
 */
public final class PathfindingState {

    public static final PathfindingState INSTANCE = new PathfindingState();

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * 目的地までこの水平距離まで来たら、飛行の案内をやめて歩行の経路へ引き継ぐ（ブロック）＝3チャンク。
     *
     * <p>両側から挟んで決まる値。<b>遠すぎると</b>まだ空を飛んでいるうちに空中経路が消え、目的地への
     * 直線だけになる。<b>近すぎると</b>歩行の経路が出るより先に着く——最良滑空の水平成分は
     * 1.51ブロック/tick＝約30ブロック/秒（{@code ElytraPhysics}の掃引値）、急降下なら67ブロック/秒
     * 出るので、16ブロックでは巡航で0.5秒・急降下で0.24秒しかなく、探索が間に合わない。
     * 48ブロックなら巡航1.6秒・急降下0.7秒で、この距離の歩行探索は開けた地形なら十分収まる。
     *
     * <p>{@code detailHorizonBlocks}（既定96）の半分でもある——引き継いだ時点の歩行経路が必ず
     * 一度の探索で解け、中間目標を挟まずに目的地まで通しで出る。
     */
    private static final double LANDING_APPROACH_ENTER_BLOCKS = 48.0;

    /**
     * 引き継いだ後、これを超えて離れたら飛行の案内へ戻す（ブロック）＝5チャンク。
     *
     * <p>往復しないよう入口より広く取るが、<b>広く取りすぎてはいけない</b>。留まる条件には
     * 「真下に地面があること」が入っていない（谷や溶岩の海をまたぐたびに飛行へ戻さないため）ので、
     * ここが広いと再び飛び立った後も歩行の案内のまま高空を滑空することになり、足元に床が無い始点で
     * 探索を投げ続けて「経路なし」が出続ける。入口の1.67倍＝離陸し直してから約1秒で飛行へ戻る。
     */
    private static final double LANDING_APPROACH_EXIT_BLOCKS = 80.0;

    /** 着地できる地面を探す深さ（ブロック）。{@code StanceFinder.VERTICAL_SEARCH}に合わせる。 */
    private static final int LANDING_GROUND_SEARCH_BLOCKS = 32;

    /** 到着表示を出しておく長さ（tick）。過ぎたら目的地ごと片付ける。 */
    private static final int ARRIVAL_DISPLAY_TICKS = 100;

    /** 経路から外れたときの再計算の下限間隔（tick）。外れている間ずっと探索を投げ続けないための頭打ち。 */
    private static final int MIN_RECALC_INTERVAL_TICKS = 10;

    /** 目的地の列が読み込まれたかを見に行く間隔。見るたびに列を縦に走査するので毎tickは見ない。 */
    private static final int GOAL_RESOLVE_CHECK_TICKS = 20;

    /**
     * 打ち切られた経路の末端がこの距離まで近づいたら、その先を計算し直す（ブロック）。
     *
     * <p>探索には数百msかかるうえ結果は次tick以降に反映されるので、末端に着いてから引き直しても
     * そこで案内が一度途切れる。走って到達するまでの時間が計算時間を十分上回る距離を取る。
     */
    static final double EXTEND_DISTANCE_BLOCKS = 64.0;

    /** 経路を大きく引き直したことを知らせておく長さ（tick）。 */
    private static final int REROUTE_NOTICE_TICKS = 60;

    /** 経路が出せなかったあと、再挑戦するまでに動く距離（ブロック）。 */
    private static final double RETRY_MOVE_BLOCKS = 4.0;

    /**
     * 継ぎ足しが「前へ出た」と認める最小の水平距離（ブロック）。
     *
     * <p>予算切れの末端から伸ばすのは筋が通るが、行き止まりの袋小路でも
     * {@code AStarPathfinder#selectFallback}は「始点から5ブロック以上離れた最良点」を返すので、
     * 歯止めが無いと数ブロックずつ這い続ける。
     */
    static final double MIN_EXTEND_PROGRESS_BLOCKS = 12.0;

    /**
     * 再挑戦の予約（{@link #wideSearchNeededTarget} / {@link #coarseGuideNeededTarget}）を
     * 「同じゴール」とみなす距離（ブロック）。
     *
     * <p>座標の完全一致で照合してはいけない。detail-targetは<b>プレイヤー位置からルート上へ
     * 補間し直す</b>ので、歩いている限り毎回1〜3ブロックずれる。一致を求めると、予約を立てた
     * 次のtickにはもう別の座標になっていて再挑戦が発動しない——実機ログでは「展開ノード数の
     * 上限に当たりました」が0.5〜0.7秒間隔で20〜30回続く間、粗い経由地チェーンが1回しか
     * 走っていなかった（立ち止まると発動する、という形で症状が出る）。
     *
     * <p>予約が意味しているのは「この辺りの地形では通常の探索が届かない」という<b>局所的な事実</b>
     * なので、点ではなくその近傍で照合するのが本来の形。幅は
     * {@link #REFINED_WAYPOINT_MIN_SPACING_BLOCKS}（waypointの間引き間隔）に合わせてある。
     */
    private static final double RETRY_TARGET_TOLERANCE_BLOCKS = 24.0;

    /**
     * 長距離ルートのwaypointをゴールとして許す半径（ブロック）。
     *
     * <p>層1のwaypointはチャンク(16ブロック)の代表点、層2で精緻化しても地表データ由来の推定でしかない。
     * <b>中継地点は通る場所ではなく向かう方角</b>——層1の役割の定義そのものなので、座標ぴったりを
     * 要求すると本来不要な遠回りが経路に乗る。層2の精緻版がある場合を見込んで、セルの半幅(8)より
     * やや小さく取る。
     */
    private static final int WAYPOINT_GOAL_RADIUS_BLOCKS = 6;

    /**
     * waypointへ向かう線上の補間点（{@link #pointAlong}）をゴールとして許す半径（ブロック）。
     *
     * <p>こちらは地形をまったく見ていない<b>人工的な点</b>——waypointが遠すぎて一度に狙えないときに、
     * その方角へ進むためだけに置いている。ここを厳密なゴールにするのが「遠回り」の最大の発生源なので、
     * waypoint本体より大きく取る。
     */
    private static final int INTERPOLATED_GOAL_RADIUS_BLOCKS = 16;

    /**
     * 遠い目的地で、航法グラフが初めて組み上がるのを待つ上限（ミリ秒）。
     *
     * <p>待たずに3D粗層や中間目標で引くと、地図の読み込み・3D粗層・航法グラフが揃うたびに線が描き変わり、
     * 最初の十数秒どちらへ歩けばよいか分からない（実機のネザー: 3回描き変わった末に航法グラフの経路に落ち着いた）。
     * 組めないまま待ち続けないよう、上限を過ぎたら従来の探索で引く。そうして引いた経路も、組み上がったガイドで遠回りと分かれば
     * 見直しで引き直される（{@link #reviewAgainstNavGraph}）。
     */
    private static final long NAV_GRAPH_WAIT_MILLIS = 10_000L;

    /**
     * 組み直したガイドで見直して、引き直す遠回りの量（tick）と、見直した区間の値段に対する割合。両方を超えたら引き直す。
     * {@link RouteReview}参照。
     */
    private static final double REVIEW_MIN_EXTRA_TICKS = 40.0;
    private static final double REVIEW_MIN_EXTRA_RATIO = 0.05;

    /**
     * 見直しで引き直したあと、次に見直すまでに歩く距離（ブロック）。ガイドと探索が食い違って引き直しても遠回りが消えない場所で、
     * 組み直しのたびに線を描き変え続けないための歯止め。
     */
    private static final double REVIEW_RETRY_MOVE_BLOCKS = 32.0;

    /** 経路が出せず、その場から動いてもいない場合の再挑戦間隔（tick）。 */
    private static final int NO_ROUTE_RETRY_TICKS = 200;

    /**
     * 長距離ルートを同じ場所から引き直さないための移動距離（ブロック）。
     *
     * <p>層1の引き直しは{@link XaeroMapReader#readSurface}（メインスレッド）と{@link CoarseRouter}の
     * A*2回で、目的地までの距離に比例して重くなる。届く中間目標が1つも無い間はこれが毎回の再計算で
     * 走るが、<b>同じ場所から引き直せば同じルートになる</b>——結果が変わりうるのは、地図に載る範囲が
     * 変わる程度に動いたときだけ。
     */
    private static final double COARSE_ROUTE_RETRY_MOVE_BLOCKS = 32.0;

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

    /**
     * 通常探索が予算切れした地点から、これだけ離れるまでは通常探索を省いて粗い経由地チェーンから
     * 始める（ブロック）。{@link #plainSearchHopeless}参照。
     */
    private static final double PLAIN_RETRY_MOVE_BLOCKS = 32.0;

    /**
     * 通常の予算で解けなかった場所で、予算を何倍にして粘るか。
     *
     * <p><b>実測で決めた値。</b>ジ・エンドの島渡り（実機の保存データを{@code RealEndTerrainTest}へ
     * 取り込んで測定）では、105ステップ・最長42ブロックの橋を含む正しい経路を出すのに
     * <b>532,724ノード</b>必要だった。既定の100,000では6ステップの切れ端しか出ない——
     * 「奈落を渡る経路が見つからない」の正体はこれで、上限でも地図でもなく単純に予算不足だった。
     * 400,000でも足りず600,000で解けた。
     *
     * <p><b>ネザーの溶岩の海（{@code NetherLavaSeaTest}）はさらに要る。</b>40ブロック下が溶岩の
     * 開けた空を50ブロック以上橋で渡る形で、571,059ノードかかる——600,000では余裕が5%しかなく、
     * 地形が少し違うだけで届かなくなる。8倍(800,000)にして3割の余裕を持たせてある。
     *
     * <p>時間も倍率で伸ばす（{@link #DEEP_SEARCH_MAX_MILLIS}で頭を打つ）。ノード予算だけ
     * 増やしても時間で先に切れる。
     *
     * <p>単発で深い予算<b>だけ</b>を使うのは{@link #plainSearchHopeless}が真の場所だけ。
     * それ以外の通常の探索でも、深い予算は{@code PathfindingExecutor#submitWithDeepFallback}で
     * 通常予算と<b>並列に</b>試される——通常予算がすぐ届く普段の地形では即座に打ち切られるので、
     * 応答性への影響は「通常予算と同じ時間だけ、もう1コア使う」程度に留まる。
     */
    private static final int DEEP_SEARCH_BUDGET_FACTOR = 8;

    /**
     * <b>並列フォールバックの通常予算側だけに使う、軽くした重み。</b>
     *
     * <p>重み付きA*(既定1.5)は展開ノードを大きく減らす代わりに、展開済みノードを開き直さないぶん
     * 「先に着いた少し悪い経路」がそのまま確定する。実測（実機9地形・種固定の乱数で振った経路、
     * {@code PathOptimalityTest}）では、最適との差も無駄な上下もほとんどこの重みで説明が付く:
     *
     * <pre>
     *          重み1.5          重み1.2          重み1.0
     * サバンナ  比1.049/上下1.62 比1.026/上下1.24 比1.003/上下1.05
     * 山岳      比1.048/上下1.33 比1.018/上下1.06 比1.003/上下1.01
     * </pre>
     *
     * <p><b>一律に下げてはいけない。</b>140〜200ブロックの経路では展開ノードが3〜5倍に増え、
     * 山岳では既定予算(10万)で届かない経路が20本中1本から3本に増える。
     *
     * <p>そこで{@code PathfindingExecutor#submitWithDeepFallback}が<b>元から通常予算と深い予算を
     * 並列に走らせている</b>ことを使う——通常側だけこの重みにすると、易しい経路は通常側が勝って
     * 質が上がり、難しい経路は今までどおり深い側（{@link AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}）が
     * 拾う。深い側は重みも予算も従来の通常探索以上なので、<b>待ち時間は増えない</b>。
     *
     * <p>継ぎ足し・合流・区間チェーンには掛けない。あちらは深い予算の受け皿を持たないので、
     * 重みを下げて届かなくなると「継ぎ足せない」「合流できない」がそのまま案内の途切れになる。
     */
    private static final double QUALITY_HEURISTIC_WEIGHT = 1.2;

    /**
     * 深い予算での探索に許す最長時間（ミリ秒）。倍率だけで決めると、{@code maxExpandedNodes}を
     * 大きくしている環境で1回の探索が分単位になりうる——その間ずっと案内が古いままになる。
     *
     * <p><b>実機報告のネザーの溶岩の海で15秒では足りないと分かった。</b>渡るのに571,059ノード
     * 必要で、その地形での実機の展開速度は<b>毎秒約2万ノード</b>（実機ログ: 474,944ノード/24秒。
     * 橋の分岐が多いぶん普段の7〜10万より桁が落ちる）——換算すると約29秒かかる。
     * 15秒で切れると深い予算は必ず失敗し、その後に走る粗い経由地チェーンは<b>単発より重い</b>
     * （104万 対 57万ノード）のでそちらも失敗する、というのが「渡れない」の中身だった。
     *
     * <p>30秒は「渡れないよりはマシ」と「待たされている感じ」の折り合い。伸ばした代償は、
     * 本当に詰んでいる地形で「行けません」が出るまでが遅くなること。
     */
    private static final long DEEP_SEARCH_MAX_MILLIS = 30_000;

    /** 長距離ルートの地図読み取りが「遅い」とみなす所要時間。1tick(20TPS)相当。 */
    private static final long SLOW_MAP_READ_THRESHOLD_MILLIS = 50L;
    /** 遅い読み取りが続く間、警告を再度出すまでの間隔。毎回だとログが洪水になる。 */
    private static final long SLOW_MAP_READ_LOG_INTERVAL_MILLIS = 5_000L;
    private static final ChangeGate<Boolean> slowMapReadGate = new ChangeGate<>();

    /**
     * 経路が始点→目標の直線からこれだけ外れていたら、内訳をログに出す（{@link #noteSuspiciousShape}）。
     * 大きく迂回すること自体は正常なので、閾値は「普段は黙っている」程度に高く取る。
     */
    private static final double SUSPICIOUS_DEVIATION_BLOCKS = 24.0;

    /**
     * 引き直した経路の末端が、表示中の経路の末端よりこれだけ目的地から遠ければログに出す
     * （{@link #noteRouteRegression}）。末端は中間目標や探索の打ち切り位置で数ブロックは普通に揺れるので、
     * 揺れでは鳴らず、線が目に見えて縮んだときだけ鳴る幅にする。
     */
    private static final double ROUTE_REGRESSION_LOG_BLOCKS = 16.0;

    /**
     * 経路のステップのうち掘削・設置がこの割合を超えたら、迂回していなくても内訳を出す
     * （{@link #noteSuspiciousShape}）。
     *
     * <p>ずれだけを引き金にしていると、<b>まっすぐ進みながら道中ずっと地形を壊している経路が
     * 1行も残らない</b>。ユーザー報告「無駄な掘削・設置が多い」はそちらの形で出るので、
     * 診断もそちらを直接見る必要がある。
     */
    private static final double SUSPICIOUS_TERRAIN_EDIT_FRACTION = 0.1;

    /**
     * 上の割合と<b>あわせて</b>要求する掘削・設置の実数。
     *
     * <p>割合だけだと短い経路で必ず鳴る——5ステップ先の段差を1つ掘る経路は2割で、これは普通の
     * 案内。しかも経路は数十tickごとに引き直されるので、同じ1行がその間ずっと出続ける。
     * 見たいのは「道中ずっと壊している」方なので、実数の下限で普段の1〜2手を落とす。
     */
    private static final int SUSPICIOUS_TERRAIN_EDIT_STEPS = 8;

    /**
     * 地上優先ナビ（{@link #shouldClimbToSurface}）に入る深さの下限（ブロック）。
     * 地上のすぐ下は、洞窟の入口も崖もたいてい目と鼻の先にあるので、中継区間を挟むより
     * そのまま目的地を目指した方が短い。数マスのために案内を2段階にする価値はない。
     */
    private static final int MIN_UNDERGROUND_DEPTH = 5;

    /** 地上へ出る経路が見つからなかった地点から、もう一度試すまでに動く距離（ブロック）。 */
    private static final double SURFACE_RETRY_MOVE_BLOCKS = 16.0;

    /**
     * 地上へ出る中継区間で、水平方向の探索マージンに掛ける倍率。洞窟の出口は目的地の方角にあるとは
     * 限らないので、通常の範囲のままでは出口ごと範囲の外に落ちる。
     */
    private static final int SURFACE_SEARCH_MARGIN_FACTOR = 2;

    /**
     * 層2廊下で精緻化したwaypoint列を間引く最小間隔（ブロック）。層2はブロック単位の点列を返すため、
     * 間引かないとHUDの「長距離ルート N/M」やwaypoint数が層1の頃と比べて桁違いに増える。
     */
    private static final int REFINED_WAYPOINT_MIN_SPACING_BLOCKS = 24;

    /**
     * detail-targetまでの水平距離の下限（ブロック）。案内として意味のある長さの下限であって、
     * waypoint間隔とは関係が無い（間隔より短いreachは{@link #pointAlong}で表現する）。
     */
    static final int MIN_DETAIL_REACH_BLOCKS = REFINED_WAYPOINT_MIN_SPACING_BLOCKS;

    private final PathfindingExecutor executor = new PathfindingExecutor();
    // 層2廊下の精緻化専用。executorと共用すると、詳細探索の頻繁な再投入（逸脱・末端接近のたびに
    // 走る）のたびにsubmitが「前のジョブ」を打ち切ってしまい、廊下探索が終わる前に必ず潰れる
    private final PathfindingExecutor corridorExecutor = new PathfindingExecutor();
    // 長距離ルート（層1）の解き専用。executor/corridorExecutorはsubmitが前のジョブを打ち切るので相乗りできない
    private final ExecutorService coarseExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-coarse-route");
        thread.setDaemon(true);
        return thread;
    });
    // 滑空中の案内。目的地と「いま滑空しているか」はこちらが持ち、その目的地への空中経路だけを
    // 向こうが持つ。非同期結果の鮮度はstillFlyingToで問い合わせてもらう
    private final FlightNavState flight = new FlightNavState(this::stillFlyingTo, this::publishNavigationView);
    // clear()・新規setGoal()のたびに増分する。非同期結果を適用する直前にこれと照合し、
    // 一致しなければ「もう古くなったリクエストの結果」として捨てる(clear後に古い結果が
    // currentResultを復活させてしまう競合を防ぐ)。
    private final AtomicLong generation = new AtomicLong();

    private volatile @Nullable BlockPos goal;
    // 目的地を設定した次元。座標だけを覚えていると、ネザーへ移動したあとも同じ座標を目指してしまう
    private volatile @Nullable ResourceKey<Level> goalDimension;
    // 目的地の列が未読み込みのまま設定されたときの、指定そのままの座標。読み込まれたら立てる高さへ寄せ直す
    // （resolveGoalStandable）。寄せ終えたらnull
    private volatile @Nullable BlockPos unresolvedGoal;
    private volatile @Nullable DisplayedPath displayed;
    private int ticksSinceGoalResolveCheck;
    private volatile boolean computing;
    private volatile boolean arrived;
    // 地上へ出る経路が出せなかった地点。掘削を切っている・密閉された場所では中継区間そのものが
    // 成立しないので、その付近では地上優先ナビを諦めて本来の目的地へ直接向かう
    private volatile @Nullable BlockPos surfaceLegFailedAt;
    // 長距離ルートの中間目標。地形は不変なので、目的地が変わらない限り引き直さない
    private volatile @Nullable CoarseRoute coarseRoute;
    // coarseRouteの各区間を層2廊下（ブロック解像度）で解決し直した精緻版。層1はチャンク平均でしか
    // 地形を見ないため、waypointが実際には崖の上や湖の中を指すことがある。用意でき次第
    // cachedOrFreshRoute/NavigationView#coarseRouteWaypointsがこちらへ差し替わる
    // （発動条件が一つでも欠けたら層1のcoarseRouteへフォールバックする、という既存の考え方の延長）
    private volatile @Nullable RefinedRoute refinedRoute;
    // 精緻化がバックグラウンドで完了したことを示す、次tickで拾うためのフラグ（pendingWideRetryと
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
    // 目的地が無いまま過ごしたtick数（warmUpWhenIdle）。クライアントスレッドだけが触る
    private int idleTicks;
    private static final int WARM_UP_IDLE_TICKS = 200;
    // 地図の読み込み待ちで引き直した回数（COARSE_MAP_RETRY_LIMIT）。クライアントスレッドだけが触る
    private int coarseMapRetries;
    // 中間目標へ立ち寄らず目的地をそのまま狙っているか（天井のある次元、または航法グラフのガイドがあるとき）。
    // selectDetailTargetが書き、HUDが読む（経路に中間目標の添字が付かないため）
    private volatile boolean aimingPastWaypoints;
    // 天井のある次元の3D粗層。目的地ごとに1つ組み、継ぎ足しにも使い回す
    private final NetherVoxelGuide voxelGuide = new NetherVoxelGuide();
    // 天井の無い次元の航法グラフ。目的地ごとに組み、歩くにつれて窓の差分だけ組み足す
    private final NavGraphGuide navGraphGuide = new NavGraphGuide();
    // 航法グラフが初めて組み上がるのを待っている（NAV_GRAPH_WAIT_MILLIS）。クライアントスレッドだけが触る
    private boolean awaitingNavGraph;
    private long navGraphWaitStartedMillis;
    // 最後に経路を見直したガイドと、見直しで引き直した位置（REVIEW_RETRY_MOVE_BLOCKS）。クライアントスレッドだけが触る
    private @Nullable WindowField reviewedField;
    private @Nullable BlockPos reviewReplannedAt;
    // 詳細探索が通常マージンでは届かなかった探索ゴール。次のrecalculateで範囲を広げて再挑戦する
    // 目印。本来の目的地と長距離ルートの中間目標を区別しないのは、どちらも「描画距離の内側にある
    // 詳細探索のゴール」で、壁や湖を迂回する経路が範囲の外に落ちる事情が同じだから。
    // 「直前の探索が未到達か」のbooleanで持つと、同じ場所を指定し直すたびにclear()で落ちて
    // 通常マージンからやり直しになる（届かないから指定し直す、という一番ありがちな操作で
    // 再挑戦が永久に発動しない）。ゴールそのものを覚えて照合すれば、指定の経緯によらず
    // 「ここには広い範囲が要る」が残る。そのためclear()でも消さない — 別のゴールには
    // 一致しないので勝手に無効化される。whenComplete（ワーカースレッド）で書き、次の
    // recalculate（クライアントスレッド）で読むのでvolatileが要る
    private volatile BlockPos wideSearchNeededTarget;
    // 範囲を広げた再挑戦をすぐに投げ直すべきか。他の再計算トリガー（経路からの逸脱・打ち切られた
    // 末端への接近・経路上の地形変化）はどれもプレイヤーが動くことを前提にしているので、これが
    // 無いと「行き止まりまで歩く」までは広い範囲での探索が始まらない。目的地に着いた直後に次の
    // 目的地を指定して、その場に立ったまま結果を見るような使い方では永久に発動しない
    private volatile boolean pendingWideRetry;
    // wideSearchNeededTarget/pendingWideRetryと対になる、局所障害（描画距離内部の崖・湖）対策の
    // 再挑戦目印。展開ノード数の上限に当たって未到達だった場合、範囲を広げても同じ上限に同じように
    // 当たるだけ（wideRetryが対象外にしている理由そのもの）なので、代わりに読み込み済みチャンクの
    // 生データから粗い地図を組み立て、その経由地を区間ごとに辿る（層3）
    private volatile BlockPos coarseGuideNeededTarget;
    private volatile boolean pendingCoarseGuideRetry;
    // detail-targetまでの水平距離の上限（ブロック）。0は「まだ測っていない＝renderRadiusまで許す」。
    //
    // renderRadiusは「詳細探索が届く距離」の代理には使えない。実測（ネザー・描画距離18＝renderRadius
    // 288）では、探索範囲1369チャンクのうち読み込み済みは367チャンク＝半径173ブロック相当しかなく、
    // さらに1列あたりの通行可能セルが約5.9個（地表を歩くだけの地上とは桁が違う3D迷路）あるため、
    // 既定の10万ノードで実際に経路を引けたのは70〜90ブロック先までだった。予算を4.5倍にしても
    // ステップ数は2.8倍にしかならない＝到達距離は予算の平方根でしか伸びないので、上限を上げても
    // 解決しない。届かない目標を投げ続けると毎回上限まで展開して部分経路しか返らないため、
    // 直近の実績そのものを次回の上限にする。
    //
    // 地上のように予算内で解けている限りrenderRadiusに張り付くので、従来の挙動は変わらない。
    // whenComplete（ワーカースレッド）で書き、次のrecalculate（クライアントスレッド）で読む
    // 前回のdetail-targetが向いていたwaypoint（生座標）。ルート上で後ろへ戻らないための歯止め。
    // 添字ではなく座標で覚えるのは、ルートを引き直すと添字の意味が変わるため——新しい列に同じ点が
    // 無ければ歯止めは自動的に外れる
    private volatile BlockPos lastAimedWaypoint;
    // エリトラで滑空中か。滑空中は地上A*・長距離ルートの計算を一切止め、目的地への直線（点線）だけを
    // 見せる（自動エリトラ検知。「空はプレイヤー自身が見て操縦できる」ため障害物回避の
    // 経路は不要という判断）
    private volatile boolean flying;
    // 目的地の近くまで来て歩行の案内へ引き継いだか。境界での往復を防ぐヒステリシスに使う
    private volatile boolean landingApproachActive;

    /** エリトラの滑空を飛行とみなすかの判定（時間と高さのヒステリシス）。 */
    private final ElytraTrigger elytraTrigger = new ElytraTrigger();
    /**
     * 通過済みとみなす中間目標の数。地図の点線をどこから描くかにだけ使う。
     *
     * <p>経路の末端が向かっている添字まで<b>単調に</b>進める。表示中の経路が中間目標を向いていない
     * 間（本来の目的地へ直行している・まだ経路が無い・到着表示中）も通過済みぶんを描かずに済ませる
     * ためで、モードだけで判断すると、先読みの最後の区間が目的地へ届いてモードがGOALへ変わった
     * 瞬間に通過済みの中間目標が一斉に描き直される——歩いてきた道に線がそのまま残ったように見える。
     */
    private volatile int passedWaypoints;

    /**
     * 直前の通常探索（粗い経由地チェーンではない側）が展開ノード数の上限に当たった地点。
     * {@link #plainSearchHopeless}参照。
     */
    private volatile BlockPos plainBudgetExhaustedAt;

    /**
     * 直近に「立てない」と報告した探索目標。同じ目標を毎回ログに出さないための重複除去
     * （{@link #noteTargetStandability}）。
     */
    private final ChangeGate<BlockPos> unstandableTargetGate = new ChangeGate<>();

    /** 予算を積んだ探索を次tickで投げ直すか。{@link #pendingCoarseGuideRetry}の一段手前。 */
    private volatile boolean pendingDeepRetry;

    /** 「目的地へ行けない」の判定。詳細は{@link StuckTracker}のクラスJavadoc参照。 */
    private final StuckTracker stuckTracker = new StuckTracker();

    /** 「いちばん近づいた所から遠ざかった」ことの検出。詳細は{@link RetreatWatcher}のクラスJavadoc参照。 */
    private final RetreatWatcher retreatWatcher = new RetreatWatcher();

    /**
     * {@link #logSearchReach}が「前進できません」を書いた回数。後退のログに添えると、
     * 引き返しが<b>探索の行き止まり</b>から来たのか、ガイドの組み直しで判断が変わっただけなのかが
     * 区別できる（実機2026-09-18の往復では、その直前に経由地チェーンが3回とも到達=falseだった）。
     */
    private static final AtomicInteger stalledSearches = new AtomicInteger();

    /**
     * 「歩いていた経路が使えなくなった」ことを知らせておく残りtick。行き止まり・世界の変化で
     * 手前の経路ごと引き直したときだけ立てる。逸脱は自分で外れただけなので対象にしない。
     */
    private volatile int rerouteNoticeTicks;

    /**
     * HUD・地図/ワールド描画が読む、地上ナビ関連stateの1フレーム分の合成snapshot。
     *
     * <p>{@link #goal}・{@link #flying}・{@link #arrived}等は個別のvolatileなので、描画側が
     * 複数回に分けて読むと、その間にワーカーcallbackが割り込んで「どの瞬間にも存在しなかった
     * 組み合わせ」を1フレームだけ見せうる。ここに載せたフィールドが変わる出入口
     * （{@link #setGoal}・{@link #clear}・{@link #onClientTick}・各whenCompleteの完了処理・
     * {@link FlightNavState}のonChanged通知）は必ず{@link #publishNavigationView()}を呼び、
     * 描画側は個々のgetterではなく{@link #navigationView()}が返す1つのインスタンスだけを読むこと。
     */
    private volatile NavigationView navigationView = NavigationView.empty();

    // 直近の探索に使った入力。以下はクライアントスレッドからのみ触る。
    private BlockPos lastStart;
    private int ticksSinceRecalc;
    private int ticksSinceValidation;
    private int arrivedTicks;

    /**
     * 非同期結果をメインスレッドへ戻す経路。本番は{@code Minecraft.getInstance()::execute}だが、
     * 注入可能にしてあるのはテスト用（{@link GenerationGate}のcancel伝播を実クライアント無しで
     * 検証するため、TEST-01）。このクラス自体は他の箇所でMinecraft.getInstance()を直接使い続ける
     * ——注入するのはこの1点だけで十分（世代管理・非同期完了順の検証がここに集約されているため）。
     */
    private final Consumer<Runnable> onMainThread;
    /** 5箇所の非同期完了処理が同じ「世代チェック→メインスレッドへ戻す」を個別に書いていたので共通化する。 */
    private final GenerationGate generationGate;
    /** 繋ぎ目の解き直し（{@link SeamRepair}参照）。goal/displayed/computingへは{@link SeamRepair.Host}経由で触る。 */
    private final SeamRepair seamRepair;
    /** 経路の帯からの逸脱に対する合流（{@link Splice}参照）。同じくHost経由でしか状態へ触らない。 */
    private final Splice splice;
    /** 経路の末端からの継ぎ足し（{@link Extend}参照）。長距離ルート選定への問い合わせもHost経由。 */
    private final Extend extend;
    /** 直前に再確認が不成立と判定したセル（{@link RecentFailures}参照）。地上の探索はここを避ける。 */
    private final RecentFailures recentFailures = new RecentFailures();

    private PathfindingState() {
        this(runnable -> Minecraft.getInstance().execute(TickLaps.timed(runnable)));
    }

    PathfindingState(Consumer<Runnable> onMainThread) {
        this.onMainThread = onMainThread;
        this.generationGate = new GenerationGate(generation, onMainThread);
        this.seamRepair = new SeamRepair(executor, generation, generationGate, this::publishNavigationView,
                new SeamRepair.Host() {
                    @Override
                    public @Nullable BlockPos goal() {
                        return goal;
                    }

                    @Override
                    public DisplayedPath displayed() {
                        return displayed;
                    }

                    @Override
                    public void setDisplayed(DisplayedPath path) {
                        displayed = path;
                    }

                    @Override
                    public void setComputing(boolean value) {
                        computing = value;
                    }
                }, recentFailures);
        this.splice = new Splice(executor, generation, generationGate, this::publishNavigationView,
                new Splice.Host() {
                    @Override
                    public @Nullable BlockPos goal() {
                        return goal;
                    }

                    @Override
                    public DisplayedPath displayed() {
                        return displayed;
                    }

                    @Override
                    public void setDisplayed(DisplayedPath path) {
                        displayed = path;
                    }

                    @Override
                    public void setComputing(boolean value) {
                        computing = value;
                    }

                    @Override
                    public @Nullable WindowField guide() {
                        BlockPos currentGoal = goal;
                        return currentGoal == null ? null : navGraphGuide.latest(currentGoal);
                    }
                }, seamRepair, recentFailures);
        this.extend = new Extend(executor, generation, generationGate, this::publishNavigationView,
                new Extend.Host() {
                    @Override
                    public @Nullable BlockPos goal() {
                        return goal;
                    }

                    @Override
                    public DisplayedPath displayed() {
                        return displayed;
                    }

                    @Override
                    public void setDisplayed(DisplayedPath path) {
                        displayed = path;
                    }

                    @Override
                    public void setComputing(boolean value) {
                        computing = value;
                    }

                    @Override
                    public int coarseRoutePendingRegions(BlockPos currentGoal) {
                        CoarseRoute route = coarseRoute;
                        return route != null && route.goal().equals(currentGoal) ? route.pendingRegions() : -1;
                    }

                    @Override
                    public DetailTarget selectDetailTarget(BlockPos start, BlockPos currentGoal, int renderRadius,
                                                            int reach, boolean boatAvailable,
                                                            boolean playerAnchored, int minWaypointIndex,
                                                            boolean ceilingDimension, boolean navGraphGuided) {
                        return PathfindingState.this.selectDetailTarget(start, currentGoal, renderRadius, reach,
                                boatAvailable, playerAnchored, minWaypointIndex, ceilingDimension, navGraphGuided);
                    }

                    @Override
                    public @Nullable GoalGuide goalGuide(Level level, Player player, BlockPos from,
                                                         BlockPos currentGoal, int renderRadius) {
                        return PathfindingState.this.goalGuide(level, player, from, currentGoal, renderRadius, false);
                    }

                    @Override
                    public void noteSearchOutcome(BlockPos start, BlockPos planEnd, PathResult result) {
                        PathfindingState.this.noteSearchOutcome(start, planEnd, result);
                    }
                }, seamRepair, recentFailures);
    }

    /** 到着時間の表示に使う、{@code goal}への出来上がっているガイド。 */
    @Nullable WindowField guideForDisplay(BlockPos goal) {
        WindowField field = navGraphGuide.latest(goal);
        return field != null && field.reachesGoal() ? field : null;
    }

    /** {@link NavGraphGuide#farScaleForDisplay}。 */
    double guideFarScaleForDisplay() {
        return navGraphGuide.farScaleForDisplay();
    }

    /** 今のフレームで使うべき、地上ナビ関連stateの合成snapshot。{@link #publishNavigationView()}参照。 */
    public NavigationView navigationView() {
        return navigationView;
    }

    private void publishNavigationView() {
        navigationView = new NavigationView(goal, flying, arrived, computing || awaitingNavGraph, stuckTracker.reason(), displayed,
                coarseRoute, refinedRoute, passedWaypoints, rerouteNoticeTicks > 0,
                flying ? flight.route() : FlightRoute.NONE);
    }

    /**
     * {@link #navigationView()}が返す不変snapshot。フィールドを個別に読むgetterの代わりに、
     * HUD・地図/ワールド描画はこれを1フレームにつき1度だけ取得して使う。
     */
    public record NavigationView(BlockPos goal, boolean flying, boolean arrived, boolean computing,
                                  StuckReason stuckReason, DisplayedPath displayed, CoarseRoute coarseRoute,
                                  RefinedRoute refinedRoute, int passedWaypoints, boolean rerouted,
                                  FlightRoute flightRoute) {

        private static NavigationView empty() {
            return new NavigationView(null, false, false, false, null, null, null, null, 0, false,
                    FlightRoute.NONE);
        }

        /** {@link PathfindingState#currentResult()}と同じ規則。 */
        public PathResult currentResult() {
            if (flying || displayed == null) {
                return null;
            }
            return displayed.result();
        }

        /** {@link PathfindingState#climbingToSurface()}と同じ規則。 */
        public boolean climbingToSurface() {
            return displayed != null && displayed.mode() == PathMode.TO_SURFACE;
        }

        /** {@link PathfindingState#currentPathEndsAtDestination()}と同じ規則。 */
        public boolean currentPathEndsAtDestination() {
            return displayed != null && displayed.mode() == PathMode.GOAL && displayed.result().complete();
        }

        /** {@link PathfindingState#coarseRouteWaypoints}と同じ規則。まだ通っていない中間目標だけ。 */
        public List<BlockPos> coarseRouteWaypoints() {
            if (flying || arrived) {
                return List.of();
            }
            List<BlockPos> all = routeWaypoints();
            if (all.isEmpty()) {
                return all;
            }
            if (displayed != null && displayed.mode() == PathMode.GOAL && displayed.result().complete()) {
                return List.of();
            }
            int from = displayed != null && displayed.mode() == PathMode.WAYPOINT
                    ? Math.max(passedWaypoints, displayed.waypointIndex())
                    : passedWaypoints;
            if (from <= 0) {
                return all;
            }
            return from >= all.size() ? List.of() : all.subList(from, all.size());
        }

        private List<BlockPos> routeWaypoints() {
            if (coarseRoute == null || goal == null || !coarseRoute.goal().equals(goal)) {
                return List.of();
            }
            return refinedRoute != null && refinedRoute.source() == coarseRoute
                    ? refinedRoute.waypoints() : coarseRoute.waypoints();
        }
    }

    /**
     * 目的地を設定する。
     *
     * @return 実際に採用した、立てる高さへ解決済みの目的地。ワールドが無ければ {@code null}
     */
    public @Nullable BlockPos setGoal(BlockPos goal) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return null;
        }
        clear();
        this.goal = resolveGoalStandable(level, goal);
        this.goalDimension = level.dimension();
        this.unresolvedGoal = level.getChunkSource().getChunkNow(goal.getX() >> 4, goal.getZ() >> 4) == null
                ? goal : null;
        // 滑空中に指定された目的地は、地上へ戻るまで歩行の経路を引かない
        // （引いても表示せず捨てるだけになる）
        this.flying = airborne(level, player);
        GoalWaypoint.sync(this.goal);
        if (this.flying) {
            flight.recalculate(this.goal);
        } else {
            recalculate("目的地の設定");
        }
        publishNavigationView();
        return this.goal;
    }

    /**
     * 目的地のYを、その列で実際に立てる高さへ寄せる。到達判定は座標の完全一致（{@code AStarPathfinder}）
     * なので、Yが地面とずれているだけで探索は到達可能空間を舐め尽くして未到達に終わる。目的地のYは
     * 地図クリックでは地図側の推定値、手入力ではおおよその値で指定されるのが普通で、
     * ブロック単位で正しいことを前提にはできない。
     *
     * <p>要求されたYに最も近い立てる高さを選ぶ（最寄りの地表とは限らない — 洞窟内の目的地も指定できる）。
     * 列が未読み込みならXaeroの地図データへ、それも無ければ元の座標へ順に落とす。
     */
    private static BlockPos resolveGoalStandable(Level level, BlockPos goal) {
        int x = goal.getX();
        int z = goal.getZ();
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            BlockPos fromMap = XaeroPresence.mapPresent() ? resolveGoalOnSurface(goal) : null;
            return fromMap != null ? fromMap : goal;
        }
        int minY = GameCompat.minBuildHeight(level) + 1;
        int maxY = GameCompat.maxBuildHeight(level) - 2;
        int requested = Mth.clamp(goal.getY(), minY, maxY);
        for (int offset = 0; offset <= maxY - minY; offset++) {
            int below = requested - offset;
            if (below >= minY && standableAt(level, x, below, z)) {
                return new BlockPos(x, below, z);
            }
            int above = requested + offset;
            if (offset > 0 && above <= maxY && standableAt(level, x, above, z)) {
                return new BlockPos(x, above, z);
            }
        }
        return goal;
    }

    /**
     * 未読み込みのまま設定した目的地の列が読み込まれたら、立てる高さへ寄せ直す。寄せ直したなら{@code true}。
     *
     * <p>設定時に列が読めなければ、目的地のYは地図の推定か指定そのまま——地図クリックのYは岩の中に落ちることがある
     * （実機のネザー: 要塞の柱の中）。そのままだと航法グラフが目的地へ繋がらず、窓に入った途端にガイドが使えなくなる。
     * 変わるのは高さだけなので、引いてある経路と航法グラフは残す（{@link #retargetGoal}）。
     *
     * <p>読み込み直後のチャンクは中身がまだ届いていないことがある（{@link PathValidator}参照）。立てる所が
     * 見つからないうちは寄せ直しを諦めず、次の機会に読み直す。
     */
    private boolean resolveGoalOnceLoaded(Level level) {
        BlockPos requested = unresolvedGoal;
        BlockPos current = goal;
        if (requested == null || current == null || ticksSinceGoalResolveCheck++ < GOAL_RESOLVE_CHECK_TICKS
                || level.getChunkSource().getChunkNow(requested.getX() >> 4, requested.getZ() >> 4) == null) {
            return false;
        }
        ticksSinceGoalResolveCheck = 0;
        BlockPos resolved = resolveGoalStandable(level, requested);
        if (!standableAt(level, resolved.getX(), resolved.getY(), resolved.getZ())) {
            return false;
        }
        unresolvedGoal = null;
        if (resolved.equals(current)) {
            return false;
        }
        LOGGER.info("XaeroNav: 目的地の列が読み込まれたので立てる高さへ寄せ直しました ({} → {})",
                current.toShortString(), resolved.toShortString());
        if (flying) {
            setGoal(requested);
        } else {
            retargetGoal(resolved);
        }
        return true;
    }

    /**
     * 目的地を同じ列の別の高さへ差し替える。{@link #setGoal}と違って、引いてある経路は消さない。
     *
     * <p>消すと航法グラフを窓全体から組み直すまで経路が出ない（実機のネザー: 寄せ直しから約19秒）。高さが変わっても
     * 経路はおおむね同じ所へ向かっていて、末端からの継ぎ足しは新しい目的地を狙う。航法グラフの辺も高さに依存しないので、
     * 組み直すのはガイドだけで済む。
     */
    private void retargetGoal(BlockPos resolved) {
        goal = resolved;
        GoalWaypoint.sync(resolved);
        CoarseRoute route = coarseRoute;
        if (route != null && !route.waypoints().isEmpty()) {
            // 長距離ルートは目的地の座標で持ち主を見分けるので、そのままだと捨てられてHUDの点線が消える
            List<BlockPos> waypoints = route.reachedGoal() ? replaceLast(route.waypoints(), resolved) : route.waypoints();
            coarseRoute = new CoarseRoute(resolved, route.computedFrom(), route.reachedGoal(), route.pendingRegions(),
                    waypoints);
        }
        CoarseMapForGoal latestMap = latestCoarseMap;
        if (latestMap != null) {
            latestCoarseMap = new CoarseMapForGoal(resolved, latestMap.map());
        }
        // 解いている最中の長距離ルートは古い目的地のもの。次の再計算で引き直す
        solvingCoarse = null;
        navGraphGuide.retarget();
        reviewedField = null;
        reviewReplannedAt = null;
        // 岩の中の目的地へ行けないと判断していたなら、それは寄せ直す前の高さの話
        stuckTracker.reset();
        publishNavigationView();
    }

    /** 足元に立てる地面があり、体の2セルが掘らずに入れるか。{@code AStarPathfinder}の移動の前提と同じ。 */
    private static boolean standableAt(Level level, int x, int y, int z) {
        return CellData.standable(CellData.flagsOf(level.getBlockState(new BlockPos(x, y - 1, z))))
                && CellData.occupiableWithoutDigging(CellData.flagsOf(level.getBlockState(new BlockPos(x, y, z))))
                && CellData.occupiableWithoutDigging(CellData.flagsOf(level.getBlockState(new BlockPos(x, y + 1, z))));
    }

    public void clear() {
        // 世代を進めた時点で実行中の探索の結果は捨てられる。その結果待ちを表すcomputingもここで下ろす
        generation.incrementAndGet();
        GoalWaypoint.sync(null);
        this.computing = false;
        this.goal = null;
        this.goalDimension = null;
        this.unresolvedGoal = null;
        this.displayed = null;
        this.lastStart = null;
        this.arrived = false;
        this.surfaceLegFailedAt = null;
        this.coarseRoute = null;
        this.refinedRoute = null;
        this.refiningRoute = null;
        this.pendingRefinedRouteReady = null;
        this.solvingCoarse = null;
        this.latestCoarseMap = null;
        this.mapRetryBefore = null;
        this.coarseMapRetryAfterMillis = 0L;
        this.coarseMapRetries = 0;
        this.aimingPastWaypoints = false;
        this.voxelGuide.clear();
        this.navGraphGuide.clear();
        this.awaitingNavGraph = false;
        this.navGraphWaitStartedMillis = 0L;
        this.reviewedField = null;
        this.reviewReplannedAt = null;
        this.pendingWideRetry = false;
        this.pendingCoarseGuideRetry = false;
        this.pendingDeepRetry = false;
        this.lastAimedWaypoint = null;
        this.passedWaypoints = 0;
        this.plainBudgetExhaustedAt = null;
        this.splice.clearBlock();
        this.seamRepair.clear();
        this.recentFailures.clear();
        this.stuckTracker.reset();
        this.retreatWatcher.reset();
        stalledSearches.set(0);
        mapReadsWithoutGain = 0;
        lastMapReadFrom = null;
        lastMapKnownCells = -1;
        this.extend.clear();
        this.rerouteNoticeTicks = 0;
        this.flying = false;
        this.flight.reset();
        this.landingApproachActive = false;
        // elytraTriggerはここで戻さない。追っているのは目的地ではなく<b>プレイヤーの体の状態</b>で、
        // 滑空中にgotoを打つと「もう滑空している」という継続が消え、飛行モードへ入り直すまでの
        // 0.5秒だけ地上の探索が走ってHUDに「経路なし」が出る。滑空していなければ次のtickの
        // updateが自分で戻すので、持ち越して困ることもない
        this.arrivedTicks = 0;
        publishNavigationView();
    }

    public BlockPos goal() {
        return goal;
    }

    /**
     * 直近の再計算判断の要約。実機デバッグで「今何が起きているか」をログを遡らず
     * 把握するためのもの（{@code /xaeronav debug summary}）。状態は変えない。
     */
    public record DiagnosticSummary(@Nullable String spliceRefusal, @Nullable String seamRepairRefusal,
                                     @Nullable BlockPos unstandableTarget) {
    }

    public DiagnosticSummary diagnosticSummary() {
        return new DiagnosticSummary(splice.currentRefusal(), seamRepair.currentRefusal(),
                unstandableTargetGate.current());
    }

    public PathResult currentResult() {
        if (flying) {
            return null;
        }
        DisplayedPath shown = displayed;
        return shown == null ? null : shown.result();
    }

    /**
     * 地図描画がその1フレームで必要とするものを組む。
     *
     * <p>個別のgetterで埋めてはいけない。経路・目的地・長距離ルート・空中経路はワーカースレッドが
     * それぞれ別のタイミングで差し替えるので、読む順に古い状態と新しい状態が混ざる——
     * 「もう捨てた経路の末端から新しい目的地へ伸びる点線」のような、どの時点にも存在しなかった
     * 組み合わせが1フレームだけ描かれる。{@link MapPathOverlay.Snapshot}が防いでいるのと同じ
     * 食い違いが1段内側で起きないよう、{@link #navigationView()}が既に発行済みの1つの
     * snapshotから組む。空中の曲がり点線（{@code flight.dashWaypoints}）だけは
     * {@link FlightNavState}自身の内部状態（{@code coarseRoute}/{@code guideWaypoints}）を
     * 参照する既存の作りを残しており、この部分の相互整合はここでは保証しない
     * （空になるとき限定の代替経路同士なので、混ざっても見た目の破綻は小さい）。
     */
    public MapPathOverlay.Snapshot mapOverlaySnapshot(BlockPos playerPos) {
        NavigationView view = navigationView();
        boolean airborne = view.flying();
        boolean done = view.arrived();
        BlockPos currentGoal = view.goal();
        FlightRoute route = view.flightRoute();

        PathResult ground = view.currentResult();
        if (ground != null && ground.steps().isEmpty()) {
            ground = null;
        }
        List<Vec3> dash = flight.dashWaypoints(airborne, done, currentGoal);
        return new MapPathOverlay.Snapshot(ground,
                currentGoal,
                XaeroNavConfig.INSTANCE.straightLineEnabled(),
                XaeroNavConfig.INSTANCE.goalMarkerEnabled() && !GoalWaypoint.placed(),
                playerPos,
                view.coarseRouteWaypoints(),
                route.points(),
                FlightProgress.INSTANCE.segmentFor(route) + 1,
                dash);
    }

    /** エリトラで滑空中か。滑空中は経路を計算せず、目的地への直線（点線）だけを見せる。 */
    public boolean flying() {
        return flying;
    }

    /**
     * 空中経路の非同期結果を適用してよいか（{@link FlightNavState.Current}）。目的地・次元が
     * 計算した時点から変わっておらず、まだ滑空しているときだけ。
     */
    private boolean stillFlyingTo(BlockPos computedGoal, ResourceKey<Level> dimension) {
        return flying && computedGoal.equals(goal) && dimension.equals(goalDimension);
    }

    /**
     * 滑空中の空中経路。飛んでいない・まだ計算できていない・引けなかった場合は空。
     *
     * <p>先頭の点は<b>計算した時点</b>のプレイヤー位置なので、届く頃には最大で再計算間隔ぶん古い。
     * 描画側は先頭を捨てて今の位置から引き直すこと。
     */
    public FlightRoute flightRoute() {
        if (!flying) {
            return FlightRoute.NONE;
        }
        return flight.route();
    }

    /**
     * 空中経路の折れ線を、どの点から描き始めるか。通り過ぎた区間を描かないための添字で、
     * ワールド内描画と地図で必ず共有すること（片方だけ切り詰めると、地図にだけ自分の後ろへ
     * 伸びた線が残る）。
     */
    public int flightRouteFrom() {
        return flight.routeFrom();
    }

    /**
     * 点線が辿るべき中間点。<b>始点も目的地も含まない</b>——描画側はどちらも自分で持っている
     * （始点は太線の末端か現在地、終点は目的地）ので、端を含めると必ず添字をずらす処理が要る。
     *
     * <p>長距離ルートがあればその中間目標を返し、無ければ曲がり点線へ落ちる。呼び出し側から見て
     * 「点線をどこで折るか」という1つの問いなので、2つの供給元をここで1本にまとめる。
     */
    public List<Vec3> flightDashWaypoints() {
        return flight.dashWaypoints(flying, arrived, goal);
    }

    /** 探索がまだ走っているか。まだ経路が無いのが計算中だからなのかを案内表示が区別するために使う。 */
    public boolean computing() {
        return computing;
    }

    /** 目的地に着いたか。着いた瞬間から{@link #ARRIVAL_DISPLAY_TICKS}の間だけtrueになる。 */
    public boolean arrived() {
        return arrived;
    }

    /** 歩いていた経路が使えなくなって引き直した直後か。HUDが知らせるために読む。 */
    public boolean rerouted() {
        return rerouteNoticeTicks > 0;
    }

    /**
     * 目的地へ行けないと判断した理由。まだ判断していない・解消したなら{@code null}。
     *
     * <p>「経路が見つかりません」（＝今回の探索が空だった）とは別のこと。あちらは1回の探索の状態で、
     * 次の探索では出るかもしれない。こちらは<b>何度やっても目的地へ近づけなかった</b>という結論で、
     * この状態の間は探索そのものを止めている（プレイヤーが動くまで結果が変わらないため）。
     */
    public StuckReason stuckReason() {
        return stuckTracker.reason();
    }

    /**
     * 詰みの理由を説明する文言のキー。
     *
     * <p>「掘削や足場の設置を許可してください」は、実際に切っているときにしか意味が無い。
     * 既に許可済みの人に出すと的外れな助言になり、この行ごと読み飛ばす癖がつく——助言の価値は
     * 「読めば次に何をすればいいか分かる」ことなので、当てはまらない助言は出さない方がよい。
     */
    public static String stuckHintKey(StuckReason reason) {
        if (reason == StuckReason.NO_WAY_THROUGH
                && XaeroNavConfig.INSTANCE.diggingEnabled() && XaeroNavConfig.INSTANCE.bridgingEnabled()) {
            return "hud.xaeronav.unreachable_blocked_detour";
        }
        return reason.hintKey();
    }

    /** 表示中の経路が、本来の目的地ではなく「まず地上へ出るまで」の中継経路か。 */
    public boolean climbingToSurface() {
        DisplayedPath shown = displayed;
        return shown != null && shown.mode() == PathMode.TO_SURFACE;
    }

    /** 表示中の実線が中継地点や探索の末端ではなく、本来の目的地まで届いているか。 */
    public boolean currentPathEndsAtDestination() {
        DisplayedPath shown = displayed;
        return shown != null && shown.mode() == PathMode.GOAL && shown.result().complete();
    }

    /**
     * 目的地が無いまま{@link #WARM_UP_IDLE_TICKS}過ごしたら、航法グラフの組み立てを1回だけ下準備する
     * （{@link NavGraphGuide#warmUp}）。ワールドに入った直後はチャンクの読み込みで忙しいので、少し待ってから。
     */
    private void warmUpWhenIdle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            idleTicks = 0;
            return;
        }
        if (++idleTicks == WARM_UP_IDLE_TICKS && XaeroNavConfig.INSTANCE.costToGoGuideEnabled()) {
            navGraphGuide.warmUp(mc.level, mc.player, XaeroNavConfig.INSTANCE.movementOptions());
        }
    }

    public void onClientTick() {
        try {
            if (rerouteNoticeTicks > 0) {
                rerouteNoticeTicks--;
            }
            BlockPos currentGoal = goal;
            if (currentGoal == null) {
                warmUpWhenIdle();
                return;
            }
            GoalWaypoint.sync(currentGoal);
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            if (!mc.level.dimension().equals(goalDimension)) {
                // 別の次元へ移った。同じ座標を目指し続けても意味がないので目的地ごと捨てる
                clear();
                return;
            }
            if (resolveGoalOnceLoaded(mc.level)) {
                return;
            }
            StuckReason notice = stuckTracker.takePendingNotice();
            if (notice != null) {
                // 判断はワーカースレッドで行われる。チャットへの出力はメインスレッド専用なのでここで拾う
                mc.player.displayClientMessage(TextCompat.translatable("hud.xaeronav.unreachable_notice",
                        TextCompat.translatable(stuckHintKey(notice))), false);
            }
            if (arrived) {
                arrivedTicks++;
                if (arrivedTicks >= ARRIVAL_DISPLAY_TICKS) {
                    clear();
                }
                return;
            }
            // 経路とモードは一組で差し替わるので、1tickの判断は同じスナップショットの上で行う
            DisplayedPath shown = displayed;
            // 対応づけの更新は、この下のどの早期returnよりも先に置く。地図を開いている間・滑空中は
            // 更新されないままだったので、その間に経路が差し替わると「別の経路に対して測った距離」が
            // 残り続ける——逸脱の判定・案内・描画がまとめてその値を読む。地図を開いたまま経路が
            // 出来上がるのは一番ありがちな操作（下のコメント参照）で、そこが一番当たりやすい
            long progressLap = TickLaps.start();
            PathProgress.INSTANCE.update(shown == null ? null : shown.result(), mc.player.position());
            TickLaps.add("進捗の対応づけ", progressLap);
            // 画面を開いている間の早期returnより先に置く。ここから下で止まるのはプレイヤーが
            // 動けない状況だけなので、後退の観測を落としても取りこぼしは無いが、順序を変えると
            // 「滑空中は数えない」のような穴が生まれる
            long retreatLap = TickLaps.start();
            noteRetreat(mc.player.blockPosition(), currentGoal, shown);
            TickLaps.add("後退の監視", retreatLap);
            // Xaeroの世界地図やインベントリを開いている間、プレイヤーは動けない。ここで止めないと
            // 地図を眺めているだけの間ずっと同じ入力に対する探索が走り続ける。
            if (mc.screen != null) {
                // 一度きりの再挑戦だけは通す。地図で目的地を指定してそのまま地図で経路を眺めるのは
                // 一番ありがちな操作で、ここで止めると「通常マージンでは届かなかった」遠い目的地の
                // 経路が地図を閉じるまで出てこない（他の再計算トリガーはどれもプレイヤーが動くことを
                // 前提にしているので、画面を開いている間に走る心配がない）
                pendingEscalation(mc.player);
                return;
            }
            boolean nowFlying = airborne(mc.level, mc.player) && !landingApproach(mc.level, mc.player, currentGoal);
            if (nowFlying != flying) {
                flying = nowFlying;
                if (nowFlying) {
                    // 世代を進めた時点で走っている探索の結果は捨てられる。ただし世代不一致の
                    // whenCompleteは早期returnしてcomputingを書かないので、ここで明示的に下ろす
                    generation.incrementAndGet();
                    computing = false;
                    // 離陸した瞬間から線を曲げたい。周期を待つと最初の数秒だけ山を突き抜けて見える
                    flight.recalculate(currentGoal);
                } else {
                    // 着地した。離陸前の経路は遠く離れた場所のものなので先に消してから引き直す
                    // （消さないと、新しい経路が届くまでの数tickだけ古い線が残って見える）
                    displayed = null;
                    flight.dropRoute();
                    recalculate("着地");
                    return;
                }
            }
            if (flying) {
                // 滑空中は地上の経路追従・A*の再計算を止め、空中経路だけを見る
                checkArrival(mc.player, currentGoal, null);
                flight.tick(mc.level, mc.player, currentGoal);
                return;
            }
            if (shown != null && shown.mode() == PathMode.WAYPOINT) {
                passedWaypoints = Math.max(passedWaypoints, shown.waypointIndex());
            }
            if (shown != null && shown.mode() == PathMode.TO_SURFACE && !computing
                    && surfaceLegDone(mc.level, mc.player, currentGoal, shown)) {
                // 地上に出た。ここから先は本来の目的地に向けて経路を引き直す
                // （新しい経路が届くまでは中継経路のまま表示し続ける。先にモードだけ戻すと、
                // 中継経路の終端＝いまの足元が「経路の終わり」と見なされて誤って到着になる）
                recalculate("地上に出た");
                return;
            }
            PathResult result = shown == null ? null : shown.result();
            if (checkArrival(mc.player, currentGoal, shown)) {
                return;
            }
            if (computing) {
                // 計算中は再計算のトリガーを一旦止める。さもないと非同期結果が返ってくるまでの
                // 数tickの間、毎tick探索を投げ直してしまう。
                return;
            }
            if (awaitingNavGraph) {
                // 組み上がったかは探索を投げる側（recalculate）が見る。組み上がるまでは何も投げずに戻る
                recalculate("航法グラフ待ち");
                return;
            }
            ticksSinceRecalc++;
            ticksSinceValidation++;
            if (stuckTracker.reason() != null
                    && !stuckTracker.retryDue(lastStart, mc.player.blockPosition(),
                            ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS)) {
                // 目的地へ行けないと判断済み。同じ場所から投げ直しても読み込み済みチャンクも地形も
                // 変わっていないので結果は同じ——実機では通常探索10万＋粗い経由地チェーン20万ノードを
                // 3秒おきに焼き続けていた。プレイヤーが動くか、世界が変わりうるだけの時間が経つまで待つ
                return;
            }
            if (pendingEscalation(mc.player)) {
                return;
            }
            // 継ぎ足しより前に置く。深い先読みでは継ぎ足しが数tick続くので、後ろに置くと
            // その間ずっと starve して、欠けた地図で引いた大局のまま歩き続けることになる。
            // 中継区間（TO_SURFACE）は長距離ルートを使っていないので対象外
            if ((shown == null || shown.mode() != PathMode.TO_SURFACE)
                    && redrawCoarseRouteForLoadedMap(currentGoal)) {
                return;
            }

            if (result == null || result.steps().isEmpty()) {
                retryWithoutRoute(mc.player.blockPosition());
                return;
            }
            // 経路の帯からはみ出したときだけ引き直す。1〜2マス横にずれた程度で作り直すと、
            // そのたびに違う経路が出てきて線が落ち着かない（歩いているだけで案内が変わる）
            if (offPathDistance(mc.level, mc.player, result)
                    > XaeroNavConfig.INSTANCE.deviationThresholdBlocks()) {
                if (ticksSinceRecalc >= MIN_RECALC_INTERVAL_TICKS
                        && !splice.trySplice(mc.level, mc.player, shown, 0)) {
                    // 合流できない経路だけ、全部引き直す
                    recalculate("逸脱して合流できなかった");
                }
                return;
            }
            // 継ぎ足しは逸脱・到着の判定より後に置く。深い先読みでは区間を連続で探索しうるので、
            // 先に置くとその間ずっと逸脱検知が止まる（computing中は下のトリガーが全て止まるため）
            // 継ぎ足しは中間目標へ向かう経路だけのものではない。目的地へ直接向かう経路も、予算切れで
            // 打ち切られていれば末端から伸ばせる——ここを WAYPOINT に限ると、そういう経路は下の
            // 「打ち切られた末端に近づいたら引き直す」に落ちて毎回<b>全置換</b>され、手前の案内まで
            // 描き変わる。地上へ出る中継区間（TO_SURFACE）だけはゴールの意味が違うので対象外
            if (shown.mode() != PathMode.TO_SURFACE) {
                int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
                if (extend.shouldExtend(mc.player, shown, renderRadius)) {
                    // 末端から継ぎ足す。着いてから引き直すのでは遅い——探索に数百msかかり、反映は
                    // さらに次tick以降なので、その間ずっと「もう終わっている経路」を見せることになる。
                    //
                    // ここに間隔ゲートを掛けないのは、継ぎ足しが手前の経路を変えないから。全置換だった
                    // 頃は頻度を上げるとそのまま案内のちらつきになったが、継ぎ足しにはその副作用が無い
                    extend.extendPath(shown);
                    return;
                }
                if (reachedPathEnd(mc.player, shown)) {
                    // 末端に着いたのに伸ばせていない＝行き止まりか予算切れ。ここで初めて全体を引き直す。
                    //
                    // ここへ来た時点で案内は途切れる——引き直しには数秒かかり、その間プレイヤーは
                    // 案内の無い状態で立たされる（実機報告「ルートの先まで着いて計算が追いついていない」）。
                    // <b>継ぎ足しがなぜ間に合わなかったのか</b>が分からないと直しようがないので、
                    // 断った理由をここで残す。1本の経路につき1回しか出ない（この直後にcomputingが立つ）
                    LOGGER.debug("XaeroNav: 経路の末端に着いたので引き直します (継ぎ足せなかった理由={}, {}ステップ)",
                            extend.extendRefusal(mc.player, shown, renderRadius), shown.result().steps().size());
                    recalculate("経路の末端に到着");
                    return;
                }
                // 継ぎ足す先も末端への到達も無いtickでだけ、直前の繋ぎ目を解き直す。案内を先へ
                // 伸ばす方が常に優先——修復は既に引いてある線の質の話でしかない
                if (!seamRepair.isEmpty() && seamRepair.tryRepair(mc.level, mc.player, shown, renderRadius)) {
                    return;
                }
                if (ticksSinceRecalc >= MIN_RECALC_INTERVAL_TICKS
                        && reviewAgainstNavGraph(mc.player, currentGoal, shown)) {
                    return;
                }
            }
            if (ticksSinceRecalc >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()
                    && !result.complete() && nearPathEnd(mc.player.position(), result)
                    && retryTruncatedNow(mc.player)) {
                // 打ち切られた末端に近づいた。ここから先は新しく読み込まれたチャンクを使って伸ばせる
                recalculate("打ち切られた末端に接近");
                return;
            }
            if (ticksSinceValidation >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
                // プレイヤーが動かなくてもワールドは変わりうる。経路上のセルだけを定期的に見る
                ticksSinceValidation = 0;
                // この定期検証が「地形が変わった」の第一の入口——まだ歩いていない先も見るので、
                // ユーザーが経路上のどこかにブロックを置いただけでも即座にここで引っかかる。
                // 理由を出さないと「置いたら経路が消えた」の説明が付かない
                // （実際にユーザーからその報告が出て、この行で裏が取れた）
                //
                // 見るのは<b>いま居るステップから先</b>だけ。もう歩き終えた区間の変化はこれから通る道に
                // 関係が無いうえ、そこから走査すると背後の変化で止まって先の変化を見落とす。
                // 描画距離より先は goto 直後のストリーミング中に誤爆するので見ない（PathValidator参照）
                int validationHorizon = ClientCompat.renderDistance(mc.options) * 16;
                long validationLap = TickLaps.start();
                PathValidator.Failure failure = PathValidator.firstFailureFrom(mc.level, result,
                        PathProgress.INSTANCE.indexFor(result), mc.player.blockPosition(), validationHorizon);
                TickLaps.add("経路の検証", validationLap);
                if (failure != null) {
                    noteUnusableCell(failure);
                    handleBlockedPath(mc.level, mc.player, shown, failure);
                }
            }
        } finally {
            long publishLap = TickLaps.start();
            publishNavigationView();
            TickLaps.add("表示の更新", publishLap);
        }
    }

    /**
     * 経路上のセルが世界の変化で成立しなくなった。<b>塞がった箇所の前後だけ</b>を作り直せるなら
     * そうして、できないときだけ全部引き直す。
     *
     * <p>橋を渡るために自分でブロックを置くのは意図した操作で、経路の線上にも普通に置く。それを
     * 全引き直しの理由にすると、280ステップの経路が一手ごとに丸ごと作り直される——引き直した先が
     * 同じ経路になる保証は無いので（{@link #pathWorthKeeping}参照）、置くたびに案内が別物になる。
     */
    private void handleBlockedPath(Level level, Player player, DisplayedPath shown, PathValidator.Failure failure) {
        if (splice.trySplice(level, player, shown, failure.stepIndex() + 1)) {
            // 迂回はHUDで知らせない。合流点から先はそのまま残るので「歩いていた道が突然消えた」
            // ことにはならず、橋を架けながら置くたびに警告が出続けるだけになる
            LOGGER.debug("XaeroNav: 経路上のセルが変化したため塞がった箇所を迂回します ({})", failure.reason());
            return;
        }
        // 迂回できずに全部引き直す。案内が急に変わる理由が分からないままなので、変わったこと自体を
        // 知らせる
        LOGGER.debug("XaeroNav: 経路上のセルが変化したため引き直します ({})", failure.reason());
        rerouteNoticeTicks = REROUTE_NOTICE_TICKS;
        recalculate("経路上のセルが変化");
    }

    /**
     * ワーカースレッドが立てた「一度きりの再挑戦」の予約を拾って投げ直す。投げたなら{@code true}。
     *
     * <p>他の再計算トリガー（逸脱・末端への到達・定期検証）と違って、これらはプレイヤーが動くことを
     * 前提にしていない。探索が「範囲が足りなかった」「予算が足りなかった」と分かった時点で立ち、
     * 立てた側は次の一手を持っていないので、ここで拾わないと予約は永久に消化されない。
     *
     * @return 引き直しを投げたか（投げたなら、この後の再計算トリガーは見なくてよい）
     */
    private boolean pendingEscalation(Player player) {
        if (flying || computing) {
            return false;
        }
        if (stuckTracker.reason() != null
                && !stuckTracker.retryDue(lastStart, player.blockPosition(), ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS)) {
            return false;
        }
        if (pendingWideRetry) {
            // 通常マージンでは届かなかった。範囲を広げて投げ直す（間隔を空ける必要はない —
            // 広い範囲での探索は目的地ごとに一度だけで、失敗しても二度目のpendingWideRetryは立たない）
            pendingWideRetry = false;
            recalculate(Escalation.WIDE, "予約した再挑戦");
            return true;
        }
        if (pendingDeepRetry) {
            // 展開ノード数の上限に当たって未到達だった。まずは予算を積んで投げ直す——実測では
            // 区間分割より単発探索に予算を与える方が確実だった（DEEP_SEARCH_BUDGET_FACTOR参照）。
            // 予約として持つのが要点で、これが無いと深い探索は他のトリガー（逸脱・末端への接近）が
            // たまたま引かれるまで走らない
            pendingDeepRetry = false;
            recalculate(Escalation.DEEP, "予約した再挑戦");
            return true;
        }
        if (pendingCoarseGuideRetry) {
            // 展開ノード数の上限に当たって未到達だった。範囲を広げても同じ上限に当たるだけなので、
            // 代わりに粗い経由地チェーンで区間を分割して投げ直す
            pendingCoarseGuideRetry = false;
            recalculate(Escalation.COARSE_GUIDED, "予約した再挑戦");
            return true;
        }
        RefinedRoute pendingRefined = pendingRefinedRouteReady;
        if (pendingRefined != null && pendingRefined.source() == coarseRoute) {
            // 層2廊下による精緻化がバックグラウンドで終わった。まだ層1ベースのwaypointへ
            // 向かっていれば、精緻版へ切り替えるために引き直す
            pendingRefinedRouteReady = null;
            refinedRoute = pendingRefined;
            recalculate("層2の精緻化が完了");
            return true;
        }
        if (pendingRefined != null) {
            // 完了後に粗経路が差し替わった古いイベント。現行探索を巻き込まず捨てる。
            pendingRefinedRouteReady = null;
        }
        return false;
    }

    /**
     * 地図が欠けたまま引いた長距離ルートを、読み込みが進んだところで引き直す。引き直したなら{@code true}。
     *
     * <p>{@link #cachedOrFreshRoute}にある同じ判断は{@code playerAnchored}の枝——つまり
     * {@link #recalculate}の中——にしか無く、<b>完走できる経路を逸脱せずに歩いている間
     * {@code recalculate}は一度も走らない</b>（走るのは逸脱・末端への到達・打ち切り経路・経路上の
     * 変化・エスカレーションだけで、継ぎ足しは{@code playerAnchored=false}）。実機のネザーでは、
     * 32%がデータ無し・20リージョン未読み込みの地図で決めた大局が目的地まで残っていた——未知セルは
     * {@link CoarseRouter}でほぼ最安なので、まだ見えていない溶岩の海を突っ切る大局が選ばれ、
     * 層2・層3がそれを迂回して経路が膨らむ。読み込みの要求も{@link #freshRoute}の中でしか
     * 出さないので、ここが無いと要求そのものが最初の1回で止まる。
     *
     * <p><b>{@link #freshRoute}だけを呼ぶのでは足りない。</b>中間目標の列が入れ替わると添字の意味が
     * 変わり、表示中の経路の{@code waypointIndex}を下限に使う{@link #extendPath}が新しい列の
     * 末尾＝目的地に張り付く。{@code recalculate}なら経路ごと入れ替わるので添字が食い違わない。
     */
    private boolean redrawCoarseRouteForLoadedMap(BlockPos currentGoal) {
        CoarseRoute before = coarseRoute;
        if (before == null || !before.goal().equals(currentGoal) || before.pendingRegions() == 0
                || coarseMapRetries >= COARSE_MAP_RETRY_LIMIT) {
            return false;
        }
        // 精緻化の最中に引き直すと、その結果は由来元の不一致で捨てられる（cachedOrFreshRouteと同じ条件）
        if (refiningRoute != null || solvingCoarse != null || MonotonicTime.millis() < coarseMapRetryAfterMillis) {
            return false;
        }
        coarseMapRetries++;
        DisplayedPath shown = displayed;
        Minecraft mc = Minecraft.getInstance();
        boolean guided = shown != null && shown.mode() == PathMode.GOAL && navGraphGuide.latest(currentGoal) != null;
        // 引き直しの結果は同期でも裏で解いても採用のとき（adoptCoarseRoute）にログへ出す
        mapRetryBefore = before;
        if (guided && mc.player != null) {
            // 航法グラフで引いた経路は層1の中間目標を使っていない。地図が埋まって変わるのは窓の外の推定とHUDの点線だけなので、
            // 経路ごと引き直さない——引き直すと読み込みが進むたびに線が描き変わる（遠回りは組み直したガイドの見直しが拾う）
            freshRouteInBackground(mc.player.blockPosition(), currentGoal, ChunkView.boatAvailable(mc.player), true);
        } else {
            recalculate("地図の読み込みが進んだ");
        }
        if (guided) {
            // 経路を投げていないので、呼び出し元の他のトリガーをこのtickで止める理由は無い
            return false;
        }
        return true;
    }

    /**
     * 中継区間（地上へ出るまで）を終えて、本来の目的地へ引き直してよいか。
     *
     * <p>高さだけで判断すると、天井の下にある洞窟の坑道でも「地上に出た」ことになり、そこから
     * 目的地へ直行する経路＝避けたかった一直線の掘り進みに戻ってしまう。中継が要らなくなったか
     * （空の下に出た、あるいはこの付近では中継を諦めた）で判断する。
     *
     * <p>あわせて中継経路の終端に立ったかも見る。地上かどうかの判定は、探索側がハイトマップを、
     * ここが{@code canSeeSky}を使っており、ガラス屋根のように両者が食い違う場所がありうる。
     * 終端を見ておかないと、そこに立ったまま次の区間へ進めなくなる。
     */
    private boolean surfaceLegDone(Level level, Player player, BlockPos currentGoal, DisplayedPath shown) {
        BlockPos at = player.blockPosition();
        if (!shouldClimbToSurface(level, at, currentGoal, surfaceReferenceY(level, at))) {
            return true;
        }
        return reachedPathEnd(player, shown);
    }

    /**
     * 表示中の経路の終端に着いたか。中間目標はチャンク中心の代表点で、地形によっては
     * 真上に立てないことがあるので、目的地そのものではなく経路の終端で判定する。
     */
    private boolean reachedPathEnd(Player player, DisplayedPath shown) {
        List<PathStep> steps = shown.result().steps();
        return !steps.isEmpty()
                && near(player, steps.get(steps.size() - 1).pos(), XaeroNavConfig.INSTANCE.arrivalRadiusBlocks());
    }

    /**
     * 目的地に着いたかどうか。水平・垂直とも{@code arrivalRadiusBlocks}以内に来たら到着とする。
     *
     * <p>掘っても辿り着けない座標が目的地のこともある（{@link net.prason.xaeronav.pathfinding.world.StanceFinder}
     * が寄せた地点までしか経路が伸びない）。その場合は実際に辿れる経路の終端を基準に到着を判定する。
     * 地上へ出るまでの中継経路・長距離ルートの中間目標（{@link DisplayedPath#mode}が{@code GOAL}でない）は
     * この対象に含めない — 本来の目的地ではないので、着いてもここでは「到着」にしない
     * （{@link #onClientTick}側で次の区間へ引き継ぐ）。
     */
    private boolean checkArrival(Player player, BlockPos currentGoal, DisplayedPath shown) {
        double radius = XaeroNavConfig.INSTANCE.arrivalRadiusBlocks();
        if (near(player, currentGoal, radius)) {
            arrive();
            return true;
        }
        if (shown == null || shown.mode() != PathMode.GOAL) {
            return false;
        }
        PathResult result = shown.result();
        List<PathStep> steps = result.steps();
        if (result.complete() && !steps.isEmpty()
                && near(player, steps.get(steps.size() - 1).pos(), radius)) {
            arrive();
            return true;
        }
        return false;
    }

    private static boolean near(Player player, BlockPos pos, double radius) {
        return horizontalDistanceSq(player, pos) <= radius * radius
                && Math.abs(pos.getY() - player.blockPosition().getY()) <= radius;
    }

    private static double horizontalDistanceSq(Player player, BlockPos pos) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private void arrive() {
        // 走っている探索の結果で経路が復活しないように世代を進める
        generation.incrementAndGet();
        computing = false;
        displayed = null;
        flight.dropRoute();
        arrivedTicks = 0;
        arrived = true;
        stuckTracker.clearReason();
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(
                    //? if >=1.17 {
                    SoundEvents.NOTE_BLOCK_BELL.value(),
                    //?} else {
                    /*SoundEvents.NOTE_BLOCK_BELL,
                    *///?}
                    0.4f, 1.5f);
        }
    }

    /**
     * 経路が出せなかったときの再挑戦。届かない目的地（海の向こう・未読み込み）では毎回上限まで
     * 探索して失敗するので、間隔を空けないと同じ計算を数秒おきに繰り返すだけになる。
     */
    /**
     * この地点からの通常探索は予算切れが確定しているか。確定しているなら、通常探索を省いて
     * 最初から粗い経由地チェーンで解く。
     *
     * <p>実機（エンドの島渡り）では通常探索が<b>1回も成功せず</b>、毎周期「30万ノードを焼いて
     * 失敗する通常探索 → 粗い経由地チェーン」を繰り返していた。捨てると分かっている探索に
     * 1〜1.5秒を払う間、案内は古いままで、その間にプレイヤーは経路から離れていく。
     *
     * <p>{@link #PLAIN_RETRY_MOVE_BLOCKS}ぶん歩けば失効する——同じ場所からの引き直しは同じ結果に
     * なるが、地形が変われば通常探索で解けるようになる（{@link #retryTruncatedNow}と同じ考え方）。
     */
    private boolean plainSearchHopeless(BlockPos start) {
        BlockPos exhausted = plainBudgetExhaustedAt;
        return exhausted != null
                && exhausted.distSqr(start) < PLAIN_RETRY_MOVE_BLOCKS * PLAIN_RETRY_MOVE_BLOCKS;
    }

    /**
     * 打ち切られた経路を引き直してよい頃合いか。
     *
     * <p><b>同じ場所から引き直しても同じ結果になる</b>。読み込み済みチャンクも地形も変わっていない
     * のに2秒おきに全置換すると、経路が頻繁に変わるように見えるだけで何も得られない——引き直しが
     * 意味を持つのは新しいチャンクが読まれたとき、つまりプレイヤーが動いたとき。
     *
     * <p>動かないまま長く経つ場合だけは、世界の側が変わっている可能性があるので緩い間隔で試す
     * （{@link #retryWithoutRoute}が経路ゼロのときにしているのと同じ考え方）。
     */
    private boolean retryTruncatedNow(Player player) {
        BlockPos start = lastStart;
        boolean moved = start == null
                || start.distSqr(player.blockPosition()) >= RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS;
        return moved || ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS;
    }

    /**
     * この探索の結果を詰みの判定へ反映する（{@link StuckTracker}参照）。ここでは
     * {@link StuckTracker}が必要とする2つの条件——完走した地上経路が今も表示中か、
     * 層1が目的地まで届いていないか——を、この状態機械が持つ{@code displayed}/{@code coarseRoute}
     * から求めるだけにする。
     */
    private void noteSearchOutcome(BlockPos start, BlockPos planEnd, PathResult result) {
        BlockPos currentGoal = goal;
        if (currentGoal == null) {
            return;
        }
        DisplayedPath shown = displayed;
        boolean hasCompleteGroundRoute = shown != null && shown.mode() != PathMode.TO_SURFACE
                && shown.result().complete() && !shown.result().steps().isEmpty();
        CoarseRoute route = coarseRoute;
        boolean routeUnmapped = route != null && route.goal().equals(currentGoal) && !route.reachedGoal();
        stuckTracker.noteOutcome(start, planEnd, currentGoal, hasCompleteGroundRoute, result, routeUnmapped, () -> {
            voxelGuide.noteStalled();
            navGraphGuide.noteStalled();
        });
    }

    private void retryWithoutRoute(BlockPos start) {
        if (ticksSinceRecalc < XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
            return;
        }
        boolean moved = lastStart == null
                || lastStart.distSqr(start) >= RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS;
        if (moved || ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS) {
            recalculate("経路が無いので再試行");
        }
    }

    /** 再挑戦の予約と今回のゴールが「同じ場所」か。{@link #RETRY_TARGET_TOLERANCE_BLOCKS}参照。 */
    private static boolean sameRetryTarget(BlockPos target, BlockPos reserved) {
        return reserved != null && horizontalDistance(target, reserved) <= RETRY_TARGET_TOLERANCE_BLOCKS;
    }

    /**
     * 引き直しの結果が、表示中の経路より目的地から遠い所で終わる（または空になる）なら1行残す。
     *
     * <p>再計算の多くは理由をログに出さず、途中までの経路どうしの差し替えは{@link #pathWorthKeeping}の
     * 保護の外なので何の行も残らない。これが無いと「経路が消えた」を「どのトリガーで・どの探索が・
     * 何を何で置き換えたか」まで遡れない。前進する差し替えは普通の出来事なので出さない。
     */
    private void noteRouteRegression(String trigger, Escalation forced, BlockPos start, BlockPos currentGoal,
            PathResult replacement) {
        DisplayedPath before = displayed;
        if (before == null || before.result().steps().isEmpty()) {
            return;
        }
        PathResult old = before.result();
        double oldLeft = horizontalDistance(endOf(old, start), currentGoal);
        double newLeft = horizontalDistance(endOf(replacement, start), currentGoal);
        if (!replacement.steps().isEmpty() && newLeft <= oldLeft + ROUTE_REGRESSION_LOG_BLOCKS) {
            return;
        }
        LOGGER.debug("XaeroNav: 引き直しで経路が後退しました (理由={}, 再挑戦={}, 始点={}, "
                        + "前={}ステップ/{}/{}/末端から目的地まで{}, 新={}ステップ/{}/{}/末端から目的地まで{}, 展開={})",
                trigger, forced, start.toShortString(),
                old.steps().size(), old.complete() ? "完走" : "途中まで", before.mode(), Math.round(oldLeft),
                replacement.steps().size(), replacement.complete() ? "完走" : "途中まで", replacement.termination(),
                Math.round(newLeft), replacement.expandedNodes());
    }

    /** 経路が実際に届いた地点。1歩も進めなかったときは始点そのもの。 */
    static BlockPos endOf(PathResult result, BlockPos start) {
        List<PathStep> steps = result.steps();
        return steps.isEmpty() ? start : steps.get(steps.size() - 1).pos();
    }

    static double distanceTo(Vec3 position, BlockPos pos) {
        double dx = pos.getX() + 0.5 - position.x;
        double dy = pos.getY() - position.y;
        double dz = pos.getZ() + 0.5 - position.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 目的地の近くまで来ていて、そろそろ降りて歩くべきか。
     *
     * <p>空中経路は「どちらへ機首を向けるか」を示すもので、着地と最後の数十ブロックはそれでは
     * 案内できない。目的地の近くまで来たら歩行の経路へ引き継ぐ方が、降りる場所も歩く道も
     * そのまま出る。
     *
     * <p>条件に<b>真下に地面があること</b>を入れているのが要点。高い所を飛んでいる間に切り替えると、
     * {@code StanceFinder.resolveStart}が始点を解決できず（真下{@code VERTICAL_SEARCH}ブロックしか
     * 見ない）、辺が1本も出ないまま探索を投げ続けてHUDに「経路なし」が出続ける——飛行中に地上の
     * 探索を止めている元々の理由そのもの。降りられる高さに来て初めて切り替える。
     *
     * <p>いったん切り替えたら、少し離れたくらいでは戻さない（{@link #LANDING_APPROACH_EXIT_BLOCKS}）。
     * 境界上で飛行と歩行を往復すると、そのたびに経路が丸ごと作り直される。
     */
    private boolean landingApproach(Level level, Player player, BlockPos currentGoal) {
        double distance = Math.sqrt(horizontalDistanceSq(player, currentGoal));
        if (landingApproachActive) {
            // 真下の地面は<b>入るとき</b>にだけ要る。留まる条件にも入れると、谷や溶岩の海を
            // またぐたびに飛行へ戻り、そのたびに経路が丸ごと作り直される
            landingApproachActive = distance <= LANDING_APPROACH_EXIT_BLOCKS;
        } else {
            landingApproachActive = distance <= LANDING_APPROACH_ENTER_BLOCKS && groundBelow(level, player);
        }
        return landingApproachActive;
    }

    /** 真下に立てる場所があるか（{@code StanceFinder}と同じ判定・同じ深さ）。 */
    private static boolean groundBelow(Level level, Player player) {
        return groundClearance(level, player) <= LANDING_GROUND_SEARCH_BLOCKS;
    }

    /**
     * 足元から真下の地面までの高さ（ブロック）。{@link #LANDING_GROUND_SEARCH_BLOCKS}以内に
     * 地面が無ければその値より大きい数を返す。
     */
    private static int groundClearance(Level level, Player player) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int x = player.blockPosition().getX();
        int z = player.blockPosition().getZ();
        int y = player.blockPosition().getY();
        for (int dy = 0; dy <= LANDING_GROUND_SEARCH_BLOCKS; dy++) {
            cursor.set(x, y - dy, z);
            if (cursor.getY() < GameCompat.minBuildHeight(level)) {
                break;
            }
            // 水面も降りられる場所として数える。{@code StanceFinder#isStance}が水のセルを
            // 立ち位置として受ける以上、海や湖の上は「地面が無い」ではなく「そこへ降りられる」
            // ——固体しか見ていなかったので、水上を飛んでいる間は目的地が48ブロック以内に
            // 入っても歩行の案内へ引き継がれなかった
            long flags = CellData.flagsOf(level.getBlockState(cursor));
            if (CellData.standable(flags) || CellData.water(flags)) {
                return dy;
            }
        }
        return LANDING_GROUND_SEARCH_BLOCKS + 1;
    }

    /**
     * 空にいて、地上の経路が意味を持たない状態か。エリトラの滑空だけでなくクリエイティブ・
     * スペクテイターの飛行も含む。
     *
     * <p>どちらも「空はプレイヤー自身が見て操縦できる」ので障害物回避の経路は要らず、そもそも
     * 足元に床が無いので{@code StanceFinder.resolveStart}が始点を解決できない——止めずに置くと、
     * 開始ノードから辺が1本も出ないまま2秒おきに探索を投げ続け、HUDには「経路なし」が出続ける
     * （意図して止めているのではなく<b>失敗しているように見える</b>）。
     *
     * <p>{@code getAbilities().flying}はクライアント自身が持つ状態で、二段ジャンプの切り替えも
     * 着地時の自動解除も同じtickのうちに{@code LocalPlayer#aiStep}が書く。スペクテイターは
     * {@code GameType#updatePlayerAbilities}と{@code MultiPlayerGameMode#isAlwaysFlying}が
     * 常にtrueへ固定するので、これ一つで飛行モード全部を捉えられる。<b>こちらには高さを課さない</b>
     * ——本当に立てないので、猶予を置くと足元に床の無い始点で探索を投げ続けることになる。
     *
     * <p>エリトラ（{@code isFallFlying}）だけは判定を鈍らせる。切り替えの代償が大きい
     * （{@code generation}を進めて走っている探索ごと捨て、着地時には表示中の経路を消して引き直す）
     * ので、鈍らせるのは{@link ElytraTrigger}の責務にまとめてある。
     */
    private boolean airborne(Level level, Player player) {
        if (GameCompat.abilities(player).flying) {
            elytraTrigger.reset();
            return true;
        }
        if (!player.isFallFlying()) {
            return elytraTrigger.update(false, 0, 0);
        }
        int required = XaeroNavConfig.INSTANCE.elytraFlyingMinGroundClearanceBlocks();
        // 高さを問わない設定なら真下を走査するだけ無駄になる
        int clearance = required <= 0 ? 0 : groundClearance(level, player);
        return elytraTrigger.update(true, clearance, required);
    }

    /**
     * 詳細探索が一度に狙う最大の水平距離。これより遠い目的地には長距離ルートの中間目標を挟む。
     *
     * <p><b>地形によらない固定値</b>であることが要点。かつては直近の探索が実際に引けた距離を
     * 測って使っていたが（{@code detailReach}）、プレイヤー周辺の既踏地形で測った値を経路の
     * 末端から未踏地形へ伸ばす探索にも使うため、成功と失敗が交互に入って収束しなかった。
     * 届かなかったときは部分経路をそのまま案内に使い、末端から継ぎ足して伸ばす。
     *
     * <p>描画距離で頭打ちにするのは、読み込まれていないチャンクの中を目標にしても
     * 到達しようがないため（未ロードのセルは進入不可）。
     */
    static int detailHorizon(int renderRadius) {
        return Math.min(renderRadius, XaeroNavConfig.INSTANCE.detailHorizonBlocks());
    }

    private boolean nearPathEnd(Vec3 position, PathResult result) {
        PathStep last = result.steps().get(result.steps().size() - 1);
        double dx = last.pos().getX() + 0.5 - position.x;
        double dy = last.pos().getY() - position.y;
        double dz = last.pos().getZ() + 0.5 - position.z;
        return dx * dx + dy * dy + dz * dz <= EXTEND_DISTANCE_BLOCKS * EXTEND_DISTANCE_BLOCKS;
    }

    /**
     * この再計算で、探索の作り方をどう変えるか。
     *
     * <p>以前は「前回届かなかったゴールの座標」を覚えておき、新しく選び直したゴールと<b>完全一致</b>
     * したときだけエスカレーションしていた。しかしdetail-targetはプレイヤー位置からルート上へ
     * 補間し直すので、1ブロック歩けば別座標になる——予約を立てた次のtickにはもう一致せず、
     * 通常探索を延々と回すだけのループになっていた（実機ログで「展開ノード数の上限に当たりました」が
     * 0.5〜0.7秒間隔で20〜30回続く間、粗い経由地チェーンは1回しか走らなかった）。
     *
     * <p>エスカレーションは「このゴールが届かない」ではなく「<b>この状況では探索の作り方を変える</b>」
     * という判断なので、そもそもゴールの同一性に依存させる必要が無い。決めた側が引数で渡す。
     */
    private enum Escalation {
        NONE,
        /** 箱を描画距離いっぱいまで広げる。壁や湖を大きく迂回する経路が範囲外に落ちていた場合。 */
        WIDE,
        /**
         * 探索の作り方は変えず、予算だけを積む。展開ノード数の上限に当たっていた場合の<b>最初の</b>手。
         * {@link #DEEP_SEARCH_BUDGET_FACTOR}参照。
         */
        DEEP,
        /** 箱は広げず、粗い経由地チェーンで区間を分割する。深い予算でも届かなかった場合。 */
        COARSE_GUIDED
    }

    /** @param trigger 引き直しの理由。経路が後退・消滅したときのログ（{@link #noteRouteRegression}）にだけ使う */
    private void recalculate(String trigger) {
        recalculate(Escalation.NONE, trigger);
    }

    private void recalculate(Escalation forced, String trigger) {
        long lap = TickLaps.start();
        try {
            recalculateNow(forced, trigger);
        } finally {
            TickLaps.add("再計算", lap);
        }
    }

    private void recalculateNow(Escalation forced, String trigger) {
        ticksSinceRecalc = 0;
        ticksSinceValidation = 0;
        // 全部引き直すなら、手前の経路ごと繋ぎ目も消える
        seamRepair.dropPending();
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        BlockPos currentGoal = this.goal;
        if (level == null || player == null || currentGoal == null) {
            return;
        }

        BlockPos start = player.blockPosition();
        lastStart = start;
        boolean boatAvailable = ChunkView.boatAvailable(player);

        int surfaceY = surfaceReferenceY(level, start);
        boolean climbing = shouldClimbToSurface(level, start, currentGoal, surfaceY);
        int renderRadius = ClientCompat.renderDistance(mc.options) * 16;
        // 判定はメインスレッドでしかできない（ワールドの参照・経路への対応づけ）。結果が返る頃には
        // 別の判断材料になってしまうので、投げる時点の答えを写し取ってワーカーへ渡す
        DisplayedPath worthKeeping = pathWorthKeeping(level, player);
        WindowField keepGuide = navGraphGuide.latest(currentGoal);
        boolean mayAwait = !climbing && mayAwaitNavGraph(start, currentGoal, renderRadius);
        if (mayAwait && XaeroPresence.mapPresent()) {
            // 窓の外の推定（層1）とHUDの点線は、待っている間に用意しておく。組み上がってからでは初回のガイドに間に合わない
            prepareCoarseRoute(start, currentGoal, boatAvailable, true, true);
        }
        GoalGuide goalGuide = goalGuide(level, player, start, currentGoal, renderRadius, climbing);
        boolean navGraphGuided = goalGuide != null && goalGuide.navGraph();
        awaitingNavGraph = mayAwait && !navGraphGuided && navGraphGuide.latest(currentGoal) == null
                && !navGraphGuide.failedRecently()
                && MonotonicTime.millis() - navGraphWaitStartedMillis <= NAV_GRAPH_WAIT_MILLIS;
        if (awaitingNavGraph) {
            return;
        }

        // 地上優先ナビが最優先（逆にすると地中で長距離の中間目標へ掘り進んでしまう）。
        // それ以外は、目的地が描画距離の外にあるときだけ長距離ルートの中間目標を挟む
        PathMode mode;
        BlockPos target;
        int waypointIndex;
        int goalRadius;
        if (climbing) {
            mode = PathMode.TO_SURFACE;
            // 地上優先ナビ中は、遠い本来の目的地の箱に広げても意味が無い（ゴールが1点ではなく
            // 「空の下ならどこでも」なので）。垂直方向は地表まで確実に届くよう、同じ列で
            // その高さにある仮想ゴールとして範囲を組み立てる
            target = new BlockPos(start.getX(), surfaceY, start.getZ());
            waypointIndex = -1;
            // searchToSurfaceが自前の領域ゴール（y >= surfaceY）を使うので、この値は読まれない
            goalRadius = 0;
        } else {
            DetailTarget detail = selectDetailTarget(start, currentGoal, renderRadius,
                    detailHorizon(renderRadius), boatAvailable, true, -1,
                    level.dimensionType().hasCeiling(), navGraphGuided);
            target = detail.target();
            mode = target.equals(currentGoal) ? PathMode.GOAL : PathMode.WAYPOINT;
            waypointIndex = detail.waypointIndex();
            goalRadius = detail.goalRadius();
        }

        // 探索範囲は描画距離で切る。読み込み済みチャンクの外は読めないので、そこまで広げても
        // 未ロード扱いのセルを舐めるだけになる。同時に、描画距離を下げているマシンでは
        // 探索の負荷も自動的に下がる。
        // そのぶん自分の周囲は広めに取る。洞窟の出口が目的地の方角にあるとは限らず、通常の
        // マージンでは出口ごと範囲の外に落ちる。この区間は掘削を切って探すので通れるセルが
        // 空洞だけに絞られ、範囲を広げても展開数はほとんど増えない
        NavigationTuning tuning = XaeroNavConfig.INSTANCE.navigationTuning();
        int horizontalMargin = tuning.searchHorizontalMargin();
        boolean wideSearch = false;
        boolean coarseGuided = false;
        if (climbing) {
            horizontalMargin *= SURFACE_SEARCH_MARGIN_FACTOR;
        } else if ((forced == Escalation.WIDE || sameRetryTarget(target, wideSearchNeededTarget))
                && horizontalDistance(start, target) <= renderRadius) {
            // 前回、この探索ゴールへ通常マージンでは届かなかった。チャンクはrenderRadiusの
            // 正方形いっぱいまで読み込み済みなので、通常マージン(既定64)で切っていた箱をそこまで
            // 広げて再挑戦する。壁や湖を大きく迂回する経路が「探索範囲の外」という理由だけで
            // 出ない問題への対処
            horizontalMargin = renderRadius;
            wideSearch = true;
        } else if ((forced == Escalation.COARSE_GUIDED || sameRetryTarget(target, coarseGuideNeededTarget))
                && horizontalDistance(start, target) <= renderRadius) {
            // 粗い経由地チェーンで区間を分割する（層3の局所障害対策）。
            //
            // <b>箱も広げる。</b>以前は「範囲を広げても同じ上限に当たるだけ」として広げていなかったが、
            // それは単一の障害物（湖・壁）を大きく迂回する経路が範囲外に落ちていた別の事例の話——
            // そちらは道が1本しか無いので、箱を広げても最終的に同じ長さの迂回を同じ上限で
            // 探すだけになる。今回はそれとは違う形の失敗だった: 実機（ジ・エンドの崖ぎわ、
            // 2026-08-28）で、既定の水平マージン(64)の箱には迂回できる飛び石の島が入らず、
            // 層1の粗い地図（LiveCoarseSampler、`view`と同じ箱しか見えない）が奈落を直進する
            // 解けないルートしか出せなかった。箱がわずかに広い試行（既知セル182/168）だけ
            // その飛び石が見え、中間目標が2個→3個に増えて一発で成功した（51,000+17,000ノード、
            // 直進ルートは毎回300,000ノードで失敗）。
            //
            // 広げるコストはほぼ無い——`ChunkView.capture`は`getChunkNow`で<b>既に読み込み済みの
            // チャンクを拾うだけ</b>で新規ロードを強制しない。飛び石が範囲外に落ちていただけなら、
            // 広げれば追加のチャンク読み込みを待たずに即座に見える。
            //
            // <b>renderRadiusいっぱいまで広げてよい。</b>最初にそうしたところ実機で1区間の探索が
            // 0.7〜0.9秒から1.4〜1.6秒に伸びる副作用が出たが、原因は
            // `PathfindingExecutor#legCoarseMap`（区間ごとのコストガイドが、区間分割の計画に
            // 使う広い箱をそのまま使い回していたこと——ガイドのDijkstraは箱の面積に比例した
            // 状態数を持ち、区間の数だけ払うので広げた箱の負担が倍加していた）で解消済み。
            // ガイドは区間専用の狭い箱で組み直すので、ここを広げても区間ごとの負担は増えない
            horizontalMargin = renderRadius;
            coarseGuided = true;
        }
        SearchBounds bounds = navGraphGuided
                ? navGraphBounds(level, start, target, start, renderRadius, horizontalMargin)
                : SearchBounds.around(level, start, target, horizontalMargin, verticalSearchMargin(level, wideSearch),
                        renderRadius);
        long captureLap = TickLaps.start();
        ChunkView view = ChunkView.capture(level, player, bounds, tuning.movementOptions());
        TickLaps.add("チャンク集め", captureLap);
        if (!climbing) {
            noteTargetStandability(view, target, mode, waypointIndex);
        }

        SearchLimits limits = navGraphGuided ? navGraphLimits(tuning.searchLimits()) : tuning.searchLimits();
        // ここは通常の予算では解けないと分かっている。<b>区間分割へ逃がすのではなく予算を積む。</b>
        // 実測（RealEndTerrainTest、実機の保存データ）では、区間分割は同じ地形で倍のノードを
        // 使ったうえに遅く、素直に予算を与えた単発探索の方が確実だった
        // （直接600,000で到達532,724ノード / 区間分割は800,000必要で628,593ノード）
        SearchLimits deepLimits = new SearchLimits(limits.maxExpandedNodes() * DEEP_SEARCH_BUDGET_FACTOR,
                Math.min(DEEP_SEARCH_MAX_MILLIS, limits.timeLimitMillis() * DEEP_SEARCH_BUDGET_FACTOR),
                limits.heuristicWeight());
        boolean deepBudgetOnly = !climbing && (forced == Escalation.DEEP || plainSearchHopeless(start));
        // 通常予算で足りるかどうかまだ分からない初回はここ。並列に深い予算も試しておく
        // （PathfindingExecutor#submitWithDeepFallback参照）——直列であれば「通常予算が
        // 予算切れと確定するまで待ってから次tickで深い予算を投げ直す」ため2回分の時間が
        // 足し算になる（実測で3.7秒相当）。同時に始めておけば通常予算が確定する頃には
        // 深い方もほぼ終わっている（実測でおよそ半分の1.9秒）
        boolean deepBudgetInParallel = !climbing && !coarseGuided && !deepBudgetOnly;
        if (deepBudgetOnly) {
            limits = deepLimits;
        }
        long myGeneration = generation.incrementAndGet();
        computing = true;
        PathMode finalMode = mode;
        BlockPos finalTarget = target;
        int finalWaypointIndex = waypointIndex;
        boolean finalWideSearch = wideSearch;
        boolean finalCoarseGuided = coarseGuided;
        // 並列フォールバックを使う回も、結果が届かなければ深い予算まで試し終えたのと同じ意味になる
        // （届かなかったのは深い方も含めて、であって通常予算だけの話ではない）。次のエスカレーション先
        // （粗い経由地チェーンか、深い予算からのやり直しか）の判定はこれで揃う
        boolean finalDeepBudget = deepBudgetOnly || deepBudgetInParallel;
        // 次元はメインスレッドで確定させる。whenCompleteはワーカースレッドで走るうえ、
        // そこではプレイヤーが既に別次元へ移動している可能性がある
        ResourceKey<Level> searchDimension = level.dimension();
        CompletableFuture<PathResult> future;
        boolean costToGoGuideEnabled = tuning.costToGoGuideEnabled();
        // 3D粗層・航法グラフは最終目的地に対して組む。中間目標を狙う探索には掛けない——
        // 起点が目的地に固定された表なので、別の点を狙う探索では方向がずれる
        CostToGo prepared = goalGuide != null && finalTarget.equals(currentGoal) ? goalGuide.costToGo() : null;
        // 直前の再確認が不成立と判定したセルは、この探索でも選ばせない。避けないと、引き直した
        // 経路がまた同じセルを通って即座に無効と判断される（{@link RecentFailures}参照）
        List<BlockPos> avoided = recentFailures.avoided();
        if (climbing) {
            future = executor.submitToSurface(AvoidedCellSource.wrap(view.withoutDigging(), avoided),
                    AvoidedCellSource.wrap(view, avoided), start, surfaceY, limits);
        } else if (coarseGuided) {
            future = executor.submitCoarseGuided(AvoidedCellSource.wrap(view, avoided), bounds, start,
                    finalTarget, limits, costToGoGuideEnabled, goalRadius);
        } else if (deepBudgetInParallel) {
            // 深い予算は別スレッドで同時に走るので、セルのキャッシュを共有させない
            future = executor.submitWithDeepFallback(AvoidedCellSource.wrap(view, avoided),
                    AvoidedCellSource.wrap(view.forParallelSearch(), avoided), start, finalTarget,
                    qualityLimits(limits), deepLimits, costToGoGuideEnabled, goalRadius, prepared);
        } else {
            future = executor.submit(AvoidedCellSource.wrap(view, avoided), start, finalTarget, limits,
                    costToGoGuideEnabled, goalRadius, Carryover.NONE, prepared);
        }
        generationGate.whenStillCurrent(future, myGeneration, TickLaps.timed("受け取り/再計算", (result, error) -> {
            try {
                computing = false;
                if (error != null) {
                    // キャンセルは正常な終わり方（新しいリクエストに置き換わった）
                    if (!(error instanceof CancellationException)) {
                        LOGGER.error("XaeroNav: 経路探索に失敗しました", error);
                    }
                    return;
                }
                // 探索が1つ終わった時点で、直前の探索が残した再挑戦の予約は用済み。以降で必要なら立て直す
                pendingWideRetry = false;
                pendingCoarseGuideRetry = false;
                pendingDeepRetry = false;
                if (!result.complete() && LOGGER.isDebugEnabled()) {
                    // 経路が目的地まで届かなかった理由は、探索の打ち切りか本当に道が無いかのどちらか。
                    // 展開ノード数を出しておかないと、maxExpandedNodesを上げ下げした効果を確かめる
                    // 手段がなく、「なぜ線が途中で切れるのか」に答えられない
                    LOGGER.debug("XaeroNav: 経路が未到達のまま終了しました (粗い経由地チェーン={}, 展開ノード数={}, 上限={}, ステップ数={})",
                            finalCoarseGuided, result.expandedNodes(), XaeroNavConfig.INSTANCE.maxExpandedNodes(),
                            result.steps().size());
                }
                if (finalCoarseGuided) {
                    // 粗い経由地チェーンが実際に発動したことと、その結果を確認する手段が無いと
                    // 「発動したのに足りなかった」のか「そもそも発動していない」のか切り分けられない。
                    // 発生頻度は展開ノード数の上限に当たった場合だけなので、debugゲート無しでも実害は無い
                    //
                    // 設置可否と橋の本数を併記するのは、溶岩で止まったときに「ブロックを持っていない」
                    // 「橋は架かったが足りない」「橋が1本も生成されない」を切り分けるため。
                    // ホットバーにブロックが無ければ溶岩の橋は原理的に1本も出ない
                    //
                    // 打ち切り理由を併記するのは、「資源が足りない」と「範囲内に道が無い」が
                    // 到達=falseでは区別できないため。前者は目的地を手前に取れば解決するが、
                    // 後者は何度やっても同じで、打つ手がまったく違う
                    LOGGER.debug("XaeroNav: 粗い経由地チェーンで再挑戦しました"
                                    + " (目標={}, 到達={}, {}, 展開ノード数={}, ステップ数={}, 設置可={}, 橋={}本)",
                            finalTarget.toShortString(), result.complete(), result.termination(),
                            result.expandedNodes(), result.steps().size(),
                            view.canPlaceBlocks(), result.steps().stream().filter(PathStep::bridging).count());
                }
                if (finalMode == PathMode.TO_SURFACE && (!result.complete() || result.steps().isEmpty())) {
                    // 地上まで届かなかった中継経路は表示しない。辿っても地上には出られないので、
                    // 途中まで案内したところで、その先でまた同じ未到達な経路が引かれるだけになる。
                    // 1歩も進まない中継（＝探索から見ればもう地上）も同じ扱いにする。どちらも
                    // この付近では中継を諦め、本来の目的地へ直接向かう（次tickで引き直される）
                    surfaceLegFailedAt = start;
                    PathResult withheld = new PathResult(List.of(), result.termination(),
                            result.expandedNodes(), result.distinctNodes());
                    noteRouteRegression(trigger, forced, start, currentGoal, withheld);
                    displayed = new DisplayedPath(withheld, PathMode.TO_SURFACE, -1);
                    return;
                }
                // 中継区間（TO_SURFACE）だけは対象外。ゴールが1点ではなく「空の下ならどこでも」なので
                // 未到達＝範囲が狭いではないし、水平マージンには専用の倍率が掛かっている
                if (finalMode != PathMode.TO_SURFACE) {
                    // 未到達の理由が展開ノード数の上限だった場合、箱を広げても同じ上限に同じように
                    // 当たるだけで結果は変わらない（実機で確認済み: 通常マージンと拡大後で展開ノード数が
                    // 一致し、どちらも上限ちょうどで打ち切られていた）。この場合は広げても意味が無いので
                    // 「広い範囲が要る」扱いにしない — 毎回の再計算のたびに無駄な拡大探索を繰り返さないため
                    //
                    // finalCoarseGuidedがtrue（今回のtickが既に粗い経由地チェーンだった）なら、それ以上の
                    // エスカレーションはしない。複数区間の合算expandedNodesは単一探索の上限と単純比較
                    // できないうえ、ここで再びtrueにすると次tickでまた同じ粗い経由地チェーンを試み、
                    // また同じ理由で失敗し…と無限往復する。エスカレーションは1段階までに留める
                    //
                    // 打ち切り理由はPathResultが持っている。展開数だけを見ていた頃は、2秒の時間上限が
                    // 先に効いた探索が「予算切れではない」＝範囲が狭いと誤判定され、粗い経由地チェーンの
                    // 代わりに無意味な箱の拡大が選ばれていた（しかもdetailReachも更新されなかった）
                    boolean budgetExhausted = !finalCoarseGuided && result.budgetExhausted();
                    if (!finalCoarseGuided) {
                        // 通常探索がここで予算切れしたかどうかは、次の再計算で「通常探索を省いてよいか」を
                        // 決める材料になる（plainSearchHopeless）。粗い経由地チェーンの結果では書き換えない
                        // ——あちらの成否は通常探索の見込みについて何も言っていない
                        plainBudgetExhaustedAt = budgetExhausted ? start : null;
                    }
                    // 距離上限は再挑戦の予約フラグより先に書くこと。クライアントスレッドは
                    // pendingCoarseGuideRetryを見た次の瞬間にrecalculateへ入り、そこでdetailReachを読んで
                    // 目標を選び直す。順序が逆だと、絞ったはずの上限が間に合わず、届かないと分かった
                    // 目標のまま粗い経由地チェーン（単一探索の4倍の予算）が走る。
                    //
                    // 粗い経由地チェーンも計算資源を使い切った側。区間ごとの合算なのでhitNodeBudgetでは
                    // 拾えないが、発動条件が「直前がノード上限に当たった」なので予算切れなのは確定している
                    logSearchReach(start, finalTarget, result);
                    // 再挑戦の予約は、実際に発動できるときだけ立てる。どちらの再挑戦もrenderRadius以内の
                    // ゴールを前提にしている（箱を広げる側は広げ先がrenderRadius、粗い経由地チェーン側は
                    // 読み込み済みチャンクからしか粗い地図を作れない）。予約だけ立てて発動条件が
                    // 通らないと、次tickで同じ探索をやり直しては同じ予約を立て直す無限ループになる
                    boolean retryTargetInBox = horizontalDistance(start, finalTarget) <= renderRadius;
                    boolean needsWideRetry = !result.complete() && !budgetExhausted && !finalCoarseGuided
                            && retryTargetInBox;
                    // 区間分割へ逃がすのは<b>深い予算でも足りなかった</b>ときだけ。順序が要点で、
                    // 予算不足に対する最初の答えは「予算を積む」——実測（RealEndTerrainTest）では
                    // 同じ地形で区間分割の方が倍のノードを使ったうえに遅く、単発探索に予算を与える方が
                    // 確実だった。深い予算を挟まずここへ来ると、区間分割も同じ予算不足で失敗する
                    boolean needsDeepRetry = !result.complete() && budgetExhausted && !finalCoarseGuided
                            && !finalDeepBudget && retryTargetInBox;
                    // 航法グラフのガイドで探しているときは区間分割へ逃がさない。区間分割はそのガイドを受け取らず
                    // （submitCoarseGuided）区間ごとの粗い地図で狙うので、ガイド付きの深い予算より悪い案内しか作れない——
                    // 実機のネザーで9回中8回が未到達、1回10〜13秒で、結果は置き換える前の経路より手前で切れた
                    boolean needsCoarseGuideRetry = !result.complete() && budgetExhausted && !finalCoarseGuided
                            && finalDeepBudget && retryTargetInBox && !navGraphGuided;
                    // 成功した・広げても無駄だったときは通常マージンに戻す。pendingWideRetryはこの書き込みの
                    // 後に立てること（クライアントスレッドはpendingWideRetryを見てからwideSearchNeededTargetを読む）
                    wideSearchNeededTarget = needsWideRetry ? finalTarget : null;
                    pendingWideRetry = needsWideRetry && !finalWideSearch;
                    coarseGuideNeededTarget = needsCoarseGuideRetry ? finalTarget : null;
                    pendingCoarseGuideRetry = needsCoarseGuideRetry;
                    pendingDeepRetry = needsDeepRetry;
                    if (needsCoarseGuideRetry && LOGGER.isDebugEnabled()) {
                        // 予約を立てただけの行はdebugに留める。実際に何が起きたかは1秒後の
                        // 「粗い経由地チェーンで再挑戦しました」が目標ごと記録している——予算切れが
                        // 常態化する地形（ネザー）では、これをINFOに出すと同じ内容が3秒おきに3行ずつ並ぶ
                        LOGGER.debug("XaeroNav: 展開ノード数の上限に当たりました。次tickで粗い経由地チェーンを試します"
                                + " (目標={})", finalTarget.toShortString());
                    }
                    // 再挑戦の予約が残っている間はまだ手を尽くしていない。詰みの判定は、この探索に
                    // 対してできることを全部やり終えてからにする（さもないと、エスカレーションで
                    // 解決するはずの状況を先に詰みと決めつけてその再挑戦ごと止めてしまう）
                    if (!pendingWideRetry && !pendingCoarseGuideRetry && !pendingDeepRetry) {
                        long outcomeLap = TickLaps.start();
                        noteSearchOutcome(start, endOf(result, start), result);
                        TickLaps.add("詰みの判定", outcomeLap);
                    }
                }
                if (!result.complete() && worthKeeping != null && displayed == worthKeeping) {
                    if (worthKeeping.result().complete()) {
                        // 完走した経路は「ここからそこまで実際に歩ける」という証明で、未到達の結果は
                        // その証明を持たない。証明を持たないもので上書きしない（pathWorthKeeping参照）
                        LOGGER.debug("XaeroNav: 完走した経路を残しました (表示中={}ステップ, 新しい結果={}ステップ, {})",
                                worthKeeping.result().steps().size(), result.steps().size(), result.termination());
                        return;
                    }
                    long compareLap = TickLaps.start();
                    PartialProgress kept = PartialProgress.compare(worthKeeping.result(), result, start, currentGoal,
                            keepGuide);
                    TickLaps.add("途中までの比較", compareLap);
                    if (kept.oldAhead()) {
                        // 途中までどうしなら、目的地の近くまで引けている方が案内として上。予算切れの探索は
                        // 引き直すたびに違う所で打ち切られるので、比べずに差し替えると、目的地まで16ブロックの
                        // 所まで引けていた経路が145ブロック手前で切れる経路へ縮む（実機のネザー）
                        LOGGER.debug("XaeroNav: 途中までの経路を残しました (表示中={}ステップ, 新しい結果={}ステップ, {}, "
                                        + "末端の残り 表示中={} 新={}, 物差し={})",
                                worthKeeping.result().steps().size(), result.steps().size(), result.termination(),
                                Math.round(kept.oldLeft()), Math.round(kept.newLeft()), kept.yardstick());
                        return;
                    }
                }
                if (leadsBackward(result, currentGoal)) {
                    // 経路そのものではなくプレイヤーの進み方を見る。案内どおりに歩くほど
                    // 目的地から離れるなら、それは案内として成立していない
                    return;
                }
                // 新しい経路に対する合流可否は測り直しになる。前の経路で失敗した記録は持ち越さない
                splice.clearBlock();
                long shapeLap = TickLaps.start();
                noteSuspiciousShape(start, finalTarget, result);
                TickLaps.add("形の点検", shapeLap);
                long regressionLap = TickLaps.start();
                noteRouteRegression(trigger, forced, start, currentGoal, result);
                TickLaps.add("後退の点検", regressionLap);
                displayed = new DisplayedPath(result, finalMode, finalWaypointIndex);
            } finally {
                publishNavigationView();
            }
        }));
    }

    /**
     * いま出ている経路を、この再計算の結果が<b>未到達だったときに</b>残してよいか。残すなら経路、
     * 残せないなら{@code null}。
     *
     * <p>完走した経路は「ここからそこまで実際に歩ける」という証明で、未到達の経路はその証明を
     * 持たない。証明を持たない結果で証明を捨てると取り返しがつかない——詳細探索のゴールは
     * 再計算のたびに少し揺れる（{@link #resolveWaypointOnSurface}・プレイヤー位置からの補間）ので、
     * 同じ経路をもう一度引き当てられる保証はどこにもない。実機（エンドの島渡り）では完走した
     * 128ステップ・橋21本の経路が3秒後に未到達の93ステップへ置き換わり、以後78→39→15と
     * 単調に劣化した。しかも{@code trimUnfinishedPlacements}が未到達の経路から末尾の設置を
     * 落とすので、置き換わった先は橋が1本も無い切り株になる。
     *
     * <p>途中までの経路も候補にする。こちらは証明を持たないので無条件には残さず、新しい結果より
     * 目的地の近くまで引けているときだけ残す（{@link PartialProgress}）。
     *
     * <p>残せるのは証明がいまも通用するときだけ。足元がまだ経路の帯の中にあること（逸脱したなら
     * その経路はもう自分の経路ではない）、まだ終端に着いていないこと（着いているなら必要なのは
     * 次の区間で、残しても案内は止まったまま）、世界の側も変わっていないこと。中継区間
     * （{@code TO_SURFACE}）はゴールの意味が違うので対象外。
     */
    private DisplayedPath pathWorthKeeping(Level level, Player player) {
        DisplayedPath shown = displayed;
        if (shown == null || shown.mode() == PathMode.TO_SURFACE) {
            return null;
        }
        PathResult result = shown.result();
        if (result.steps().isEmpty()) {
            return null;
        }
        // 完走した経路を手放す判断は、この経路が二度と引き当てられない可能性を伴う。手放した
        // 理由が残っていないと、案内が悪くなったときに「どのトリガーが壊したのか」を実機ログから
        // 特定できない（再計算そのものの理由はどこにも出ていない）。頻度は完走した経路を
        // 持っている間の再計算だけなので、debugゲート無しでも並ばない
        boolean tracked = PathProgress.INSTANCE.tracking(result);
        double offPath = tracked ? offPathDistance(level, player, result)
                : distanceToPath(result, player.position());
        String dropped = null;
        // 「地形が変わった」は内訳まで出す。どのステップの何が不成立になったのかが分からないと、
        // 渡り切った直後に完走ルートが手放されるような症状の原因を追えない
        PathValidator.Failure validationFailure = null;
        if (offPath > XaeroNavConfig.INSTANCE.deviationThresholdBlocks()) {
            dropped = "逸脱";
        } else if (reachedPathEnd(player, shown)) {
            dropped = "終端に到着";
        } else if ((validationFailure = PathValidator.firstFailureFrom(level, result,
                PathProgress.INSTANCE.indexFor(result), player.blockPosition(),
                ClientCompat.renderDistance(Minecraft.getInstance().options) * 16)) != null) {
            // もう歩き終えた区間の変化では手放さない。渡ってきた橋を後ろから壊しても、
            // これから通る道が使えることの証明は失われない
            dropped = "地形が変わった";
        }
        if (dropped != null && !result.complete()) {
            // 途中までの経路を手放すのは普通の出来事（逸脱のたびに起きる）なので黙って手放す
            return null;
        }
        if (dropped != null) {
            if (validationFailure != null) {
                noteUnusableCell(validationFailure);
            }
            LOGGER.debug("XaeroNav: 完走した経路を手放しました"
                            + " (理由={}, {}ステップ, 経路までの距離={}, 対応づけ={}, 現在地={}, 経路の先頭={}{})",
                    dropped, result.steps().size(), Math.round(offPath), tracked,
                    player.blockPosition().toShortString(), result.steps().get(0).pos().toShortString(),
                    validationFailure == null ? "" : ", " + validationFailure.reason());
            return null;
        }
        return shown;
    }

    /**
     * 探索が使えると判断したのに再確認で使えなかったセルを覚える。次の探索はここを避ける
     * （{@link RecentFailures}参照）。
     */
    private void noteUnusableCell(PathValidator.Failure failure) {
        recentFailures.note(failure.unusableCell());
    }

    /**
     * 逸脱の判定に使う、経路までの距離（ブロック）。
     *
     * <p><b>同じ水の中で縦に繋がっている間は、上下のずれを数えない。</b>水中では上下に自由に
     * 動けるので、経路のYは「そこを通れ」という指示ではない——泳ぎは息継ぎのたびに浮上と潜降を
     * 繰り返すので、縦のずれを逸脱に数えると、水面の経路を追っているだけで既定の閾値(4)を超え、
     * 数秒おきに合流と引き直しが走る。<b>真上・真下にいるなら経路を辿れている</b>という見方は、
     * 全体走査へ落ちるかの判定（{@link PathProgress#horizontalDistance()}）と同じ。
     *
     * <p>繋がりを見るのは、水中洞窟へ入り込んで本当に外れた場合まで見逃さないため。
     */
    private static double offPathDistance(Level level, Player player, PathResult result) {
        double distance = PathProgress.INSTANCE.distance();
        double horizontal = PathProgress.INSTANCE.horizontalDistance();
        // 縦のずれが無い＝どちらで測っても同じ。水を舐めるまでもない
        if (distance - horizontal < 1.0e-6) {
            return distance;
        }
        BlockPos step = result.steps().get(PathProgress.INSTANCE.indexFor(result)).pos();
        return swimmableBetween(level, player.blockPosition(), step) ? horizontal : distance;
    }

    /**
     * プレイヤーとこのステップが、同じ水の中で縦に繋がっているか。繋がっているなら、上下のずれは
     * その場で泳いで詰められる。
     *
     * <p>走査は<b>プレイヤーの柱</b>で行う。ステップ側の柱を見ると、間に挟まった陸地越しに
     * 繋がって見えることがある（岸のすぐ横を泳いでいて、経路が崖の上を通っている場面）。
     *
     * <p>足元のセルが水でなければ1つ下から始める。水面を泳ぐプレイヤーは水面をまたいで浮き沈み
     * するので、ブロック座標は水面のセルとその1つ上を行き来する——上に居る瞬間だけ「水の中に
     * いない」と判定すると、まさに息継ぎの瞬間に逸脱が立つ。
     */
    private static boolean swimmableBetween(Level level, BlockPos player, BlockPos step) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int playerY = player.getY();
        if (!water(level, cursor.set(player.getX(), playerY, player.getZ()))) {
            // 緩めるのはプレイヤー側の1マスだけ。ステップ側まで緩めると、水の上の崖を通る経路が
            // 「水柱で繋がっている」ことになる
            playerY--;
        }
        for (int y = Math.min(playerY, step.getY()); y <= Math.max(playerY, step.getY()); y++) {
            if (!water(level, cursor.set(player.getX(), y, player.getZ()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean water(Level level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
                && CellData.water(CellData.flagsOf(level.getBlockState(pos)));
    }

    /**
     * この経路までの最短距離（ブロック）。{@link PathProgress}が<b>この経路に対して</b>測った値を
     * 持っていないときの代わりに使う。
     *
     * <p>{@link PathProgress}が窓で探すのに対してこちらは全ステップを見る。窓は「経路のどこを
     * 歩いているか」を追うためのもので、対応づけを持っていない経路には最初から適用できない。
     */
    private static double distanceToPath(PathResult result, Vec3 position) {
        List<PathStep> steps = result.steps();
        return distanceTo(position, steps.get(nearestStepIndex(steps, position)).pos());
    }

    /** この位置に最も近いステップの添字。 */
    private static int nearestStepIndex(List<PathStep> steps, Vec3 position) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < steps.size(); i++) {
            double distance = distanceTo(position, steps.get(i).pos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * この位置に最も近い、<b>いま実際に立てる</b>ステップの添字（無ければ{@code -1}）。
     * {@code minIndex}より手前は見ない。
     *
     * <p>橋のステップは「これから置くブロックの上」なので、その足元はまだ空気（奈落・溶岩）。
     * そこを合流先にすると{@code StanceFinder}が立てる場所へ寄せ直し、合流区間は経路と別の
     * セルで終わって繋がらない。合流できるのは実在する床の上だけ。
     *
     * <p>世界の変化でいま塞がっているステップも同じ理由で外す。塞がった箇所を迂回するときは
     * 連続してブロックが置かれていることがあり、その塊を抜けた最初のステップへ合流したい。
     */

    /**
     * 並列フォールバックの通常予算側に渡す上限。予算と時間はそのままに、重みだけ
     * {@link #QUALITY_HEURISTIC_WEIGHT}まで落とす。設定でそれより低くしている人の値は下げない。
     */
    static SearchLimits qualityLimits(SearchLimits limits) {
        return new SearchLimits(limits.maxExpandedNodes(), limits.timeLimitMillis(),
                Math.min(limits.heuristicWeight(), QUALITY_HEURISTIC_WEIGHT));
    }

    /**
     * 詳細探索のゴールを決める。目的地が詳細探索の届く範囲の外にあり、Xaeroの地図データが
     * あるときだけ長距離ルートの中間目標を挟む。地図が無い・範囲外・引き直してもなお中間目標が
     * 一つも届く範囲に無い、のいずれでも本来の目的地へ直接向かう従来動作にフォールバックする
     * （発動条件が一つでも欠けたら長距離ルートには入らない）。
     *
     * <p>境界は描画距離ではなく{@code reach}（{@link #updateDetailReach}の実測値）。描画距離を
     * 使うと「圏内なら詳細探索がフル解像度データで解けるはず」を前提にすることになるが、それが
     * 成り立つのは地表を歩くときだけ。実測では描画距離32（512ブロック）のネザーで、目的地まで
     * 502ブロックになった時点で長距離ルートが外れ、以降ずっと500ブロック先を一発で解こうとして
     * 失敗し続けた（粗い経由地チェーンが毎回0ステップ＝「経路が見つかりません」）。
     */
    private DetailTarget selectDetailTarget(BlockPos start, BlockPos currentGoal, int renderRadius,
                                             int reach, boolean boatAvailable, boolean playerAnchored,
                                             int minWaypointIndex, boolean ceilingDimension, boolean navGraphGuided) {
        if (horizontalDistance(start, currentGoal) <= reach) {
            aimingPastWaypoints = false;
            return new DetailTarget(currentGoal, -1, 0);
        }
        if (navGraphGuided) {
            // 航法グラフのガイドがあれば中間目標へ立ち寄らない。窓の中のガイドは探索と同じ移動の本物の残りコストで、
            // 中間目標へ寄ること自体が遠回りになる（歩き通しの実測: 広域長距離1.067→1.017倍、エンド1.122→1.014倍）。
            //
            // 層1は引き続き引く（HUDと地図の点線、窓の外の推定）。層2の精緻化は目標に使わないので掛けない
            if (XaeroPresence.mapPresent()) {
                prepareCoarseRoute(start, currentGoal, boatAvailable, playerAnchored, true);
            }
            aimingPastWaypoints = true;
            return new DetailTarget(currentGoal, -1, 0);
        }
        if (!XaeroPresence.mapPresent()) {
            aimingPastWaypoints = false;
            return goalOrPointToward(start, currentGoal, reach);
        }
        if (ceilingDimension) {
            // <b>天井のある次元では中間目標へ立ち寄らない。</b>目的地をそのまま狙い、箱で
            // 切られた部分経路を継ぎ足していく。方向は3D粗層（{@link NetherVoxelGuide}）が出す。
            //
            // 立ち寄る側は実測で基準の2.175〜3.100倍。層1はチャンクごとに床を数枚持つだけで
            // ネザーの縦に積まれた通路を表せず、その中間目標へ寄り道すること自体が遠回りになる。
            // <b>地形を測って切り替える必要は無い</b>——天井のある次元では答えが常に同じで、
            // 測る側（探索の実コスト比）は現世の山岳でも同じ値に達するので分けられない。
            //
            // 層1は引き続き引く（HUDと地図の点線）。ここで結果を使わないだけ。
            prepareCoarseRoute(start, currentGoal, boatAvailable, playerAnchored, true);
            aimingPastWaypoints = true;
            return new DetailTarget(currentGoal, -1, 0);
        }
        aimingPastWaypoints = false;
        DetailTarget target = reachableWaypointTarget(start, currentGoal,
                cachedOrFreshRoute(start, currentGoal, boatAvailable, playerAnchored, false),
                renderRadius, reach, playerAnchored,
                minWaypointIndex);
        if (target != null) {
            return target;
        }
        if (!playerAnchored) {
            // 継ぎ足しはルートの持ち主ではない。ここでfreshRouteを呼ぶと、末端の位置を始点に
            // 長距離ルートごと引き直すことになり、層2の精緻化も投げ直されて手前の案内が入れ替わる
            return goalOrPointToward(start, currentGoal, reach);
        }
        CoarseRoute inFlight = refiningRoute;
        if (inFlight != null && inFlight.goal().equals(currentGoal)) {
            // 層2の精緻化がまさにこの目的地に対して進行中。ここで引き直すとその結果を捨てて
            // 同じ状況をやり直すだけになる——描画距離が小さい環境では層1の96ブロック間隔が
            // 1つも届かず必ずここへ来るので、素通しにすると「0.5秒ごとにメインスレッドで
            // 地図を読み直しては精緻化を捨てる」ループに入り、精緻版が永久に完成しない。
            // 完成すればpendingRefinedRouteReadyが引き直しをかけ、24ブロック間隔のwaypointが
            // 届くようになる。それまでは長距離ルートを挟まない従来動作へ落とす
            return goalOrPointToward(start, currentGoal, reach);
        }
        CoarseRoute cached = coarseRoute;
        if (cached != null && cached.goal().equals(currentGoal)
                && horizontalDistance(start, cached.computedFrom()) < COARSE_ROUTE_RETRY_MOVE_BLOCKS) {
            // 引き直したところで同じルートになる。層1の引き直しはメインスレッドでの地図読みと
            // 層1A*2回ぶんで、届く中間目標が1つも無い間はそれを毎回の再計算で払うことになる
            return goalOrPointToward(start, currentGoal, reach);
        }
        // キャッシュ済みのwaypointが1つも描画距離内に届かない＝大きく迂回して経路から外れた。
        // 目的地は変わっていないのでキャッシュは効くはずだが、地形は不変でも自分の位置は変わるので、
        // 今の位置を始点に引き直す（地形が変わらない限り引き直さない、という原則の唯一の例外）
        target = reachableWaypointTarget(start, currentGoal,
                freshRoute(start, currentGoal, boatAvailable, false),
                renderRadius, reach, true, minWaypointIndex);
        return target != null ? target : goalOrPointToward(start, currentGoal, reach);
    }

    /**
     * 最初の経路を、航法グラフが組み上がるまで待って引いてよいか。待ち始めの時刻もここで記録する。
     *
     * <p>待つのは、まだ経路が1本も出ていない、目的地が一度に狙える距離より遠いときだけ。近い目的地は従来の探索でも一発で
     * 最短に着くので、窓全体を組む数秒を待たせる理由が無い。出ている経路を消して待つと、歩いている途中で案内が途切れる。
     */
    private boolean mayAwaitNavGraph(BlockPos start, BlockPos currentGoal, int renderRadius) {
        DisplayedPath shown = displayed;
        if (!XaeroNavConfig.INSTANCE.costToGoGuideEnabled() || (shown != null && !shown.result().steps().isEmpty())
                || horizontalDistance(start, currentGoal) <= detailHorizon(renderRadius)) {
            return false;
        }
        if (navGraphWaitStartedMillis == 0L) {
            navGraphWaitStartedMillis = MonotonicTime.millis();
        }
        return true;
    }

    /**
     * 組み直した航法グラフのガイドで、引いてある経路の先を見直す。遠回りだと分かったら引き直して{@code true}。
     *
     * <p>経路は窓の外を推定で狙って引かれ、以後は末端から継ぎ足すだけで手前を見直さない。歩いて窓が進むと推定だった所が
     * 正確になり、もっと近い向きが見えることがある（実機のネザー: 西へ伸びた線が、北へ斜めに行く方が近いと分かっても残った）。
     * 見直すのはガイドが組み直されたときに1回だけで、目的地が窓の中に入ってから（{@link RouteReview#detour}）。
     */
    private boolean reviewAgainstNavGraph(Player player, BlockPos currentGoal, DisplayedPath shown) {
        // 中間目標へ向かう経路（航法グラフを待ちきれずに引いたもの）も見直す。ガイドは最終目的地までの値なので、
        // 中間目標へ寄ること自体が遠回りならそれも遠回りとして測れる
        if (shown.mode() == PathMode.TO_SURFACE) {
            return false;
        }
        WindowField field = navGraphGuide.latest(currentGoal);
        if (field == null || field == reviewedField || !field.reachesGoal()) {
            return false;
        }
        BlockPos at = player.blockPosition();
        if (reviewReplannedAt != null && horizontalDistance(at, reviewReplannedAt) < REVIEW_RETRY_MOVE_BLOCKS) {
            return false;
        }
        reviewedField = field;
        List<PathStep> steps = shown.result().steps();
        int index = PathProgress.INSTANCE.indexFor(shown.result());
        RouteReview.Detour detour = RouteReview.detour(field, steps.get(index).pos(), steps, index + 1);
        if (!detour.worthReplanning(REVIEW_MIN_EXTRA_TICKS, REVIEW_MIN_EXTRA_RATIO)) {
            return false;
        }
        LOGGER.debug("XaeroNav: 組み直したガイドで見ると遠回りなので引き直します (余計に{}tick, 見直した区間{}tick, 現在地={})",
                Math.round(detour.extraTicks()), Math.round(detour.walkedTicks()), at.toShortString());
        reviewReplannedAt = at;
        recalculate("ガイドの見直しで遠回り");
        return true;
    }

    /**
     * 目的地をそのまま狙う探索へ渡すガイド。航法グラフが組み上がっていればそれ（窓の外の推定は、ネザーは3D粗層・現世は層1・
     * エンドは直線距離）、まだなら天井のある次元は3D粗層、それ以外は{@code null}で中間目標へ寄る従来の探索になる。
     *
     * <p>中間目標を狙う探索に掛けてはいけない——表の起点は最終目的地に固定されているので、
     * 別の点を狙う探索では見積もりが「そちらへ寄り道してから目的地へ」の形になり、
     * 幾何ヒューリスティックとのmaxで単に大きすぎる値になる。
     *
     * <p><b>メインスレッドから呼ぶこと</b>（Xaeroの地図・チャンクを読む）。
     */
    private @Nullable GoalGuide goalGuide(Level level, Player player, BlockPos from, BlockPos currentGoal,
                                          int renderRadius, boolean climbing) {
        if (climbing) {
            return null;
        }
        boolean navGraphEnabled = XaeroNavConfig.INSTANCE.costToGoGuideEnabled();
        NavGraphGuide.Far far;
        CostToGo fallback = null;
        if (level.dimensionType().hasCeiling()) {
            if (!XaeroPresence.mapPresent()) {
                return null;
            }
            long voxelLap = TickLaps.start();
            CostToGo voxel = voxelGuide.forGoal(level, level.dimension(), player.blockPosition(), currentGoal,
                    XaeroNavConfig.INSTANCE.movementOptions().lavaBridgingEnabled());
            TickLaps.add("3D粗層の起動", voxelLap);
            if (voxel == null || !navGraphEnabled) {
                return voxel == null ? null : new GoalGuide(voxel, false);
            }
            // 窓の外が幾何下限だとネザーは3D粗層だけより悪い（実測1.257倍）。3D粗層が組み上がってから航法グラフを使う
            fallback = voxel;
            far = new NavGraphGuide.Far("3D粗層", voxel, () -> FarField.of(
                    (x, y, z) -> NavGraphGuide.VOXEL_FAR_SCALE * voxel.estimate(x, y, z)));
        } else if (!navGraphEnabled) {
            return null;
        } else {
            CoarseMapForGoal latestMap = latestCoarseMap;
            CoarseMap map = latestMap != null && latestMap.goal().equals(currentGoal) ? latestMap.map() : null;
            // 層1を窓の外の推定に使うのは現世だけ。エンドの層1は奈落と島を2.5Dの床で持つだけで、窓の境界に置くと
            // 幾何下限より悪い（実測: 1.197倍に対して1.009倍）
            far = map == null || level.dimension() == Level.END ? null
                    : new NavGraphGuide.Far("層1", map, () -> FarField.of(
                            CoarseRouter.costToGo(map, currentGoal, false, CoarseRouter.BridgePolicy.BRIDGE)));
        }
        long navGraphLap = TickLaps.start();
        WindowField field = navGraphGuide.forGoal(level, player, currentGoal, renderRadius,
                XaeroNavConfig.INSTANCE.movementOptions(), far);
        TickLaps.add("航法グラフの起動", navGraphLap);
        // 窓の中の目的地が殻に繋がっていない回は使わない。窓全体の値が縁の外の推定だけから来る
        if (field != null && field.reachesGoal()) {
            return new GoalGuide(field, true);
        }
        return fallback == null ? null : new GoalGuide(fallback, false);
    }

    /**
     * {@link #goalGuide}の結果。{@code navGraph}なら探索の作り方も変える（{@link #navGraphLimits}・{@link #navGraphBounds}）。
     */
    record GoalGuide(CostToGo costToGo, boolean navGraph) {
    }

    /**
     * 航法グラフのガイドで探すときの予算。重みだけ1.0にする——窓の中のガイドは本物の残りコストなので、
     * 重みを掛けると最適な経路から外れるだけで速くならない（実測の質はすべて重み1.0）。
     */
    static SearchLimits navGraphLimits(SearchLimits limits) {
        return new SearchLimits(limits.maxExpandedNodes(), limits.timeLimitMillis(), 1.0);
    }

    /**
     * 航法グラフのガイドで探すときの箱。全高を見て、プレイヤーを中心とする窓（{@link NavGraphGuide#window}）で切る。
     *
     * <p>窓の外ではガイドが層1か幾何の推定に落ちるので、そこまで広げると測っていない探索になる。
     * 高さを切らないのは、ガイドが掘り上がる・降りる道を指したときに箱の外で行き止まらせないため。
     */
    static SearchBounds navGraphBounds(Level level, BlockPos from, BlockPos target, BlockPos player,
                                       int renderRadius, int horizontalMargin) {
        int window = NavGraphGuide.window(renderRadius);
        SearchBounds box = SearchBounds.around(level, from, target, horizontalMargin, level.getHeight(), window);
        return new SearchBounds(Math.max(box.minX(), player.getX() - window), box.minY(),
                Math.max(box.minZ(), player.getZ() - window), Math.min(box.maxX(), player.getX() + window),
                box.maxY(), Math.min(box.maxZ(), player.getZ() + window));
    }

    /**
     * ルート上に狙える点が無いときの目標。
     *
     * <p><b>一度に狙える距離({@code reach})の外にある目的地をそのまま渡してはいけない。</b>探索の箱は
     * 描画距離で切られるので、その外のゴールには原理的に到達できない——A\*は毎回フル予算を焼いて
     * 「一番近づけた地点まで」の部分経路を返すだけになる。実機のprobeで、509ブロック先の目的地を
     * 上限なしで狙わせると465,536ノード・2秒を使って届かなかった（箱は140×305）。
     *
     * <p>そこで{@code reach}ぶんだけ目的地の方向へ進んだ点を狙う。中間目標に対して
     * {@link #pointAlongRoute}がやっているのと同じことを、ルートが無い場合にも適用する。
     * 到着判定は目的地そのものを見ている（{@code checkArrival}）ので、手前で切っても着けなくならない。
     *
     * <p><b>例外が1つだけある。</b>天井のある次元では、箱の外の目的地をわざとそのまま狙う
     * ——「フル予算を焼いて部分経路を返すだけ」がそこでは望ましい動きで、中間目標へ立ち寄るより
     * 安く着く（実測2.599倍→1.257倍、3D粗層を掛けるとさらに1.02〜1.13倍）。空振りと分かっている
     * 再挑戦の梯子は{@code PathfindingExecutor}側が畳む（{@code goalInsideBounds}）。
     */
    private DetailTarget goalOrPointToward(BlockPos start, BlockPos currentGoal, int reach) {
        BlockPos aim = aimTowardGoal(start, currentGoal, reach);
        if (aim.equals(currentGoal)) {
            return new DetailTarget(currentGoal, -1, 0);
        }
        // Xaeroの地図が無ければ地表の解決に入ってはいけない。{@code XaeroMapReader}はXaero未導入だと
        // クラスのロード自体が落ちる（この関数はmapPresent()がfalseの経路からも呼ばれる）。
        // 補間したYのまま渡してよい——探索側の{@code StanceFinder#resolveGoal}が同じ柱で
        // 実際に立てる高さへ寄せ直す
        BlockPos resolved = XaeroPresence.mapPresent() ? resolveWaypointOnSurface(aim) : aim;
        return new DetailTarget(resolved, -1, INTERPOLATED_GOAL_RADIUS_BLOCKS);
    }

    /** 目的地そのもの、または遠すぎるならその方向へ{@code reach}だけ進んだ点。 */
    static BlockPos aimTowardGoal(BlockPos start, BlockPos goal, int reach) {
        double distance = horizontalDistance(start, goal);
        return distance <= reach ? goal : pointAlong(start, goal, distance, reach);
    }

    /**
     * この点を目標にすると長さ0の経路しか出ないか。
     *
     * <p>ゴールは領域（半径{@link #WAYPOINT_GOAL_RADIUS_BLOCKS}）なので、始点がその中に入っていれば
     * 探索は始点ノードを取り出した瞬間に到達扱いで終わる。判定を「間引き間隔」ではなく
     * 「半径＋間引き間隔」で見るのはそのため。
     */
    static boolean tooCloseToAim(BlockPos start, BlockPos aim) {
        return horizontalDistance(start, aim)
                < WAYPOINT_GOAL_RADIUS_BLOCKS + REFINED_WAYPOINT_MIN_SPACING_BLOCKS;
    }

    /**
     * <b>層3へ渡す直前の目標に、実際に立てるかを記録する（診断）。</b>
     *
     * <p>「謎にわたらせる・遠回り」の容疑のうち、<b>どの上流経路が原因でも最後に必ずここへ現れる</b>
     * 一点。立てない目標を渡すと、層3は{@code goalRadius}の円柱へ近づこうとして
     * {@code addBridge}で奈落・溶岩・水のただ中へ橋を架ける（実機ジ・エンドの保存データでの
     * A/Bで橋8本 vs 0本）。
     *
     * <p>オフラインでは層1が出す中間目標の97%以上が層2の寄せ（{@code CorridorLegSolver}の
     * 半径8）で救われており、この事象は<b>層2が使えないときにしか起きないはず</b>——
     * それがXaeroのリージョン読み込み状態に依存するため手元で測れない。実機で数えるための
     * ログをここに置く。
     *
     * <p>判定は層3自身の{@link StanceFinder#resolveGoal}を使う（Y方向32ブロックの寄せ込みを
     * 含む本番と同じ規則）。同じ目標を繰り返し報告しないよう、座標が変わったときだけ出す。
     */
    private void noteTargetStandability(ChunkView view, BlockPos target, PathMode mode, int waypointIndex) {
        if (StanceFinder.resolveGoal(view, target) != null) {
            unstandableTargetGate.reset();
            return;
        }
        if (!unstandableTargetGate.changed(target)) {
            return;
        }
        LOGGER.debug("XaeroNav: 探索目標に立てません (目標={}, 種別={}, 中間目標#{}, 層2の精緻版={})",
                target.toShortString(), mode, waypointIndex,
                refinedRouteInUse() ? "使用中" : "無し");
    }

    /** いま{@link #cachedOrFreshRoute}が層2の精緻版を返しているか（上のログの内訳用）。 */
    private boolean refinedRouteInUse() {
        CoarseRoute cached = coarseRoute;
        RefinedRoute refined = refinedRoute;
        return cached != null && refined != null && refined.source() == cached;
    }

    /**
     * 経路が始点→目標の直線から大きく外れていたら、その内訳を残す（診断）。
     *
     * <p>ユーザー報告「地図の線が長方形にジグザグする」（2026-08-30、実機スクショ）の切り分け用。
     * 見た目だけでは<b>どの手が並んでいるのか</b>が分からず、原因の候補が絞れなかった:
     * 急斜面を降りるための折り返し（{@code DESCEND}が多い）なのか、橋（{@code BRIDGE}）なのか、
     * 中間目標が飛んでいるのか。1行あれば区別が付く。
     *
     * <p>普段は黙っている——{@link #SUSPICIOUS_DEVIATION_BLOCKS}を超えて外れたときと、
     * 掘削・設置が{@link #SUSPICIOUS_TERRAIN_EDIT_STEPS}手を超えつつ
     * {@link #SUSPICIOUS_TERRAIN_EDIT_FRACTION}も超えたときだけ出す。
     */
    private void noteSuspiciousShape(BlockPos start, BlockPos target, PathResult result) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        List<PathStep> steps = result.steps();
        if (steps.isEmpty()) {
            return;
        }
        double gx = target.getX() - start.getX();
        double gz = target.getZ() - start.getZ();
        double length = Math.sqrt(gx * gx + gz * gz);
        if (length < 1.0) {
            return;
        }
        double deviation = 0.0;
        int turns = 0;
        int previousDx = 0;
        int previousDz = 0;
        int cursorX = start.getX();
        int cursorZ = start.getZ();
        Map<MovementType, Integer> kinds = new EnumMap<>(MovementType.class);
        int bridges = 0;
        int digs = 0;
        for (PathStep step : steps) {
            kinds.merge(step.movement(), 1, Integer::sum);
            if (step.bridging()) {
                bridges++;
            }
            if (step.digging()) {
                digs++;
            }
            int dx = Integer.signum(step.pos().getX() - cursorX);
            int dz = Integer.signum(step.pos().getZ() - cursorZ);
            cursorX = step.pos().getX();
            cursorZ = step.pos().getZ();
            if ((dx != previousDx || dz != previousDz) && (dx != 0 || dz != 0)) {
                turns++;
            }
            previousDx = dx;
            previousDz = dz;
            deviation = Math.max(deviation, Math.abs((step.pos().getX() - start.getX()) * gz
                    - (step.pos().getZ() - start.getZ()) * gx) / length);
        }
        boolean manyEdits = bridges + digs >= SUSPICIOUS_TERRAIN_EDIT_STEPS
                && bridges + digs > steps.size() * SUSPICIOUS_TERRAIN_EDIT_FRACTION;
        if (deviation < SUSPICIOUS_DEVIATION_BLOCKS && !manyEdits) {
            return;
        }
        BlockPos currentGoal = goal;
        WindowField field = currentGoal == null ? null : navGraphGuide.latest(currentGoal);
        BlockPos end = steps.get(steps.size() - 1).pos();
        double finalDeviation = deviation;
        int finalTurns = turns;
        int finalBridges = bridges;
        int finalDigs = digs;
        NavGraphGuide.logOffThread(() -> LOGGER.debug("XaeroNav: 経路が直線から大きく外れています "
                        + "(ずれ={}ブロック, 目標まで{}ブロック, {}ステップ, 曲がり{}, 橋{}, 掘削{}, 内訳={}, "
                        + "始点の値の出どころ={}, 末端{}の値の出どころ={})",
                Math.round(finalDeviation), Math.round(length), steps.size(), finalTurns, finalBridges, finalDigs, kinds,
                field == null ? "航法グラフ無し" : NavGraphGuide.origin(field, start), end.toShortString(),
                field == null ? "航法グラフ無し" : NavGraphGuide.origin(field, end)));
    }

    /**
     * この探索がどこまで引けたかを記録に残す。かつてはこれを次回の目標距離の上限
     * （{@code detailReach}）へ反映していたが、その仕組みは廃止した——プレイヤー周辺の
     * 既踏地形で測った値を、経路の末端から未踏地形へ伸ばす探索の上限にも使っていたため、
     * 「プレイヤー基準で成功して上がる → 末端基準で同じ値に失敗して下がる」を交互に
     * 繰り返して収束しなかった（実機ログで 24→48→24→48… が規則的に並んだ）。
     * 目標距離が変わるたびに経路が引き直されるので、これがそのまま「行ったり来たり」に見える。
     *
     * <p>いまは目標を「読み込み済みチャンクの縁」に置き、届かなければ部分経路をそのまま
     * 案内に使う（打ち切り時も最良の部分経路が返る）。当てにいく数値そのものが無くなった。
     */
    static void logSearchReach(BlockPos start, BlockPos target, PathResult result) {
        BlockPos end = endOf(result, start);
        if (!result.complete() && (result.steps().isEmpty()
                || horizontalDistance(start, end) < MIN_EXTEND_PROGRESS_BLOCKS)) {
            stalledSearches.incrementAndGet();
            LOGGER.debug("XaeroNav: 詳細探索が十分に前進できません (始点={}, 目標={}, 末端={}, {}, 展開={}, ステップ={})",
                    start.toShortString(), target.toShortString(), end.toShortString(),
                    result.termination(), result.expandedNodes(), result.steps().size());
        }
        if (!LOGGER.isDebugEnabled()) {
            return;
        }
        LOGGER.debug("XaeroNav: 詳細探索 (目標 {} ({} ブロック先), 実到達 {} ブロック, {}, 展開 {})",
                target.toShortString(), Math.round(horizontalDistance(start, target)),
                Math.round(horizontalDistance(start, end)), result.termination(), result.expandedNodes());
    }

    /**
     * この未到達の経路は、<b>いちばん近づいた所よりさらに遠くで終わる</b>か。終わるなら案内に使わない。
     *
     * <p>実機（2026-09-19 01:10、ネザー）で<b>プレイヤーが197ブロックの地点から277ブロックへ
     * 80ブロック連れ戻された</b>。92%が溶岩の場所で詳細探索が32万ノード使って5〜11ステップしか
     * 進めず、そこで選ばれた「途中までの経路」が東（目的地と反対）へ110ステップ伸びていた。
     *
     * <p><b>終点選び（{@code AStarPathfinder#selectFallback}）は壊れていない。</b>候補は
     * {@code h(候補) + g/c < h(始点)}を満たすものしか採らないので、1回の探索は必ずガイドの上で
     * 目的地に近づく点を選ぶ。問題は<b>ガイドがそう評価したこと</b>で、しかもプレイヤーが離れるほど
     * 3D粗層の箱が広がって格子が粗くなる（実機で辺4→6）ため、離れるほどガイドが悪くなる。
     * ガイドを信じ続ける限りこの正のフィードバックは止まらない。
     *
     * <p>そこで<b>ガイドではなく実際の進み方に上限を置く</b>。正しい迂回（溶岩の海の縁を回る）は
     * 模型のネザー実測で最悪67ブロックの後退なので、{@link RetreatWatcher#RETREAT_BLOCKS}の
     * 外側だけを弾く。弾かれると経路は出ないが、<b>間違った向きへ歩かされるよりは良い</b>
     * ——最接近の記録は消さないので、前へ進む経路が出た時点でそのまま採られる。
     *
     * <p>完走した経路は対象外。目的地まで引けているなら、途中どれだけ迂回しても着く。
     */
    private boolean leadsBackward(PathResult result, BlockPos currentGoal) {
        BlockPos anchor = retreatWatcher.closestAt();
        if (result.complete() || result.steps().isEmpty() || anchor == null) {
            return false;
        }
        if (!retreatWatcher.leadsAway(() -> result.steps().stream().map(PathStep::pos).iterator(),
                currentGoal)) {
            return false;
        }
        BlockPos end = result.steps().get(result.steps().size() - 1).pos();
        LOGGER.info("XaeroNav: 目的地から遠ざかる案内なので採りません "
                        + "(末端={}で{}, 最接近={}で{}, {}ステップ, {})",
                end.toShortString(), Math.round(horizontalDistance(end, currentGoal)), anchor.toShortString(),
                Math.round(retreatWatcher.closest()), result.steps().size(), result.termination());
        return true;
    }

    /**
     * 目的地へいちばん近づいた所から大きく遠ざかったら、そのときの判断材料を1行残す。
     *
     * <p>ネザーの実機（2026-09-18）で<b>約300ブロックの往復</b>が出たが、当時のログには
     * 「遠ざかった」こと自体が1行も無く、繋ぎ目の解き直し位置から軌跡を復元して初めて分かった。
     * 次に起きたときは、ここ1行で「どこから引き返したか・探索が行き止まっていたか・
     * そのときガイドが何tickと言っていたか」が揃う。
     */
    private void noteRetreat(BlockPos at, BlockPos currentGoal, @Nullable DisplayedPath shown) {
        RetreatWatcher.Retreat retreat = retreatWatcher.observe(at, currentGoal);
        if (retreat == null) {
            return;
        }
        WindowField field = navGraphGuide.latest(currentGoal);
        List<PathStep> steps = shown == null ? List.of() : shown.result().steps();
        LOGGER.info("XaeroNav: 目的地から遠ざかっています (現在地={}, 目的地まで{}, 最接近={}で{}, 遠ざかった{}, "
                        + "経路の末端={}, {}, 前進できません{}回, ガイド={})",
                at.toShortString(), Math.round(retreat.distance()),
                retreat.closestAt().toShortString(), Math.round(retreat.closest()),
                Math.round(retreat.retreated()),
                steps.isEmpty() ? "無し" : steps.get(steps.size() - 1).pos().toShortString(),
                shown == null ? "経路無し" : shown.result().complete() ? "目的地まで引けている" : "途中まで",
                stalledSearches.get(),
                field == null ? "無し"
                        : Math.round(field.estimate(at.getX(), at.getY(), at.getZ())) + "tick");
    }

    /**
     * 層2の精緻版があればそちらを優先する（{@link NavigationView#coarseRouteWaypoints}と同じ順序）。層1は
     * チャンク解像度で中間目標が100ブロック近く離れることがあり、描画距離を下げた環境では
     * {@link #reachableWaypointTarget}の「renderRadius以内」を1つも満たせず長距離ルートごと
     * 空振りする。精緻版は{@link #REFINED_WAYPOINT_MIN_SPACING_BLOCKS}間隔なのでここを埋められる。
     */
    private List<BlockPos> cachedOrFreshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                               boolean playerAnchored, boolean ceilingDimension) {
        if (coarseRouteStale(currentGoal, playerAnchored)) {
            return freshRoute(start, currentGoal, boatAvailable, ceilingDimension);
        }
        CoarseRoute cached = coarseRoute;
        RefinedRoute refined = refinedRoute;
        return refined != null && refined.source() == cached ? refined.waypoints() : cached.waypoints();
    }

    /**
     * {@link #cachedOrFreshRoute}の、結果をその場で使わない呼び出し元用。引き直すなら地図だけ読んで、
     * 解くのはバックグラウンドに回す（HUDと地図の点線・窓の外の推定のためだけに引く場面で、
     * メインスレッドを解きで止めない）。
     */
    private void prepareCoarseRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                    boolean playerAnchored, boolean ceilingDimension) {
        if (coarseRouteStale(currentGoal, playerAnchored)) {
            freshRouteInBackground(start, currentGoal, boatAvailable, ceilingDimension);
        }
    }

    private boolean coarseRouteStale(BlockPos currentGoal, boolean playerAnchored) {
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

    private List<BlockPos> freshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                       boolean ceilingDimension) {
        long coarseLap = TickLaps.start();
        CoarseAttempt attempt = solveCoarseRoute(readCoarseMapFor(start, currentGoal), start, currentGoal,
                boatAvailable);
        TickLaps.add("長距離ルート", coarseLap);
        // 裏で解いている要求より、いま同期で引いた方が新しい
        solvingCoarse = null;
        return adoptCoarseRoute(start, currentGoal, attempt, ceilingDimension).waypoints();
    }

    /**
     * 地図だけをここで読み、解くのは{@link #coarseExecutor}に回す。解き終わったらメインスレッドで
     * {@link #adoptCoarseRoute}する。同じ目的地をまだ解いている間は重ねて投げない。
     */
    private void freshRouteInBackground(BlockPos start, BlockPos currentGoal, boolean boatAvailable,
                                        boolean ceilingDimension) {
        CoarseSolve pending = solvingCoarse;
        if (pending != null && pending.goal().equals(currentGoal)) {
            return;
        }
        long readLap = TickLaps.start();
        CoarseRead read = readCoarseMapFor(start, currentGoal);
        TickLaps.add("長距離ルートの地図読み", readLap);
        CoarseSolve solve = new CoarseSolve(currentGoal);
        solvingCoarse = solve;
        CompletableFuture.supplyAsync(() -> solveCoarseRoute(read, start, currentGoal, boatAvailable), coarseExecutor)
                .whenComplete((attempt, error) -> onMainThread.accept(() -> {
                    if (solvingCoarse != solve) {
                        return;
                    }
                    solvingCoarse = null;
                    if (error != null) {
                        LOGGER.error("XaeroNav: 長距離ルートの計算に失敗しました", error);
                        return;
                    }
                    adoptCoarseRoute(start, currentGoal, attempt, ceilingDimension);
                    publishNavigationView();
                }));
    }

    /** 長距離ルートの地図を読み、窓の外の推定がすぐ使えるように{@link #latestCoarseMap}へ置く。 */
    private CoarseRead readCoarseMapFor(BlockPos start, BlockPos currentGoal) {
        CoarseRead read = readCoarseMap(start, currentGoal);
        latestCoarseMap = read.map() == null ? null : new CoarseMapForGoal(currentGoal, read.map());
        coarseMapRetryAfterMillis = MonotonicTime.millis() + COARSE_MAP_RETRY_INTERVAL_MILLIS;
        return read;
    }

    private CoarseRoute adoptCoarseRoute(BlockPos start, BlockPos currentGoal, CoarseAttempt attempt,
                                         boolean ceilingDimension) {
        CoarseRouter.Route route = attempt.route();
        List<BlockPos> waypoints = route.waypoints();
        if (!waypoints.isEmpty() && route.reachedGoal()) {
            // 粗い終点はチャンク中心±8ブロックで高さも代表値なので、そのままでは到着できない。
            // 最後だけ本来の目的地に差し替える
            waypoints = replaceLast(waypoints, currentGoal);
        }
        CoarseRoute thisRoute = new CoarseRoute(currentGoal, start, route.reachedGoal(),
                attempt.pendingRegions(), waypoints);
        CoarseRoute before = mapRetryBefore;
        coarseRoute = thisRoute;
        // 新しい列では添字の意味が変わる。引き直しは今の位置を始点にするので、先頭が通過済みに
        // なることはない
        passedWaypoints = 0;
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
            LOGGER.debug("XaeroNav: 地図の読み込みを待って長距離ルートを引き直しました"
                            + " (未読み込みリージョン={}→{}, 中間目標={}→{}個, {}, {}回目/{})",
                    before.pendingRegions(), thisRoute.pendingRegions(),
                    before.waypoints().size(), waypoints.size(),
                    waypoints.equals(before.waypoints()) ? "同じルート" : "変わった",
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
            onMainThread.accept(() -> {
                if (refiningRoute == forRoute) {
                    refiningRoute = null;
                }
                if (downsampled == null) {
                    return;
                }
            // 世代の検査はここではなく読み出し側（RefinedRoute.source()）で行う。ここで
            // 「検査してから書き込む」形にすると、その間に世代が進んだ場合に古い精緻版が
            // 素通りする（stitch/downsampleは点列全体を走査するので、その隙間は実時間で開く）
                pendingRefinedRouteReady = new RefinedRoute(currentGoal, forRoute, downsampled);
            });
        });
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

    /**
     * 中間目標は順番に1つずつではなく、詳細探索が届く範囲で最も遠い未通過点を選ぶ。
     * waypoint間隔は詳細探索が一度に届く距離より十分短いので、1つずつ渡すと探索能力を捨てる。
     * 1つも描画距離内に無ければ{@code null}（呼び出し側が引き直すかどうかを判断する）。
     *
     * <p>「届く範囲」は描画距離ではなく{@code reach}（{@link #updateDetailReach}の実測値）で切る。
     * 描画距離まで読み込み済みとは限らないうえ、同じ予算で解ける距離は地形の密度で何倍も変わる。
     */
    private @Nullable DetailTarget reachableWaypointTarget(BlockPos start, BlockPos currentGoal,
                                                  List<BlockPos> waypoints, int renderRadius, int reach,
                                                  boolean playerAnchored, int minWaypointIndex) {
        int farthestInRadius = -1;
        int farthestInReach = -1;
        int nearestInRadius = -1;
        int previouslyAimed = -1;
        double nearestDistance = Double.MAX_VALUE;
        // 後戻りの歯止めは2系統ある。プレイヤー基準は「前回向いていた点」を座標で覚える
        // （lastAimedWaypoint）。継ぎ足し（始点＝経路の末端）は末端の区間が向かっている添字を
        // 下限にする——両者で同じフィールドを共有すると、末端が数区間先まで進んだあとの歯止めが
        // プレイヤー基準の選定を遠い添字へ固定してしまい、今いる場所に合った点を選び直せなくなる
        //
        // <b>ただし歯止めを掛けるのは前へ進めている間だけ。</b>行き詰まっているなら、後ろへ
        // 回り込む遠回りこそが答えでありうる——奈落を渡れる幅の狭い場所が背後にある地形
        // （ジ・エンドの島）では、いま向いている点より手前の中間目標を選び直せないと解が消える。
        // lastAimedWaypointはclear()まで単調にしか進まないので、一度掴むとその手前は二度と
        // 選べない: 改善しえない値を歯止めに使うと永久に外れない、というnoteSearchOutcomeで
        // 踏んだのと同じ形（[[xaeronav-architecture]]の「ラッチ」の項）。
        // 詰みかけているときだけ外すので、通常の前進中に「前進する目標と背後の目標が交互に出る」
        // 振動（この歯止めを入れた理由そのもの）は起きない
        boolean strandedHere = stuckTracker.stranded();
        BlockPos aimedBefore = playerAnchored && !strandedHere ? lastAimedWaypoint : null;
        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos waypoint = waypoints.get(i);
            if (waypoint.equals(aimedBefore)) {
                previouslyAimed = i;
            }
            double distance = horizontalDistance(start, waypoint);
            if (distance > renderRadius) {
                continue;
            }
            farthestInRadius = i;
            if (distance <= reach) {
                farthestInReach = i;
            }
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestInRadius = i;
            }
        }
        if (farthestInRadius < 0) {
            return null;
        }
        // reach内に1つも無ければ最寄りのwaypointを向く。そのまま狙うと遠すぎるが、下で手前に切る
        int farthestIndex = farthestInReach >= 0 ? farthestInReach : nearestInRadius;
        // ルート上で後ろへは戻らない。reachがwaypoint間隔（層1で最大90ブロック）より短いと上の選定は
        // ほぼ必ず「最寄り」に落ちるが、waypointを通り過ぎた直後はそれが背後にある——そのまま狙うと
        // 引き返す案内になる（実機で確認: 前進する目標と背後の目標が交互に出て行ったり来たりした）。
        // 経路から大きく外れてルートを引き直した場合は前回の点が新しい列に無いので、この歯止めは外れる。
        //
        // renderRadiusの外まで引きずってはいけない。歯止めは「まだ手前にある点を掴まない」ためのもので、
        // もう届かない遠い点へ目標を固定するためのものではない
        farthestIndex = Math.max(farthestIndex, Math.min(previouslyAimed, farthestInRadius));
        if (!playerAnchored) {
            // 継ぎ足しは末端が向かっている区間より手前を狙ってはいけない。下の「最寄り」
            // フォールバックは通り過ぎた点を掴みうるので、これが無いと経路が自分の後ろへ
            // 折り返して伸びる（実機で、末端245,-332から96ブロック後ろの159,-375へ継ぎ足していた）。
            // 下限がrenderRadiusの外なら、そこへ向かう道の上のreach地点（下のpointAlongRoute）が目標になる
            farthestIndex = Math.max(farthestIndex, Math.min(minWaypointIndex, waypoints.size() - 1));
        }
        // ここまでの歯止めは「手前を狙わない」ための下限。手前に切るときに辿る折れ線は、
        // この地点から先だけを通す——歯止めが飛ばした中間目標は自分より後ろにあるので、
        // 折れ線に含めると目標が背後へ寄ってしまう
        int routeFrom = farthestIndex;
        // 選んだ点が近すぎると長さ0の経路しか出せない。ゴールを領域にしたぶん、始点がその領域の
        // 中に入っていれば探索は始点ノードを取り出した瞬間に到達扱いで終わる——閾値は
        // 「間引き間隔」ではなく「半径＋間引き間隔」で見ないと、0ステップが多発する
        if (tooCloseToAim(start, waypoints.get(farthestIndex))) {
            // 前へ出すための調整なので、歯止めより手前へ戻してはいけない
            farthestIndex = Math.max(farthestIndex, Math.min(farthestIndex + 1, farthestInRadius));
        }
        BlockPos aim = waypoints.get(farthestIndex);
        double distance = horizontalDistance(start, aim);
        // 近すぎるのに前へ出せなかった＝この列にはもう先が無い。<b>目的地へ届かなかったルートは
        // 最終waypointが目的地に差し替わらない</b>（replaceLastはreachedGoalのときだけ）ので、
        // そこへ着くとここに落ちる。そのまま返すと目標が自分の位置になり、探索は0ステップで
        // 「到達」を返す——経路は空、しかも失敗ではないのでエスカレーションも走らない。
        // nullを返して呼び出し側の目的地方向への切り詰めに任せる
        if (tooCloseToAim(start, aim) && !aim.equals(currentGoal)) {
            return null;
        }
        // 本来の目的地は歯止めに使わない。ルートを引き直しても最後の要素は必ず目的地なので、
        // ここへ一度触れると「新しい列に無ければ歯止めが外れる」という逃げ道が永久に塞がる——
        // 以降どれだけ離れても目的地そのものを狙い続け、詳細探索は毎回予算を焼くことになる
        // （実機ログ: 291ブロック先の目的地を数秒おきに20万ノードで狙い続けていた）
        if (playerAnchored && !aim.equals(currentGoal)) {
            lastAimedWaypoint = aim;
        }
        // 本来の目的地（replaceLastで置き換わった最終waypoint）は{@link #setGoal}で既に立てる座標へ
        // 解決済み。到達判定は座標の完全一致なので、届く距離にあるなら手前に切ってはいけない
        // （切ると永久に到着しなくなる）。逆に届かない距離なら、他のwaypointと同じく手前へ切る——
        // 一度に解けない距離を目標にしても予算を焼くだけで、到着判定はゴールそのものを見ている
        if (aim.equals(currentGoal) && distance <= reach) {
            // 本来の目的地は動かせない。ユーザーが指した点そのものなので半径0
            return new DetailTarget(aim, farthestIndex, 0);
        }
        // waypointの間隔は詳細探索が一度に狙う距離より短く保ってあるので（CoarseRouterの
        // WAYPOINT_SPACING_CELLS参照）、普通はここで手前に切る必要は無い。切るのは、近すぎる
        // waypointを1つ飛ばした直後と、ルートから大きく外れて手近な点が1つも無いとき。
        // waypointIndexは向かっている先を指したままなので、HUDのカウンタも地図の点線
        // （未通過ぶんだけを描く）もずれない
        boolean interpolated = distance > reach;
        BlockPos target = interpolated ? pointAlongRoute(start, waypoints, routeFrom, farthestIndex, reach) : aim;
        return new DetailTarget(resolveWaypointOnSurface(target), farthestIndex,
                interpolated ? INTERPOLATED_GOAL_RADIUS_BLOCKS : WAYPOINT_GOAL_RADIUS_BLOCKS);
    }

    /**
     * {@code start}から粗いルートの折れ線（{@code waypoints[fromIndex..aimIndex]}）に沿って、
     * 水平距離{@code reach}だけ進んだ点。
     *
     * <p><b>始点から目標waypointへの直線で測ってはいけない。</b>層1が決めているのは「どこを通るか」で、
     * その直線はルートが曲がっている所で角を大きく切り落とす——切った先は層1が避けた地形
     * （溶岩の海など）で、そこに人工的な目標が落ちる。詳細探索は橋を架けてでもそこへ寄っていき、
     * 通り過ぎてから本来の道へ戻るので、ユーザーからは「もっと良い道があるのに中継地点へ
     * 寄り道してから進む」に見える（実機ログ: 目標が常に始点からちょうどreach=96の位置に出て、
     * 経路に橋が30本前後——{@code maxBridgeRunBlocks}の上限張り付き——乗っていた）。
     *
     * <p>{@code fromIndex}の点が数ブロック手前に過ぎ去っていても構わない。折れ線の長さが
     * その分だけ伸びて目標がわずかに手前へ寄るだけで、目標がルートから外れることはない。
     */
    private static BlockPos pointAlongRoute(BlockPos start, List<BlockPos> waypoints, int fromIndex,
                                             int aimIndex, double reach) {
        BlockPos cursor = start;
        double remaining = reach;
        for (int i = Math.max(0, fromIndex); i <= aimIndex; i++) {
            BlockPos next = waypoints.get(i);
            double leg = horizontalDistance(cursor, next);
            if (leg >= remaining) {
                return pointAlong(cursor, next, leg, remaining);
            }
            remaining -= leg;
            cursor = next;
        }
        return waypoints.get(aimIndex);
    }

    /** {@code from}から{@code to}へ向かう線上で、水平距離{@code reach}だけ進んだ点。Yも比例配分する。 */
    static BlockPos pointAlong(BlockPos from, BlockPos to, double distance, double reach) {
        double ratio = reach / distance;
        return new BlockPos(
                (int) Math.round(from.getX() + (to.getX() - from.getX()) * ratio),
                (int) Math.round(from.getY() + (to.getY() - from.getY()) * ratio),
                (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * ratio));
    }

    /**
     * 層1のwaypoint（チャンク中心+代表高さ）を、層2のブロック解像度データで実際に立てる座標へ
     * 寄せる。X,Zは変えずYだけ調整する（2D地図の点線・進捗表示はwaypointの生座標のままなので、
     * Yしか変えなければ無改修でも整合する）。データが無ければ元のwaypointをそのまま返す
     * （長距離ルートの他の発動条件と同じく、層2が使えない場合は素の層1へフォールバックする）。
     */
    private static BlockPos resolveWaypointOnSurface(BlockPos waypoint) {
        return resolveOnSurface(waypoint, false);
    }

    /**
     * 目的地版。水の列で<b>要求されたYに近い方</b>（水面か水底か）を選ぶ点だけが違う。
     * 中間目標は向かう方角を示すものなので水面で構わないが、目的地はユーザーが指した点そのもので、
     * 海底を指したなら水面で「到着」にしてはいけない。
     */
    private static BlockPos resolveGoalOnSurface(BlockPos goal) {
        return resolveOnSurface(goal, true);
    }

    private static BlockPos resolveOnSurface(BlockPos waypoint, boolean preferRequestedY) {
        int chunkX = waypoint.getX() >> 4;
        int chunkZ = waypoint.getZ() >> 4;
        int referenceY = waypoint.getY();
        XaeroMapReader.RegionStats stats = XaeroMapReader.surveyRegions(chunkX, chunkZ, 1, 1, referenceY);
        if (stats.pendingLoad() > 0) {
            XaeroMapReader.requestLoad(chunkX, chunkZ, 1, 1, referenceY);
        }
        SurfaceGrid grid = XaeroMapReader.readSurfaceDetailed(chunkX * 16, chunkZ * 16, 16, 16, referenceY);
        BlockPos resolved = preferRequestedY
                ? grid.resolveStandableNear(waypoint.getX(), waypoint.getZ(), referenceY)
                : grid.resolveStandable(waypoint.getX(), waypoint.getZ());
        return resolved != null ? resolved : waypoint;
    }

    /** 長距離ルートの地図を読み、読めた量をログに残す。<b>メインスレッド専用</b>（{@link XaeroMapReader#readSurface}）。 */
    private static CoarseRead readCoarseMap(BlockPos start, BlockPos goal) {
        CoarseMapWindow.Window window = CoarseMapWindow.read(start, goal, CoarseMap.MAX_FLOORS);
        CoarseMap map = window.map();
        if (map == null) {
            return new CoarseRead(null, 0);
        }
        noteMapNotGrowing(start, window, map);
        // 地図がどれだけ見えていたかを残す。「溶岩をLAVAとして見たうえで通した」のか「まだ
        // NO_DATAで見えていなかった」のかは、ここが黙っていると実機ログから区別できない——
        // 未知セルはCoarseRouterでほぼ最安なので、見えていなければ溶岩の海を直進するルートが引かれる
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("XaeroNav: 長距離ルートの地図 (既知セル={}/{}, {}, レイヤー別={}, 未読み込みリージョン={}, 読み取り={}ms)",
                    map.knownCells(), map.totalCells(), map.kindBreakdown(), window.layerBreakdown(),
                    window.pendingRegions(), window.readMillis());
        }
        // 実機のカクつきがメインスレッドの地図読み取り側かを継続的に見張る。1tick(20TPS)相当の
        // 50msを超えたら、閾値以下に戻るまでの間も5秒おきに知らせる（毎回だと洪水になる）
        if (window.readMillis() > SLOW_MAP_READ_THRESHOLD_MILLIS
                && slowMapReadGate.changed(true, MonotonicTime.millis(), SLOW_MAP_READ_LOG_INTERVAL_MILLIS)) {
            LOGGER.warn("XaeroNav: 長距離ルートの地図読み取りが遅い ({}ms > {}ms)",
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
    private static CoarseAttempt solveCoarseRoute(CoarseRead read, BlockPos start, BlockPos goal,
                                                  boolean boatAvailable) {
        CoarseMap map = read.map();
        if (map == null) {
            return new CoarseAttempt(new CoarseRouter.Route(List.of(), false), 0);
        }
        CoarseRouter.Route avoided = CoarseRouter.findRoute(map, start, goal, boatAvailable,
                CoarseRouter.BridgePolicy.AVOID);
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
                CoarseRouter.BridgePolicy.ALLOW);
        if (allowed.reachedGoal()) {
            LOGGER.info("XaeroNav: 奈落・溶岩混じりを避ける道が見つからないため、そこを通る長距離ルートに切り替えました");
            return new CoarseAttempt(allowed, read.pendingRegions());
        }

        CoarseRouter.Route bridged = CoarseRouter.findRoute(map, start, goal, boatAvailable,
                CoarseRouter.BridgePolicy.BRIDGE);
        if (bridged.reachedGoal()) {
            LOGGER.info("XaeroNav: 溶岩を避ける道が見つからないため、橋を架けて渡る長距離ルートに切り替えました");
            return new CoarseAttempt(bridged, read.pendingRegions());
        }
        return new CoarseAttempt(furtherRoute(furtherRoute(avoided, allowed), bridged), read.pendingRegions());
    }

    /** ほぼ同じ場所から読み直したとみなす距離（ブロック）。これを超えたら別の範囲として数え直す。 */
    private static final double MAP_GAIN_SAME_PLACE_BLOCKS = 32.0;

    /**
     * 「読み込みを要求しても地図が増えない」と判断するまでの読み直しの回数。
     * 読み直しは{@link #COARSE_MAP_RETRY_INTERVAL_MILLIS}間隔なので、5回で約15秒。
     */
    private static final int MAP_GAIN_ATTEMPTS = 5;

    private static BlockPos lastMapReadFrom;
    private static int lastMapKnownCells = -1;
    private static int mapReadsWithoutGain;
    private static final ChangeGate<Boolean> mapNotGrowingGate = new ChangeGate<>();

    /**
     * <b>読み込みを要求しているのに地図が増えないことを知らせる。</b>
     *
     * <p>{@link XaeroMapReader#requestLoad}は効いているのに、読み込まれたリージョンの中身が空
     * ——という状態が実機で起きた（2026-09-18: {@code /xaeronav debug mapdata}を同じ場所で3回
     * 連続して撃つと「30 awaiting → 0 awaiting」まで進むのに、既知セルは3213/4225のまま
     * 1つも増えなかった）。Xaeroがそのリージョンのキャッシュを{@code .outdated}へ退避していると
     * こうなる。<b>黙っていると、薄い地図のまま経路を決め続けていることに誰も気づけない</b>
     * （気づく手段が診断コマンドしかなかった）。
     */
    private static void noteMapNotGrowing(BlockPos start, CoarseMapWindow.Window window, CoarseMap map) {
        boolean samePlace = lastMapReadFrom != null
                && horizontalDistance(start, lastMapReadFrom) <= MAP_GAIN_SAME_PLACE_BLOCKS;
        if (!samePlace || map.knownCells() > lastMapKnownCells) {
            mapReadsWithoutGain = 0;
        } else if (window.pendingRegions() > 0) {
            mapReadsWithoutGain++;
        }
        lastMapReadFrom = start;
        lastMapKnownCells = map.knownCells();
        if (mapReadsWithoutGain < MAP_GAIN_ATTEMPTS) {
            return;
        }
        if (!mapNotGrowingGate.changed(true, MonotonicTime.millis(), MAP_NOT_GROWING_LOG_INTERVAL_MILLIS)) {
            return;
        }
        LOGGER.warn("XaeroNav: 地図の読み込みを要求しても増えません ({}回続けて既知セル={}/{}, 未読み込みリージョン={})"
                        + " — Xaeroがこの範囲のキャッシュを読めていない可能性があります"
                        + "（世界地図でこの範囲を表示すると直る場合があります）",
                mapReadsWithoutGain, map.knownCells(), map.totalCells(), window.pendingRegions());
    }

    /** 上の警告を繰り返す間隔。同じ状態が続く間ずっと出しても意味が無い。 */
    private static final long MAP_NOT_GROWING_LOG_INTERVAL_MILLIS = 60_000L;

    /**
     * {@link #solveCoarseRoute}の結果と、それを引いたときに<b>まだ読み込まれていなかった</b>
     * リージョンの数。0より大きければ、待って引き直すと違うルートになりうる。
     */
    private record CoarseAttempt(CoarseRouter.Route route, int pendingRegions) {
    }

    /** メインスレッドで読んだ長距離ルートの地図。{@code map}はXaeroの地図が無ければnull。 */
    private record CoarseRead(@Nullable CoarseMap map, int pendingRegions) {
    }

    /** 目的地まで届かなかったルート同士の比較。中間目標が多い方＝より遠くまで進めた方を採る。 */
    private static CoarseRouter.Route furtherRoute(CoarseRouter.Route a, CoarseRouter.Route b) {
        return b.waypoints().size() > a.waypoints().size() ? b : a;
    }

    private static List<BlockPos> replaceLast(List<BlockPos> waypoints, BlockPos replacement) {
        List<BlockPos> copy = new ArrayList<>(waypoints);
        copy.set(copy.size() - 1, replacement);
        return List.copyOf(copy);
    }

    static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 探索範囲の垂直マージン。既定の{@code searchVerticalMargin}は水平マージンと違って
     * 一度も広がらず、天井のある次元（ネザー）ではこれが致命的になる——既定32だとY方向
     * 65ブロック厚のスライスしか見えず、溶岩の海を上下に大きく迂回する経路がそもそも
     * 探索範囲の外という理由だけで存在しなくなる（箱の外は掘れない壁として扱われるため）。
     *
     * <p>天井のある次元は{@code getHeight()}自体が高々128前後しかないので、常に全高を
     * 使っても探索コストは些細。{@code widen}（水平と同じ再挑戦トリガー）でも同様に広げる——
     * 「範囲が狭くて届かない」という失敗のしかたを、垂直方向でも取り除く。
     */
    static int verticalSearchMargin(Level level, boolean widen) {
        int configured = XaeroNavConfig.INSTANCE.searchVerticalMargin();
        if (level.dimensionType().hasCeiling() || widen) {
            return Math.max(configured, level.getHeight());
        }
        return configured;
    }

    /**
     * 「まず地上へ出る」区間を挟むべきか（地上優先ナビ）。
     *
     * <p>目的地が地上にあるとき、目的地の1点だけを狙う探索は最短距離ゆえに真下からの垂直の穴掘りを
     * 選びやすい。地下からの出発に限り、先に「y &gt;= groundLevelの空の下ならどこでもゴール」の探索を
     * 挟むことで、近くの洞窟や崖があればそちらを、無ければ掘削を、状況に応じて選ばせる
     * （探索そのものは{@link net.prason.xaeronav.pathfinding.async.PathfindingExecutor#submitToSurface}が
     * 掘らない道を先に、見つからなければ掘る道を、の順に試す）。
     *
     * <p>判断にYだけを使わないのは、Yが低いことと地下にいることが別だから。川底・谷底・海岸は
     * 既定の{@code groundLevelY}(60)より下にいくらでもあり、そこを歩くたびに中継区間が挟まると、
     * 案内が目的地と関係ない方向へ振れる。空が見えているならそこはもう地上として扱う。
     *
     * <p>{@code surfaceY}は<b>プレイヤーの列の地表</b>（{@link #surfaceReferenceY}）。設定の
     * {@code groundLevelY}は世界に1つの定数なので、山の下では地表がそれより遥かに上にある
     * ——既定(60)のままだと、山中の洞窟でy=70にいるプレイヤーが「もう地上の高さ」と判定され、
     * 60ブロック下の地下にいても中継区間に入らなかった（#45）。
     */
    private boolean shouldClimbToSurface(Level level, BlockPos start, BlockPos goal, int surfaceY) {
        if (!climbWorthwhile(start.getY(), goal.getY(), surfaceY)) {
            return false;
        }
        // 空の無い次元・天井のある次元（ジ・エンド／ネザー）では、そもそも「地上」が存在しない。
        // ネザーで地上優先ナビに入ると、岩盤天井へ向かって掘り進む案内になってしまう
        if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling()) {
            return false;
        }
        // 頭の上に空が見えているなら地上。屋根の下・洞窟の中にいるときだけ中継区間を挟む。
        //
        // canSeeSkyではなくcanSeeSkyFromBelowWaterを使うのは、水がスカイライトを減衰させるため
        // canSeeSkyが水中で必ずfalseになるから。海底は既定のgroundLevelY(60)を下回るので、
        // 潜っただけで「洞窟の中」と判定されて中継区間に入っていた。しかも中継区間のゴールは
        // openSkyY（MOTION_BLOCKINGは流体を含むので水面の1つ上＝水の外）で、そこは空気で足場が
        // 無い＝外洋では原理的に到達できず、届かなかった中継経路は空の経路として表示される。
        // これが「海の下から線が伸びない」の正体だった。
        if (level.canSeeSkyFromBelowWater(start.above())) {
            return false;
        }
        BlockPos failedAt = surfaceLegFailedAt;
        return failedAt == null
                || failedAt.distSqr(start) > SURFACE_RETRY_MOVE_BLOCKS * SURFACE_RETRY_MOVE_BLOCKS;
    }

    /**
     * 高さだけで見た「まず地上へ出る」区間の要否。{@code surfaceY}は出ていく先の地表
     * （{@link #surfaceReferenceY}）。
     *
     * <p>{@code Level}を切り離してあるのは、ここだけ取り出せばワールド無しで振る舞いを固定できるから
     * （{@code Splice#joinableStepIndex}と同じ）。
     *
     * <p>目的地を<b>同じ基準</b>で見るのが要点。地表より下の目的地＝洞窟から洞窟への移動なので、
     * わざわざ地上へ出てから潜り直す道理が無い(#45「洞窟内から洞窟内なら洞窟を通るのは良い」)。
     */
    static boolean climbWorthwhile(int startY, int goalY, int surfaceY) {
        return goalY >= surfaceY && startY <= surfaceY - MIN_UNDERGROUND_DEPTH;
    }

    /**
     * 「まず地上へ出る」区間が出ていく先のY。<b>プレイヤーの列の地表</b>で、読めない列では
     * 設定の{@code groundLevelY}へ落ちる。
     *
     * <p>ここを世界に1つの定数に戻してはいけない——山・海・高原では実際の地表がそこから
     * 何十ブロックも離れる。中継区間のゴールだけでなく<b>探索の箱</b>もこの値から組み立てるので
     * （{@code recalculate}の仮想ゴール）、ずれていると箱がプレイヤーの下側へ寄る。
     */
    private static int surfaceReferenceY(Level level, BlockPos start) {
        int local = ChunkView.openSkyY(level, start.getX(), start.getZ());
        return local == Integer.MAX_VALUE ? XaeroNavConfig.INSTANCE.groundLevelY() : local;
    }

    /**
     * 目的地へ行けないと判断した理由。HUDの文言・チャットの通知・ログで共有する。
     *
     * <p>文言のキーを列挙側に持たせるのは、理由を増やしたときに翻訳キーの追加が同じ場所で
     * 要求されるようにするため（HUD側でswitchすると、片方だけ増えても黙って通る）。
     */
    public enum StuckReason {
        /** 探索範囲の中に到達手段が無いことが証明された（オープンセットが尽きた）。 */
        NO_WAY_THROUGH("hud.xaeronav.unreachable_blocked"),
        /** 資源を使い切っても近づけない。地形が複雑すぎて詳細探索が解き切れない。 */
        SEARCH_TOO_HARD("hud.xaeronav.unreachable_too_hard"),
        /** 粗い地図（Xaeroの地図データ）の上で、目的地まで繋がっていない。 */
        UNMAPPED("hud.xaeronav.unreachable_unmapped");

        private final String hintKey;

        StuckReason(String hintKey) {
            this.hintKey = hintKey;
        }

        /** 原因と、そこからユーザーが取れる手を1行にまとめた文言のキー。 */
        public String hintKey() {
            return hintKey;
        }
    }

    /** 表示中の経路が向かう先の種類。 */
    enum PathMode {
        GOAL,
        TO_SURFACE,
        WAYPOINT
    }

    /**
     * 表示中の経路と、それが向かう先の種類（{@link PathMode}）。
     *
     * <p>2つを別々のフィールドに置くと、経路を差し替える瞬間に片方だけが新しい状態になる。
     * 実際、モードを探索の開始時に、経路を完了時に更新していたときは、地上に出た直後の1tickだけ
     * 「中継経路 + 目的地モード」になり、中継経路の終端（＝いまの足元）が目的地の代わりとして
     * 到着判定に掛かって、着いていないのに「到着！」で案内が終了していた。
     *
     * @param waypointIndex {@code mode}が{@link PathMode#WAYPOINT}のとき、{@link CoarseRoute#waypoints()}中の
     *         何番目を指しているか（0始まり）。それ以外のモードでは{@code -1}。
     *         これは<b>経路の末端</b>が向かっている先で、プレイヤーが今どこを歩いているかは
     *         {@link #segments}から求める（先読みで経路が数区間先まで伸びるため、両者はずれる）
     * @param segments 継ぎ足した区間の境目。先読みで1本の経路に複数の中間目標ぶんが含まれるので、
     *         HUDの進捗（プレイヤーがいま何番目に向かっているか）を出すのにこれが要る
     */
    record DisplayedPath(PathResult result, PathMode mode, int waypointIndex,
                          List<PathSegment> segments) {

        DisplayedPath(PathResult result, PathMode mode, int waypointIndex) {
            this(result, mode, waypointIndex,
                    List.of(new PathSegment(Math.max(0, result.steps().size() - 1), waypointIndex)));
        }

        /** {@code stepIndex}を含む区間が向かっている中間目標の番号。 */
        int waypointIndexAtStep(int stepIndex) {
            for (PathSegment segment : segments) {
                if (stepIndex <= segment.endStep()) {
                    return segment.waypointIndex();
                }
            }
            return waypointIndex;
        }
    }

    /**
     * 継ぎ足された1区間。{@code endStep}はこの区間の最後のステップの添字（その値を含む）。
     *
     * <p>ステップの添字で持つのは、継ぎ足しが手前の添字を変えないから——座標で持つと、
     * 経路が自分の近くを通る地形で区間の切れ目を取り違える。
     */
    record PathSegment(int endStep, int waypointIndex) {
    }

    /**
     * 長距離ルートの中間目標のキャッシュ。地形は不変なので、目的地が変わらない限り引き直さない。
     *
     * @param computedFrom このルートを引いたときの始点。同じ場所からの引き直しは同じ結果になるので、
     *         それを避けるための照合に使う（{@link #COARSE_ROUTE_RETRY_MOVE_BLOCKS}）
     * @param reachedGoal 粗い地図の上で目的地まで届いたか。届いていないなら、詳細探索を
     *         いくら回しても届かない（粗い地図で通行不能になるのは溶岩だけで、未探索セルは
     *         通行可能として扱われる）ので、詰みの理由を言い当てる材料になる
     * @param pendingRegions このルートを引いたとき、ディスクにはあるのにまだメモリへ載って
     *         いなかったリージョンの数。0より大きければ<b>このルートは欠けた地図の上で引かれた</b>
     *         ということで、読み込みを待って引き直す（{@link #cachedOrFreshRoute}）
     */
    private record CoarseRoute(BlockPos goal, BlockPos computedFrom, boolean reachedGoal, int pendingRegions,
                                List<BlockPos> waypoints) {
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
     * 今の{@code coarseRoute}と同一インスタンスかを見る（順序に依存しない）。
     */
    private record RefinedRoute(BlockPos goal, CoarseRoute source, List<BlockPos> waypoints) {
    }

    /** {@link #selectDetailTarget}の戻り値。詳細探索のゴールと、それが粗いルート中の何番目かの組。 */
    /**
     * @param goalRadius 詳細探索がこのゴールを「触れた」とみなす半径（ブロック）。
     *                   中継地点は<b>通る場所ではなく向かう方角</b>でしかないので、座標ぴったりを
     *                   要求すると、そのための遠回りが経路に乗る。不確かさの大きさは目標の由来で
     *                   違うので、由来ごとに変える（{@link #WAYPOINT_GOAL_RADIUS_BLOCKS}参照）
     */
    record DetailTarget(BlockPos target, int waypointIndex, int goalRadius) {
    }
}
