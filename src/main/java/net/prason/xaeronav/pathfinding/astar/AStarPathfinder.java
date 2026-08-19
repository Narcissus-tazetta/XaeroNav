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
 * design doc §4。Traverse/Diagonal/Ascend/Descend/Bridgeを扱う。
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
     * 遊泳(9.09 tick/マスに対し下限は3.56)——でA*がほぼDijkstraに退化し、展開数の上限が数十マス先で
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

    /** {@link #firstNonAirBelow}が走査範囲内で何も見つけられなかったことを表す。 */
    private static final int NOTHING_BELOW = Integer.MIN_VALUE;

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

    /** この探索が{@link #maxBridgeRun}を理由に橋の移動を1つでも捨てたか。 */
    private boolean bridgeRunCapBlocked;

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
    private final BinaryHeapOpenSet open = new BinaryHeapOpenSet();
    private final PathNode[] bestSoFar = new PathNode[COEFFICIENTS.length];
    private final double[] bestHeuristic = new double[COEFFICIENTS.length];

    private int goalX;
    private int goalY;
    private int goalZ;
    // trueなら「y >= surfaceY のセルならどこでもゴール」として探索する（design doc外・地上優先ナビ用）。
    // 目的地の真下から一直線に掘るのではなく、周囲のどこからでも地上に出られる経路を許すために
    // 固定の1点ではなく高さだけを条件にする。
    private boolean surfaceGoal;
    private int surfaceY;

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
        this(view, limits, costToGo, view.maxBridgeRunBlocks());
    }

    /**
     * 連続する橋の長さの上限を明示するコンストラクタ。0を渡すと無制限になる——
     * 上限のせいで範囲内に道が一本も無くなった場合の、詰み回避の探し直しに使う
     * （「マグマの橋は最後の手段だが、詰みよりはマシ」という優先順）。
     */
    public AStarPathfinder(CellSource view, SearchLimits limits, CostToGo costToGo, int maxBridgeRun) {
        this.maxBridgeRun = maxBridgeRun;
        this.view = view;
        this.minDescentPerBlock = view.minDescentTicksPerBlock();
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
     * 打ち切り条件（展開数上限・時間上限・cancelled）のいずれかに達したら、その時点で最も有望な
     * 暫定経路を返す（design doc §4-4）。
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
     * 探すためのもの（design doc外・地上優先ナビ用、{@link net.prason.xaeronav.client.PathfindingState}参照）。
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

    private PathResult runSearch(BlockPos start, BooleanSupplier cancelled) {
        PathNode startNode = node(start.getX(), start.getY(), start.getZ());
        startNode.bridgeRun = startBridgeRun;
        startNode.cost = 0.0;
        startNode.combinedCost = heuristicWeight * startNode.estimatedCostToGoal;
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
            return node.y >= surfaceY && node.y >= view.openSkyY(node.x, node.z);
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
        return new PathResult(steps, termination, expanded, nodes.size());
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
        long key = BlockPos.asLong(x, y, z);
        PathNode existing = nodes.get(key);
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
            heuristic = Heuristic.estimate(x, y, z, goalX, goalY, goalZ, minDescentPerBlock);
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
        PathNode created = new PathNode(x, y, z, heuristic);
        nodes.put(key, created);
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
            addClimb(current, dx, dz);
            addJumpGap(current, dx, dz);
        }
        for (int i = 0; i < DIAGONAL_DX.length; i++) {
            addDiagonalTraverse(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            addDiagonalAscend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
            addDiagonalDescend(current, DIAGONAL_DX[i], DIAGONAL_DZ[i]);
        }
        // 上下の泳ぎ・昇降は、いま水中／梯子の中にいるときしか始まらない。それ以外では判定ごと省く
        long standingCell = view.cell(current.x, current.y, current.z);
        if (CellData.water(standingCell)) {
            addSwimUp(current);
            addSwimDown(current);
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
     */
    private int firstNonAirBelow(int x, int topY, int z) {
        for (int i = 0; i < COLUMN_SCAN_DEPTH; i++) {
            int y = topY - i;
            if (!CellData.passableEmpty(view.cell(x, y, z))) {
                return y;
            }
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
        relax(from, x, y, z, stepCost(x, y, z) + submerged(bodyCost, x, y + 1, z),
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
     * 同一高度での斜め移動（design doc §4-1）。カーディナル4方向のみだと、斜めに続く地形で
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
        relax(from, x, y, z, stepCost(x, y, z) * ActionCosts.DIAGONAL_DISTANCE + submerged(bodyCost, x, y + 1, z),
                inWater ? MoveKind.SWIM : MoveKind.DIAGONAL);
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
                        + submerged(clearanceCost + bodyCost, x, y + 1, z),
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
        double baseCost = intoWater ? ActionCosts.WALK_ONE_IN_WATER
                : ActionCosts.descendOneBlock(takeoffSpeedFactor(from.x, from.y, from.z));
        relax(from, x, y, z, baseCost + submerged(bodyCost, x, y + 1, z),
                intoWater ? MoveKind.SWIM_DESCEND : MoveKind.DESCEND);
    }

    /**
     * 斜め1マスで1段登りながら進む（design doc外・近距離レパートリー拡充）。カーディナル4方向限定の
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
        relax(from, x, y, z, ActionCosts.diagonalDescendOneBlock(takeoffSpeedFactor(from.x, from.y, from.z)),
                MoveKind.DIAGONAL_DESCEND);
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

        for (int gap = 1; gap <= MAX_JUMP_GAP_BLOCKS; gap++) {
            int gapX = from.x + gap * dx;
            int gapZ = from.z + gap * dz;
            // 跳び越える空間が塞がっていれば、その先へはどれだけ助走しても届かない
            if (!clearWithoutDigging(gapX, y, gapZ)
                    || !CellData.occupiableWithoutDigging(view.cell(gapX, y + 2, gapZ))) {
                return;
            }
            // 下が溶岩の隙間は跳ばない。跳躍は外せば落ちるという前提でコストを積んであるが、
            // 溶岩ではその「外したとき」が死なので、コストの多寡で釣り合う話ではなくなる
            if (lavaBelow(gapX, y, gapZ)) {
                return;
            }
            int x = from.x + (gap + 1) * dx;
            int z = from.z + (gap + 1) * dz;
            if (!CellData.standable(view.cell(x, y - 1, z))) {
                // まだ着地できない。隙間はもう1マス続く
                continue;
            }
            if (!clearWithoutDigging(x, y, z)) {
                return;
            }
            relax(from, x, y, z, ActionCosts.jumpAcrossGap(gap), MoveKind.JUMP);
            return;
        }
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

    /** 跳び損ねたときに落ちる先が溶岩か。足元から{@link #JUMP_LAVA_SCAN_DEPTH}マス下までを見る。 */
    private boolean lavaBelow(int x, int y, int z) {
        for (int depth = 1; depth <= JUMP_LAVA_SCAN_DEPTH; depth++) {
            long cell = view.cell(x, y - depth, z);
            if (CellData.lava(cell)) {
                return true;
            }
            if (CellData.standable(cell)) {
                // 溶岩より先に床がある。ここへ落ちても溶岩には触れない
                return false;
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
        relax(from, x, y, z, ActionCosts.WALK_ONE_IN_WATER, MoveKind.SWIM);
    }

    /** 水中を浮上する。水面まで上がってから水平に泳ぐ経路を作るために要る。 */
    private void addSwimUp(PathNode from) {
        int y = from.y + 1;
        if (!CellData.water(view.cell(from.x, y, from.z))
                || !CellData.occupiableWithoutDigging(view.cell(from.x, y + 1, from.z))) {
            return;
        }
        relax(from, from.x, y, from.z, ActionCosts.WALK_ONE_IN_WATER, MoveKind.SWIM_UP);
    }

    /** 水中を潜る。水底の地形沿いに進む方が近い場合に使う。 */
    private void addSwimDown(PathNode from) {
        int y = from.y - 1;
        if (!CellData.water(view.cell(from.x, y, from.z))) {
            return;
        }
        relax(from, from.x, y, from.z, ActionCosts.WALK_ONE_IN_WATER, MoveKind.SWIM_DOWN);
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
        if (obstacleY == NOTHING_BELOW) {
            // 底が見えない＝落ちても着地しない
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
        if (view.canMlgWaterBucket()) {
            relax(from, x, obstacleY + 1, z,
                    ActionCosts.fallCost(drop, takeoff) + ActionCosts.MLG_WATER_OVERHEAD_TICKS,
                    MoveKind.FALL_MLG);
        }
        if (damage <= view.maxFallDamagePoints()) {
            relax(from, x, obstacleY + 1, z,
                    ActionCosts.fallCost(drop, takeoff) + damage * ActionCosts.FALL_DAMAGE_PENALTY_PER_POINT,
                    MoveKind.FALL_DAMAGE);
        }
    }

    /**
     * 床が存在しない空洞（ジ・エンドの島間など）をブロックを置いて渡る移動。design doc §4-1のPillarの
     * 水平版。掘削とは逆に、床セルが完全な空虚（{@code passableEmpty}）である場合のみ許可する — 水面の
     * 上には置かない（design doc §3-3の安全確認とは別に、そもそも設置対象として扱わない）。
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
        if (!view.canPlaceBlocks()) {
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
        // 床が溶岩なら、置くブロックがその溶岩を置き換える。何がそれを支えているかは関係ない
        boolean lavaFarBelow = false;
        if (!overLava && obstacleY != NOTHING_BELOW) {
            long obstacle = view.cell(x, obstacleY, z);
            // 読めなかったセル（未ロード・探索範囲外）で走査が止まっただけの場所は、その下に何が
            // あるか分からない。水面の上に足場を敷けと言い出すのはこの取り違えから起きる
            if (!CellData.present(obstacle) || CellData.water(obstacle)) {
                return;
            }
            // 足元・隣接には溶岩が無くても、遥か下（ネザーの開けた空洞の底など）が溶岩なら
            // 設置を外したときの結末は変わらない。hasAdjacentLavaは足元1マス下しか見ないので、
            // ここを見ないと「空中で溶岩の上を長々と橋渡しする」経路が無傷の橋と同じ扱いになる
            lavaFarBelow = CellData.lava(obstacle);
        }
        // 水に接する場所へは置かない。流れ込んで足場ごと押し流される
        if (hasAdjacentWater(x, y - 1, z)) {
            return;
        }
        boolean lavaNearby = overLava || lavaFarBelow || hasAdjacentLava(x, y - 1, z);
        if (lavaNearby && !view.lavaBridgingEnabled()) {
            return;
        }
        // 連続した橋の長さで打ち切る。ここで「重いコスト」ではなく「移動を作らない」を選ぶのが要点——
        // 重みで抑えると、A*は安い辺から展開するので橋に手を伸ばす前に周囲を展開し尽くし、
        // 展開ノード数を焼き切ったうえで結局その先に進めない（ActionCosts#LAVA_BRIDGE_PENALTY_TICKS
        // に記録された実測そのもの）。辺を作らなければ、探索は最初から迂回路だけを見る
        int bridgeRun = from.bridgeRun + 1;
        if (maxBridgeRun > 0 && bridgeRun > maxBridgeRun) {
            bridgeRunCapBlocked = true;
            return;
        }
        double bodyCost = standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        double cost = ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS
                + (lavaNearby ? ActionCosts.LAVA_BRIDGE_PENALTY_TICKS : 0.0)
                + submerged(bodyCost, x, y + 1, z);
        relax(from, x, y, z, cost, MoveKind.BRIDGE, bridgeRun);
    }

    /**
     * 跳びながら足元にブロックを置いて真上へ1マス登る（design doc §4-1のPillar）。
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
        if (!view.canPlaceBlocks()) {
            return;
        }
        long standing = view.cell(from.x, from.y, from.z);
        // 置く先は自分がいるセルそのもの。梯子・ツタに掴まっている間は onGround() が false で
        // jumpFromGround() が呼ばれず（LivingEntity#aiStep）、掴まったまま接地していても
        // handleOnClimbable が水平・下向きの速度を±0.15に固定するので、跳んで積む動作が成立しない
        if (!CellData.replaceable(standing) || CellData.climbable(standing)) {
            return;
        }
        double clearanceCost = columnCost(from.x, from.y + 2, from.y + 2, from.z, null);
        if (Double.isInfinite(clearanceCost)) {
            return;
        }
        double cost = ActionCosts.ascendOneBlock(takeoffSpeedFactor(from.x, from.y, from.z))
                + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS
                + submerged(clearanceCost, from.x, from.y + 2, from.z);
        relax(from, from.x, from.y + 1, from.z, cost, MoveKind.PILLAR);
    }

    /** ブロックを置くセルの周り（真上を除く5面）に水があるか。 */
    private boolean hasAdjacentWater(int x, int y, int z) {
        return hasAdjacent(x, y, z, CellData::water);
    }

    /** ブロックを置くセルの周り（真上を除く5面）に溶岩があるか。 */
    private boolean hasAdjacentLava(int x, int y, int z) {
        return hasAdjacent(x, y, z, CellData::lava);
    }

    private boolean hasAdjacent(int x, int y, int z, LongPredicate test) {
        return test.test(view.cell(x, y - 1, z))
                || test.test(view.cell(x + 1, y, z)) || test.test(view.cell(x - 1, y, z))
                || test.test(view.cell(x, y, z + 1)) || test.test(view.cell(x, y, z - 1));
    }

    private void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        relax(from, x, y, z, edgeCost, kind, 0);
    }

    /**
     * {@code bridgeRun}を明示的に渡す版。{@link #addBridge}だけが非0を渡す——
     * それ以外の移動は橋の連続を断つので0になる。
     */
    private void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind, int bridgeRun) {
        double tentativeCost = from.cost + edgeCost;
        PathNode neighbor = node(x, y, z);
        if (neighbor.closed || neighbor.cost - tentativeCost <= MIN_IMPROVEMENT) {
            return;
        }

        neighbor.previous = from;
        neighbor.cost = tentativeCost;
        neighbor.combinedCost = tentativeCost + heuristicWeight * neighbor.estimatedCostToGoal;
        neighbor.kind = kind;
        neighbor.bridgeRun = bridgeRun;
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
     */
    private double submerged(double digCost, int x, int headY, int z) {
        if (digCost <= 0.0 || !CellData.water(view.cell(x, headY, z))) {
            return digCost;
        }
        return digCost * ActionCosts.SUBMERGED_DIG_PENALTY;
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
     * 連なっている分を一度だけ加える（design doc §3-3）。必須セル自体は個別に数えるだけなので、
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
