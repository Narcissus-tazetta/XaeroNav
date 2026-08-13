package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

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
     * <p>空気が続く限りしか下りないので、地形のある場所では1〜2マスで止まる。深さが効くのは
     * 空虚の上（ジ・エンド）だけで、そこでは全ノードがこの走査を通るため、無制限にはしない。
     */
    private static final int COLUMN_SCAN_DEPTH = 32;

    /** {@link #firstNonAirBelow}が走査範囲内で何も見つけられなかったことを表す。 */
    private static final int NOTHING_BELOW = Integer.MIN_VALUE;

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
        this.view = view;
        this.maxExpandedNodes = limits.maxExpandedNodes();
        this.timeLimitMillis = limits.timeLimitMillis();
        this.heuristicWeight = limits.heuristicWeight();
        // 展開したノードの周囲も含めるとノード数は展開数を超える。小さく作ると探索の途中で
        // 表の作り直しが何度も走り、そのたびに全エントリの再配置が起きる
        this.nodes = new Long2ObjectOpenHashMap<>(
                Math.min(limits.maxExpandedNodes(), MAX_PRESIZED_NODES), 0.75f);
    }

    /**
     * 打ち切り条件（展開数上限・時間上限・cancelled）のいずれかに達したら、その時点で最も有望な
     * 暫定経路を返す（design doc §4-4）。
     */
    public PathResult search(BlockPos start, BlockPos goal, BooleanSupplier cancelled) {
        this.surfaceGoal = false;
        this.goalX = goal.getX();
        this.goalY = goal.getY();
        this.goalZ = goal.getZ();
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
        startNode.cost = 0.0;
        startNode.combinedCost = heuristicWeight * startNode.estimatedCostToGoal;
        open.insert(startNode);
        Arrays.fill(bestSoFar, startNode);
        Arrays.fill(bestHeuristic, startNode.estimatedCostToGoal);

        long deadline = System.currentTimeMillis() + timeLimitMillis;
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxExpandedNodes) {
            if ((expanded & CHECK_INTERVAL_MASK) == 0
                    && (cancelled.getAsBoolean() || System.currentTimeMillis() >= deadline)) {
                break;
            }

            PathNode current = open.removeLowest();
            current.closed = true;
            expanded++;
            if (reachedGoal(current)) {
                return buildResult(startNode, current, true, expanded);
            }
            expand(current);
        }

        return buildResult(startNode, selectFallback(startNode), false, expanded);
    }

    private boolean reachedGoal(PathNode node) {
        // 高さだけでは天井の下も地上に数えてしまう。深い洞窟の坑道は水平に長く、
        // 既定の地上高より上を通ることが珍しくない。そこで中継を終えると、洞窟の中から
        // 目的地へ直行する経路＝避けたかった一直線の掘り進みに戻る
        return surfaceGoal ? node.y >= surfaceY && node.y >= view.openSkyY(node.x, node.z)
                : node.x == goalX && node.y == goalY && node.z == goalZ;
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

    private PathResult buildResult(PathNode startNode, PathNode end, boolean complete, int expanded) {
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
        return new PathResult(steps, complete, expanded, nodes.size());
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
        // 素通しでsurfaceYを渡すと、そこから下りる分を残コストとして数えてしまい過大評価になる
        double heuristic = surfaceGoal
                ? Heuristic.estimate(x, y, z, x, Math.max(y, surfaceY), z)
                : Heuristic.estimate(x, y, z, goalX, goalY, goalZ);
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
        relax(from, x, y, z, ActionCosts.ASCEND_ONE_BLOCK + submerged(clearanceCost + bodyCost, x, y + 1, z),
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
        double baseCost = intoWater ? ActionCosts.WALK_ONE_IN_WATER : ActionCosts.DESCEND_ONE_BLOCK;
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
        relax(from, x, y, z, ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK, MoveKind.DIAGONAL_ASCEND);
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
        relax(from, x, y, z, ActionCosts.DIAGONAL_DESCEND_ONE_BLOCK, MoveKind.DIAGONAL_DESCEND);
    }

    /**
     * 1マスの隙間を飛び越える（同一高度、カーディナル方向のみ）。
     *
     * <p>これが無いと、誰でも何も考えずに跨げる1マスの割れ目（小川・洞窟の裂け目・峡谷の枝）で、
     * ブロックを置いて渡るか大きく迂回することになる。
     *
     * <p>2マス以上の跳躍は扱わない。助走とタイミングが要り、外せば落ちる。落ちる先が峡谷なら
     * 死ぬのだから、案内が「ここを飛べ」と言ってよい範囲は、誰がやっても失敗しない1マスまで。
     *
     * <p>空中では掘れないので、通り抜ける空間は掘削なしで通れることを求める。頭上も見る —
     * ジャンプは1.25マス上がるので、天井があると跳べずに隙間へ落ちる。
     */
    private void addJumpGap(PathNode from, int dx, int dz) {
        int gapX = from.x + dx;
        int gapZ = from.z + dz;
        int y = from.y;
        if (CellData.standable(view.cell(gapX, y - 1, gapZ))) {
            // 隙間ではなく床がある。歩いて行けるならTraverseの方が安い
            return;
        }
        int x = from.x + 2 * dx;
        int z = from.z + 2 * dz;
        if (!CellData.standable(view.cell(x, y - 1, z))) {
            return;
        }
        if (!clearWithoutDigging(gapX, y, gapZ) || !clearWithoutDigging(x, y, z)) {
            return;
        }
        // 踏み切り地点と跳び越える隙間の頭上。ここが塞がっていると跳躍そのものが成立しない
        if (!CellData.occupiableWithoutDigging(view.cell(from.x, from.y + 2, from.z))
                || !CellData.occupiableWithoutDigging(view.cell(gapX, y + 2, gapZ))) {
            return;
        }
        relax(from, x, y, z, ActionCosts.JUMP_ACROSS_GAP, MoveKind.JUMP);
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

    /** 梯子・ツタを降りる。 */
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
     * <p>落下ダメージを受ける高さは提示しない（{@link ActionCosts#SAFE_FALL_BLOCKS}まで）。降りる手段は
     * 掘り下げ（Descend + 掘削）もあるので、痛い近道を勧めるより階段状に降りる経路を出す方がよい。
     * ただし着水はバニラが落下距離をリセットするので、高さを問わず安全に降りられる。
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

        long obstacle = view.cell(x, obstacleY, z);
        if (CellData.water(obstacle)) {
            relax(from, x, obstacleY, z, ActionCosts.fallCost(from.y - obstacleY), MoveKind.FALL_TO_WATER);
            return;
        }
        if (!CellData.standable(obstacle)) {
            // 柵や梯子など、落ちても足場にならないもの
            return;
        }
        int drop = from.y - obstacleY - 1;
        if (drop >= 2 && drop <= ActionCosts.SAFE_FALL_BLOCKS) {
            relax(from, x, obstacleY + 1, z, ActionCosts.fallCost(drop), MoveKind.FALL);
        }
    }

    /**
     * 床が存在しない空洞（ジ・エンドの島間など）をブロックを置いて渡る移動。design doc §4-1のPillarの
     * 水平版。掘削とは逆に、床セルが完全な空虚（{@code passableEmpty}）である場合のみ許可する — 水面や
     * 溶岩の上には置かない（design doc §3-3の安全確認とは別に、そもそも設置対象として扱わない）。
     *
     * <p>水面のすぐ上も空気なので、床セルだけを見ても空虚と区別がつかない。海の上にブロックを敷いて
     * 渡るのは泳いで渡れる場所にわざわざ足場を作ることになるので、下に水が見えたらこの移動を作らない。
     */
    private void addBridge(PathNode from, int dx, int dz, int obstacleY) {
        if (!view.canPlaceBlocks()) {
            return;
        }
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        long floorCell = view.cell(x, y - 1, z);
        if (CellData.standable(floorCell) || !CellData.passableEmpty(floorCell)) {
            return;
        }
        if (obstacleY != NOTHING_BELOW) {
            long obstacle = view.cell(x, obstacleY, z);
            // 読めなかったセル（未ロード・探索範囲外）で走査が止まっただけの場所は、その下に何が
            // あるか分からない。水面の上に足場を敷けと言い出すのはこの取り違えから起きる
            if (!CellData.present(obstacle) || CellData.water(obstacle)) {
                return;
            }
        }
        // 水・溶岩に接する場所へは置かない。水は流れ込んで足場ごと押し流され、溶岩は論外。
        // 実際にやろうとすると難しいだけの指示になる
        if (hasAdjacentFluid(x, y - 1, z)) {
            return;
        }
        double bodyCost = standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        double cost = ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS
                + submerged(bodyCost, x, y + 1, z);
        relax(from, x, y, z, cost, MoveKind.BRIDGE);
    }

    /** ブロックを置くセルの周り（真上を除く5面）に水・溶岩があるか。 */
    private boolean hasAdjacentFluid(int x, int y, int z) {
        return isFluid(x, y - 1, z)
                || isFluid(x + 1, y, z) || isFluid(x - 1, y, z)
                || isFluid(x, y, z + 1) || isFluid(x, y, z - 1);
    }

    private boolean isFluid(int x, int y, int z) {
        long cell = view.cell(x, y, z);
        return CellData.water(cell) || CellData.lava(cell);
    }

    private void relax(PathNode from, int x, int y, int z, double edgeCost, MoveKind kind) {
        double tentativeCost = from.cost + edgeCost;
        PathNode neighbor = node(x, y, z);
        if (neighbor.closed || neighbor.cost - tentativeCost <= MIN_IMPROVEMENT) {
            return;
        }

        neighbor.previous = from;
        neighbor.cost = tentativeCost;
        neighbor.combinedCost = tentativeCost + heuristicWeight * neighbor.estimatedCostToGoal;
        neighbor.kind = kind;
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
