package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;


import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * Traverse/Diagonal/Ascend/Descend/Bridgeを扱う。
 * ワーカースレッドから呼ぶ想定 — {@link CellSource}以外のMinecraft状態には一切触れない。
 *
 * <p>探索の内側ではオブジェクトを作らない。座標は{@code int}のまま扱い、隣接ノードの評価結果は
 * その場でノードへ反映する。{@link BlockPos}や身体通過セルのリストを作るのは、最終経路を
 * 組み立てるときだけに限る（探索中に作ると、展開したノード数×十数個のゴミが毎回生まれ、
 * ワーカースレッド側のGCがメインスレッドごと止めてしまう）。
 */
public final class AStarPathfinder {

    /**
     * 打ち切りの主条件。時間ではなく展開ノード数を主条件にすることで、同じ地形・同じ始点終点なら
     * 常に同じ経路が返る。時間で打ち切ると、その瞬間のマシン負荷で到達点が変わり、
     * 再計算のたびに表示される経路が変わってしまう。
     */
    public static final int DEFAULT_MAX_EXPANDED_NODES = 100_000;

    /** 想定外に重い地形でワーカースレッドが張り付き続けないための安全弁。通常は展開数上限が先に効く。 */
    public static final long DEFAULT_TIME_LIMIT_MILLIS = 2_000;

    /**
     * ヒューリスティックに掛ける重み（weighted A*）。1.0なら最短経路を保証する通常のA*。
     *
     * <p>1.0のままだと、実コストがヒューリスティックを大きく上回る地形——掘削(石1セルあたり数十tick)や
     * 遊泳(5.56 tick/マスに対し下限は3.56)——でA*がほぼDijkstraに退化し、展開数の上限が数十マス先で
     * 尽きる。重みを掛けると最短性の保証は失うが、同じ展開数で辿り着ける距離が大きく伸びる。
     * 展開数で打ち切る設計なので、重みを掛けても「同じ地形なら同じ経路」は保たれる。
     *
     * <p>重みを掛けるとヒューリスティックの一貫性が崩れ、展開済みノードのコストが後から改善しうる。
     * 展開済みを再びオープンセットへ戻すことはしない（{@link PathNode#closed}）ので、各セルの展開は
     * 高々1回に収まり、経路のコストは最適のこの倍数以内に収まる。
     */
    public static final double DEFAULT_HEURISTIC_WEIGHT = 1.5;

    /** 落下ブロックが延々と積まれている異常な塔でも1エッジの評価が固まらないようにする安全弁。 */
    private static final int MAX_FALLING_CHAIN_SCAN = 16;

    /**
     * ゴールに到達できなかった場合の到達点候補を、{@code h + g / 係数}という複数の指標で同時に追う。
     * ヒューリスティック単独で最良の点を選ぶと、ゴールに近いだけで行き止まりの地点（崖の縁など）を
     * 掴んでしまう。係数が小さいほど「実際に進んだ距離」を重く見る。
     */
    private static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    /** これ未満しか進めない暫定経路は提示する価値がない（ブロック）。 */
    private static final double MIN_DIST_PATH = 5.0;

    /**
     * 平坦地では直進と斜めの組み合わせで 10^-16 オーダーのコスト差が生まれることがある。
     * この程度の改善のために再伝播・decrease-keyを走らせるのは、得られる経路の質に見合わない。
     */
    private static final double MIN_IMPROVEMENT = 0.01;

    /** 時刻とキャンセルの確認間隔（ノード数）。単調時計の呼び出しも内側では間引く。 */
    private static final int CHECK_INTERVAL_MASK = (1 << 6) - 1;

    private static final int[] CARDINAL_DX = {0, 1, 0, -1};
    private static final int[] CARDINAL_DZ = {-1, 0, 1, 0};
    private static final int[] DIAGONAL_DX = {1, 1, -1, -1};
    private static final int[] DIAGONAL_DZ = {1, -1, 1, -1};

    final CellSource view;
    private final int maxExpandedNodes;
    private final long timeLimitMillis;
    private final double heuristicWeight;
    /**
     * 層1のcost-to-goを併用するための差し替え口。{@code null}なら{@link #node}が
     * {@link Heuristic}（既定の幾何学的下限）をそのまま使う。
     */
    private final CostToGo costToGo;

    /** 縦走査と、その結果の列ごとの覚え書き。探索1回ぶんで使い捨てる。 */
    final ColumnScans scans;

    /** 連続して架けてよい橋の長さ（ブロック）。0なら無制限。{@link CellSource#maxBridgeRunBlocks()}。 */
    final int maxBridgeRun;

    /**
     * 溶岩の上で効く橋の長さの上限（ブロック）。0なら無制限。{@link RunCaps#effectiveLavaBridgeRun()}が
     * {@link #maxBridgeRun}との厳しい方を選んだ後の値なので、ここでは単独で比べてよい。
     */
    final int maxLavaBridgeRun;

    /**
     * 底の無い空虚の上で効く橋の長さの上限（ブロック）。0なら無制限。
     * {@link #maxLavaBridgeRun}と同じく{@link #maxBridgeRun}を織り込み済み。
     */
    final int maxVoidBridgeRun;

    /**
     * この探索が{@link #maxBridgeRun}・{@link #maxLavaBridgeRun}・{@link #maxVoidBridgeRun}を
     * 理由に橋の移動を1つでも捨てたか。
     */
    boolean bridgeRunCapBlocked;

    /**
     * 経路全体で置いてよい足場の総数。0なら無制限。{@link Tolerances#placedBlockBudget()}。
     *
     * <p>{@link #maxBridgeRun}が連続長なのに対しこちらは累積——短い橋を何度も架ける経路は
     * 連続長では止まらないが、持ち物は同じだけ減る。
     */
    final int placedBudget;

    /** この探索が{@link #placedBudget}を理由に設置の移動を1つでも捨てたか。 */
    boolean placedBudgetBlocked;

    /**
     * 足場を1つ置く動作そのものの値段（tick）。既定は
     * {@link ActionCosts#PLACE_BLOCK_AIM_TICKS}そのもので、<b>持ち物が乏しいときだけ</b>
     * 呼び出し側が割り増した値を渡す（{@code PathfindingExecutor}の節約の引き直し）。
     *
     * <p><b>割増が掛かるのは置く動作の側だけ</b>で、走行を中断するぶん
     * （{@link ActionCosts#TERRAIN_EDIT_INTERRUPTION_TICKS}）には掛からない。節約の引き直しが
     * 減らしたいのは<b>使う枚数</b>なので、枚数に比例する成分だけを割り増すのが筋
     * ——そして{@code PathfindingExecutor}が割増を差し引いて2つの経路を比べられるのも、
     * 全ての設置が同じ額だけ膨らんでいるからこそ。
     *
     * <p><b>探索の開始時に決まる一律の値であること。</b>残り枚数で値段を変えると、同じ辺の値段が
     * 到達経路によって変わってA*の前提が崩れる（{@link PathNode#placedTotal}がノードの同一性に
     * 入っていないので、なおさら意味を持たない）。
     *
     * <p>割り増す向きは安全側——実コストが上がるだけなので、{@link Heuristic}も
     * {@link CostToGo}のガイドも下限であり続ける。
     */
    final double placementCostTicks;

    /** 持ち物にブロックが無くても設置の移動を作ってよいか。{@link Tolerances#placeWithoutBlocks()}。 */
    final boolean placeWithoutBlocks;

    /** この探索が「置けるブロックを持っていない」を理由に設置の移動を1つでも捨てたか。 */
    boolean placementBlockedByEmptyInventory;

    /** {@link #trimUnfinishedPlacements}が末尾から落とした設置ステップの数。診断用。 */
    private int trimmedPlacements;

    /** 落下ダメージを何点まで許容してよいか。{@link CellSource#maxFallDamagePoints()}を上書きできる。 */
    final int maxFallDamagePoints;

    /**
     * この探索が、落下ダメージの許容量<b>だけ</b>を理由に着地を捨てたか。
     *
     * <p>立てる床が読めていて、そこへ落ちれば届くのにダメージが許容量を超えていた場合にだけ立てる。
     * 奈落（{@link #NOTHING_BELOW}）や未ロード（{@link #UNREADABLE_BELOW}）で捨てた場合は立てない
     * ——そちらは許容量をいくら緩めても着地点が現れないので、探し直しても同じ結果になる。
     */
    boolean fallDamageCapBlocked;

    /** 奈落・致死落差の上での跳躍を避けるか。{@link Tolerances#allowRiskyJumps()}の裏返し。 */
    final boolean avoidRiskyJumps;

    /**
     * この探索が{@link #avoidRiskyJumps}を理由に跳躍を1つでも捨てたか。捨てていなければ、
     * 許して探し直しても結果は変わらない（{@code bridgeRunCapBlocked}と同じ役割）。
     */
    boolean riskyJumpBlocked;

    /** 頭を水に浸けたまま続けてよい時間（tick）。0なら無制限。{@link CellSource#maxSubmergedTicks()}。 */
    private final int maxSubmergedTicks;

    /** この探索が{@link #maxSubmergedTicks}を理由に移動を1つでも捨てたか。 */
    private boolean submergedRunCapBlocked;

    /** 手前の区間から引き継ぐ累積（橋の連続長・設置数）。 */
    private Carryover carried = Carryover.NONE;

    /** ゴールを領域として扱う半径（ブロック）。0なら座標の完全一致。 */
    /**
     * 領域ゴールの垂直方向の許容幅（ブロック）。水平の{@code goalRadius}とは別に、広めに固定する。
     *
     * <p>領域ゴールはどれも粗い層が置いた点で、そのYは<b>チャンク代表高さ</b>か直線補間か、
     * Xaeroの詳細データが読めなかったときの生の推定値でしかない。水平と同じ幅でYを縛ると、
     * 推定が外れた中間目標は<b>原理的に到達不能</b>になり、それを発見するために毎回ノード上限を
     * 使い切ることになる（実機ログ: 同じ中継地点(920,584)がY=66とY=81の2通りで出て、
     * 66の側は3回とも20万ノードを焼いて未到達、81の側は2.8万ノードで到達していた）。
     *
     * <p>幅は層1が中間目標を置く垂直間隔（{@code CoarseRouter#WAYPOINT_VERTICAL_SPACING_BLOCKS}）に
     * 揃える——それより細かいYの差は、そもそも層1が表現していない。ゆるめる方向なので探索の
     * 許容性は壊れない（ヒューリスティックの割引は水平半径のままで、過小割引にしかならない）。
     */
    private static final int GOAL_VERTICAL_TOLERANCE_BLOCKS = 24;

    private int goalRadius;

    /** {@link CellSource#minDescentTicksPerBlock()}。探索中は不変なので1度だけ読む。 */
    private final double minDescentPerBlock;

    /** 移動候補生成（ARCH-02）。探索1回につき1つだけ作る——{@link GroundMoves}のクラスJavadoc参照。 */
    private final GroundMoves groundMoves = new GroundMoves(this);
    private final WaterMoves waterMoves = new WaterMoves(this);
    private final BuildMoves buildMoves = new BuildMoves(this);

    private final NodeTable nodes = new NodeTable();

    /**
     * ボートに乗った状態のノード。{@link PathNode#boating}が同一性の一部なので、座標が同じでも
     * 乗っている／いないは別のノードになる。{@link BlockPos#asLong}は64bitを使い切っていて
     * キーに1bit足せないため、表そのものを分けている。ボートを持っていなければ空のまま。
     */
    private final NodeTable boatNodes = new NodeTable();

    /** 作ったノードの総数（展開したノードの周りも含む）。 */
    private int createdNodes;
    private final BinaryHeapOpenSet open = new BinaryHeapOpenSet();
    private final PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];
    private final double[] bestHeuristic = new double[COEFFICIENTS.length];

    int goalX;
    private int goalY;
    int goalZ;
    // trueなら「y >= surfaceY のセルならどこでもゴール」として探索する（地上優先ナビ用）。
    // 目的地の真下から一直線に掘るのではなく、周囲のどこからでも地上に出られる経路を許すために
    // 固定の1点ではなく高さだけを条件にする。
    private boolean surfaceGoal;
    private int surfaceY;

    /**
     * 取り出し順序を「引き分けのときだけ」ずらすための刻み幅（tick）。
     *
     * <p>平地でナビの線がL字・階段になるのは、平坦で開けた地形では octile の{@link Heuristic}が
     * <b>厳密</b>なので経路上で{@code g + h}が一定になり、
     * {@code f = g + weight*h = 一定 + (weight-1)*h} ＝ <b>hを最も速く減らす手が常に勝つ</b>ため。
     * 斜め1手はhを{@code DIAGONAL}(5.040)減らし、直進は{@code STRAIGHT}(3.564)しか減らさないので、
     * 探索は「斜めを全部消化してから直進」へ倒れる。差は{@code (1.5-1)*(5.040-3.564)=0.738 tick/手}。
     *
     * <p><b>fに直線からのずれを加算してはいけない</b>（2026-08-30に実機で踏んだ）。加算すると
     * 「線へ引き戻す力」が経路全体に効き続け、直線が地形で塞がれるたびに<b>出ては戻るを繰り返す
     * 長方形の階段</b>になる。実機エンドの区間で曲がり回数が4→21に増えていた。
     *
     * <p>代わりにfを{@code LINE_TIE_BREAK_TICKS}刻みに<b>量子化</b>し、同じ刻みに入った
     * ノード同士だけをずれの小さい順に取り出す。刻み(2.0)は上の0.738より大きいので平地の偏りは
     * 消え、地形を迂回する本物のコスト差（1手＝3.564以上）は刻みを跨ぐので<b>まったく干渉しない</b>。
     */
    private static final double LINE_TIE_BREAK_TICKS = 2.0;

    /** 引き分け内での並べ替え幅。刻みを跨がないよう{@link #LINE_TIE_BREAK_TICKS}より必ず小さく保つ。 */
    private static final double LINE_TIE_BREAK_FRACTION = 0.9;

    /** ずれがこの値のとき、並べ替え幅のちょうど半分になる（飽和の効き始め、ブロック）。 */
    private static final double LINE_TIE_BREAK_HALF_BLOCKS = 8.0;

    /** 始点→ゴールの直線（XZ平面）。{@link #orderingCost}が使う。長さ0なら無効。 */
    private int lineStartX;
    private int lineStartZ;
    private double lineDirX;
    private double lineDirZ;
    private boolean lineTieBreak;

    public AStarPathfinder(CellSource view) {
        this(view, SearchLimits.DEFAULT);
    }

    public AStarPathfinder(CellSource view, SearchLimits limits) {
        this(view, limits, null);
    }

    /**
     * {@code costToGo}を明示的に指定するコンストラクタ。{@code null}なら
     * {@link Heuristic}（既定の幾何学的下限）を使う既存の挙動と完全に同じになる。
     */
    public AStarPathfinder(CellSource view, SearchLimits limits, CostToGo costToGo) {
        this(view, limits, costToGo, Tolerances.of(view));
    }

    /**
     * 危険の許容量を明示するコンストラクタ。上限のせいで範囲内に道が一本も無くなった場合の、
     * 詰み回避の探し直しに使う（「マグマの橋も溺れる危険も痛い落下も最後の手段だが、詰みよりは
     * マシ」という優先順）。
     */
    public AStarPathfinder(CellSource view, SearchLimits limits, CostToGo costToGo, Tolerances tolerances) {
        this(view, limits, costToGo, tolerances, 1.0);
    }

    /**
     * 足場を置く手間の値段に掛ける係数を明示するコンストラクタ。{@code 1.0}が既定
     * （{@link ActionCosts#PLACE_BLOCK_AIM_TICKS}そのもの）。
     *
     * <p>持ち物が乏しいときに「置く手数を減らした経路」を探し直すためのもの
     * （{@code PathfindingExecutor}の節約の引き直し）。<b>上限（{@link #placedBudget}）とは
     * 役割が違う</b>——上限は実行可能かどうかの線引きで、こちらは実行できる範囲での好みを表す。
     *
     * @param placementCostScale {@link #placementCostTicks}に掛ける係数。1.0未満は渡さないこと
     *                           （安くすると{@link CostToGo}のガイドが下限でなくなる）
     */
    public AStarPathfinder(CellSource view, SearchLimits limits, CostToGo costToGo, Tolerances tolerances,
                            double placementCostScale) {
        RunCaps caps = tolerances.caps();
        this.placementCostTicks = ActionCosts.PLACE_BLOCK_AIM_TICKS * placementCostScale;
        this.maxBridgeRun = caps.maxBridgeRunBlocks();
        this.maxLavaBridgeRun = caps.effectiveLavaBridgeRun();
        this.maxVoidBridgeRun = caps.effectiveVoidBridgeRun();
        this.maxSubmergedTicks = caps.maxSubmergedTicks();
        this.placedBudget = tolerances.placedBlockBudget();
        this.placeWithoutBlocks = tolerances.placeWithoutBlocks();
        this.avoidRiskyJumps = !tolerances.allowRiskyJumps();
        this.maxFallDamagePoints = tolerances.maxFallDamagePoints();
        // 生成器は同じセルを何度も読み直す（1ノードあたり197〜413回の読みに対し、触れる列は
        // 探索全体で2万本ほど）。ここで包んでおくと、2回目以降がハッシュ表を引かずに済む
        this.view = new MemoCells(view);
        // 落下ダメージの許容量を緩めたら下降の下限も一緒に緩める。許せる落差が伸びるほど
        // 1ブロックあたりの実コストは終端速度へ近づいて安くなるので、元の下限のままでは
        // ヒューリスティックが実コストを上回りうる（＝非許容）
        this.minDescentPerBlock = view.minDescentTicksPerBlock(this.maxFallDamagePoints);
        this.maxExpandedNodes = limits.maxExpandedNodes();
        this.timeLimitMillis = limits.timeLimitMillis();
        this.heuristicWeight = limits.heuristicWeight();
        this.costToGo = costToGo;
        this.scans = new ColumnScans(this.view);
    }

    /**
     * この探索が、連続する橋の長さの上限を理由に移動を捨てたか。捨てていない場合、
     * 上限を外して探し直しても結果は変わらない。
     */
    public boolean bridgeRunCapBlocked() {
        return bridgeRunCapBlocked;
    }

    /**
     * この探索が、持ち物のブロック数の予算を理由に設置の移動を捨てたか。捨てていない場合、
     * 予算を外して探し直しても結果は変わらない。
     */
    public boolean placedBudgetBlocked() {
        return placedBudgetBlocked;
    }

    /**
     * 手前の区間から引き継いだ設置数（{@link Carryover#placedBlocks()}）。
     *
     * <p>呼び出し側が「この経路は持ち物のどれだけを使うのか」を出すのに要る——この探索が返す
     * 経路の設置数だけでは、区間に割って解いたときに<b>いつも手持ちに余裕があるように見える</b>。
     */
    public int carriedPlacedBlocks() {
        return carried.placedBlocks();
    }

    /**
     * この探索が「置けるブロックを持っていない」を理由に設置の移動を捨てたか。捨てていない場合、
     * 持たない前提を開いて探し直しても結果は変わらない。
     */
    public boolean placementBlockedByEmptyInventory() {
        return placementBlockedByEmptyInventory;
    }

    /**
     * この探索が、連続する潜水の長さの上限を理由に移動を捨てたか。捨てていない場合、
     * 上限を外して探し直しても結果は変わらない。
     */
    public boolean submergedRunCapBlocked() {
        return submergedRunCapBlocked;
    }

    /**
     * この探索が「外したら死ぬ跳躍」を避けたことで移動を捨てたか。捨てていない場合、
     * 許して探し直しても結果は変わらない。
     */
    public boolean riskyJumpBlocked() {
        return riskyJumpBlocked;
    }

    /**
     * {@link #trimUnfinishedPlacements}が経路の末尾から落とした設置ステップの数。
     *
     * <p>診断のためだけにある。切り落とした後の経路を見ると「橋を一本も架けなかった」と
     * 「橋を架けたが渡り切れなかった」が同じ<b>設置0</b>に見えてしまい、原因が正反対なのに
     * 区別が付かない。
     */
    public int trimmedPlacements() {
        return trimmedPlacements;
    }

    /**
     * 打ち切り条件（展開数上限・時間上限・cancelled）のいずれかに達したら、その時点で最も有望な
     * 暫定経路を返す。
     */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled) {
        return search(start, goal, cancelled, Carryover.NONE, 0);
    }

    /**
     * ゴールを「点」ではなく<b>半径{@code goalRadius}の領域</b>として探索する。
     *
     * <p>長距離ルートの中間目標は、チャンク平均から作った代表点（層1）や、ルート上の直線補間点
     * （{@code pointAlong}）でしかない。地形とは無関係な人工的な点なので、そこへ座標ぴったり寄せる
     * ために本来不要な遠回りが生まれる——中継地点は<b>通る場所</b>ではなく<b>向かう方角</b>である、
     * というのが層1の役割の定義そのもの。
     *
     * <p>{@link #searchToSurface}が「y &gt;= surfaceY ならどこでもゴール」として既にこの形を取っている。
     * その一般化にあたる。本来の目的地に対しては0を渡すこと（ユーザーが指した点は動かせない）。
     */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled, int goalRadius) {
        return search(start, goal, cancelled, Carryover.NONE, goalRadius);
    }

    /**
     * 手前の区間から累積を引き継いで探索する（{@link Carryover}）。
     *
     * <p>経路は区間ごとに別の探索器で解かれるので、引き継がないと<b>区間の数だけ上限が復活する</b>
     * ——橋の連続長は境目で0に戻り、持ち物の予算は区間ごとに満額になる。
     */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled, Carryover carried,
                              int goalRadius) {
        this.surfaceGoal = false;
        this.goalX = goal.getX();
        this.goalY = goal.getY();
        this.goalZ = goal.getZ();
        this.carried = carried;
        this.goalRadius = goalRadius;
        return runSearch(start, cancelled);
    }

    /**
     * 「y &gt;= surfaceY のセルならどこでもゴール」として探索する。地下から地上への移動を、
     * 出発地の真上を一直線に掘る1点ゴールではなく、周囲のどこからでも地上に出られる経路として
     * 探すためのもの（地上優先ナビ用、{@link net.prason.xaeronav.client.PathfindingState}参照）。
     *
     * <p>ヒューリスティックは各ノード自身の(x, z)を目的地の(x, z)として扱う（水平距離0扱い）ことで、
     * 「あと何マス上がるか」だけの下限値になる。実際の残りコストには水平移動が乗ることがあるので
     * 下限であり続け、A*の最適性は保たれる（水平方向には実質Dijkstraになり、探索が広がりやすくなる）。
     * すでに{@code surfaceY}以上にあるノードはそれ自体がゴールなので0にする（{@link #node}）。
     */
    public PathResult searchToSurface(BlockPos start, int surfaceY, BooleanSupplier cancelled) {
        this.surfaceGoal = true;
        this.surfaceY = surfaceY;
        return runSearch(start, cancelled);
    }

    /**
     * 取り出し順序を決める値。{@code f}を{@link #LINE_TIE_BREAK_TICKS}刻みに量子化し、
     * 同じ刻みの中だけ「始点→ゴールの直線に近い順」に並べる。
     *
     * <p>加算ではなく量子化なのが要点。刻みを跨ぐコスト差（＝地形を迂回する本物の理由）には
     * 一切触れず、刻みの中の引き分けだけを解く。
     */
    private double orderingCost(double totalCost, int x, int z) {
        if (!lineTieBreak || LINE_TIE_BREAK_FRACTION <= 0.0) {
            return totalCost;
        }
        double dx = x - lineStartX;
        double dz = z - lineStartZ;
        // 方向ベクトルは単位長なので、外積の絶対値がそのまま垂線の長さ
        double deviation = Math.abs(dx * lineDirZ - dz * lineDirX);
        double tie = LINE_TIE_BREAK_FRACTION * LINE_TIE_BREAK_TICKS
                * (deviation / (deviation + LINE_TIE_BREAK_HALF_BLOCKS));
        return Math.floor(totalCost / LINE_TIE_BREAK_TICKS) * LINE_TIE_BREAK_TICKS + tie;
    }

    /**
     * 始点→ゴールの直線を用意する（{@link #LINE_TIE_BREAK_TICKS}用）。
     * ゴールが面（{@link #searchToSurface}）のときと、始点とゴールが同じ列のときは無効にする。
     */
    private void prepareDeviationLine(BlockPos start) {
        lineStartX = start.getX();
        lineStartZ = start.getZ();
        double dx = goalX - start.getX();
        double dz = goalZ - start.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        lineTieBreak = !surfaceGoal && length > 0.0;
        if (lineTieBreak) {
            lineDirX = dx / length;
            lineDirZ = dz / length;
        }
    }

    private PathResult runSearch(BlockPos start, BooleanSupplier cancelled) {
        prepareDeviationLine(start);
        // 既にボートに乗っているなら、乗っている状態から始める。乗り込む1手のコストをもう一度
        // 計上すると、残りの水面が短い場面で「降りて泳いだ方が安い」という案内になる。
        // 水面のセルであることも確かめるのは、乗ったまま陸に乗り上げている場合を除くため
        boolean startBoating = view.ridingBoat()
                && isBoatSurface(start.getX(), start.getY(), start.getZ());
        PathNode startNode = node(start.getX(), start.getY(), start.getZ(), startBoating);
        startNode.bridgeRun = carried.bridgeRun();
        // 手前の区間で使うと決まっている枚数を先に計上する。これが無いと、区間ごとに予算が
        // 満額になって合計では手持ちの何倍も置く経路が出る
        startNode.placedTotal = carried.placedBlocks();
        startNode.cost = 0.0;
        startNode.combinedCost = orderingCost(heuristicWeight * startNode.estimatedCostToGoal,
                startNode.x, startNode.z);
        open.insert(startNode);
        Arrays.fill(bestSoFar, startNode);
        Arrays.fill(bestHeuristic, startNode.estimatedCostToGoal);

        long deadline = MonotonicTime.millis() + timeLimitMillis;
        int expanded = 0;

        // openが尽きるまで回り切ったなら、探索範囲の中に到達手段が無かったということ。
        // 予算切れと区別しないと、意味の無い再挑戦を延々と仕掛けることになる
        PathResult.Termination termination = PathResult.Termination.EXHAUSTED;
        while (!open.isEmpty()) {
            if (expanded >= maxExpandedNodes) {
                termination = PathResult.Termination.NODE_BUDGET;
                break;
            }
            if ((expanded & CHECK_INTERVAL_MASK) == 0) {
                if (cancelled.getAsBoolean()) {
                    termination = PathResult.Termination.CANCELLED;
                    break;
                }
                if (MonotonicTime.millis() >= deadline) {
                    termination = PathResult.Termination.TIME_LIMIT;
                    break;
                }
            }

            PathNode current = open.removeLowest();
            current.closed = true;
            expanded++;
            if (reachedGoal(current)) {
                return buildResult(startNode, current, PathResult.Termination.REACHED_GOAL, expanded);
            }
            expand(current);
        }

        return buildResult(startNode, selectFallback(startNode), termination, expanded);
    }

    /** ゴール判定と、スナップショットへの到達可能性の判定とで共有する垂直の許容幅。 */
    public static int goalVerticalRadius(int goalRadius) {
        return goalRadius <= 0 ? 0 : Math.max(goalRadius, GOAL_VERTICAL_TOLERANCE_BLOCKS);
    }

    private boolean reachedGoal(PathNode node) {
        // 高さだけでは天井の下も地上に数えてしまう。深い洞窟の坑道は水平に長く、
        // 既定の地上高より上を通ることが珍しくない。そこで中継を終えると、洞窟の中から
        // 目的地へ直行する経路＝避けたかった一直線の掘り進みに戻る
        if (surfaceGoal) {
            return node.y >= surfaceY && node.y >= view.surfacedY(node.x, node.z);
        }
        if (goalRadius <= 0) {
            return node.x == goalX && node.y == goalY && node.z == goalZ;
        }
        // 球ではなく「水平の円柱」で見る。中間目標のYはチャンク代表高さや直線補間でしか決まって
        // おらず、水平座標より遥かに当てにならない——同じ半径でYを縛ると、地形なりに数マス
        // 上下しただけの正しい経路を弾いてしまう
        int dx = node.x - goalX;
        int dz = node.z - goalZ;
        return dx * dx + dz * dz <= goalRadius * goalRadius
                && Math.abs(node.y - goalY) <= goalVerticalRadius(goalRadius);
    }

    /**
     * ゴールに届かなかったときの到達点を選ぶ。係数の小さい（＝実際に進んだ距離を重く見る）ものから順に、
     * 始点から{@link #MIN_DIST_PATH}以上離れている候補を採用する。どれも届かない場合は始点自身を返し、
     * 空の経路＝「提示できる経路なし」として扱う。
     *
     * <p><b>距離は{@link #trimUnfinishedPlacements}で切り落とした後で測る。</b>候補そのものは
     * 架けかけの橋の上にいることがあり、その橋は渡り切れると証明できていないので提示できない
     * ——切る前の距離で選ぶと、<b>切った後には何も残らない候補</b>を掴んで空の経路を返してしまう。
     * 実測（{@code nether_wide}、溶岩の海の岸）: 7つの候補が全部30手ぶんの橋の上に乗っていて、
     * 10万ノードを使ったうえで<b>線が1本も出ない</b>——実機の「展開47万・ステップ数0」がこれ。
     * 岸まで戻して測れば、次の候補（徒歩で進める向き）へ移れる。
     */
    private PathNode selectFallback(PathNode startNode) {
        double threshold = MIN_DIST_PATH * MIN_DIST_PATH;
        for (PathNode candidate : bestSoFar) {
            PathNode landed = backOffUnfinishedBridge(candidate);
            double dx = landed.x - startNode.x;
            double dy = landed.y - startNode.y;
            double dz = landed.z - startNode.z;
            if (dx * dx + dy * dy + dz * dz > threshold) {
                return landed;
            }
        }
        return startNode;
    }

    /** 末尾で自分が置いた足場に乗っている間、手前へ戻る（{@link #trimUnfinishedPlacements}と同じ範囲）。 */
    private static PathNode backOffUnfinishedBridge(PathNode node) {
        PathNode cursor = node;
        while (cursor.previous != null
                && cursor.kind.placedBlockPos(cursor.x, cursor.y, cursor.z) != null) {
            cursor = cursor.previous;
        }
        return cursor;
    }

    private PathResult buildResult(PathNode startNode, PathNode end, PathResult.Termination termination,
                                   int expanded) {
        List<PathStep> steps = new ArrayList<>();
        for (PathNode cursor = end; cursor != startNode && cursor.previous != null; cursor = cursor.previous) {
            PathNode from = cursor.previous;
            int x = cursor.x;
            int y = cursor.y;
            int z = cursor.z;
            steps.add(new PathStep(new BlockPos(x, y, z), cursor.kind.movementType(),
                    cursor.cost - from.cost, cursor.kind.bodyCells(from.x, from.y, from.z, x, y, z),
                    digCells(from, cursor), PathRisk.NONE, cursor.kind.placedBlockPos(x, y, z)));
        }
        Collections.reverse(steps);
        if (trimCapViolations(steps)) {
            // 同一座標へ異なる資源状態で着く候補が統合されても、安全上限を超えた完成経路は
            // 外へ出さない。上位runnerはblockedフラグを見て緩和段を選べる。
            termination = PathResult.Termination.EXHAUSTED;
        }
        if (termination != PathResult.Termination.REACHED_GOAL) {
            trimUnfinishedPlacements(steps);
        }
        return new PathResult(steps, termination, expanded, createdNodes);
    }

    /** 探索中の近似状態が取りこぼしても、公開する経路の設置上限を最後に必ず守る。 */
    private boolean trimCapViolations(List<PathStep> steps) {
        int bridgeRun = carried.bridgeRun();
        int placed = carried.placedBlocks();
        int bridgeStart = bridgeRun > 0 ? 0 : -1;
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            if (!step.bridging()) {
                bridgeRun = 0;
                bridgeStart = -1;
                continue;
            }
            if (bridgeStart < 0) {
                bridgeStart = i;
            }
            bridgeRun++;
            placed++;
            if (maxBridgeRun > 0 && bridgeRun > maxBridgeRun) {
                bridgeRunCapBlocked = true;
                steps.subList(bridgeStart, steps.size()).clear();
                return true;
            }
            if (placedBudget > 0 && placed > placedBudget) {
                placedBudgetBlocked = true;
                steps.subList(bridgeStart, steps.size()).clear();
                return true;
            }
        }
        return false;
    }

    /**
     * 打ち切られた経路の末尾から、自分で置いた足場に乗っているステップを落とす。
     *
     * <p>ゴールへ届かなかった経路は「そこまでは進める」という意味しか持たないが、末尾が橋の途中だと
     * 意味が変わる——<b>ブロックを消費して、渡り切れるかも分からない行き止まりに立たされる</b>。
     * 岸で終わらせておけば、続きは新しいチャンクが読まれた後の継ぎ足しが引き受ける。
     * 「渡り切れると証明できた橋しか案内しない」がこれで成り立つ。
     *
     * <p>提示側ではなく探索の出口で切るのが要点。ここで切れば、線の描画・末端への到達判定・
     * 継ぎ足しの起点・区間をまたぐ連続長の引き継ぎが<b>全部同じ経路を見る</b>。
     * 描画だけ切ると、案内の矢印が線の無い方向を指す。
     */
    private void trimUnfinishedPlacements(List<PathStep> steps) {
        int end = steps.size();
        while (end > 0 && steps.get(end - 1).bridging()) {
            end--;
        }
        trimmedPlacements = steps.size() - end;
        steps.subList(end, steps.size()).clear();
    }

    /**
     * この移動で実際に壊すセル。コスト計算とまったく同じ関数へ収集用のリストを渡して求める。
     * 別途「掘る必要があるセル」を判定し直すと、コストは払ったのに表示されないセル（頭上の
     * 落下ブロック連鎖など）や、その逆が生まれる。
     */
    private List<BlockPos> digCells(PathNode from, PathNode to) {
        List<BlockPos> cells = new ArrayList<>();
        switch (to.kind) {
            case DESCEND, SWIM_DESCEND -> descendingBodyCost(to.x, from.y, to.z, cells);
            case ASCEND -> {
                columnCost(from.x, from.y + 2, from.y + 2, from.z, cells);
                standingBodyCost(to.x, to.y, to.z, cells);
            }
            // 斜め昇降は掘削を許可しない（addDiagonalAscend/addDiagonalDescendがclearWithoutDiggingで
            // 事前に確認済み）。デフォルト分岐に流すと、頭上の落下ブロック連鎖を拾って「払っていない
            // 掘削コスト」を表示してしまいうる
            case DIAGONAL_ASCEND, DIAGONAL_DESCEND -> {
            }
            // 登るために掘るのは新しい頭になるセルだけ。到着地点の身体2セルを数えると、
            // 元の頭（既に通れることが確認済み）まで掘削セルとして表示されてしまう
            case PILLAR -> columnCost(from.x, from.y + 2, from.y + 2, from.z, cells);
            default -> standingBodyCost(to.x, to.y, to.z, cells);
        }
        return List.copyOf(cells);
    }

    private PathNode node(int x, int y, int z) {
        return node(x, y, z, false);
    }

    private PathNode node(int x, int y, int z, boolean boating) {
        PathNode[] page = (boating ? boatNodes : nodes).page(x, y, z);
        int index = NodeTable.index(x, y, z);
        PathNode existing = page[index];
        if (existing != null) {
            return existing;
        }
        // 地上ゴールでは、すでにsurfaceY以上のセルはそれ自体がゴール（残コスト0）。
        // 素通しでsurfaceYを渡すと、そこから下りる分を残コストとして数えてしまい過大評価になる。
        // costToGoは特定のゴール座標に紐付いたテーブルなので、ゴールが1点に定まらない
        // surfaceGoalモードでは使わない
        double heuristic;
        if (surfaceGoal) {
            heuristic = Heuristic.estimate(x, y, z, x, Math.max(y, surfaceY), z);
        } else {
            // ボートに乗っているノードは水平の下限が漕ぎ速度まで下がる。疾走のまま見積もると
            // ボートの枝に対して非許容になり、乗り込む1手の一時コストと相まって一度も展開されない
            heuristic = Heuristic.estimate(x, y, z, goalX, goalY, goalZ, minDescentPerBlock,
                    boating ? ActionCosts.PADDLE_ONE_BLOCK : ActionCosts.SPRINT_ONE_BLOCK);
            // 領域ゴールでは、中心までの見積もりは半径ぶん過大＝非許容になる。
            // 最安の水平移動で半径ぶん詰められるとみなして差し引く（searchToSurfaceが
            // 「あと何マス上がるか」だけの下限へ書き換えているのと同じ考え方）
            double radiusAllowance = goalRadius * ActionCosts.SPRINT_ONE_BLOCK;
            heuristic = Math.max(0.0, heuristic - radiusAllowance);
            if (costToGo != null) {
                // 両者の大きい方を使う。Heuristicは幾何学的な下限、costToGoは層1が壁や溶岩の海を
                // 回避したぶんだけ現実に近い見積もり。
                //
                // <b>ガイド側にも半径ぶんを差し引く。</b>領域ゴールで差し引いた下限を、そのまま
                // 中心までを測るガイドで上書きしては元に戻してしまう。
                //
                // ガイドは崖ペナルティ等の「発明された」重みを含むので厳密な下限ではなく、
                // 上回った瞬間に経路の形が変わる。層1の解像度に由来する上振れは
                // {@code CoarseRouter#centerOffsetCost}が落としてある——あれが無いと
                // hに16ブロック周期の鋸歯が乗り、経路がチャンク境界へ吸い寄せられて直角になる
                heuristic = Math.max(heuristic, costToGo.estimate(x, y, z) - radiusAllowance);
            }
        }
        PathNode created = new PathNode(x, y, z, boating, heuristic);
        page[index] = created;
        createdNodes++;
        return created;
    }

    private void expand(PathNode current) {
        for (int i = 0; i < CARDINAL_DX.length; i++) {
            int dx = CARDINAL_DX[i];
            int dz = CARDINAL_DZ[i];
            groundMoves.addTraverse(current, dx, dz);
            groundMoves.addAscend(current, dx, dz);
            groundMoves.addDescend(current, dx, dz);
            waterMoves.addSwim(current, dx, dz);
            waterMoves.addBoatPaddle(current, dx, dz, false);
            waterMoves.addBoatEnter(current, dx, dz);
            groundMoves.addClimb(current, dx, dz);
            groundMoves.addJumpGap(current, dx, dz);
        }
        for (int i = 0; i < DIAGONAL_DX.length; i++) {
            groundMoves.addDiagonalTraverse(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            waterMoves.addDiagonalSwim(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            waterMoves.addBoatPaddle(current, DIAGONAL_DX[i], DIAGONAL_DZ[i], true);
            groundMoves.addDiagonalAscend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            groundMoves.addDiagonalDescend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
        }
        // 上下の泳ぎ・昇降は、いま水中／梯子の中にいるときしか始まらない。それ以外では判定ごと省く
        long standingCell = view.cell(current.x, current.y, current.z);
        if (CellData.water(standingCell)) {
            waterMoves.addSwimUp(current);
            waterMoves.addSwimDown(current);
            for (int i = 0; i < CARDINAL_DX.length; i++) {
                waterMoves.addSwimAscend(current, CARDINAL_DX[i], CARDINAL_DZ[i]);
            }
            for (int i = 0; i < DIAGONAL_DX.length; i++) {
                waterMoves.addDiagonalSwimAscend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            }
        }
        if (CellData.climbable(standingCell)) {
            groundMoves.addClimbUp(current);
            groundMoves.addClimbDown(current);
        }
        buildMoves.addPillar(current);
        // 踏み出した先の下に何があるかは落下と設置で共通なので、方向ごとに1度だけ辿る。
        // ブロックの設置を最後に評価するのは、同コストなら地形をそのまま使う移動を採用させるため
        for (int i = 0; i < CARDINAL_DX.length; i++) {
            int dx = CARDINAL_DX[i];
            int dz = CARDINAL_DZ[i];
            int obstacleY = scans.firstNonAirBelow(current.x + dx, current.y - 1, current.z + dz);
            groundMoves.addFall(current, dx, dz, obstacleY);
            buildMoves.addBridge(current, dx, dz, obstacleY);
        }
    }

    /**
     * 進入先を1マス通り抜けるのにかかる時間。水と蜘蛛の巣はどちらも当たり判定を持たないので
     * 「通れる」だけを見ると走って抜けられるように見えるが、実際には桁が違うほど遅い。
     * 蜘蛛の巣は足元と頭のどちらか一方でも掛かっていれば減速する。
     */
    double stepCost(int x, int y, int z) {
        long feet = view.cell(x, y, z);
        if (CellData.water(feet)) {
            // 足が着いていても水の中の速度で進む（{@link ActionCosts#SWIM_ONE_BLOCK}参照）
            return ActionCosts.SWIM_ONE_BLOCK;
        }
        if (CellData.cobweb(feet) || CellData.cobweb(view.cell(x, y + 1, z))) {
            return ActionCosts.SPRINT_ONE_IN_COBWEB;
        }
        // ソウルサンド・蜂蜜は遅く、氷は速い。バニラと同じく、足元のセルに倍率が無ければ
        // 実際に踏んでいる1つ下のブロックを見る（{@code Entity#getBlockSpeedFactor}）
        double speedFactor = CellData.speedFactor(feet);
        if (speedFactor == 1.0) {
            speedFactor = CellData.speedFactor(view.cell(x, y - 1, z));
        }
        return ActionCosts.SPRINT_ONE_BLOCK / speedFactor;
    }

    /**
     * ボートが浮けるセルか。水面＝「そのセルが水で、真上は水ではなく体を置ける」。
     * 水中の途中の高さにボートは浮かないので、この判定が船の高さそのものになる。
     */
    boolean isBoatSurface(int x, int y, int z) {
        long here = view.cell(x, y, z);
        long above = view.cell(x, y + 1, z);
        return CellData.water(here) && !CellData.water(above)
                && CellData.occupiableWithoutDigging(above);
    }

    /** 立った姿勢が占める2セルを、掘らずにそのまま通り抜けられるか。 */
    boolean clearWithoutDigging(int x, int y, int z) {
        return CellData.occupiableWithoutDigging(view.cell(x, y, z))
                && CellData.occupiableWithoutDigging(view.cell(x, y + 1, z));
    }

    /**
     * この地点から踏み切るときの水平速度倍率。探し方はバニラの{@code Entity#getBlockSpeedFactor}と
     * 同じで、足元のセルに倍率が無ければ実際に踏んでいる1つ下のブロックを見る。
     *
     * <p><b>1.0を超える側（氷）は返さない。</b>{@link Heuristic}は昇りの下限に
     * {@code ASCEND_ONE_BLOCK}、水平の下限に{@code SPRINT_ONE_BLOCK}を置いているので、
     * そこを割ると非許容になる。速くなる側の得は{@link #stepCost}が水平移動でだけ表す。
     */
    double takeoffSpeedFactor(int x, int y, int z) {
        double speedFactor = CellData.speedFactor(view.cell(x, y, z));
        if (speedFactor == 1.0) {
            speedFactor = CellData.speedFactor(view.cell(x, y - 1, z));
        }
        return Math.min(1.0, speedFactor);
    }

    /**
     * この探索が、落下ダメージの許容量を理由に着地を捨てたか。捨てていない場合、許容量を緩めて
     * 探し直しても結果は変わらない。
     */
    public boolean fallDamageCapBlocked() {
        return fallDamageCapBlocked;
    }

    /**
     * その移動を終えた時点で頭が水に浸かっているか（＝息が減るか）。
     *
     * <p>頭のセルが水ならそのまま。<b>掘って通る固体セル</b>だけは例外で、いま固体でも
     * 水中で掘れば水が流れ込むので、水に接しているなら浸かっている扱いにする——ここを見ないと、
     * 水中を掘り進む経路が「頭のセルは石だから水中ではない」として息の上限をすり抜ける。
     *
     * <p>掘らずに通れるセル（空気）は対象外。そうしないと、海から浜へ上がる1手が
     * 「隣が海だからまだ潜っている」と数えられ、岸に上がれなくなる。
     */
    private boolean headSubmerged(PathNode from, int x, int headY, int z) {
        long head = view.cell(x, headY, z);
        if (CellData.water(head)) {
            return true;
        }
        return from.submergedTicks > 0.0 && !CellData.occupiableWithoutDigging(head)
                && hasAdjacentWater(x, headY, z);
    }

    /**
     * ブロックを置くセルの周り（真上を除く5面）に水があるか。{@link #headSubmerged}と
     * {@link BuildMoves#addBridge}の両方が使う（後者は水に接する場所へ置かない判定）。
     *
     * <p><b>毎回読み直してよい。</b>読みは{@code MemoCells}のページ配列に当たるので、ここに
     * セルごとの覚え書きを足しても速くならない——覚え書きの引き当ての方が高くつく。
     */
    boolean hasAdjacentWater(int x, int y, int z) {
        return CellData.water(view.cell(x, y - 1, z))
                || CellData.water(view.cell(x + 1, y, z)) || CellData.water(view.cell(x - 1, y, z))
                || CellData.water(view.cell(x, y, z + 1)) || CellData.water(view.cell(x, y, z - 1));
    }

    void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        relax(from, x, y, z, edgeCost, kind, 0);
    }

    /** ボートに乗った状態のノードへ緩和する。{@link #addBoatEnter}/{@link #addBoatPaddle}専用。 */
    void relaxBoating(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        relax(from, x, y, z, edgeCost, kind, 0, true);
    }

    /**
     * {@code bridgeRun}を明示的に渡す版。非0を渡すのは自分で置いた足場の上に着く移動
     * （{@link #addBridge}・{@link #addPillar}）だけで、それ以外は実在する床に着くので0になる。
     */
    void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind, int bridgeRun) {
        relax(from, x, y, z, edgeCost, kind, bridgeRun, false);
    }

    void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind, int bridgeRun,
               boolean boating) {
        // 息の勘定より先に「そもそも安くならない候補」を捨てる。割増（SUBMERGED_TRAVEL_PENALTY）は
        // 1倍を下回らないので、割増前のコストで改善できないなら割増後も改善できない。
        // ここを後回しにすると、捨てると分かっている候補のために頭上と周り5面を読むことになる。
        //
        // この先で立てる{@code submergedRunCapBlocked}をここで取りこぼすが、それでよい——
        // 改善しない辺が上限で消えても答えは変わらないので、それを理由に上限を外して
        // 探し直しても同じ経路が出る
        PathNode neighbor = node(x, y, z, boating);
        if (neighbor.closed || neighbor.cost - (from.cost + edgeCost) <= MIN_IMPROVEMENT) {
            return;
        }

        // 移動の種類に関わらず、着地点で頭が水に浸かるならその移動にかかった時間だけ息が減る。
        // ここで一括して見るのは、泳ぎ以外（水中を歩く・沈む・掘る・水へ落ちる）でも同じだから——
        // とりわけ採掘は1手に数十tickかかるので、マス数で数えると息の上限をすり抜ける
        double submergedTicks = 0.0;
        boolean submerged = headSubmerged(from, x, y + 1, z);
        if (submerged) {
            submergedTicks = from.submergedTicks + edgeCost;
            if (maxSubmergedTicks > 0.0 && submergedTicks > maxSubmergedTicks) {
                submergedRunCapBlocked = true;
                return;
            }
        }

        // 潜ったまま横断せず、先に水面へ出てから渡らせる。対象外にするのは浮上だけで、
        // 水平移動にも潜降にも掛ける——水平だけに掛けると、斜め浮上と斜め降下を繰り返して
        // 上下に跳ねながら進むことで割増を回避できてしまう。
        //
        // <b>免除は水平1マス以内の浮上に限る。</b>斜めに進みながら上がる手まで免除すると、
        // 斜めに進むべき区間で「斜めに上がって斜めに降りる」の往復（√3 + √2·P）が
        // 斜め水平2手（2·√2·P）より安くなり、同じ跳ねが斜めの形で戻ってくる
        // （{@code doesNotBobDiagonallyToDodgeTheSubmergedPenalty}で実測）。
        // カーディナルに進める区間では元から水平2手の方が安いので、この穴は斜めでしか出ない。
        //
        // 割増は経路の選択のためのもので、息の勘定（submergedTicks）には混ぜない——あちらは
        // 実際にかかる時間でなければ意味がない
        boolean surfacing = y > from.y && Math.abs(x - from.x) + Math.abs(z - from.z) <= 1;
        double tentativeCost = from.cost
                + (submerged && !surfacing ? edgeCost * ActionCosts.SUBMERGED_TRAVEL_PENALTY : edgeCost);
        if (neighbor.cost - tentativeCost <= MIN_IMPROVEMENT) {
            return;
        }

        neighbor.previous = from;
        neighbor.cost = tentativeCost;
        neighbor.combinedCost = orderingCost(
                tentativeCost + heuristicWeight * neighbor.estimatedCostToGoal, neighbor.x, neighbor.z);
        neighbor.kind = kind;
        neighbor.bridgeRun = bridgeRun;
        // 置いた枚数は種類から導ける（引数を増やすと呼び出し全てに0を書き足すことになる）
        neighbor.placedTotal = from.placedTotal + (kind == MoveKind.BRIDGE || kind == MoveKind.PILLAR ? 1 : 0);
        neighbor.submergedTicks = submergedTicks;
        if (neighbor.isOpen()) {
            open.update(neighbor);
        } else {
            open.insert(neighbor);
        }

        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
            if (bestHeuristic[i] - heuristic > MIN_IMPROVEMENT) {
                bestHeuristic[i] = heuristic;
                bestSoFar[i] = neighbor;
            }
        }
    }

    /**
     * 水中の採掘は水中採掘のエンチャントが無ければ5倍遅い。掘るセルごとではなく「掘っている間プレイヤーの頭が
     * 水にあるか」で決まるので、セル単体のコストではなく移動ごとの掘削コスト合計に掛ける。
     *
     * <p>水中かどうかの判定に{@link #headSubmerged}を使うのが要点。頭のセルが水かだけを見ると、
     * <b>これから掘る固体セル</b>は「水ではない」ので割増が乗らない——水中を掘り進む区間が丸ごと
     * 陸と同じ値段になっていた。息の勘定と同じ判定に揃えてある。
     */
    double submerged(PathNode from, double digCost, int x, int headY, int z) {
        if (digCost <= 0.0 || !headSubmerged(from, x, headY, z)) {
            return digCost;
        }
        // 足が着いているかで5倍違う（Player#getDigSpeedの !onGround() の分岐）。足元は頭の1つ下、
        // その床はさらに1つ下。掘る対象そのものが床のこともあるが、掘る前に立っている高さで測るのが正しい
        boolean onGround = CellData.standable(view.cell(x, headY - 2, z));
        return digCost * (onGround ? ActionCosts.SUBMERGED_DIG_PENALTY : ActionCosts.SWIMMING_DIG_PENALTY);
    }

    /**
     * 立った姿勢で占有する2セル（足元・頭）の破壊コスト。
     */
    double standingBodyCost(int x, int y, int z, List<BlockPos> cells) {
        return columnCost(x, y, y + 1, z, cells);
    }

    /**
     * 一段降りる移動で身体が通過する3セル分。{@code y}は降りる手前の高さ（足元が{@code y}、頭が{@code y+1}、
     * 降りた先が{@code y-1}）。
     */
    double descendingBodyCost(int x, int y, int z, List<BlockPos> cells) {
        return columnCost(x, y - 1, y + 1, z, cells);
    }

    /**
     * 縦1列（{@code bottomY}〜{@code topY}）の破壊コスト。さらに真上から落下ブロック（砂・砂利等）が
     * 連なっている分を一度だけ加える。必須セル自体は個別に数えるだけなので、
     * 隣接する必須セル同士で連鎖コストが重複しない。
     *
     * <p>{@code cells}が非nullなら、実際に壊すセルをそこへ集める。コストを払う判断と壊すセルの列挙を
     * 同じ経路で行うためのもので、これを分けて書くと表示と探索が食い違う。
     */
    double columnCost(int x, int bottomY, int topY, int z, List<BlockPos> cells) {
        double total = 0.0;
        boolean doorCharged = false;
        for (int y = bottomY; y <= topY; y++) {
            // ドアは上下2セルに分かれているが、開ける動作は1回。両方に開閉コストを払うと
            // 1枚のドアが2枚分の重さになり、ドアのある正しい通り道を避けるようになる
            long cell = view.cell(x, y, z);
            boolean openable = CellData.openable(cell);
            if (openable && doorCharged) {
                continue;
            }
            double cost = occupyCost(cell, x, y, z, cells);
            if (Double.isInfinite(cost)) {
                return ActionCosts.INFEASIBLE;
            }
            total += cost;
            doorCharged |= openable;
        }
        return total + fallingChainCost(x, topY + 1, z, cells);
    }

    private double fallingChainCost(int x, int startY, int z, List<BlockPos> cells) {
        double total = 0.0;
        for (int i = 0; i < MAX_FALLING_CHAIN_SCAN; i++) {
            int y = startY + i;
            long cell = view.cell(x, y, z);
            if (!CellData.fallingBlock(cell)) {
                break;
            }
            double cost = occupyCost(cell, x, y, z, cells);
            if (Double.isInfinite(cost)) {
                break;
            }
            total += cost;
        }
        return total;
    }

    private double occupyCost(long cell, int x, int y, int z, List<BlockPos> cells) {
        if (!CellData.present(cell)) {
            return ActionCosts.INFEASIBLE;
        }
        if (CellData.occupiableWithoutDigging(cell)) {
            return 0.0;
        }
        if (CellData.openable(cell)) {
            // ドアは壊すものではなく開けるもの。掘削セルとしても数えない
            return ActionCosts.OPEN_DOOR_OVERHEAD_TICKS;
        }
        double ticks = CellData.digTicks(cell);
        // 掘れないセル（掘削禁止・硬度負）は落下ブロック連鎖の打ち切りにも使われるので、集めない
        if (cells != null && !Double.isInfinite(ticks)) {
            // Ascendの天井掘削は、頭上が砂・砂利のとき落下ブロック連鎖と同じセルを指すことがある
            BlockPos pos = new BlockPos(x, y, z);
            if (!cells.contains(pos)) {
                cells.add(pos);
            }
        }
        return ticks;
    }
}
