package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.corridor.CorridorLegSolver;
import net.prason.xaeronav.pathfinding.corridor.CorridorWaypoints;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.xaero.XaeroMapReader;
import net.prason.xaeronav.xaero.XaeroPresence;

/**
 * クライアント側の経路探索状態。
 *
 * <p>{@link #setGoal}/{@link #onClientTick}はクライアントスレッド（メインスレッド）から呼ぶこと。
 * メインスレッドで行うのは{@link ChunkView}の構築（読み込み済みチャンクへの参照集め）だけで、
 * ブロックの読み取りとA*探索はどちらも{@link PathfindingExecutor}のワーカースレッドで行う。
 */
public final class PathfindingState {

    public static final PathfindingState INSTANCE = new PathfindingState();

    private static final Logger LOGGER = LogUtils.getLogger();

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

    /**
     * 打ち切られた経路の末端がこの距離まで近づいたら、その先を計算し直す（ブロック）。
     *
     * <p>探索には数百msかかるうえ結果は次tick以降に反映されるので、末端に着いてから引き直しても
     * そこで案内が一度途切れる。走って到達するまでの時間が計算時間を十分上回る距離を取る。
     */
    private static final double EXTEND_DISTANCE_BLOCKS = 64.0;

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
    private static final double MIN_EXTEND_PROGRESS_BLOCKS = 12.0;

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

    /** 目的地へ近づけないまま終わる探索がこの回数続いたら、詰みと判断する。 */
    private static final int STUCK_SEARCH_STREAK = 4;

    /** 「目的地へ近づけた」と認める最小の距離（ブロック）。測定のゆらぎで判断が揺れないための幅。 */
    private static final double STUCK_PROGRESS_BLOCKS = 8.0;

    /** 詰みと判断したあと、探索を投げ直すまでにプレイヤーが動く距離（ブロック）。 */
    private static final double STUCK_RETRY_MOVE_BLOCKS = 16.0;

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
     * 400,000でも足りず600,000で解けたので、余裕を見て6倍にしてある。
     *
     * <p>時間も同じ倍率で伸ばす。実機の展開速度は毎秒7〜10万ノードなので、532,724ノードには
     * 5〜8秒かかる——2秒のままではノード予算だけ増やしても時間で先に切れる。
     *
     * <p>掛かるのは{@link #plainSearchHopeless}が真の場所だけ。通常の地形では一度も発動しない
     * （＝普段の応答性は変わらない）。
     */
    private static final int DEEP_SEARCH_BUDGET_FACTOR = 6;

    /**
     * 深い予算での探索に許す最長時間（ミリ秒）。倍率だけで決めると、{@code maxExpandedNodes}を
     * 大きくしている環境で1回の探索が分単位になりうる——その間ずっと案内が古いままになる。
     * 15秒は「渡れないよりはマシ」と「待たされている感じ」の折り合いで、実測（53万ノードに
     * 実機換算5〜8秒）に対して余裕を持たせた値。
     */
    private static final long DEEP_SEARCH_MAX_MILLIS = 15_000;

    /**
     * 経路へ合流し直せる最大の距離（ブロック）。これより遠いなら、その経路はもう自分の経路では
     * ないので全部引き直す。{@link #splicePath}参照。
     */
    private static final double SPLICE_MAX_JOIN_BLOCKS = 64.0;

    /**
     * 合流区間の展開ノード数の上限。合流先は{@link #SPLICE_MAX_JOIN_BLOCKS}以内の1点なので、
     * 全体を引き直す探索と同じ予算を与える意味が無い（与えると、合流に失敗したときの待ち時間が
     * 引き直しと同じになり、安く済ませるという目的自体が消える）。
     */
    private static final int SPLICE_MAX_EXPANDED_NODES = 30_000;

    /** 合流に失敗した地点から、これだけ歩けばもう一度試す（ブロック）。 */
    private static final double SPLICE_RETRY_MOVE_BLOCKS = 8.0;

    /**
     * 地上優先ナビ（{@link #shouldClimbToSurface}）に入る深さの下限（ブロック）。
     * 地上のすぐ下は、洞窟の入口も崖もたいてい目と鼻の先にあるので、中継区間を挟むより
     * そのまま目的地を目指した方が短い。数マスのために案内を2段階にする価値はない。
     */
    private static final int MIN_UNDERGROUND_DEPTH = 5;

    /** 地上へ出る経路が見つからなかった地点から、もう一度試すまでに動く距離（ブロック）。 */
    private static final double SURFACE_RETRY_MOVE_BLOCKS = 16.0;

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
    private static final int MIN_DETAIL_REACH_BLOCKS = REFINED_WAYPOINT_MIN_SPACING_BLOCKS;

    private final PathfindingExecutor executor = new PathfindingExecutor();
    // 層2廊下の精緻化専用。executorと共用すると、詳細探索の頻繁な再投入（逸脱・末端接近のたびに
    // 走る）のたびにsubmitが「前のジョブ」を打ち切ってしまい、廊下探索が終わる前に必ず潰れる
    private final PathfindingExecutor corridorExecutor = new PathfindingExecutor();
    // 滑空中の案内。目的地と「いま滑空しているか」はこちらが持ち、その目的地への空中経路だけを
    // 向こうが持つ。非同期結果の鮮度はstillFlyingToで問い合わせてもらう
    private final FlightNavState flight = new FlightNavState(this::stillFlyingTo);
    // clear()・新規setGoal()のたびに増分する。非同期結果を適用する直前にこれと照合し、
    // 一致しなければ「もう古くなったリクエストの結果」として捨てる(clear後に古い結果が
    // currentResultを復活させてしまう競合を防ぐ)。
    private final AtomicLong generation = new AtomicLong();

    private volatile BlockPos goal;
    // 目的地を設定した次元。座標だけを覚えていると、ネザーへ移動したあとも同じ座標を目指してしまう
    private volatile ResourceKey<Level> goalDimension;
    private volatile DisplayedPath displayed;
    private volatile boolean computing;
    private volatile boolean arrived;
    // 地上へ出る経路が出せなかった地点。掘削を切っている・密閉された場所では中継区間そのものが
    // 成立しないので、その付近では地上優先ナビを諦めて本来の目的地へ直接向かう
    private volatile BlockPos surfaceLegFailedAt;
    // 長距離ルートの中間目標。地形は不変なので、目的地が変わらない限り引き直さない
    private volatile CoarseRoute coarseRoute;
    // coarseRouteの各区間を層2廊下（ブロック解像度）で解決し直した精緻版。層1はチャンク平均でしか
    // 地形を見ないため、waypointが実際には崖の上や湖の中を指すことがある。用意でき次第
    // currentRouteWaypoints()がこちらへ差し替わる（発動条件が一つでも欠けたら層1のcoarseRouteへ
    // フォールバックする、という既存の考え方の延長）
    private volatile RefinedRoute refinedRoute;
    // 精緻化がバックグラウンドで完了したことを示す、次tickで拾うためのフラグ（pendingWideRetryと
    // 同じ構造）。whenComplete（ワーカースレッド）で立て、onClientTick（クライアントスレッド）で読む
    private volatile boolean pendingRefinedRouteReady;
    // 精緻化が進行中のcoarseRoute。進行中に長距離ルートを引き直すと、その結果は由来元の不一致で
    // 捨てられる——引き直しの間隔（最短0.5秒）は精緻化（区間ごとに最大300ms）より短くなりうるので、
    // 素通しにすると精緻版が一度も完成しないまま、メインスレッドの地図読みだけを回し続けることになる
    private volatile CoarseRoute refiningRoute;
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

    /** エリトラの滑空を飛行とみなしているか。抜けるときの閾値を下げるためのヒステリシス。 */
    private volatile boolean elytraGliding;
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
     * 目的地へ最も近づけた水平距離（案内できた経路の末端か、プレイヤー自身）。詰みの判定に使う。
     *
     * <p>見るのは「経路が引けたか」ではなく「<b>近づけたか</b>」。予算切れの探索は行き止まりへ
     * 向かう部分経路を毎回返すので、経路の有無で判断すると詰みは一度も検知できない。
     */
    private volatile double bestApproachBlocks = Double.MAX_VALUE;

    /**
     * 直前の通常探索（粗い経由地チェーンではない側）が展開ノード数の上限に当たった地点。
     * {@link #plainSearchHopeless}参照。
     */
    private volatile BlockPos plainBudgetExhaustedAt;

    /** {@link #splicePath}が合流に失敗したときのプレイヤー位置。{@link #SPLICE_RETRY_MOVE_BLOCKS}で失効。 */
    private volatile BlockPos spliceBlockedFrom;

    /** 予算を積んだ探索を次tickで投げ直すか。{@link #pendingCoarseGuideRetry}の一段手前。 */
    private volatile boolean pendingDeepRetry;

    /** {@link #bestApproachBlocks}を縮められないまま終わった探索の連続回数。 */
    private volatile int stalledSearches;

    /** 直近で「前進しなかった」と数えた探索の始点。{@link #noteSearchOutcome}参照。 */
    private volatile BlockPos lastStalledAt;

    /** 目的地へ行けないと判断した理由。判断していない・解消したなら{@code null}。 */
    private volatile StuckReason stuckReason;

    /** 詰みをチャットで1度だけ知らせるための引き継ぎ。ワーカースレッドで立て、次tickで読む。 */
    private volatile StuckReason pendingStuckNotice;

    /**
     * 経路の末端から先へ伸ばせなかった地点。同じ末端で延長を試み続けないための歯止めで、
     * 経路が差し替わる（＝別の末端になる）と自然に外れる。
     */
    private volatile BlockPos extendBlockedAt;

    /** {@link #extendBlockedAt}を立てたときのプレイヤー位置。{@link #EXTEND_RETRY_MOVE_BLOCKS}参照。 */
    private volatile BlockPos extendBlockedFrom;

    /**
     * 「歩いていた経路が使えなくなった」ことを知らせておく残りtick。行き止まり・世界の変化で
     * 手前の経路ごと引き直したときだけ立てる。逸脱は自分で外れただけなので対象にしない。
     */
    private volatile int rerouteNoticeTicks;

    // 直近の探索に使った入力。以下はクライアントスレッドからのみ触る。
    private BlockPos lastStart;
    private int ticksSinceRecalc;
    private int ticksSinceValidation;
    private int arrivedTicks;

    private PathfindingState() {
    }

    public void setGoal(BlockPos goal) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null) {
            return;
        }
        clear();
        this.goal = resolveGoalStandable(level, goal);
        this.goalDimension = level.dimension();
        // 滑空中に指定された目的地は、着地するまで経路を引かない（引いても表示せず捨てるだけになる）
        this.flying = airborne(level, player);
        GoalWaypoint.sync(this.goal);
        if (this.flying) {
            flight.recalculate(this.goal);
        } else {
            recalculate();
        }
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
        if (!level.hasChunkAt(x, z)) {
            BlockPos fromMap = XaeroPresence.mapPresent() ? resolveGoalOnSurface(goal) : null;
            return fromMap != null ? fromMap : goal;
        }
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
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
        this.displayed = null;
        this.lastStart = null;
        this.arrived = false;
        this.surfaceLegFailedAt = null;
        this.coarseRoute = null;
        this.refinedRoute = null;
        this.refiningRoute = null;
        this.pendingRefinedRouteReady = false;
        this.pendingWideRetry = false;
        this.pendingCoarseGuideRetry = false;
        this.pendingDeepRetry = false;
        this.lastAimedWaypoint = null;
        this.passedWaypoints = 0;
        this.bestApproachBlocks = Double.MAX_VALUE;
        this.plainBudgetExhaustedAt = null;
        this.spliceBlockedFrom = null;
        this.stalledSearches = 0;
        this.lastStalledAt = null;
        this.stuckReason = null;
        this.pendingStuckNotice = null;
        this.extendBlockedAt = null;
        this.extendBlockedFrom = null;
        this.rerouteNoticeTicks = 0;
        this.flying = false;
        this.flight.reset();
        this.landingApproachActive = false;
        this.elytraGliding = false;
        this.arrivedTicks = 0;
    }

    public BlockPos goal() {
        return goal;
    }

    public PathResult currentResult() {
        if (flying) {
            return null;
        }
        DisplayedPath shown = displayed;
        return shown == null ? null : shown.result();
    }

    /**
     * 地図描画がその1フレームで必要とするものを、状態を1つにつき1度だけ読んで組む。
     *
     * <p>個別のgetterで埋めてはいけない。経路・目的地・長距離ルート・空中経路はワーカースレッドが
     * それぞれ別のタイミングで差し替えるので、読む順に古い状態と新しい状態が混ざる——
     * 「もう捨てた経路の末端から新しい目的地へ伸びる点線」のような、どの時点にも存在しなかった
     * 組み合わせが1フレームだけ描かれる。{@link MapPathOverlay.Snapshot}が防いでいるのと同じ
     * 食い違いが、1段内側で起きることになる。
     */
    public MapPathOverlay.Snapshot mapOverlaySnapshot(BlockPos playerPos) {
        boolean airborne = flying;
        boolean done = arrived;
        BlockPos currentGoal = goal;
        DisplayedPath shown = displayed;
        FlightRoute route = airborne ? flight.route() : FlightRoute.NONE;

        PathResult ground = airborne || shown == null ? null : shown.result();
        if (ground != null && ground.steps().isEmpty()) {
            ground = null;
        }
        return new MapPathOverlay.Snapshot(ground,
                currentGoal,
                XaeroNavConfig.INSTANCE.straightLineEnabled(),
                XaeroNavConfig.INSTANCE.goalMarkerEnabled() && !GoalWaypoint.placed(),
                playerPos,
                coarseRouteWaypoints(shown, currentGoal, airborne, done),
                route.points(),
                FlightProgress.INSTANCE.segmentFor(route) + 1,
                flight.dashWaypoints(airborne, done, currentGoal));
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
        return stuckReason;
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

    /**
     * 長距離ルート中、現在向かっている中間目標の番号（1始まり）。長距離ルート中でなければ0。
     *
     * <p>経路の末端ではなく<b>プレイヤーがいる区間</b>を答える。先読みで経路が数区間先まで
     * 伸びていると両者はずれ、末端を答えると歩いてもいない先の番号が出る。
     */
    public int coarseRouteWaypointNumber() {
        DisplayedPath shown = displayed;
        if (shown == null || shown.mode() != PathMode.WAYPOINT) {
            return 0;
        }
        int here = shown.waypointIndexAtStep(PathProgress.INSTANCE.indexFor(shown.result()));
        return here >= 0 ? here + 1 : 0;
    }

    /** 長距離ルートの中間目標の総数。長距離ルート中でなければ0。 */
    public int coarseRouteWaypointCount() {
        DisplayedPath shown = displayed;
        if (shown == null || shown.mode() != PathMode.WAYPOINT) {
            return 0;
        }
        return currentRouteWaypoints().size();
    }

    /**
     * Xaero地図へ点線で描くべき中間目標列＝<b>まだ通っていない分だけ</b>。無ければ空リスト。
     *
     * <p>通過済みを含む全体を返すと、粗いルートは目的地が変わらない限り引き直さない設計のため、
     * 点線がいつまでも「ルートを計算した当時の位置」から伸びたままになる。プレイヤーが経路から
     * 大きく外れるほど現在地と点線が食い違い、古いルートが残っているように見える。
     */
    private List<BlockPos> coarseRouteWaypoints(DisplayedPath shown, BlockPos currentGoal,
                                                 boolean airborne, boolean done) {
        // 到着表示の間は目的地ごと残っている（すぐ片付けると「着いた」が見えない）。中継地点は
        // もう案内ではないので、ここで描くと着いた瞬間に来た道へ点線が戻る
        if (airborne || done) {
            return List.of();
        }
        List<BlockPos> all = currentRouteWaypoints(currentGoal);
        if (all.isEmpty()) {
            return all;
        }
        if (shown != null && shown.mode() == PathMode.GOAL && shown.result().complete()) {
            // 詳細経路が本来の目的地まで届いている＝中継地点はもう案内に使っていない。ここで
            // 残りを描くと、先読みの最後の区間が目的地へ届いた瞬間に歩いてきた道へ点線が戻る
            return List.of();
        }
        // 末端が向かっている添字（あれば）と通過済みの目印の大きい方から描く。モードがWAYPOINTで
        // なくなった経路でも通過済みぶんが残らないよう、目印は単調に持っておく
        int from = shown != null && shown.mode() == PathMode.WAYPOINT
                ? Math.max(passedWaypoints, shown.waypointIndex())
                : passedWaypoints;
        if (from <= 0) {
            return all;
        }
        return from >= all.size() ? List.of() : all.subList(from, all.size());
    }

    /**
     * {@link #coarseRoute}を今の目的地に照らして読む。目的地が変わった経緯（直行圏内に戻った・
     * 新しい目的地に切り替わった等）を1つずつ潰すのではなく、読み出し側で常に「今の目的地に対する
     * ルートか」を照合する形にしておくことで、キャッシュの破棄漏れが今後増えても地図に古い点線が
     * 残らない（実際のナビゲーション先はすでに新しい目的地を向いているので、ここが古い値を返しても
     * 実害は「表示だけ」だが、それ自体が過去に踏んだ罠なので構造で防ぐ）。
     */
    private List<BlockPos> currentRouteWaypoints() {
        return currentRouteWaypoints(goal);
    }

    private List<BlockPos> currentRouteWaypoints(BlockPos currentGoal) {
        // detail-target選定(reachableWaypointTarget)とHUD/地図描画(coarseRouteWaypoints等)は
        // 必ず同じリストを共有すること。別リストにするとwaypointIndexが指す先が食い違い、
        // 地図の点線とHUDのカウンタが壊れる
        CoarseRoute route = coarseRoute;
        if (route == null || !route.goal().equals(currentGoal)) {
            return List.of();
        }
        RefinedRoute refined = refinedRoute;
        return refined != null && refined.source() == route ? refined.waypoints() : route.waypoints();
    }

    public void onClientTick() {
        if (rerouteNoticeTicks > 0) {
            rerouteNoticeTicks--;
        }
        BlockPos currentGoal = goal;
        if (currentGoal == null) {
            return;
        }
        GoalWaypoint.sync(currentGoal);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.level.dimension() != goalDimension) {
            // 別の次元へ移った。同じ座標を目指し続けても意味がないので目的地ごと捨てる
            clear();
            return;
        }
        StuckReason notice = pendingStuckNotice;
        if (notice != null) {
            // 判断はワーカースレッドで行われる。チャットへの出力はメインスレッド専用なのでここで拾う
            pendingStuckNotice = null;
            mc.player.displayClientMessage(Component.translatable("hud.xaeronav.unreachable_notice",
                    Component.translatable(stuckHintKey(notice))), false);
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
        PathProgress.INSTANCE.update(shown == null ? null : shown.result(), mc.player.position());
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
                recalculate();
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
            recalculate();
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
        ticksSinceRecalc++;
        ticksSinceValidation++;
        if (stuckReason != null && !stuckRetryDue(mc.player)) {
            // 目的地へ行けないと判断済み。同じ場所から投げ直しても読み込み済みチャンクも地形も
            // 変わっていないので結果は同じ——実機では通常探索10万＋粗い経由地チェーン20万ノードを
            // 3秒おきに焼き続けていた。プレイヤーが動くか、世界が変わりうるだけの時間が経つまで待つ
            return;
        }
        if (pendingEscalation(mc.player)) {
            return;
        }

        if (result == null || result.steps().isEmpty()) {
            retryWithoutRoute(mc.player.blockPosition());
            return;
        }
        // 経路の帯からはみ出したときだけ引き直す。1〜2マス横にずれた程度で作り直すと、
        // そのたびに違う経路が出てきて線が落ち着かない（歩いているだけで案内が変わる）
        if (PathProgress.INSTANCE.distance() > XaeroNavConfig.INSTANCE.deviationThresholdBlocks()) {
            if (ticksSinceRecalc >= MIN_RECALC_INTERVAL_TICKS
                    && !splicePath(mc.level, mc.player, shown, 0)) {
                // 合流できない経路だけ、全部引き直す
                recalculate();
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
            if (shouldExtend(mc.player, shown, mc.options.getEffectiveRenderDistance() * 16)) {
                // 末端から継ぎ足す。着いてから引き直すのでは遅い——探索に数百msかかり、反映は
                // さらに次tick以降なので、その間ずっと「もう終わっている経路」を見せることになる。
                //
                // ここに間隔ゲートを掛けないのは、継ぎ足しが手前の経路を変えないから。全置換だった
                // 頃は頻度を上げるとそのまま案内のちらつきになったが、継ぎ足しにはその副作用が無い
                extendPath(shown);
                return;
            }
            if (reachedPathEnd(mc.player, shown)) {
                // 末端に着いたのに伸ばせていない＝行き止まりか予算切れ。ここで初めて全体を引き直す
                recalculate();
                return;
            }
        }
        if (ticksSinceRecalc >= XaeroNavConfig.INSTANCE.recalcIntervalTicks()
                && !result.complete() && nearPathEnd(mc.player.position(), result)
                && retryTruncatedNow(mc.player)) {
            // 打ち切られた末端に近づいた。ここから先は新しく読み込まれたチャンクを使って伸ばせる
            recalculate();
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
            // 関係が無いうえ、そこから走査すると背後の変化で止まって先の変化を見落とす
            PathValidator.Failure failure = PathValidator.firstFailureFrom(mc.level, result,
                    PathProgress.INSTANCE.indexFor(result));
            if (failure != null) {
                handleBlockedPath(mc.level, mc.player, shown, failure);
            }
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
        if (splicePath(level, player, shown, failure.stepIndex() + 1)) {
            // 迂回はHUDで知らせない。合流点から先はそのまま残るので「歩いていた道が突然消えた」
            // ことにはならず、橋を架けながら置くたびに警告が出続けるだけになる
            LOGGER.info("XaeroNav: 経路上のセルが変化したため塞がった箇所を迂回します ({})", failure.reason());
            return;
        }
        // 迂回できずに全部引き直す。案内が急に変わる理由が分からないままなので、変わったこと自体を
        // 知らせる
        LOGGER.info("XaeroNav: 経路上のセルが変化したため引き直します ({})", failure.reason());
        rerouteNoticeTicks = REROUTE_NOTICE_TICKS;
        recalculate();
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
        if (stuckReason != null && !stuckRetryDue(player)) {
            return false;
        }
        if (pendingWideRetry) {
            // 通常マージンでは届かなかった。範囲を広げて投げ直す（間隔を空ける必要はない —
            // 広い範囲での探索は目的地ごとに一度だけで、失敗しても二度目のpendingWideRetryは立たない）
            pendingWideRetry = false;
            recalculate(Escalation.WIDE);
            return true;
        }
        if (pendingDeepRetry) {
            // 展開ノード数の上限に当たって未到達だった。まずは予算を積んで投げ直す——実測では
            // 区間分割より単発探索に予算を与える方が確実だった（DEEP_SEARCH_BUDGET_FACTOR参照）。
            // 予約として持つのが要点で、これが無いと深い探索は他のトリガー（逸脱・末端への接近）が
            // たまたま引かれるまで走らない
            pendingDeepRetry = false;
            recalculate(Escalation.DEEP);
            return true;
        }
        if (pendingCoarseGuideRetry) {
            // 展開ノード数の上限に当たって未到達だった。範囲を広げても同じ上限に当たるだけなので、
            // 代わりに粗い経由地チェーンで区間を分割して投げ直す
            pendingCoarseGuideRetry = false;
            recalculate(Escalation.COARSE_GUIDED);
            return true;
        }
        if (pendingRefinedRouteReady) {
            // 層2廊下による精緻化がバックグラウンドで終わった。まだ層1ベースのwaypointへ
            // 向かっていれば、精緻版へ切り替えるために引き直す
            pendingRefinedRouteReady = false;
            recalculate();
            return true;
        }
        return false;
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
        if (!shouldClimbToSurface(level, player.blockPosition(), currentGoal,
                XaeroNavConfig.INSTANCE.groundLevelY())) {
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
        stuckReason = null;
        pendingStuckNotice = null;
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 0.4f, 1.5f);
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

    /** 詰みと判断したあとで、もう一度探索を投げてよい頃合いか。{@link #STUCK_RETRY_MOVE_BLOCKS}参照。 */
    private boolean stuckRetryDue(Player player) {
        BlockPos start = lastStart;
        return start == null
                || start.distSqr(player.blockPosition()) >= STUCK_RETRY_MOVE_BLOCKS * STUCK_RETRY_MOVE_BLOCKS
                || ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS;
    }

    /**
     * この探索の結果を詰みの判定へ反映する。詰みは「<b>狙った先へ届きもせず、目的地へ近づきも
     * しなかった</b>探索」が{@link #STUCK_SEARCH_STREAK}回続いたこと、と定義する。
     *
     * <p>経路が引けたかどうかでは判定できない。予算切れの探索は行き止まりへ向かう部分経路を毎回
     * 返すので、実機ログではステップ数55→23→5→18→93→0…が5分間続く間ずっと同じ溶岩の海の縁に
     * 居た。逆に「近づいたか」だけで見ると、溶岩の海を大きく迂回する区間（目的地から遠ざかりながら
     * 正しく進んでいる）を詰みと誤判定する——そこでは探索は狙った中間目標へ<b>届いている</b>ので、
     * 2つを併せて初めて正しく切り分けられる。
     *
     * <p>近さの測り方にプレイヤー自身の位置も入れる。部分経路を辿って歩いて前進するのも正常な
     * 進み方なので、その間に投げた探索が何回失敗していようと詰みではない。
     *
     * <p><b>連続として数えるのは、ほぼ同じ場所から投げた探索だけ</b>（{@link #STUCK_RETRY_MOVE_BLOCKS}）。
     * 詰みの根拠は「同じ実験を繰り返しても結果が変わらない」ことなので、始点が動いていれば
     * 別の実験——読み込み済みチャンクも層1の地図も変わり、実際に結果が変わりうる。実機
     * （ジ・エンドの崖ぎわ、06:36）では、プレイヤーが崖に沿って26ブロック行き来する間の失敗が
     * 連続として数えられ「行けません」が出たが、その16秒後に橋49本で渡り切っている。
     */
    private void noteSearchOutcome(BlockPos start, BlockPos planEnd, PathResult result) {
        BlockPos currentGoal = goal;
        if (currentGoal == null) {
            return;
        }
        DisplayedPath shown = displayed;
        if (shown != null && shown.mode() != PathMode.TO_SURFACE && shown.result().complete()
                && !shown.result().steps().isEmpty()) {
            // 完走した経路が出ている＝ここから先へ実際に歩ける。中間目標へ向かう探索がその先で
            // 何回失敗しようと、歩ける経路がある間は詰みではない。実機（22:42）では、110ステップ・
            // 橋47本の経路を表示したまま「目的地へ行けません」が出ていた
            stalledSearches = 0;
            stuckReason = null;
            return;
        }
        double approach = Math.min(horizontalDistance(start, currentGoal),
                horizontalDistance(planEnd, currentGoal));
        // 高水位がSTUCK_PROGRESS_BLOCKSを切ったら、そこから更にその幅ぶん近づいた探索は
        // 原理的に出せない（距離は0未満にならない）。一度でも目的地のそばまで届いた目的地では
        // 以後どんな探索も前進と認められず、未到達がSTUCK_SEARCH_STREAK回続くだけで
        // 「行けません」になる——改善しえない値を歯止めに使うと永久に外れない
        boolean improvable = bestApproachBlocks >= STUCK_PROGRESS_BLOCKS;
        boolean progressed = result.complete() || !improvable
                || approach <= bestApproachBlocks - STUCK_PROGRESS_BLOCKS;
        bestApproachBlocks = Math.min(bestApproachBlocks, approach);
        if (progressed) {
            stalledSearches = 0;
            stuckReason = null;
            return;
        }
        BlockPos previouslyStalledAt = lastStalledAt;
        boolean sameSpot = previouslyStalledAt != null
                && previouslyStalledAt.distSqr(start) < STUCK_RETRY_MOVE_BLOCKS * STUCK_RETRY_MOVE_BLOCKS;
        stalledSearches = sameSpot ? stalledSearches + 1 : 1;
        lastStalledAt = start;
        if (stalledSearches < STUCK_SEARCH_STREAK || stuckReason != null) {
            return;
        }
        stuckReason = classifyStuck(result.termination());
        pendingStuckNotice = stuckReason;
        LOGGER.info("XaeroNav: 目的地へ行けないと判断しました (理由={}, 最接近={}ブロック, 目的地={})",
                stuckReason, Math.round(bestApproachBlocks), currentGoal.toShortString());
    }

    /**
     * 詰みの理由を、確度の高い順に見て決める。
     *
     * <p>層1（Xaeroの地図）で目的地まで繋がっていないことが最も情報量が多い——<b>この判定に使う
     * ルートは梯子の最終段（{@code BridgePolicy.BRIDGE}）まで試したもの</b>で、そこでは溶岩の海も
     * 奈落も橋を架ける前提で通れることになっており、未探索セルも通行可能として扱われる。それでも
     * 届かないなら、詳細探索をいくら回しても届かない。次に確かなのが{@code EXHAUSTED}（探索範囲の
     * 中に到達手段が無いことの証明）で、残りは資源不足。
     */
    private StuckReason classifyStuck(PathResult.Termination termination) {
        CoarseRoute route = coarseRoute;
        if (route != null && route.goal().equals(goal) && !route.reachedGoal()) {
            return StuckReason.UNMAPPED;
        }
        return termination == PathResult.Termination.EXHAUSTED
                ? StuckReason.NO_WAY_THROUGH
                : StuckReason.SEARCH_TOO_HARD;
    }

    private void retryWithoutRoute(BlockPos start) {
        if (ticksSinceRecalc < XaeroNavConfig.INSTANCE.recalcIntervalTicks()) {
            return;
        }
        boolean moved = lastStart == null
                || lastStart.distSqr(start) >= RETRY_MOVE_BLOCKS * RETRY_MOVE_BLOCKS;
        if (moved || ticksSinceRecalc >= NO_ROUTE_RETRY_TICKS) {
            recalculate();
        }
    }

    /**
     * いま経路を末端から継ぎ足すべきか。
     *
     * <p>深い先読み（{@code deepLookAheadEnabled}）では<b>末端が読み込み済みチャンクの縁に届くまで</b>
     * 伸ばし続ける。マジックナンバーを置かずに済むうえ、歩けば新しいチャンクが読まれてまた伸びるので、
     * そのまま「進むほど先が見える」になる。伸ばし切ったら自然に止まる。
     *
     * <p>浅い先読みでは従来どおり{@link #EXTEND_DISTANCE_BLOCKS}手前から。ただしこの値は経路が
     * 数百ブロックある地上世界を前提にしており、{@code detailReach}が縮む次元（ネザーの実測で24）
     * では経路長より長くなって「常に手前」＝先読みとして機能しない。経路長そのものを下限に使う。
     */
    private boolean shouldExtend(Player player, DisplayedPath shown, int renderRadius) {
        PathResult result = shown.result();
        if (!extendableTail(result)) {
            return false;
        }
        List<PathStep> steps = result.steps();
        BlockPos end = steps.get(steps.size() - 1).pos();
        if (end.equals(goal) || extendBlocked(player, end)) {
            return false;
        }
        if (XaeroNavConfig.INSTANCE.deepLookAheadEnabled()) {
            // 案内として意味のある長さぶん読み込み済みの土地が残っているときだけ伸ばす。
            // renderRadiusぎりぎりまで許すと、目標が読み込み済み正方形の外へ出る（extendLeadを参照）
            return extendLead(player, end, renderRadius) >= MIN_DETAIL_REACH_BLOCKS;
        }
        double lead = Math.min(EXTEND_DISTANCE_BLOCKS, pathLength(steps));
        return distanceTo(player.position(), end) <= lead;
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
        return renderRadius - (int) Math.round(horizontalDistance(player.blockPosition(), end));
    }

    /**
     * この末端は「伸ばせなかった」印が立っていて、まだ失効していないか。
     * {@link #EXTEND_RETRY_MOVE_BLOCKS}ぶん歩けば新しいチャンクが読まれるので、そこで印を捨てる。
     */
    private boolean extendBlocked(Player player, BlockPos end) {
        if (!end.equals(extendBlockedAt)) {
            return false;
        }
        BlockPos blockedFrom = extendBlockedFrom;
        if (blockedFrom != null && blockedFrom.distSqr(player.blockPosition())
                > EXTEND_RETRY_MOVE_BLOCKS * EXTEND_RETRY_MOVE_BLOCKS) {
            extendBlockedAt = null;
            extendBlockedFrom = null;
            return false;
        }
        return true;
    }

    /** 再挑戦の予約と今回のゴールが「同じ場所」か。{@link #RETRY_TARGET_TOLERANCE_BLOCKS}参照。 */
    private static boolean sameRetryTarget(BlockPos target, BlockPos reserved) {
        return reserved != null && horizontalDistance(target, reserved) <= RETRY_TARGET_TOLERANCE_BLOCKS;
    }

    /** 経路の端から端までの直線距離。先読みの余裕を経路長より長く取らないための目安。 */
    private static double pathLength(List<PathStep> steps) {
        BlockPos first = steps.get(0).pos();
        BlockPos last = steps.get(steps.size() - 1).pos();
        return Math.sqrt(first.distSqr(last));
    }

    /** 経路が実際に届いた地点。1歩も進めなかったときは始点そのもの。 */
    private static BlockPos endOf(PathResult result, BlockPos start) {
        List<PathStep> steps = result.steps();
        return steps.isEmpty() ? start : steps.get(steps.size() - 1).pos();
    }

    private static double distanceTo(Vec3 position, BlockPos pos) {
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
            if (cursor.getY() < level.getMinBuildHeight()) {
                break;
            }
            if (CellData.standable(CellData.flagsOf(level.getBlockState(cursor)))) {
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
     * <p>エリトラ（{@code isFallFlying}）だけは足元の高さを見る。装備したまま連続ジャンプしていると
     * 1tickだけ滑空判定が立ち、その瞬間に地上の経路を捨てて空中へ切り替わる——着地した次のtickで
     * 戻るので、跳ねるたびに経路が丸ごと作り直されていた。切り替えの代償が大きい
     * （{@code generation}を進めて走っている探索ごと捨てる）ので、判定そのものを鈍くするのが正しい。
     */
    private boolean airborne(Level level, Player player) {
        if (player.getAbilities().flying) {
            elytraGliding = false;
            return true;
        }
        if (!player.isFallFlying()) {
            elytraGliding = false;
            return false;
        }
        int required = XaeroNavConfig.INSTANCE.elytraFlyingMinGroundClearanceBlocks();
        if (required <= 0) {
            elytraGliding = true;
            return true;
        }
        // 入りと抜けで閾値を変える。同じ高さで判定すると、境界の上を滑空している間ずっと
        // 飛行と歩行を往復し、そのたびに経路が作り直される（landingApproachと同じ形）
        int clearance = groundClearance(level, player);
        elytraGliding = clearance >= (elytraGliding ? Math.max(1, required / 2) : required);
        return elytraGliding;
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
    private static int detailHorizon(int renderRadius) {
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

    private void recalculate() {
        recalculate(Escalation.NONE);
    }

    private void recalculate(Escalation forced) {
        ticksSinceRecalc = 0;
        ticksSinceValidation = 0;
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

        int groundLevel = XaeroNavConfig.INSTANCE.groundLevelY();
        boolean climbing = shouldClimbToSurface(level, start, currentGoal, groundLevel);
        int renderRadius = mc.options.getEffectiveRenderDistance() * 16;
        // 判定はメインスレッドでしかできない（ワールドの参照・経路への対応づけ）。結果が返る頃には
        // 別の判断材料になってしまうので、投げる時点の答えを写し取ってワーカーへ渡す
        DisplayedPath worthKeeping = pathWorthKeeping(level, player);

        // 地上優先ナビが最優先（逆にすると地中で長距離の中間目標へ掘り進んでしまう）。
        // それ以外は、目的地が描画距離の外にあるときだけ長距離ルートの中間目標を挟む
        PathMode mode;
        BlockPos target;
        int waypointIndex;
        int goalRadius;
        if (climbing) {
            mode = PathMode.TO_SURFACE;
            // 地上優先ナビ中は、遠い本来の目的地の箱に広げても意味が無い（ゴールが1点ではなく
            // 「空の下ならどこでも」なので）。垂直方向はgroundLevelまで確実に届くよう、同じ列で
            // groundLevelにある仮想ゴールとして範囲を組み立てる
            target = new BlockPos(start.getX(), groundLevel, start.getZ());
            waypointIndex = -1;
            // searchToSurfaceが自前の領域ゴール（y >= surfaceY）を使うので、この値は読まれない
            goalRadius = 0;
        } else {
            DetailTarget detail = selectDetailTarget(start, currentGoal, renderRadius,
                    detailHorizon(renderRadius), boatAvailable, true, -1);
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
        int horizontalMargin = XaeroNavConfig.INSTANCE.searchHorizontalMargin();
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
        SearchBounds bounds = SearchBounds.around(level, start, target,
                horizontalMargin, verticalSearchMargin(level, wideSearch),
                renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.movementOptions());

        SearchLimits limits = XaeroNavConfig.INSTANCE.searchLimits();
        boolean deepBudget = !climbing && (forced == Escalation.DEEP || plainSearchHopeless(start));
        if (deepBudget) {
            // ここは通常の予算では解けないと分かっている。<b>区間分割へ逃がすのではなく予算を積む。</b>
            // 実測（RealEndTerrainTest、実機の保存データ）では、区間分割は同じ地形で倍のノードを
            // 使ったうえに遅く、素直に予算を与えた単発探索の方が確実だった
            // （直接600,000で到達532,724ノード / 区間分割は800,000必要で628,593ノード）
            limits = new SearchLimits(limits.maxExpandedNodes() * DEEP_SEARCH_BUDGET_FACTOR,
                    Math.min(DEEP_SEARCH_MAX_MILLIS, limits.timeLimitMillis() * DEEP_SEARCH_BUDGET_FACTOR),
                    limits.heuristicWeight());
        }
        long myGeneration = generation.incrementAndGet();
        computing = true;
        PathMode finalMode = mode;
        BlockPos finalTarget = target;
        int finalWaypointIndex = waypointIndex;
        boolean finalWideSearch = wideSearch;
        boolean finalCoarseGuided = coarseGuided;
        boolean finalDeepBudget = deepBudget;
        // 次元はメインスレッドで確定させる。whenCompleteはワーカースレッドで走るうえ、
        // そこではプレイヤーが既に別次元へ移動している可能性がある
        ResourceKey<Level> searchDimension = level.dimension();
        CompletableFuture<PathResult> future;
        boolean costToGoGuideEnabled = XaeroNavConfig.INSTANCE.costToGoGuideEnabled();
        if (climbing) {
            future = executor.submitToSurface(view.withoutDigging(), view, start, groundLevel, limits);
        } else if (coarseGuided) {
            future = executor.submitCoarseGuided(view, bounds, start, finalTarget, limits, costToGoGuideEnabled,
                    goalRadius);
        } else {
            future = executor.submit(view, start, finalTarget, limits, costToGoGuideEnabled, goalRadius);
        }
        future.whenComplete((result, error) -> {
            if (generation.get() != myGeneration) {
                // 追い越された古いリクエスト。computingは今走っているリクエストのものなので触らない
                return;
            }
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
                LOGGER.info("XaeroNav: 粗い経由地チェーンで再挑戦しました"
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
                displayed = new DisplayedPath(new PathResult(List.of(), result.termination(),
                        result.expandedNodes(), result.distinctNodes()), PathMode.TO_SURFACE, -1);
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
                boolean needsCoarseGuideRetry = !result.complete() && budgetExhausted && !finalCoarseGuided
                        && finalDeepBudget && retryTargetInBox;
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
                    noteSearchOutcome(start, endOf(result, start), result);
                }
            }
            if (!result.complete() && worthKeeping != null && displayed == worthKeeping) {
                // 完走した経路は「ここからそこまで実際に歩ける」という証明で、未到達の結果は
                // その証明を持たない。証明を持たないもので上書きしない（pathWorthKeeping参照）
                LOGGER.info("XaeroNav: 完走した経路を残しました (表示中={}ステップ, 新しい結果={}ステップ, {})",
                        worthKeeping.result().steps().size(), result.steps().size(), result.termination());
                return;
            }
            // 新しい経路に対する合流可否は測り直しになる。前の経路で失敗した記録は持ち越さない
            spliceBlockedFrom = null;
            displayed = new DisplayedPath(result, finalMode, finalWaypointIndex);
        });
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
        if (!result.complete() || result.steps().isEmpty()) {
            return null;
        }
        // 完走した経路を手放す判断は、この経路が二度と引き当てられない可能性を伴う。手放した
        // 理由が残っていないと、案内が悪くなったときに「どのトリガーが壊したのか」を実機ログから
        // 特定できない（再計算そのものの理由はどこにも出ていない）。頻度は完走した経路を
        // 持っている間の再計算だけなので、debugゲート無しでも並ばない
        boolean tracked = PathProgress.INSTANCE.tracking(result);
        double offPath = tracked ? PathProgress.INSTANCE.distance() : distanceToPath(result, player.position());
        String dropped = null;
        // 「地形が変わった」は内訳まで出す。どのステップの何が不成立になったのかが分からないと、
        // 渡り切った直後に完走ルートが手放されるような症状の原因を追えない
        PathValidator.Failure validationFailure = null;
        if (offPath > XaeroNavConfig.INSTANCE.deviationThresholdBlocks()) {
            dropped = "逸脱";
        } else if (reachedPathEnd(player, shown)) {
            dropped = "終端に到着";
        } else if ((validationFailure = PathValidator.firstFailureFrom(level, result,
                PathProgress.INSTANCE.indexFor(result))) != null) {
            // もう歩き終えた区間の変化では手放さない。渡ってきた橋を後ろから壊しても、
            // これから通る道が使えることの証明は失われない
            dropped = "地形が変わった";
        }
        if (dropped != null) {
            LOGGER.info("XaeroNav: 完走した経路を手放しました"
                            + " (理由={}, {}ステップ, 経路までの距離={}, 対応づけ={}, 現在地={}, 経路の先頭={}{})",
                    dropped, result.steps().size(), Math.round(offPath), tracked,
                    player.blockPosition().toShortString(), result.steps().get(0).pos().toShortString(),
                    validationFailure == null ? "" : ", " + validationFailure.reason());
            return null;
        }
        return shown;
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
    private static int joinableStepIndex(Level level, List<PathStep> steps, Vec3 position, int minIndex) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = Math.max(0, minIndex); i < steps.size(); i++) {
            PathStep step = steps.get(i);
            if (step.bridging()) {
                continue;
            }
            double distance = distanceTo(position, step.pos());
            if (distance >= bestDistance) {
                continue;
            }
            if (PathValidator.stepFailure(level, step, i) != null) {
                continue;
            }
            bestDistance = distance;
            best = i;
        }
        return best;
    }

    /**
     * 経路の帯から外れた。<b>経路そのものが生きているなら、全部引き直さずに、いまの位置から
     * その経路へ合流する区間だけを探す。</b>投げたなら{@code true}（結果は非同期で反映される）。
     *
     * <p>引き直しはこの経路を捨てることを意味する。詳細探索のゴールは再計算のたびに揺れるので、
     * 同じ経路をもう一度引き当てられる保証は無い——実機（エンドの島渡り）では、橋47本を含む
     * 110ステップの完走ルートが逸脱のたびに捨てられ、次の探索は30万ノードを焼いて未到達に
     * 終わっていた。合流区間は{@link #SPLICE_MAX_JOIN_BLOCKS}以内の1点が相手なので桁違いに安く、
     * 成功すれば高い経路（橋・掘削）をそのまま持ち越せる。
     *
     * <p>合流点は<b>最も近いステップ</b>にして、その手前は捨てる。歩いて先へ進んでいた場合も
     * これで正しく前へ詰む（通り過ぎた区間が残らない）。合流に失敗したときは
     * {@link #SPLICE_RETRY_MOVE_BLOCKS}ぶん歩くまで再挑戦せず、呼び出し側の引き直しに任せる。
     *
     * @param minJoinIndex 合流点として認める最小の添字。塞がった箇所を迂回するときは、そこより
     *                     先へ合流しないと同じ場所へ戻ってしまうので、その次を渡す
     */
    private boolean splicePath(Level level, Player player, DisplayedPath shown, int minJoinIndex) {
        if (shown.mode() == PathMode.TO_SURFACE) {
            return false;
        }
        PathResult result = shown.result();
        if (!result.complete() || result.steps().isEmpty()) {
            // 未到達の経路は「その先へ行ける」という保証を持たない。合流しても得るものが無い
            return false;
        }
        BlockPos currentGoal = this.goal;
        BlockPos playerAt = player.blockPosition();
        BlockPos blocked = spliceBlockedFrom;
        if (currentGoal == null
                || (blocked != null
                        && blocked.distSqr(playerAt) < SPLICE_RETRY_MOVE_BLOCKS * SPLICE_RETRY_MOVE_BLOCKS)) {
            return false;
        }
        int joinIndex = joinableStepIndex(level, result.steps(), player.position(), minJoinIndex);
        if (joinIndex < 0) {
            return false;
        }
        BlockPos joinPos = result.steps().get(joinIndex).pos();
        if (distanceTo(player.position(), joinPos) > SPLICE_MAX_JOIN_BLOCKS) {
            return false;
        }
        // 見るのは合流点から先だけ。手前は捨てる区間なので、そこの変化を理由に諦めると、
        // 迂回すれば繋がる経路まで呼び出し側の全引き直しへ落ちる。
        // ここで無効と分かって黙ってfalseを返すと、なぜ合流を諦めたのかがどこにも残らない
        PathValidator.Failure failure = PathValidator.firstFailureFrom(level, result, joinIndex);
        if (failure != null) {
            LOGGER.info("XaeroNav: 経路上のセルが変化していたため合流を諦めました ({})", failure.reason());
            return false;
        }

        int renderRadius = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        SearchBounds bounds = SearchBounds.around(level, playerAt, joinPos,
                XaeroNavConfig.INSTANCE.searchHorizontalMargin(), verticalSearchMargin(level, false),
                renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.movementOptions());
        SearchLimits full = XaeroNavConfig.INSTANCE.searchLimits();
        SearchLimits limits = new SearchLimits(Math.min(full.maxExpandedNodes(), SPLICE_MAX_EXPANDED_NODES),
                full.timeLimitMillis(), full.heuristicWeight());

        long myGeneration = generation.incrementAndGet();
        computing = true;
        // 合流点は実際に歩けるセル（この経路が通っている）なので、半径を与えずぴったり狙う。
        // 半径で緩めると別のセルに着いてしまい、そこから先の区間が繋がらない
        executor.submit(view, playerAt, joinPos, limits, XaeroNavConfig.INSTANCE.costToGoGuideEnabled(), 0)
                .whenComplete((splice, error) -> {
            if (generation.get() != myGeneration) {
                return;
            }
            computing = false;
            if (error != null) {
                if (!(error instanceof CancellationException)) {
                    LOGGER.error("XaeroNav: 経路への合流に失敗しました", error);
                }
                return;
            }
            if (displayed != shown || !currentGoal.equals(goal)) {
                return;
            }
            if (!splice.complete() || splice.steps().isEmpty()) {
                spliceBlockedFrom = playerAt;
                LOGGER.info("XaeroNav: 経路へ合流できませんでした ({}, 合流点={}, 展開ノード数={})",
                        splice.termination(), joinPos.toShortString(), splice.expandedNodes());
                return;
            }
            spliceBlockedFrom = null;
            displayed = spliced(shown, splice, joinIndex);
            LOGGER.info("XaeroNav: 経路へ合流しました (合流までの{}ステップ, 引き継いだ{}ステップ, 展開ノード数={})",
                    splice.steps().size(), result.steps().size() - joinIndex - 1, splice.expandedNodes());
        });
        return true;
    }

    /**
     * 合流区間と、合流点から先の既存の経路を1本に繋ぐ。合流点より手前の区間は捨てる。
     *
     * <p>打ち切り理由は<b>元の経路のもの</b>を引き継ぐ。合流区間は合流点へ届いた（そうでなければ
     * 繋がない）ので、この経路が狙った先まで届くかどうかを決めているのは元の経路の側。
     */
    private static DisplayedPath spliced(DisplayedPath shown, PathResult splice, int joinIndex) {
        List<PathStep> steps = shown.result().steps();
        List<PathStep> merged = new ArrayList<>(splice.steps());
        merged.addAll(steps.subList(joinIndex + 1, steps.size()));
        // 合流点より手前が消えたぶんだけ、区間の境目の添字がずれる
        int shift = splice.steps().size() - (joinIndex + 1);
        List<PathSegment> segments = new ArrayList<>();
        for (PathSegment segment : shown.segments()) {
            if (segment.endStep() > joinIndex) {
                segments.add(new PathSegment(segment.endStep() + shift, segment.waypointIndex()));
            }
        }
        if (segments.isEmpty()) {
            segments.add(new PathSegment(merged.size() - 1, shown.waypointIndex()));
        }
        PathResult combined = new PathResult(List.copyOf(merged), shown.result().termination(),
                splice.expandedNodes(), splice.distinctNodes());
        return new DisplayedPath(combined, shown.mode(), shown.waypointIndex(), List.copyOf(segments));
    }

    /**
     * いま表示している経路を、その<b>末端から</b>次の区間ぶん伸ばす（プレイヤーからではない）。
     *
     * <p>{@link #recalculate}との違いはそこだけだが、結果は大きく変わる。プレイヤーから引き直すと
     * すでに歩いている手前側まで毎回作り直され、目標が少し動くだけで案内全体が描き変わる
     * （{@link #updateDetailReach}が言う「行ったり来たりして見える」の主因）。末端から継ぎ足せば
     * 手前は定義上そのまま残り、探索は必ず新しい土地だけを見る。
     *
     * <p>区間ごとに解くこと自体は元々そうで、大局的な最適性は層1の粗いルートが持っている。
     * だから継ぎ足しで失うものは無い——むしろ「毎回プレイヤーから、動く目標へ」引き直す方が
     * ジグザグを生む。
     *
     * <p>継ぎ足しの<b>元</b>になれるのは末端が目標に到達した経路だけ（{@link #shouldExtend}）。
     * 未到達の末端から更に伸ばすのは行き止まりの続きを掘ることになるので、既存の再挑戦
     * （範囲拡大・粗い経由地チェーン）に任せる。一方で継ぎ足した<b>結果</b>が未到達だった場合は、
     * そこまで引けたぶんを繋ぐ——{@link #recalculate}が暫定経路をそのまま見せるのと同じ扱いで、
     * 合成後の{@code complete}がfalseになることで次からは自然に上のトリガーへ引き継がれる。
     */
    private void extendPath(DisplayedPath shown) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        BlockPos currentGoal = this.goal;
        if (level == null || player == null || currentGoal == null) {
            return;
        }
        List<PathStep> steps = shown.result().steps();
        BlockPos from = steps.get(steps.size() - 1).pos();
        boolean boatAvailable = ChunkView.boatAvailable(player);
        int renderRadius = mc.options.getEffectiveRenderDistance() * 16;
        // 継続はワーカースレッドで走るので、プレイヤー・次元はここで写し取ってから渡す
        BlockPos playerAt = player.blockPosition();
        ResourceKey<Level> searchDimension = level.dimension();

        // 末端基準の上限は、プレイヤー中心の読み込み済み正方形の「残り」で切る（extendLead参照）
        int lead = extendLead(player, from, renderRadius);
        // 探索の地平と、読み込み済みチャンクの残りの小さい方。どちらも地形の実測ではないので、
        // かつての detailReach のように成功／失敗で振動することがない
        int reach = Math.min(detailHorizon(renderRadius), lead);
        DetailTarget detail = selectDetailTarget(from, currentGoal, lead, reach, boatAvailable, false,
                shown.waypointIndex());
        BlockPos target = detail.target();
        if (target.equals(from) || horizontalDistance(from, target) > lead) {
            // これ以上伸ばす先が無いか、伸ばす先が読み込み済みチャンクの外（中間目標が1つも
            // 残りの中に無いとselectDetailTargetは本来の目的地へフォールバックする）。
            // 歯止めを立てないと、shouldExtendが毎tick真を返し続け、そのたびに
            // selectDetailTarget（＝メインスレッドの地図読み）を回すことになる
            blockExtend(from, playerAt);
            return;
        }

        SearchBounds bounds = SearchBounds.around(level, from, target,
                XaeroNavConfig.INSTANCE.searchHorizontalMargin(),
                verticalSearchMargin(level, false), renderRadius);
        ChunkView view = ChunkView.capture(level, player, bounds, XaeroNavConfig.INSTANCE.movementOptions());
        SearchLimits limits = XaeroNavConfig.INSTANCE.searchLimits();

        long myGeneration = generation.incrementAndGet();
        computing = true;
        boolean reachesGoal = target.equals(currentGoal);
        int newWaypointIndex = reachesGoal ? -1 : detail.waypointIndex();
        boolean costToGoGuideEnabled = XaeroNavConfig.INSTANCE.costToGoGuideEnabled();
        executor.submit(view, from, target, limits, costToGoGuideEnabled, detail.goalRadius())
                .whenComplete((result, error) -> {
            if (generation.get() != myGeneration) {
                return;
            }
            computing = false;
            if (error != null) {
                if (!(error instanceof CancellationException)) {
                    LOGGER.error("XaeroNav: 経路の延長に失敗しました", error);
                }
                return;
            }
            // 継ぎ足す先が入れ替わっていたら捨てる。世代が同じでも、目的地の変更や逸脱で
            // displayedごと差し替わっていることがある
            DisplayedPath current = displayed;
            if (current != shown || !currentGoal.equals(goal)) {
                return;
            }
            logSearchReach(from, target, result);
            List<PathStep> tail = result.steps();
            if (result.complete()) {
                // 継ぎ足しが狙った先まで届いた＝前へ出られている。詰みの目印はここで落とす。
                // 届かなかったことは逆に詰みの根拠にしない——継ぎ足しの失敗はたいていその先が
                // まだ未ロードなだけで、表示中の経路はそのまま歩ける。「ここから目的地へ行けるか」
                // に答えているのはプレイヤーから引き直す側（recalculate）だけ
                noteSearchOutcome(playerAt, endOf(result, from), result);
            }
            if (tail.isEmpty()) {
                // 1歩も進めなかった。手前の経路はそのまま残し、通常の再計算に委ねる
                blockExtend(from, playerAt);
                return;
            }
            if (!result.complete()
                    && horizontalDistance(from, tail.get(tail.size() - 1).pos()) < MIN_EXTEND_PROGRESS_BLOCKS) {
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
            displayed = append(current, result, newWaypointIndex, reachesGoal);
            extendBlockedAt = null;
            extendBlockedFrom = null;
        });
    }

    /**
     * この末端からは伸ばせなかった、と記録する。{@link #EXTEND_RETRY_MOVE_BLOCKS}ぶん歩けば失効する。
     *
     * <p>ここで「経路を引き直しました」の通知は出さない。手前の経路は1ブロックも変わっておらず、
     * ユーザーから見て変化が無いのに警告だけ点滅することになる。
     */
    private void blockExtend(BlockPos end, BlockPos playerAt) {
        extendBlockedAt = end;
        extendBlockedFrom = playerAt;
    }

    /**
     * 継ぎ足した経路を組み立てる。ステップ列は連結し、区間の境目を記録する。
     *
     * <p>{@link PathProgress}へ引き継ぎを伝えるのはここ。継ぎ足しは手前の添字を変えないので
     * 対応づけはそのまま通用するが、伝えないと別経路とみなされて全体走査に落ちる。
     */
    private static DisplayedPath append(DisplayedPath current, PathResult tail, int tailWaypointIndex,
                                         boolean reachesGoal) {
        List<PathStep> merged = new ArrayList<>(current.result().steps());
        merged.addAll(tail.steps());
        // completeは「この経路が狙った先まで届いたか」であって「最終目的地に着いたか」ではない
        // （中間目標へ向かう経路も、その中間目標に届いていればcomplete）。ここを reachesGoal に
        // すると、継ぎ足した瞬間に未到達扱いになってshouldExtendが止まり、1回しか伸びなくなる
        PathResult combined = new PathResult(List.copyOf(merged), tail.termination(),
                tail.expandedNodes(), tail.distinctNodes());
        List<PathSegment> segments = new ArrayList<>(current.segments());
        segments.add(new PathSegment(merged.size() - 1, tailWaypointIndex));
        PathProgress.INSTANCE.carryOver(combined);
        return new DisplayedPath(combined, reachesGoal ? PathMode.GOAL : PathMode.WAYPOINT,
                tailWaypointIndex, List.copyOf(segments));
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
                                             int minWaypointIndex) {
        if (horizontalDistance(start, currentGoal) <= reach || !XaeroPresence.mapPresent()) {
            return new DetailTarget(currentGoal, -1, 0);
        }
        DetailTarget target = reachableWaypointTarget(start, currentGoal,
                cachedOrFreshRoute(start, currentGoal, boatAvailable), renderRadius, reach, playerAnchored,
                minWaypointIndex);
        if (target != null) {
            return target;
        }
        if (!playerAnchored) {
            // 継ぎ足しはルートの持ち主ではない。ここでfreshRouteを呼ぶと、末端の位置を始点に
            // 長距離ルートごと引き直すことになり、層2の精緻化も投げ直されて手前の案内が入れ替わる
            return new DetailTarget(currentGoal, -1, 0);
        }
        CoarseRoute inFlight = refiningRoute;
        if (inFlight != null && inFlight.goal().equals(currentGoal)) {
            // 層2の精緻化がまさにこの目的地に対して進行中。ここで引き直すとその結果を捨てて
            // 同じ状況をやり直すだけになる——描画距離が小さい環境では層1の96ブロック間隔が
            // 1つも届かず必ずここへ来るので、素通しにすると「0.5秒ごとにメインスレッドで
            // 地図を読み直しては精緻化を捨てる」ループに入り、精緻版が永久に完成しない。
            // 完成すればpendingRefinedRouteReadyが引き直しをかけ、24ブロック間隔のwaypointが
            // 届くようになる。それまでは長距離ルートを挟まない従来動作へ落とす
            return new DetailTarget(currentGoal, -1, 0);
        }
        CoarseRoute cached = coarseRoute;
        if (cached != null && cached.goal().equals(currentGoal)
                && horizontalDistance(start, cached.computedFrom()) < COARSE_ROUTE_RETRY_MOVE_BLOCKS) {
            // 引き直したところで同じルートになる。層1の引き直しはメインスレッドでの地図読みと
            // 層1A*2回ぶんで、届く中間目標が1つも無い間はそれを毎回の再計算で払うことになる
            return new DetailTarget(currentGoal, -1, 0);
        }
        // キャッシュ済みのwaypointが1つも描画距離内に届かない＝大きく迂回して経路から外れた。
        // 目的地は変わっていないのでキャッシュは効くはずだが、地形は不変でも自分の位置は変わるので、
        // 今の位置を始点に引き直す（地形が変わらない限り引き直さない、という原則の唯一の例外）
        target = reachableWaypointTarget(start, currentGoal, freshRoute(start, currentGoal, boatAvailable),
                renderRadius, reach, true, minWaypointIndex);
        return target != null ? target : new DetailTarget(currentGoal, -1, 0);
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
    private static void logSearchReach(BlockPos start, BlockPos target, PathResult result) {
        if (!LOGGER.isDebugEnabled() || result.steps().isEmpty()) {
            return;
        }
        BlockPos end = result.steps().get(result.steps().size() - 1).pos();
        LOGGER.debug("XaeroNav: 詳細探索 (目標 {} ({} ブロック先), 実到達 {} ブロック, {}, 展開 {})",
                target.toShortString(), Math.round(horizontalDistance(start, target)),
                Math.round(horizontalDistance(start, end)), result.termination(), result.expandedNodes());
    }

    /**
     * 層2の精緻版があればそちらを優先する（{@link #currentRouteWaypoints}と同じ順序）。層1は
     * チャンク解像度で中間目標が100ブロック近く離れることがあり、描画距離を下げた環境では
     * {@link #reachableWaypointTarget}の「renderRadius以内」を1つも満たせず長距離ルートごと
     * 空振りする。精緻版は{@link #REFINED_WAYPOINT_MIN_SPACING_BLOCKS}間隔なのでここを埋められる。
     */
    private List<BlockPos> cachedOrFreshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable) {
        CoarseRoute cached = coarseRoute;
        if (cached == null || !cached.goal().equals(currentGoal)) {
            return freshRoute(start, currentGoal, boatAvailable);
        }
        RefinedRoute refined = refinedRoute;
        return refined != null && refined.source() == cached ? refined.waypoints() : cached.waypoints();
    }

    private List<BlockPos> freshRoute(BlockPos start, BlockPos currentGoal, boolean boatAvailable) {
        CoarseRouter.Route route = computeCoarseRoute(start, currentGoal, boatAvailable);
        List<BlockPos> waypoints = route.waypoints();
        if (!waypoints.isEmpty() && route.reachedGoal()) {
            // 粗い終点はチャンク中心±8ブロックで高さも代表値なので、そのままでは到着できない。
            // 最後だけ本来の目的地に差し替える
            waypoints = replaceLast(waypoints, currentGoal);
        }
        CoarseRoute thisRoute = new CoarseRoute(currentGoal, start, route.reachedGoal(), waypoints);
        coarseRoute = thisRoute;
        // 新しい列では添字の意味が変わる。引き直しは今の位置を始点にするので、先頭が通過済みに
        // なることはない
        passedWaypoints = 0;
        // 古い世代の精緻化結果はここでnullにしない。読み出し側がRefinedRoute.source()で
        // 由来元を検査するので、世代が違えば自動的に無視される——ここで消すと、まだ有効な
        // 精緻版まで一緒に落ちる
        // 経路が引けなかった世代でも必ず入れ替える。ここを条件付きにすると、前の世代の目印が
        // 残ったままになって「精緻化が進行中」の判定が永久に真になる
        refiningRoute = waypoints.isEmpty() ? null : thisRoute;
        if (!waypoints.isEmpty()) {
            refineRouteAsync(start, currentGoal, waypoints, thisRoute);
        }
        return waypoints;
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
            if (refiningRoute == forRoute) {
                refiningRoute = null;
            }
            if (error != null) {
                return;
            }
            List<BlockPos> stitched = CorridorWaypoints.stitch(legPoints);
            List<BlockPos> downsampled = CorridorWaypoints.downsample(stitched, REFINED_WAYPOINT_MIN_SPACING_BLOCKS);
            // 世代の検査はここではなく読み出し側（RefinedRoute.source()）で行う。ここで
            // 「検査してから書き込む」形にすると、その間に世代が進んだ場合に古い精緻版が
            // 素通りする（stitch/downsampleは点列全体を走査するので、その隙間は実時間で開く）
            refinedRoute = new RefinedRoute(currentGoal, forRoute, downsampled);
            pendingRefinedRouteReady = true;
        });
    }

    private CompletableFuture<List<BlockPos>> solveLeg(CorridorLegSolver.PreparedLeg leg, BlockPos rawTarget) {
        if (leg.view() == null) {
            return CompletableFuture.completedFuture(List.of(rawTarget));
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
    private DetailTarget reachableWaypointTarget(BlockPos start, BlockPos currentGoal,
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
        boolean strandedHere = stalledSearches > 0;
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
        if (horizontalDistance(start, waypoints.get(farthestIndex))
                < WAYPOINT_GOAL_RADIUS_BLOCKS + REFINED_WAYPOINT_MIN_SPACING_BLOCKS) {
            // 前へ出すための調整なので、歯止めより手前へ戻してはいけない
            farthestIndex = Math.max(farthestIndex, Math.min(farthestIndex + 1, farthestInRadius));
        }
        BlockPos aim = waypoints.get(farthestIndex);
        double distance = horizontalDistance(start, aim);
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
    private static BlockPos pointAlong(BlockPos from, BlockPos to, double distance, double reach) {
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

    /**
     * {@link CoarseMapWindow}の範囲でXaeroの地図データを読み、{@link CoarseRouter}で中間目標列を引く。
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
     * メインスレッドで重いため）。この梯子が走るのは{@link #freshRoute}（目的地ごとにキャッシュ）の
     * ときだけで毎tickではない。
     *
     * <p>以前はここに参照Yを変えて読み直す3段の梯子もあった（{@link CoarseMap}が1セル1階層
     * しか持てず、天井のある次元で見える地形が参照Y次第で変わっていたため）。{@link CoarseMap}が
     * 複数の床を同時に持てるようになったので、1回の{@code readSurface}で参照Y付近の全レイヤーが
     * 床として揃い、梯子は不要になった。
     */
    private static CoarseRouter.Route computeCoarseRoute(BlockPos start, BlockPos goal, boolean boatAvailable) {
        CoarseMap map = CoarseMapWindow.read(start, goal, CoarseMap.MAX_FLOORS);
        if (map == null) {
            return new CoarseRouter.Route(List.of(), false);
        }
        CoarseRouter.Route avoided = CoarseRouter.findRoute(map, start, goal, boatAvailable,
                CoarseRouter.BridgePolicy.AVOID);
        if (avoided.reachedGoal()) {
            return avoided;
        }

        CoarseRouter.Route bridged = CoarseRouter.findRoute(map, start, goal, boatAvailable,
                CoarseRouter.BridgePolicy.BRIDGE);
        if (bridged.reachedGoal()) {
            LOGGER.info("XaeroNav: 溶岩を避ける道が見つからないため、橋を架けて渡る長距離ルートに切り替えました");
            return bridged;
        }
        return furtherRoute(avoided, bridged);
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

    private static double horizontalDistance(BlockPos a, BlockPos b) {
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
     */
    private boolean shouldClimbToSurface(Level level, BlockPos start, BlockPos goal, int groundLevel) {
        if (goal.getY() < groundLevel || start.getY() > groundLevel - MIN_UNDERGROUND_DEPTH) {
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
    private enum PathMode {
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
    private record DisplayedPath(PathResult result, PathMode mode, int waypointIndex,
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
    private record PathSegment(int endStep, int waypointIndex) {
    }

    /**
     * 長距離ルートの中間目標のキャッシュ。地形は不変なので、目的地が変わらない限り引き直さない。
     *
     * @param computedFrom このルートを引いたときの始点。同じ場所からの引き直しは同じ結果になるので、
     *         それを避けるための照合に使う（{@link #COARSE_ROUTE_RETRY_MOVE_BLOCKS}）
     * @param reachedGoal 粗い地図の上で目的地まで届いたか。届いていないなら、詳細探索を
     *         いくら回しても届かない（粗い地図で通行不能になるのは溶岩だけで、未探索セルは
     *         通行可能として扱われる）ので、詰みの理由を言い当てる材料になる
     */
    private record CoarseRoute(BlockPos goal, BlockPos computedFrom, boolean reachedGoal,
                                List<BlockPos> waypoints) {
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
    private record DetailTarget(BlockPos target, int waypointIndex, int goalRadius) {
    }
}
