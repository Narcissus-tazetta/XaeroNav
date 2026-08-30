package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

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
     * 踏み出した先の下を辿る深さ。着地点・水面・空虚のどれなのかを見分けるためのもので、
     * 落下（{@link #addFall}）とブロック設置（{@link #addBridge}）が同じ結果を使う。
     *
     * <p>空気が続く限りしか下りないので、地形のある場所では1〜2マスで止まる。探索範囲の外
     * （未ロード・範囲外）に出ると{@code cell()}が{@link CellData#ABSENT}を返し、それは
     * {@code passableEmpty}ではないのでその場で止まる——毎ノード×4方向呼ばれるこの走査が
     * 際限なく続くことはない。深さが効くのは範囲内に本当に空気が続く場所（ジ・エンドの空虚、
     * ネザーの溶岩の海の上）だけ。
     *
     * <p>層1の{@code LiveCoarseSampler}が同じ理由で使っている値（128）に揃えてある。
     * 以前は32だったため、32マスを超える空洞の下にある溶岩を見逃し、溶岩の上へ跳躍を
     * 提示することがあった。
     */
    private static final int COLUMN_SCAN_DEPTH = 128;

    /**
     * {@link #firstNonAirBelow}が、読めるセルだけを辿った末に何にも当たらなかったことを表す。
     * 走査した範囲は全て空気だったと分かっている＝<b>底が無い</b>（ジ・エンドの奈落、
     * 探索範囲の下端より深い大空洞）。落ちれば助からない。
     */
    private static final int NOTHING_BELOW = Integer.MIN_VALUE;

    /**
     * {@link #firstNonAirBelow}が未ロードチャンクに当たって走査を打ち切ったことを表す。
     * {@link #NOTHING_BELOW}と分けるのが要点——{@code ChunkView}は探索範囲外も未ロードも同じ
     * {@code ABSENT}を返すので、区別せずに「読めなかったら諦める」としていた頃は、
     * <b>奈落の上に橋の辺が一本も生成されなかった</b>（ジ・エンドの島間で経路が岸で切れる正体）。
     */
    private static final int UNREADABLE_BELOW = Integer.MIN_VALUE + 1;

    /**
     * 飛び越えられる隙間の最大幅（着地点は隙間の1マス先）。疾走ジャンプは滞空約12.5tickの間に
     * 水平4マス弱しか進めないので、3マスの隙間＝4マス先への着地がバニラの到達限界になる。
     */
    private static final int MAX_JUMP_GAP_BLOCKS = 3;

    /**
     * 隙間の下に溶岩が無いかを確かめる深さ（ブロック）。{@link #COLUMN_SCAN_DEPTH}と揃える
     * （揃えないと、落下では見える深さの溶岩が跳躍では見えないという食い違いが起きる）。
     *
     * <p>「落ちても平気な高さ」で切ってはいけない。溶岩は深さに関わらず落ちれば死ぬので、
     * 落下ダメージの許容量とは別の話になる（実機で、深い割れ目の底の溶岩へ跳び損ねて死んだ）。
     */
    private static final int JUMP_LAVA_SCAN_DEPTH = COLUMN_SCAN_DEPTH;

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

    /** 時刻とキャンセルの確認間隔（ノード数）。{@code System.currentTimeMillis()}自体が安くないため間引く。 */
    private static final int CHECK_INTERVAL_MASK = (1 << 6) - 1;

    private static final int[] CARDINAL_DX = {0, 1, 0, -1};
    private static final int[] CARDINAL_DZ = {-1, 0, 1, 0};
    private static final int[] DIAGONAL_DX = {1, 1, -1, -1};
    private static final int[] DIAGONAL_DZ = {1, -1, 1, -1};

    private final CellSource view;
    private final int maxExpandedNodes;
    private final long timeLimitMillis;
    private final double heuristicWeight;
    /**
     * 層1のcost-to-goを併用するための差し替え口。{@code null}なら{@link #node}が
     * {@link Heuristic}（既定の幾何学的下限）をそのまま使う。
     */
    private final CostToGo costToGo;

    /** 連続して架けてよい橋の長さ（ブロック）。0なら無制限。{@link CellSource#maxBridgeRunBlocks()}。 */
    private final int maxBridgeRun;

    /**
     * 溶岩の上で効く橋の長さの上限（ブロック）。0なら無制限。{@link RunCaps#effectiveLavaBridgeRun()}が
     * {@link #maxBridgeRun}との厳しい方を選んだ後の値なので、ここでは単独で比べてよい。
     */
    private final int maxLavaBridgeRun;

    /**
     * 底の無い空虚の上で効く橋の長さの上限（ブロック）。0なら無制限。
     * {@link #maxLavaBridgeRun}と同じく{@link #maxBridgeRun}を織り込み済み。
     */
    private final int maxVoidBridgeRun;

    /**
     * この探索が{@link #maxBridgeRun}・{@link #maxLavaBridgeRun}・{@link #maxVoidBridgeRun}を
     * 理由に橋の移動を1つでも捨てたか。
     */
    private boolean bridgeRunCapBlocked;

    /**
     * 経路全体で置いてよい足場の総数。0なら無制限。{@link Tolerances#placedBlockBudget()}。
     *
     * <p>{@link #maxBridgeRun}が連続長なのに対しこちらは累積——短い橋を何度も架ける経路は
     * 連続長では止まらないが、持ち物は同じだけ減る。
     */
    private final int placedBudget;

    /** この探索が{@link #placedBudget}を理由に設置の移動を1つでも捨てたか。 */
    private boolean placedBudgetBlocked;

    /** 持ち物にブロックが無くても設置の移動を作ってよいか。{@link Tolerances#placeWithoutBlocks()}。 */
    private final boolean placeWithoutBlocks;

    /** この探索が「置けるブロックを持っていない」を理由に設置の移動を1つでも捨てたか。 */
    private boolean placementBlockedByEmptyInventory;

    /** {@link #trimUnfinishedPlacements}が末尾から落とした設置ステップの数。診断用。 */
    private int trimmedPlacements;

    /** 落下ダメージを何点まで許容してよいか。{@link CellSource#maxFallDamagePoints()}を上書きできる。 */
    private final int maxFallDamagePoints;

    /**
     * この探索が、落下ダメージの許容量<b>だけ</b>を理由に着地を捨てたか。
     *
     * <p>立てる床が読めていて、そこへ落ちれば届くのにダメージが許容量を超えていた場合にだけ立てる。
     * 奈落（{@link #NOTHING_BELOW}）や未ロード（{@link #UNREADABLE_BELOW}）で捨てた場合は立てない
     * ——そちらは許容量をいくら緩めても着地点が現れないので、探し直しても同じ結果になる。
     */
    private boolean fallDamageCapBlocked;

    /** 奈落・致死落差の上での跳躍を避けるか。{@link Tolerances#allowRiskyJumps()}の裏返し。 */
    private final boolean avoidRiskyJumps;

    /**
     * この探索が{@link #avoidRiskyJumps}を理由に跳躍を1つでも捨てたか。捨てていなければ、
     * 許して探し直しても結果は変わらない（{@code bridgeRunCapBlocked}と同じ役割）。
     */
    private boolean riskyJumpBlocked;

    /** 頭を水に浸けたまま続けてよい時間（tick）。0なら無制限。{@link CellSource#maxSubmergedTicks()}。 */
    private final int maxSubmergedTicks;

    /** この探索が{@link #maxSubmergedTicks}を理由に移動を1つでも捨てたか。 */
    private boolean submergedRunCapBlocked;

    /** 始点がすでに橋の途中である場合の、そこまでの連続長。 */
    private int startBridgeRun;

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

    /**
     * ノード表の初期サイズの上限。展開数上限を大きく設定されたときに、実際にはそこまで使わない表を
     * 先に確保してしまわないための頭打ち。
     */
    private static final int MAX_PRESIZED_NODES = 1 << 16;

    private final Long2ObjectOpenHashMap<PathNode> nodes;
    /**
     * ボートに乗った状態のノード。{@link PathNode#boating}が同一性の一部なので、座標が同じでも
     * 乗っている／いないは別のノードになる。{@link BlockPos#asLong}は64bitを使い切っていて
     * キーに1bit足せないため、表そのものを分けている。ボートを持っていなければ空のまま。
     */
    private final Long2ObjectOpenHashMap<PathNode> boatNodes = new Long2ObjectOpenHashMap<>();
    private final BinaryHeapOpenSet open = new BinaryHeapOpenSet();
    private final PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];
    private final double[] bestHeuristic = new double[COEFFICIENTS.length];

    private int goalX;
    private int goalY;
    private int goalZ;
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
        RunCaps caps = tolerances.caps();
        this.maxBridgeRun = caps.maxBridgeRunBlocks();
        this.maxLavaBridgeRun = caps.effectiveLavaBridgeRun();
        this.maxVoidBridgeRun = caps.effectiveVoidBridgeRun();
        this.maxSubmergedTicks = caps.maxSubmergedTicks();
        this.placedBudget = tolerances.placedBlockBudget();
        this.placeWithoutBlocks = tolerances.placeWithoutBlocks();
        this.avoidRiskyJumps = !tolerances.allowRiskyJumps();
        this.maxFallDamagePoints = tolerances.maxFallDamagePoints();
        this.view = view;
        // 落下ダメージの許容量を緩めたら下降の下限も一緒に緩める。許せる落差が伸びるほど
        // 1ブロックあたりの実コストは終端速度へ近づいて安くなるので、元の下限のままでは
        // ヒューリスティックが実コストを上回りうる（＝非許容）
        this.minDescentPerBlock = view.minDescentTicksPerBlock(this.maxFallDamagePoints);
        this.maxExpandedNodes = limits.maxExpandedNodes();
        this.timeLimitMillis = limits.timeLimitMillis();
        this.heuristicWeight = limits.heuristicWeight();
        this.costToGo = costToGo;
        // 展開したノードの周囲も含めるとノード数は展開数を超える。小さく作ると探索の途中で
        // 表の作り直しが何度も走り、そのたびに全エントリの再配置が起きる
        this.nodes = new Long2ObjectOpenHashMap<>(
                Math.min(limits.maxExpandedNodes(), MAX_PRESIZED_NODES), 0.75f);
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
        return search(start, goal, cancelled, 0, 0);
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
        return search(start, goal, cancelled, 0, goalRadius);
    }

    /**
     * 始点がすでに橋の途中であることを伝えて探索する。
     *
     * <p>粗い経由地チェーンは区間ごとに別の探索器を作るので、そのままでは
     * {@link PathNode#bridgeRun}が区間の境目で必ず0に戻る——溶岩の海を4区間に割れば、
     * 上限30でも120マスの橋が通ってしまう。前の区間の末尾で連続していた橋の長さを引き継ぐ。
     */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled, int startBridgeRun,
                              int goalRadius) {
        this.surfaceGoal = false;
        this.goalX = goal.getX();
        this.goalY = goal.getY();
        this.goalZ = goal.getZ();
        this.startBridgeRun = startBridgeRun;
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
        startNode.bridgeRun = startBridgeRun;
        startNode.cost = 0.0;
        startNode.combinedCost = orderingCost(heuristicWeight * startNode.estimatedCostToGoal,
                startNode.x, startNode.z);
        open.insert(startNode);
        Arrays.fill(bestSoFar, startNode);
        Arrays.fill(bestHeuristic, startNode.estimatedCostToGoal);

        long deadline = System.currentTimeMillis() + timeLimitMillis;
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
                if (System.currentTimeMillis() >= deadline) {
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
                && Math.abs(node.y - goalY) <= Math.max(goalRadius, GOAL_VERTICAL_TOLERANCE_BLOCKS);
    }

    /**
     * ゴールに届かなかったときの到達点を選ぶ。係数の小さい（＝実際に進んだ距離を重く見る）ものから順に、
     * 始点から{@link #MIN_DIST_PATH}以上離れている候補を採用する。どれも届かない場合は始点自身を返し、
     * 空の経路＝「提示できる経路なし」として扱う。
     */
    private PathNode selectFallback(PathNode startNode) {
        double threshold = MIN_DIST_PATH * MIN_DIST_PATH;
        for (PathNode candidate : bestSoFar) {
            double dx = candidate.x - startNode.x;
            double dy = candidate.y - startNode.y;
            double dz = candidate.z - startNode.z;
            if (dx * dx + dy * dy + dz * dz > threshold) {
                return candidate;
            }
        }
        return startNode;
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
        if (termination != PathResult.Termination.REACHED_GOAL) {
            trimUnfinishedPlacements(steps);
        }
        return new PathResult(steps, termination, expanded, nodes.size() + boatNodes.size());
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
        Long2ObjectOpenHashMap<PathNode> table = boating ? boatNodes : nodes;
        long key = BlockPos.asLong(x, y, z);
        PathNode existing = table.get(key);
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
            if (goalRadius > 0) {
                // 領域ゴールでは、中心までの見積もりは半径ぶん過大＝非許容になる。
                // 最安の水平移動で半径ぶん詰められるとみなして差し引く（searchToSurfaceが
                // 「あと何マス上がるか」だけの下限へ書き換えているのと同じ考え方）
                heuristic = Math.max(0.0, heuristic - goalRadius * ActionCosts.SPRINT_ONE_BLOCK);
            }
            if (costToGo != null) {
                // 両者の大きい方を使う。Heuristicは幾何学的な下限（admissible）、costToGoは
                // 層1が壁や溶岩の海を回避した見積もりだが、崖ペナルティ等の「発明された」重みを
                // 含むため厳密な下限ではない——大きい方を取っても許容性は壊れない
                // （Heuristic単独で既にadmissibleなので、それより小さいcostToGoを使っても
                // 損はしない。costToGoの方が大きい場面でだけ、より現実に近い見積もりへ差し替わる）
                heuristic = Math.max(heuristic, costToGo.estimate(x, y, z));
            }
        }
        PathNode created = new PathNode(x, y, z, boating, heuristic);
        table.put(key, created);
        return created;
    }

    private void expand(PathNode current) {
        for (int i = 0; i < CARDINAL_DX.length; i++) {
            int dx = CARDINAL_DX[i];
            int dz = CARDINAL_DZ[i];
            addTraverse(current, dx, dz);
            addAscend(current, dx, dz);
            addDescend(current, dx, dz);
            addSwim(current, dx, dz);
            addBoatPaddle(current, dx, dz, false);
            addBoatEnter(current, dx, dz);
            addClimb(current, dx, dz);
            addJumpGap(current, dx, dz);
        }
        for (int i = 0; i < DIAGONAL_DX.length; i++) {
            addDiagonalTraverse(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            addDiagonalSwim(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            addBoatPaddle(current, DIAGONAL_DX[i], DIAGONAL_DZ[i], true);
            addDiagonalAscend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            addDiagonalDescend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
        }
        // 上下の泳ぎ・昇降は、いま水中／梯子の中にいるときしか始まらない。それ以外では判定ごと省く
        long standingCell = view.cell(current.x, current.y, current.z);
        if (CellData.water(standingCell)) {
            addSwimUp(current);
            addSwimDown(current);
            for (int i = 0; i < CARDINAL_DX.length; i++) {
                addSwimAscend(current, CARDINAL_DX[i], CARDINAL_DZ[i]);
            }
        }
        if (CellData.climbable(standingCell)) {
            addClimbUp(current);
            addClimbDown(current);
        }
        addPillar(current);
        // 踏み出した先の下に何があるかは落下と設置で共通なので、方向ごとに1度だけ辿る。
        // ブロックの設置を最後に評価するのは、同コストなら地形をそのまま使う移動を採用させるため
        for (int i = 0; i < CARDINAL_DX.length; i++) {
            int dx = CARDINAL_DX[i];
            int dz = CARDINAL_DZ[i];
            int obstacleY = firstNonAirBelow(current.x + dx, current.y - 1, current.z + dz);
            addFall(current, dx, dz, obstacleY);
            addBridge(current, dx, dz, obstacleY);
        }
    }

    /**
     * {@code topY}から下へ、空気ではない最初のセルのYを返す。水・地面・梯子のどれで止まったかは
     * 呼び出し側がそのセルを見て判断する（{@link CellSource}がキャッシュしているので引き直しは安い）。
     *
     * <p>何にも当たらなかった場合は{@link #NOTHING_BELOW}（読めるセルだけを辿った＝本当に底が無い）と
     * {@link #UNREADABLE_BELOW}（未ロードチャンクで走査が止まった＝下は分からない）を区別して返す。
     * {@code ChunkView}はどちらも{@code ABSENT}で表すので、ここで探索範囲との位置関係から判別する。
     */
    private int firstNonAirBelow(int x, int topY, int z) {
        for (int i = 0; i < COLUMN_SCAN_DEPTH; i++) {
            int y = topY - i;
            long cell = view.cell(x, y, z);
            if (CellData.passableEmpty(cell)) {
                continue;
            }
            if (CellData.present(cell)) {
                return y;
            }
            // 空気でも実在するセルでもない＝読めなかった。範囲内なら未ロードチャンク、
            // 範囲外なら「ここまで空気しか無かった」と分かっている
            return view.isInBounds(x, y, z) ? UNREADABLE_BELOW : NOTHING_BELOW;
        }
        return NOTHING_BELOW;
    }

    private void addTraverse(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (!CellData.standable(view.cell(x, y - 1, z))) {
            return;
        }
        double bodyCost = standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        boolean inWater = CellData.water(view.cell(x, y, z));
        relax(from, x, y, z, stepCost(x, y, z) + submerged(from, bodyCost, x, y + 1, z),
                inWater ? MoveKind.SWIM : MoveKind.TRAVERSE);
    }

    /**
     * 進入先を1マス通り抜けるのにかかる時間。水と蜘蛛の巣はどちらも当たり判定を持たないので
     * 「通れる」だけを見ると走って抜けられるように見えるが、実際には桁が違うほど遅い。
     * 蜘蛛の巣は足元と頭のどちらか一方でも掛かっていれば減速する。
     */
    private double stepCost(int x, int y, int z) {
        long feet = view.cell(x, y, z);
        if (CellData.water(feet)) {
            return ActionCosts.WALK_ONE_IN_WATER;
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
     * 同一高度での斜め移動。カーディナル4方向のみだと、斜めに続く地形で
     * 本来なら1手で行ける区間を2手のジグザグで迂回することになり不必要に遠回りになる。
     * 角の2セル（{@link #clearWithoutDigging}）が両方とも掘削なしで通行可能な場合のみ許可し、
     * 体が壁の角をすり抜ける経路を生成しないようにする。
     */
    private void addDiagonalTraverse(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (!CellData.standable(view.cell(x, y - 1, z))) {
            return;
        }
        if (!clearWithoutDigging(from.x + dx, y, from.z) || !clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        double bodyCost = standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        boolean inWater = CellData.water(view.cell(x, y, z));
        relax(from, x, y, z, stepCost(x, y, z) * ActionCosts.DIAGONAL_DISTANCE + submerged(from, bodyCost, x, y + 1, z),
                inWater ? MoveKind.SWIM : MoveKind.DIAGONAL);
    }

    /**
     * 水中を斜めに泳ぐ。{@link #addDiagonalTraverse}は足場を要求するので水中では成立せず、
     * これが無いと泳ぎだけがカーディナル4方向に縛られる——斜めに進むのに2手（実コストの1.41倍）
     * 払うことになり、海を渡る経路が実際より高く見積もられるうえ展開ノード数も増える。
     *
     * <p>角2セルの通行可能性を求めるのは{@link #addDiagonalTraverse}と同じ理由（体が壁の角を
     * すり抜けないように）。
     */
    private void addDiagonalSwim(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(view.cell(x, y - 1, z))) {
            // 足場があるなら同じ移動をDiagonalTraverse側が作る。2種類のMoveKindで二重に作らない
            return;
        }
        if (!CellData.water(view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(view.cell(x, y + 1, z))) {
            return;
        }
        if (!clearWithoutDigging(from.x + dx, y, from.z) || !clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        relax(from, x, y, z, ActionCosts.SWIM_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE, MoveKind.SWIM);
    }

    /**
     * ボートが浮けるセルか。水面＝「そのセルが水で、真上は水ではなく体を置ける」。
     * 水中の途中の高さにボートは浮かないので、この判定が船の高さそのものになる。
     */
    private boolean isBoatSurface(int x, int y, int z) {
        long here = view.cell(x, y, z);
        long above = view.cell(x, y + 1, z);
        return CellData.water(here) && !CellData.water(above)
                && CellData.occupiableWithoutDigging(above);
    }

    /**
     * 水面をボートで進む。1マスあたりは泳ぎの半分以下。乗っている状態からしか出ないので、
     * 乗り降りの手間（{@link ActionCosts#BOAT_OVERHEAD_TICKS}）は{@link #addBoatEnter}で必ず先に払う。
     *
     * <p>水面から降りる移動は既存のTraverse/Ascendがそのまま担う——降りる手間は入口の
     * オーバーヘッドに畳み込んである。
     */
    private void addBoatPaddle(PathNode from, int dx, int dz, boolean diagonal) {
        if (!from.boating) {
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        if (!isBoatSurface(x, from.y, z)) {
            return;
        }
        if (diagonal && (!clearWithoutDigging(x, from.y, from.z)
                || !clearWithoutDigging(from.x, from.y, z))) {
            return;
        }
        double cost = ActionCosts.PADDLE_ONE_BLOCK * (diagonal ? ActionCosts.DIAGONAL_DISTANCE : 1.0);
        relaxBoating(from, x, from.y, z, cost, MoveKind.BOAT_PADDLE);
    }

    /**
     * ボートを出して乗り込む。乗り降りの手間をここで1度だけ払うので、短い水路では泳いで渡る方が
     * 安いままになる（損益分岐は{@link ActionCosts#BOAT_OVERHEAD_TICKS}参照）。
     *
     * <p>岸から漕ぎ出す場合と、泳いでいる途中で出す場合の両方がある。水面は岸より1マス低いのが
     * 普通なので、同じ高さと1つ下の両方を試す。
     */
    private void addBoatEnter(PathNode from, int dx, int dz) {
        if (!view.boatAvailable() || from.boating) {
            return;
        }
        // 岸に立っているか、水面に浮いているか。水中で潜ったままボートは出せない
        boolean onShore = CellData.standable(view.cell(from.x, from.y - 1, from.z))
                && !CellData.water(view.cell(from.x, from.y, from.z));
        if (!onShore && !isBoatSurface(from.x, from.y, from.z)) {
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        for (int y = from.y; y >= from.y - 1; y--) {
            if (isBoatSurface(x, y, z)) {
                relaxBoating(from, x, y, z,
                        ActionCosts.PADDLE_ONE_BLOCK + ActionCosts.BOAT_OVERHEAD_TICKS, MoveKind.BOAT_ENTER);
                return;
            }
        }
    }

    /** 立った姿勢が占める2セルを、掘らずにそのまま通り抜けられるか。 */
    private boolean clearWithoutDigging(int x, int y, int z) {
        return CellData.occupiableWithoutDigging(view.cell(x, y, z))
                && CellData.occupiableWithoutDigging(view.cell(x, y + 1, z));
    }

    private void addAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.standable(view.cell(x, from.y, z))) {
            return;
        }
        // 踏み切り地点の頭上。塞がっていればそのままではジャンプできないが、洞窟では天井を1マス
        // 崩して上がるのが普通の手段なので、掘れるなら掘るという選択肢として残す
        double clearanceCost = columnCost(from.x, from.y + 2, from.y + 2, from.z, null);
        if (Double.isInfinite(clearanceCost)) {
            return;
        }
        double bodyCost = standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        relax(from, x, y, z,
                ActionCosts.ascendOneBlock(takeoffSpeedFactor(from.x, from.y, from.z))
                        + submerged(from, clearanceCost + bodyCost, x, y + 1, z),
                MoveKind.ASCEND);
    }

    private void addDescend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y - 1;
        int z = from.z + dz;

        // 水面へ踏み込む場合は足場が要らない。海岸は水面より1マス高いのが普通なので、
        // これが無いと岸から海に入る手段そのものが無くなる
        boolean intoWater = CellData.water(view.cell(x, y, z));
        if (!intoWater && !CellData.standable(view.cell(x, y - 1, z))) {
            return;
        }
        double bodyCost = descendingBodyCost(x, from.y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        double baseCost = intoWater ? ActionCosts.SWIM_ONE_BLOCK
                : ActionCosts.descendOneBlock(takeoffSpeedFactor(from.x, from.y, from.z));
        relax(from, x, y, z, baseCost + submerged(from, bodyCost, x, y + 1, z),
                intoWater ? MoveKind.SWIM_DESCEND : MoveKind.DESCEND);
    }

    /**
     * 斜め1マスで1段登りながら進む（近距離レパートリー拡充）。カーディナル4方向限定の
     * {@link #addAscend}だと、斜めに続く階段状の地形で本来1手の区間が「登ってから横へ」の2手に
     * 分解されてしまう。{@link #addDiagonalTraverse}と同じく、体が壁の角をすり抜けないよう
     * 角2セルの掘削なし通行可能性を求める。
     *
     * <p>掘削は許可しない。角を抜ける移動で掘るくらいなら、カーディナルで素直に掘る方が安全で
     * コストも正しく出る。{@link #addAscend}と同じくジャンプ時間支配のモデルなので、
     * 地形の速度倍率（氷・ソウルサンド等）は見ない。
     */
    private void addDiagonalAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.standable(view.cell(x, from.y, z))) {
            return;
        }
        // 角2列を到着高さで見る。踏み出し高さの角は段差そのものなので塞がっていて構わない
        if (!clearWithoutDigging(from.x + dx, y, from.z) || !clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        // 踏み切り地点の頭上。塞がっていると跳べない
        if (!CellData.occupiableWithoutDigging(view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        if (!clearWithoutDigging(x, y, z)) {
            return;
        }
        relax(from, x, y, z, ActionCosts.diagonalAscendOneBlock(takeoffSpeedFactor(from.x, from.y, from.z)),
                MoveKind.DIAGONAL_ASCEND);
    }

    /**
     * 斜め1マスで1段降りながら進む。{@link #addDiagonalAscend}と同じ狙い。掘削は許可しない。
     *
     * <p>{@link #addDescend}と違い水面への踏み込みは扱わない（床は{@code standable}限定）。
     * 海岸線の水際はカーディナル側が既に扱っており、斜めまで足すと水際で経路が細かく揺れる。
     */
    private void addDiagonalDescend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y - 1;
        int z = from.z + dz;

        if (!CellData.standable(view.cell(x, y - 1, z))) {
            return;
        }
        // 角2列を踏み出し高さで見る
        if (!clearWithoutDigging(from.x + dx, from.y, from.z) || !clearWithoutDigging(from.x, from.y, from.z + dz)) {
            return;
        }
        // 到着地点の身体3セル分（Descendと同じ縦一列）。2回に分けて呼ぶことで
        // y-1〜y+1（着地の足元・頭、踏み出し地点の足元と同じ高さ）をまとめて確認する
        if (!clearWithoutDigging(x, y, z) || !clearWithoutDigging(x, y + 1, z)) {
            return;
        }
        // 身体が水中にある斜め下降は泳いで進むので、疾走を前提にした値段では速すぎる——
        // 泳ぎの斜め(7.857)より安くなり、水中で上下にジグザグして進む経路が出る。
        // 水面へ踏み込む側は上のstandable要求で既に除いてあるので、ここで見るのは
        // 「もう水の中にいる」場合だけ
        boolean swimming = CellData.water(view.cell(from.x, from.y, from.z)) || CellData.water(view.cell(x, y, z));
        double cost = swimming
                ? ActionCosts.SWIM_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE
                : ActionCosts.diagonalDescendOneBlock(takeoffSpeedFactor(from.x, from.y, from.z));
        relax(from, x, y, z, cost, swimming ? MoveKind.SWIM_DESCEND : MoveKind.DIAGONAL_DESCEND);
    }

    /**
     * 隙間を飛び越える（同一高度、カーディナル方向のみ）。
     *
     * <p>これが無いと、誰でも何も考えずに跨げる1マスの割れ目（小川・洞窟の裂け目・峡谷の枝）で、
     * ブロックを置いて渡るか大きく迂回することになる。
     *
     * <p>{@link #MAX_JUMP_GAP_BLOCKS}マスまで。これは疾走ジャンプの到達限界そのもので、
     * これ以上は助走をどれだけ取っても届かない。跳躍は外せば落ちるので、そもそも提示するかどうかを
     * {@link CellSource#jumpGapEnabled()}で切れるようにしてある。
     *
     * <p>近い隙間から順に試し、最初に着地できた距離で確定する。同じ方向に複数の着地点があるとき、
     * 手前に降りられるなら遠くまで跳ぶ理由が無い（{@link ActionCosts#jumpAcrossGap}も遠いほど高い）。
     *
     * <p>空中では掘れないので、通り抜ける空間は掘削なしで通れることを求める。頭上も見る —
     * ジャンプは1.25マス上がるので、天井があると跳べずに隙間へ落ちる。
     */
    private void addJumpGap(PathNode from, int dx, int dz) {
        if (!view.jumpGapEnabled()) {
            return;
        }
        int y = from.y;
        if (CellData.standable(view.cell(from.x + dx, y - 1, from.z + dz))) {
            // 隙間ではなく床がある。歩いて行けるならTraverseの方が安い
            return;
        }
        // 踏み切り地点の頭上。ここが塞がっていると跳躍そのものが成立しない
        if (!CellData.occupiableWithoutDigging(view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        // ソウルサンド・蜂蜜の上からは疾走の最高速度が出ない。到達距離は踏み切り時の水平速度で
        // 決まる（滞空時間は距離に依らず一定）ので、減速したまま跳ぶと必ず隙間に落ちる。
        // 倍率の探し方は歩行コスト（{@link #stepCost}）と同じくバニラの{@code getBlockSpeedFactor}に倣う
        if (slowedTakeoff(from.x, y, from.z)) {
            return;
        }
        // 梯子・ツタに掴まったままでは跳べない。onGround()がfalseなのでjumpFromGround()自体が
        // 呼ばれず（LivingEntity#aiStep）、掴まったまま接地していてもhandleOnClimbableが
        // 水平速度を±0.15に固定するので、疾走の0.286も踏み切り加算の0.2も残らない
        if (CellData.climbable(view.cell(from.x, y, from.z))
                || !CellData.standable(view.cell(from.x, y - 1, from.z))) {
            return;
        }
        // 助走が要る。疾走の最高速度は静止から約5tick（≒1マス）かけて乗り、滞空中の加速は
        // 0.02/tickしかない（LivingEntity#getFlyingSpeed）ので、到達距離は踏み切り速度で
        // そのまま決まる。1マス幅の足場からでは自分のマスの中（約0.5マス）しか助走できず、
        // 3マスの隙間は理論上届いても余裕がゼロになる——跳べと指示するだけで、外して落ちるのは
        // 人間の方（JUMP_REACH_PENALTYと同じ方針）
        if (!hasRunUp(from, y, dx, dz)) {
            return;
        }

        // 跳び越す隙間の下がどれだけ深いか。{@link #addBridge}と同じ値段表で危険料を積む
        double dropRisk = 0.0;
        for (int gap = 1; gap <= MAX_JUMP_GAP_BLOCKS; gap++) {
            int gapX = from.x + gap * dx;
            int gapZ = from.z + gap * dz;
            // 跳び越える空間が塞がっていれば、その先へはどれだけ助走しても届かない
            if (!clearWithoutDigging(gapX, y, gapZ)
                    || !CellData.occupiableWithoutDigging(view.cell(gapX, y + 2, gapZ))) {
                return;
            }
            // 下が溶岩の隙間は跳ばない。跳躍は外せば落ちるという前提でコストを積んであるが、
            // 溶岩ではその「外したとき」が死なので、コストの多寡で釣り合う話ではなくなる。
            // 下が読めない（未ロード）隙間も同じ扱いにする——溶岩でないと言い切れない
            if (lavaOrUnknownBelow(gapX, y, gapZ)) {
                return;
            }
            int gapDrop = missDrop(gapX, y, gapZ);
            if (avoidRiskyJumps && gapDrop >= view.fatalFallBlocks()) {
                // 外したら死ぬ隙間。溶岩と違って「その隙間の上を跳ぶ手そのものを永久に消す」のではなく、
                // 回り込む道が一本も無いと分かったときだけ緩和の梯子が開ける（riskyJumpBlocked）
                riskyJumpBlocked = true;
                return;
            }
            dropRisk += ActionCosts.dropRiskPenalty(gapDrop, view.fatalFallBlocks());
            int x = from.x + (gap + 1) * dx;
            int z = from.z + (gap + 1) * dz;
            if (!CellData.standable(view.cell(x, y - 1, z))) {
                // まだ着地できない。隙間はもう1マス続く
                continue;
            }
            if (!clearWithoutDigging(x, y, z)) {
                return;
            }
            relax(from, x, y, z, ActionCosts.jumpAcrossGap(gap) + dropRisk, MoveKind.JUMP);
            return;
        }
    }

    /**
     * この隙間を跳び損ねたら何マス落ちるか。底が無い（奈落）なら
     * {@link CellSource#fatalFallBlocks()}を返す。
     *
     * <p>落差の測り方は{@link #addFall}・{@link #addBridge}と揃えてある——あちらが「意図して降りる」
     * 高さを見るのに対し、こちらは同じ落差を「跳んで外したとき」として見る。溶岩と未ロードは
     * 呼び出し側（{@code lavaOrUnknownBelow}）が先に弾いている。
     */
    private int missDrop(int x, int y, int z) {
        int obstacleY = firstNonAirBelow(x, y - 1, z);
        if (obstacleY == NOTHING_BELOW || obstacleY == UNREADABLE_BELOW) {
            // 未ロードは呼び出し側が既に弾いている。ここへは来ない想定だが、
            // 「読めない＝危険ではない」と倒さないよう明示しておく
            return view.fatalFallBlocks();
        }
        long obstacle = view.cell(x, obstacleY, z);
        if (CellData.water(obstacle)) {
            // 着水はバニラが落下距離をリセットするので、どれだけ落ちても死なない
            return 0;
        }
        return y - obstacleY - 1;
    }

    /** 踏み切り地点が減速ブロックの上か（バニラの{@code Entity#getBlockSpeedFactor}と同じ探し方）。 */
    private boolean slowedTakeoff(int x, int y, int z) {
        return takeoffSpeedFactor(x, y, z) < 1.0;
    }

    /**
     * この地点から踏み切るときの水平速度倍率。探し方はバニラの{@code Entity#getBlockSpeedFactor}と
     * 同じで、足元のセルに倍率が無ければ実際に踏んでいる1つ下のブロックを見る。
     *
     * <p><b>1.0を超える側（氷）は返さない。</b>{@link Heuristic}は昇りの下限に
     * {@code ASCEND_ONE_BLOCK}、水平の下限に{@code SPRINT_ONE_BLOCK}を置いているので、
     * そこを割ると非許容になる。速くなる側の得は{@link #stepCost}が水平移動でだけ表す。
     */
    private double takeoffSpeedFactor(int x, int y, int z) {
        double speedFactor = CellData.speedFactor(view.cell(x, y, z));
        if (speedFactor == 1.0) {
            speedFactor = CellData.speedFactor(view.cell(x, y - 1, z));
        }
        return Math.min(1.0, speedFactor);
    }

    /**
     * 跳び損ねたときに落ちる先が溶岩か、それとも見通せないか。足元から
     * {@link #JUMP_LAVA_SCAN_DEPTH}マス下までを見る。
     *
     * <p>奈落（読めるセルだけを辿って底に当たらない）は{@code false}を返す——落ちれば死ぬのは
     * 溶岩と同じだが、そちらは{@code PathSafetyChecker#assessJumpRisk}が{@link PathRisk#VOID_BELOW}で
     * 警告する担当になっている。ここで一律に禁止すると、ジ・エンドでは全ての隙間が奈落の上なので
     * 跳ぶ移動が丸ごと消える。
     */
    private boolean lavaOrUnknownBelow(int x, int y, int z) {
        for (int depth = 1; depth <= JUMP_LAVA_SCAN_DEPTH; depth++) {
            int cellY = y - depth;
            long cell = view.cell(x, cellY, z);
            if (CellData.lava(cell)) {
                return true;
            }
            if (CellData.standable(cell)) {
                // 溶岩より先に床がある。ここへ落ちても溶岩には触れない
                return false;
            }
            if (!CellData.present(cell)) {
                // 読めなかった。範囲内なら未ロードチャンクで、その下が溶岩かどうか本当に分からない
                // ——外したときの結末が読めない以上、跳べとは言えない。範囲外なら「ここまで空気しか
                // 無かった」と分かっているので奈落として扱う（上の注記）
                return view.isInBounds(x, cellY, z);
            }
        }
        return false;
    }

    /**
     * 水中を泳いで進む。足場を要求しないのが{@link #addTraverse}との違いで、これが無いと海は
     * 「水底まで降りて歩く」か「水面の上にブロックを置いて渡る」でしか越えられない。
     */
    private void addSwim(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(view.cell(x, y - 1, z))) {
            // 足場があるなら同じ移動をTraverse側が作る。2種類のMoveKindで二重に作らない
            return;
        }
        if (!CellData.water(view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(view.cell(x, y + 1, z))) {
            return;
        }
        relax(from, x, y, z, ActionCosts.SWIM_ONE_BLOCK, MoveKind.SWIM);
    }

    /** 水中を浮上する。水面まで上がってから水平に泳ぐ経路を作るために要る。 */
    private void addSwimUp(PathNode from) {
        int y = from.y + 1;
        if (!CellData.water(view.cell(from.x, y, from.z))
                || !CellData.occupiableWithoutDigging(view.cell(from.x, y + 1, from.z))) {
            return;
        }
        relax(from, from.x, y, from.z, ActionCosts.SWIM_UP_ONE_BLOCK, MoveKind.SWIM_UP);
    }

    /**
     * 水中を進みながら1マス浮上する。{@link #addSwimUp}が真上にしか上がれないので、これが無いと
     * 浮上が「その場で上がってから横へ」というL字になる——泳いでいる人間は目的地を向いたまま
     * 斜めに上がるので、案内としても不自然に見える。
     *
     * <p>陸の{@link #addAscend}と同じく、踏み切り地点の頭上（＝上がっていく途中で体が通るセル）の
     * 通行可能性を求める。掘削は許可しない（水中で掘って上がるくらいなら、開いている所まで
     * 泳いだ方が速い）。
     */
    private void addSwimAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.water(view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(view.cell(x, y + 1, z))) {
            return;
        }
        if (!CellData.occupiableWithoutDigging(view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        relax(from, x, y, z, ActionCosts.SWIM_ASCEND_ONE_BLOCK, MoveKind.SWIM_ASCEND);
    }

    /** 水中を潜る。水底の地形沿いに進む方が近い場合に使う。 */
    private void addSwimDown(PathNode from) {
        int y = from.y - 1;
        if (!CellData.water(view.cell(from.x, y, from.z))) {
            return;
        }
        relax(from, from.x, y, from.z, ActionCosts.SWIM_DOWN_ONE_BLOCK, MoveKind.SWIM_DOWN);
    }

    /**
     * 梯子・ツタに横から取り付く。足場を要求しないのが{@link #addTraverse}との違いで、
     * 縦穴の途中に張られた梯子へ移るにはこれが要る。
     */
    private void addClimb(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(view.cell(x, y - 1, z))) {
            // 足場があるなら同じ移動をTraverse側が作る
            return;
        }
        if (!CellData.climbable(view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(view.cell(x, y + 1, z))) {
            return;
        }
        relax(from, x, y, z, ActionCosts.WALK_ONE_BLOCK, MoveKind.CLIMB);
    }

    /** 梯子・ツタを登る。上り切った先へは、そこから水平移動で降りる（頂上より上には行けない）。 */
    private void addClimbUp(PathNode from) {
        int y = from.y + 1;
        if (!CellData.climbable(view.cell(from.x, y, from.z))
                || !CellData.occupiableWithoutDigging(view.cell(from.x, y + 1, from.z))) {
            return;
        }
        relax(from, from.x, y, from.z, ActionCosts.LADDER_UP_ONE_BLOCK, MoveKind.CLIMB_UP);
    }

    private void addClimbDown(PathNode from) {
        int y = from.y - 1;
        if (!CellData.climbable(view.cell(from.x, y, from.z))) {
            return;
        }
        relax(from, from.x, y, from.z, ActionCosts.LADDER_DOWN_ONE_BLOCK, MoveKind.CLIMB_DOWN);
    }

    /**
     * 縁から踏み出して落ちる。1マス下は{@link #addDescend}が扱うので、ここは2マス以上の落下だけ。
     *
     * <p>既定では落下ダメージを受ける高さを提示しない（{@link ActionCosts#SAFE_FALL_BLOCKS}まで）。降りる
     * 手段は掘り下げ（Descend + 掘削）もあるので、痛い近道を勧めるより階段状に降りる経路を出す方がよい。
     * ただし着水はバニラが落下距離をリセットするので、高さを問わず安全に降りられる。
     *
     * <p>設定で許可された場合だけ、体力から決まる上限までのダメージ落下と、水バケツMLGによる無傷の
     * 落下を候補に加える（{@link CellSource#maxFallDamagePoints}／{@link CellSource#canMlgWaterBucket}）。
     */
    private void addFall(PathNode from, int dx, int dz, int obstacleY) {
        if (obstacleY == NOTHING_BELOW || obstacleY == UNREADABLE_BELOW) {
            // 底が無い（奈落）か、下に何があるか読めない。どちらも着地点を約束できない
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        // 踏み出す先の2マスが空いていないと縁から出られない。落下中は掘れないので空気であること
        if (!CellData.passableEmpty(view.cell(x, from.y, z))
                || !CellData.passableEmpty(view.cell(x, from.y + 1, z))) {
            return;
        }

        // 縁を踏み出す動作も足元のブロックに減速される（落下中と着地後は無関係）
        double takeoff = takeoffSpeedFactor(from.x, from.y, from.z);
        long obstacle = view.cell(x, obstacleY, z);
        if (CellData.water(obstacle)) {
            relax(from, x, obstacleY, z, ActionCosts.fallCost(from.y - obstacleY, takeoff),
                    MoveKind.FALL_TO_WATER);
            return;
        }
        if (!CellData.standable(obstacle)) {
            // 柵や梯子など、落ちても足場にならないもの
            return;
        }
        int drop = from.y - obstacleY - 1;
        if (drop < 2) {
            return;
        }
        if (drop <= ActionCosts.SAFE_FALL_BLOCKS) {
            relax(from, x, obstacleY + 1, z, ActionCosts.fallCost(drop, takeoff), MoveKind.FALL);
            return;
        }

        // バニラのダメージは ceil(落下距離 - SAFE_FALL_DISTANCE)。落下距離が整数マスなのでそのまま引き算になる
        int damage = drop - ActionCosts.SAFE_FALL_BLOCKS;
        boolean mlg = view.canMlgWaterBucket();
        if (mlg) {
            relax(from, x, obstacleY + 1, z,
                    ActionCosts.fallCost(drop, takeoff) + ActionCosts.MLG_WATER_OVERHEAD_TICKS,
                    MoveKind.FALL_MLG);
        }
        if (damage > maxFallDamagePoints) {
            // 立てる床はそこにあり、届きもする。許容量だけが足りない——緩めれば道になる可能性がある。
            // ここまで来ている時点で奈落でも未ロードでもないので、フラグは「緩める意味がある」を正しく指す。
            //
            // 水バケツMLGで同じ着地を既に作れているなら立てない。その辺は許容量に関わらず通れるので、
            // 緩めても増える移動が無い——立てると、緩和の梯子が何も変えずに探索を繰り返すだけになる
            fallDamageCapBlocked |= !mlg;
            return;
        }
        relax(from, x, obstacleY + 1, z,
                ActionCosts.fallCost(drop, takeoff) + damage * ActionCosts.FALL_DAMAGE_PENALTY_PER_POINT,
                MoveKind.FALL_DAMAGE);
    }

    /**
     * この探索が、落下ダメージの許容量を理由に着地を捨てたか。捨てていない場合、許容量を緩めて
     * 探し直しても結果は変わらない。
     */
    public boolean fallDamageCapBlocked() {
        return fallDamageCapBlocked;
    }

    /**
     * 床が存在しない空洞（ジ・エンドの島間など）をブロックを置いて渡る移動。Pillarの水平版。掘削とは逆に、床セルが完全な空虚（{@code passableEmpty}）である場合のみ許可する — 水面の
     * 上には置かない（{@link PathSafetyChecker}の事後チェックとは別に、そもそも設置対象として扱わない）。
     *
     * <p>水面のすぐ上も空気なので、床セルだけを見ても空虚と区別がつかない。海の上にブロックを敷いて
     * 渡るのは泳いで渡れる場所にわざわざ足場を作ることになるので、下に水が見えたらこの移動を作らない。
     *
     * <p>溶岩は{@link CellSource#lavaBridgingEnabled()}のときだけ、
     * {@link ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}を上乗せして許可する。床セルが溶岩そのものでも
     * よい——置いたブロックが溶岩を置き換えるバニラの橋架けなので、身体が溶岩に入るわけではない
     * （入る経路は{@link #standingBodyCost}が既にINFEASIBLEで弾く）。
     */
    /** 踏み切り地点の手前（跳躍方向の逆側）に、走り込める足場が1マスあるか。 */
    private boolean hasRunUp(PathNode from, int y, int dx, int dz) {
        int x = from.x - dx;
        int z = from.z - dz;
        return CellData.standable(view.cell(x, y - 1, z)) && clearWithoutDigging(x, y, z);
    }

    private void addBridge(PathNode from, int dx, int dz, int obstacleY) {
        if (!canPlace()) {
            return;
        }
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        long floorCell = view.cell(x, y - 1, z);
        boolean overLava = CellData.lava(floorCell);
        // 当たり判定が無いことと、そこへ置けることは別。しだれツタ・ねじれツタ・松明・レールは
        // 体が通り抜けられるがreplaceableではないので、狙って置いても隣のセルへ飛ぶ
        // （BlockPlaceContext#getClickedPos）——案内した位置には絶対に置かれない
        if (!overLava && (CellData.standable(floorCell) || !CellData.replaceable(floorCell))) {
            return;
        }
        // ツタ・梯子の中と、その隣には置かない。掴まれるものは当たり判定こそ薄いが視線は遮るので、
        // 置く先を狙うとそちらに当たる——普通のツタはreplaceableなのでブロックはツタのセルへ入り、
        // 梯子・しだれツタはreplaceableではないのでその隣のセルへ飛ぶ。どちらにしても
        // 案内した位置には置かれない（上のBlockPlaceContext#getClickedPosの注記が、1マス隣で起きる形）
        if (climbableNear(x, y - 1, z)) {
            return;
        }
        // 床が溶岩なら、置くブロックがその溶岩を置き換える。何がそれを支えているかは関係ない
        boolean lavaFarBelow = false;
        boolean voidBelow = false;
        // 床は在るが、そこまでの落差が致死。奈落と同じく「外せば死ぬ」橋
        boolean fatalDropBelow = false;
        // 足場を外したときに落ちる高さ。値段は{@link ActionCosts#dropRiskPenalty}で連続に決める
        int dropBelow = 0;
        if (!overLava) {
            if (obstacleY == UNREADABLE_BELOW) {
                // 未ロードチャンクで走査が止まった。下に何があるか本当に分からないので置かない
                // ——水面の上に足場を敷けと言い出すのはこの取り違えから起きる
                return;
            }
            if (obstacleY == NOTHING_BELOW) {
                // 読めるセルだけを辿って何にも当たらなかった＝底が無い。外せば助からないので、
                // 溶岩と同じ扱いにする
                voidBelow = true;
                dropBelow = view.fatalFallBlocks();
            } else {
                long obstacle = view.cell(x, obstacleY, z);
                if (CellData.water(obstacle)) {
                    return;
                }
                // 足元・隣接には溶岩が無くても、遥か下（ネザーの開けた空洞の底など）が溶岩なら
                // 設置を外したときの結末は変わらない。hasAdjacentLavaは足元1マス下しか見ないので、
                // ここを見ないと「空中で溶岩の上を長々と橋渡しする」経路が無傷の橋と同じ扱いになる
                lavaFarBelow = CellData.lava(obstacle);
                // 床は在る。だが<b>何マス下か</b>を見ないと、外したときの結末が分からない。
                // 落差が致死なら結末は奈落と同じ（死ぬ）なので、値段も規律もそちらへ揃える——
                // ユーザー報告「下にブロックあるからいいとか思ってそう」がこれ。
                // 落差の測り方は{@link #missDrop}と同じ（水は上で弾いてある）
                dropBelow = y - obstacleY - 1;
                fatalDropBelow = !lavaFarBelow && dropBelow >= view.fatalFallBlocks();
            }
        }
        // 水に接する場所へは置かない。流れ込んで足場ごと押し流される
        if (hasAdjacentWater(x, y - 1, z)) {
            return;
        }
        // 底の無い空虚の上では、目標へ近づく向きにしか橋を伸ばさない。
        //
        // 橋は地形ではなくプレイヤーが作る構造物で、奈落の上には迂回すべき地形がそもそも無い。
        // 浮遊島や柱が邪魔なら「どの岸から出るか」で避けることになり、その選択は本物の地面の上で
        // 起きるのでこの制限を受けない。逆に許すと、岸のあらゆるセルから全方位へ上限いっぱいの橋が
        // 展開対象になり、探索空間が線から面へ膨らむ。
        //
        // 合成の群島（島4つ・間は奈落）で、島1つぶんの区間を測った実測: 10万ノードを焼いて予算切れ
        // → 64978ノードで到達。測る単位は区間1本にすること——全行程で測ると、どのみち予算が
        // 足りずにどの条件でも失敗するので、効いているかどうかが見えない。
        // カーディナル移動はL1距離を必ず±1変えるので、ここで落ちるのは遠ざかる向きだけになる。
        //
        // 既知の穴: 奈落の上に浮いた障害物が真横への迂回を強いる地形では経路を失う。踏んだら、
        // 連続長が短いうちだけ全方位を許す、といった形で緩めること
        if (voidBelow && Math.abs(x - goalX) + Math.abs(z - goalZ)
                >= Math.abs(from.x - goalX) + Math.abs(from.z - goalZ)) {
            return;
        }
        boolean lavaNearby = overLava || lavaFarBelow || hasAdjacentLava(x, y - 1, z);
        if (lavaNearby && !view.lavaBridgingEnabled()) {
            return;
        }
        // 奈落・溶岩の上では、掘らないと通れない場所へは架けない。
        //
        // 1手の中に「床を置く」と「身体のセルを掘る」が同居すると、案内は<b>順序を表現できない</b>。
        // 正しいのは「先に床を置く→後で掘る」だが、掘る枠が見えている以上そちらを先にやるのが自然で、
        // 掘った先の足元は<b>まだ床が無い</b>——奈落なら落ちて死ぬ。実機でユーザーが踏んだ症状は
        // 「掘るはずのブロックの横にブロックを置けと言われる」と「そのまま掘ったらダイブする」の
        // 2つに見えていたが、原因はこの1つ。
        //
        // 空中では掘れないので{@link #addJumpGap}や斜め移動が{@code clearWithoutDigging}を
        // 要求しているのと同じ規律。底のある空洞には掛けない——掘って落ちても1マス下の床に
        // 着くだけで、結末がまるで違う
        // 致死落差もここに含める。「底のある空洞には掛けない——掘って落ちても1マス下の床に
        // 着くだけ」という上の理由づけは<b>浅い底にしか成り立たない</b>。43マス下の床は
        // 底があるうちに入らない。ただし詰みを増やさないよう、跳躍と同じ緩和の梯子
        // （{@code avoidRiskyJumps}）に載せる——奈落・溶岩は従来どおり無条件
        if ((voidBelow || lavaNearby || (fatalDropBelow && avoidRiskyJumps))
                && !clearWithoutDigging(x, y, z)) {
            return;
        }
        // 連続した橋の長さで打ち切る。ここで「重いコスト」ではなく「移動を作らない」を選ぶのが要点——
        // 重みで抑えると、A*は安い辺から展開するので橋に手を伸ばす前に周囲を展開し尽くし、
        // 展開ノード数を焼き切ったうえで結局その先に進めない（ActionCosts#LAVA_BRIDGE_PENALTY_TICKS
        // に記録された実測そのもの）。辺を作らなければ、探索は最初から迂回路だけを見る。
        //
        // 溶岩と奈落の上だけは別（より短い）上限で切る。底のある空洞なら足場を外しても落ちるだけだが、
        // この2つでは死ぬので、同じ長さの橋でも許してよい範囲が違う。両方に当たる橋は厳しい方で切る
        int cap = maxBridgeRun;
        if (lavaNearby) {
            cap = RunCaps.stricter(cap, maxLavaBridgeRun);
        }
        if (voidBelow || fatalDropBelow) {
            cap = RunCaps.stricter(cap, maxVoidBridgeRun);
        }
        int bridgeRun = from.bridgeRun + 1;
        if (cap > 0 && bridgeRun > cap) {
            bridgeRunCapBlocked = true;
            return;
        }
        // 持ち物の枚数で経路全体の設置数を切る。連続長（上の cap）は「1本の橋が何マス続いてよいか」
        // なので、短い橋を何度も架ける経路は素通りする——途中で尽きると、そこから先の案内は
        // 実行できない
        if (placedBudgetExceeded(from)) {
            return;
        }
        double bodyCost = standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        // 進む1マスぶんだけ踏み切り地点の倍率で割る。置いたブロックの上は等速なので、遅いのは
        // ソウルサンド等の上から踏み出す分だけ。設置の手間（PLACE_BLOCK_OVERHEAD_TICKS）は
        // 立っているブロックと無関係なので割らない
        double cost = ActionCosts.SPRINT_ONE_BLOCK / takeoffSpeedFactor(from.x, from.y, from.z)
                + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS
                + (lavaNearby ? ActionCosts.LAVA_BRIDGE_PENALTY_TICKS : 0.0)
                // 遥か下が溶岩なら落差は測らない。外したときの結末は既に溶岩の割増が表しているので、
                // 深さで二重に取ると測っていないネザーの橋の値段まで動く
                + (lavaFarBelow ? 0.0 : ActionCosts.dropRiskPenalty(dropBelow, view.fatalFallBlocks()))
                + submerged(from, bodyCost, x, y + 1, z);
        relax(from, x, y, z, cost, MoveKind.BRIDGE, bridgeRun);
    }

    /**
     * 跳びながら足元にブロックを置いて真上へ1マス登る（Pillar）。
     * {@link #addBridge}の垂直版で、これが無いと断崖はどれだけ低くても迂回するしかない。
     *
     * <p>置く先は自分がいま立っているセルそのものなので、そこが本当の空気であることを求める。
     * 水に浮いた状態では踏み切れず、梯子に掴まっている場所には置けない。
     *
     * <p>足場は要求しない。連続して積み上げる2手目以降は、自分が直前に置いたブロックの上に
     * 立っている——地形データにはまだ存在しないセルなので、足場を求めると1マスしか登れなくなる。
     *
     * <p>新しい頭になるセル（2つ上）だけが未検証。新しい足元は元の頭で、そこに立っている時点で
     * 通行可能性は確認済み。{@link #addAscend}と同じく、天井が塞がっていても掘れるなら掘って上がる。
     */
    private void addPillar(PathNode from) {
        if (!canPlace()) {
            return;
        }
        // 横に架けた橋の上からは積み始めない。1マス幅の足場の上で跳んで足元に置く動作で、
        // 奈落や溶岩の上ではまず外す——案内として出してよい手ではない。
        //
        // 条件に「直前も柱」を入れるのが要点。柱自身も連続長を伸ばすので、{@code bridgeRun > 0}
        // だけで切ると断崖を登る塔が1マスで止まる。
        //
        // 理由は安全性だけで、探索の効率には効かない（合成の群島で有無を測って差がゼロだった）。
        // 奈落の上の展開を抑えているのは{@link #addBridge}の方向の絞り込み
        if (from.bridgeRun > 0 && from.kind != MoveKind.PILLAR) {
            return;
        }
        // 柱にも連続長の上限を掛ける。{@code bridgeRun}を増やすだけで検査していなかったため、
        // 塔が探索範囲の天井まで伸び放題だった——実機（the_end）では島の立てるセルすべてから
        // 約150段が展開対象になり、51万セルを焼いて予算切れで終わっていた。
        //
        // 本当の害はノード数ではなく、そのせいで{@code EXHAUSTED}に到達できないこと。
        // {@code PathfindingExecutor}の上限緩和は「範囲内に道が無いと証明できた」ときにしか走らないので、
        // 予算切れで終わる限り一度も発動しない＝橋が上限に張り付いたまま渡り切れない。
        //
        // 溶岩・奈落の上限は掛けない。柱は実在する床から始まる（上の分岐がそれを保証する）ので、
        // 「外したら死ぬ場所に架かっている」という前提が成り立たない
        int pillarRun = from.bridgeRun + 1;
        if (maxBridgeRun > 0 && pillarRun > maxBridgeRun) {
            bridgeRunCapBlocked = true;
            return;
        }
        if (placedBudgetExceeded(from)) {
            return;
        }
        long standing = view.cell(from.x, from.y, from.z);
        // 置く先は自分がいるセルそのもの。梯子・ツタに掴まっている間は onGround() が false で
        // jumpFromGround() が呼ばれず（LivingEntity#aiStep）、掴まったまま接地していても
        // handleOnClimbable が水平・下向きの速度を±0.15に固定するので、跳んで積む動作が成立しない
        // 水はreplaceableなので、水中も「置ける場所」として素通りしていた。実際には浮いたまま
        // 踏み切れないので、案内した通りに積み上げることはできない
        if (!CellData.replaceable(standing) || CellData.climbable(standing)
                || CellData.water(standing)) {
            return;
        }
        double clearanceCost = columnCost(from.x, from.y + 2, from.y + 2, from.z, null);
        if (Double.isInfinite(clearanceCost)) {
            return;
        }
        double cost = ActionCosts.ascendOneBlock(takeoffSpeedFactor(from.x, from.y, from.z))
                + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS
                + submerged(from, clearanceCost, from.x, from.y + 2, from.z);
        // 積んだブロックの上は自分が置いた足場であって地形ではないので、橋の連続を断たない。
        // 0に戻していた頃は「橋を上限まで架ける→1マス積む→また上限まで架ける」が合法だった。
        // 実際に発動するかは展開順しだいで（bridgeRunはノードの同一性に入らないので、柱の上の
        // ノードへ別経路が同コストで届けばそちらの連続長が残る）、そのぶん質が悪い——同じ地形でも
        // 上限が効いたり効かなかったりし、効かなかった回は bridgeRunCapBlocked が立たないので
        // PathfindingExecutorの上限緩和も走らないまま階段状の経路が確定する
        relax(from, from.x, from.y + 1, from.z, cost, MoveKind.PILLAR, pillarRun);
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

    /** ブロックを置くセルの周り（真上を除く5面）に水があるか。 */
    private boolean hasAdjacentWater(int x, int y, int z) {
        return hasAdjacent(x, y, z, CellData::water);
    }

    /** ブロックを置くセルの周り（真上を除く5面）に溶岩があるか。 */
    private boolean hasAdjacentLava(int x, int y, int z) {
        return hasAdjacent(x, y, z, CellData::lava);
    }

    /**
     * ブロックを置くセルの中か周りに、掴まれるもの（ツタ・しだれツタ・梯子）があるか。
     *
     * <p>水・溶岩の隣接判定と違って<b>真上も見る</b>。真上は足場を置いた後に自分が立つセルで、
     * そこにツタが垂れていれば、置く先を狙う視線はまずそれに当たる。
     */
    private boolean climbableNear(int x, int y, int z) {
        return CellData.climbable(view.cell(x, y, z))
                || CellData.climbable(view.cell(x, y + 1, z))
                || hasAdjacent(x, y, z, CellData::climbable);
    }

    /**
     * 足場を置く移動を作ってよいか。持ち物にブロックが無くても、詰み回避で開けられていれば作る
     * （{@link Tolerances#placeWithoutBlocks()}）——出さないとジ・エンドの島渡りのように
     * 橋以外に道が無い地形で経路が原理的に出ず、しかも案内には何も現れない。
     */
    private boolean canPlace() {
        if (view.canPlaceBlocks()) {
            return true;
        }
        // 設定で断られている場合は開けない。開けてよいのは「持っていないだけ」のときだけ
        if (!view.bridgingAllowedBySettings()) {
            return false;
        }
        if (!placeWithoutBlocks) {
            placementBlockedByEmptyInventory = true;
            return false;
        }
        return true;
    }

    /**
     * {@code from}からもう1つ足場を置くと持ち物の予算を超えるか。超えるなら
     * {@link #placedBudgetBlocked}を立てて、予算を外した探し直しが要ることを呼び出し側へ伝える。
     */
    private boolean placedBudgetExceeded(PathNode from) {
        if (placedBudget <= 0 || from.placedTotal + 1 <= placedBudget) {
            return false;
        }
        placedBudgetBlocked = true;
        return true;
    }

    private boolean hasAdjacent(int x, int y, int z, LongPredicate test) {
        return test.test(view.cell(x, y - 1, z))
                || test.test(view.cell(x + 1, y, z)) || test.test(view.cell(x - 1, y, z))
                || test.test(view.cell(x, y, z + 1)) || test.test(view.cell(x, y, z - 1));
    }

    private void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        relax(from, x, y, z, edgeCost, kind, 0);
    }

    /** ボートに乗った状態のノードへ緩和する。{@link #addBoatEnter}/{@link #addBoatPaddle}専用。 */
    private void relaxBoating(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        relax(from, x, y, z, edgeCost, kind, 0, true);
    }

    /**
     * {@code bridgeRun}を明示的に渡す版。非0を渡すのは自分で置いた足場の上に着く移動
     * （{@link #addBridge}・{@link #addPillar}）だけで、それ以外は実在する床に着くので0になる。
     */
    private void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind, int bridgeRun) {
        relax(from, x, y, z, edgeCost, kind, bridgeRun, false);
    }

    private void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind, int bridgeRun,
                        boolean boating) {
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
        // 割増は経路の選択のためのもので、息の勘定（submergedTicks）には混ぜない——あちらは
        // 実際にかかる時間でなければ意味がない
        boolean gainsHeight = y > from.y;
        double tentativeCost = from.cost
                + (submerged && !gainsHeight ? edgeCost * ActionCosts.SUBMERGED_TRAVEL_PENALTY : edgeCost);
        PathNode neighbor = node(x, y, z, boating);
        if (neighbor.closed || neighbor.cost - tentativeCost <= MIN_IMPROVEMENT) {
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
    private double submerged(PathNode from, double digCost, int x, int headY, int z) {
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
    private double standingBodyCost(int x, int y, int z, List<BlockPos> cells) {
        return columnCost(x, y, y + 1, z, cells);
    }

    /**
     * 一段降りる移動で身体が通過する3セル分。{@code y}は降りる手前の高さ（足元が{@code y}、頭が{@code y+1}、
     * 降りた先が{@code y-1}）。
     */
    private double descendingBodyCost(int x, int y, int z, List<BlockPos> cells) {
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
    private double columnCost(int x, int bottomY, int topY, int z, List<BlockPos> cells) {
        double total = 0.0;
        boolean doorCharged = false;
        for (int y = bottomY; y <= topY; y++) {
            // ドアは上下2セルに分かれているが、開ける動作は1回。両方に開閉コストを払うと
            // 1枚のドアが2枚分の重さになり、ドアのある正しい通り道を避けるようになる
            boolean openable = CellData.openable(view.cell(x, y, z));
            if (openable && doorCharged) {
                continue;
            }
            double cost = occupyCost(x, y, z, cells);
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
            if (!CellData.fallingBlock(view.cell(x, y, z))) {
                break;
            }
            double cost = occupyCost(x, y, z, cells);
            if (Double.isInfinite(cost)) {
                break;
            }
            total += cost;
        }
        return total;
    }

    private double occupyCost(int x, int y, int z, List<BlockPos> cells) {
        long cell = view.cell(x, y, z);
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
