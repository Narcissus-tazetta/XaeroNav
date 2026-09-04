package net.prason.xaeronav.pathfinding.coarse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * {@link CoarseMap}の上で、目的地までのおおまかな道筋を1チャンク単位で引く。
 *
 * <p>結果は経路そのものではなく<b>中間目標の列</b>として使う。ここで決めるのは「海をどちら回りで
 * 避けるか」「どの谷を通るか」「（天井のある次元では）どの階層を通るか」という大局だけで、
 * 実際に辿る経路は読み込み済みチャンクを見る詳細探索が中間目標ごとに引き直す。粗い側が
 * 1マス単位の通行可否を持たない以上、ここで出した線をそのまま歩けるとは限らない。
 *
 * <p>探索の状態は{@code (chunkX, chunkZ, floor)}——{@link CoarseMap}のセル単位の床。
 * 同じXZに複数の独立した階層が重なる次元（ネザー）で、階層をまたぐ移動を「安い段差」として
 * 誤魔化さず、専用のコスト（{@link #LAYER_TRANSITION_PENALTY}）を持つ独立した辺として扱う。
 * 床数が常に1になる次元（地上・ジ・エンド）では、この状態空間は旧来の{@code (chunkX, chunkZ)}と
 * 完全に同じになる。
 *
 * <p>コストは詳細探索と同じtick単位で見積もる。単位を揃えておかないと、「粗い側では最短なのに
 * 詳細側では明らかな遠回り」という食い違いが起きたときに、どちらの見積もりが外れているのか
 * 突き合わせられない。
 */
public final class CoarseRouter {

    /** 1セルの一辺（ブロック）。{@link CoarseMap}が1チャンク単位なので16。 */
    private static final int CELL_BLOCKS = 16;

    private static final double STRAIGHT_COST = CELL_BLOCKS * ActionCosts.SPRINT_ONE_BLOCK;
    private static final double DIAGONAL_COST = STRAIGHT_COST * ActionCosts.DIAGONAL_DISTANCE;

    /**
     * 水面を渡る倍率。疾走とうつ伏せ泳ぎの速度比（5.612 / 3.6）そのもの。
     *
     * <p>層1が水セルに見ているのは<b>水面を渡る</b>コストなので{@link ActionCosts#SWIM_ONE_BLOCK}を使う。
     *
     * <p>分母が{@link ActionCosts#SPRINT_ONE_BLOCK}なのは、倍率を掛ける相手の
     * {@link #STRAIGHT_COST}が疾走を基準にしているから。徒歩で割ると、陸を疾走で見積もりながら
     * 水との比だけ徒歩で測ることになり、比が体系的にずれる。
     */
    private static final double WATER_MULTIPLIER =
            ActionCosts.SWIM_ONE_BLOCK / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * ボートで進むときの水面通過倍率。{@link ActionCosts#PADDLE_ONE_BLOCK}が
     * {@link ActionCosts#SPRINT_ONE_BLOCK}より小さいため、{@link #WATER_MULTIPLIER}と違い
     * 1未満になる＝水を避けるコストではなく積極的に選ぶ近道になる。
     */
    private static final double BOAT_MULTIPLIER =
            ActionCosts.PADDLE_ONE_BLOCK / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * 地図に無いセルを通る倍率。通れないと決めつけると、未訪問の土地を挟む目的地へは
     * 一切ルートが出ない。逆に陸と同じ扱いにすると、既知の迂回路を捨てて未知の直線へ突っ込む。
     * 「分かっている道が多少遠回りでも、そちらを選ぶ」程度に重くしておく。
     */
    private static final double UNKNOWN_MULTIPLIER = 1.6;

    /**
     * 溶岩が混じるセルを通る倍率。他の倍率と違い実測の速度比ではない——溶岩は歩みを遅くするのではなく
     * 迂回を強いるものなので、「チャンク内で溶岩を避けて回り込むぶん実距離が2倍前後になる」という
     * 見積もりに、層1からは安全に抜けられるか分からないぶんの余裕を足した値。
     *
     * <p>ネザーはこの種のセルが常時混じるので、これを通行不能にすると経路が繋がらない。
     * かといって陸と同じにすると溶岩地帯を突っ切る案内になる。「他に道があるならそちら」を選ばせる重み。
     */
    private static final double LAVA_MIXED_MULTIPLIER = 2.5;

    /**
     * {@link BridgePolicy#BRIDGE}で溶岩セルを渡る倍率。層1と層3はコストの単位をtickで揃えてあるので、
     * ここは勘ではなく層3の実コストから導く——1ブロックあたり
     * {@code SPRINT_ONE_BLOCK + PLACE_BLOCK_AIM_TICKS + LAVA_BRIDGE_PENALTY_TICKS ≒ 35.6}tick、
     * 通常の陸が3.564なので比は約10倍になる。
     *
     * <p>足すのが{@code PLACE_BLOCK_OVERHEAD_TICKS}ではなく{@code PLACE_BLOCK_AIM_TICKS}なのは、
     * 層3が溶岩・奈落の橋では走行を中断するぶんの割増を乗せないため
     * （{@code ActionCosts#TERRAIN_EDIT_INTERRUPTION_TICKS}）。層1だけ乗せると2つの層が
     * 別の値段で同じ橋を評価することになる。
     */
    private static final double LAVA_BRIDGE_MULTIPLIER =
            (ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_AIM_TICKS
                    + ActionCosts.LAVA_BRIDGE_PENALTY_TICKS) / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * {@link BridgePolicy#BRIDGE}で奈落セルを渡る倍率。{@link #LAVA_BRIDGE_MULTIPLIER}と同じく
     * 層3の実コスト（{@link ActionCosts#VOID_BRIDGE_PENALTY_TICKS}）から導く。
     *
     * <p><b>この倍率が「どこまでの奈落なら渡るか」を実質的に決めている。</b>層1はA*なので橋の
     * 連続長を状態に持てず（状態数がk倍になれば到達距離は1/√k）、{@code maxVoidBridgeRunBlocks}を
     * 層1で表現する手段が無い。代わりに1セル＝16ブロックがこの倍率で効くので、奈落2セル
     * （32ブロック）を渡る費用は徒歩約320ブロック相当になる——それより近い回り込みがあれば
     * 必ずそちらが勝つ。詳細探索が上限で渡れない長さの奈落は、そもそも層1が選ばなくなる。
     */
    private static final double VOID_BRIDGE_MULTIPLIER =
            (ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_AIM_TICKS
                    + ActionCosts.VOID_BRIDGE_PENALTY_TICKS) / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * 高低差1ブロックあたりの追加コスト。登りも下りも同じだけ掛ける。
     * 粗いセルでは崖と緩斜面を区別できないので、どちらつかずの中間の重みにしておき、
     * 「同じくらいの距離なら平坦な方」を選ばせるためだけに使う。
     */
    private static final double HEIGHT_COST_PER_BLOCK = ActionCosts.JUMP_ONE_BLOCK;

    /**
     * ガイドが1ブロックの登りに乗せる追加コスト。<b>{@link #HEIGHT_COST_PER_BLOCK}ではない</b>——
     * 登りは水平移動に相乗りするので、実際に増えるのは{@code Ascend}と疾走の差だけ
     * （{@code Heuristic}の相乗りと同じ考え方）。{@link #HEIGHT_COST_PER_BLOCK}をそのまま使うと
     * 4倍ほど過大になり、起伏のある地上で経路が平坦な方へ不必要に逃げる。
     */
    private static final double GUIDE_ASCEND_COST_PER_BLOCK =
            ActionCosts.ASCEND_ONE_BLOCK - ActionCosts.SPRINT_ONE_BLOCK;

    /** {@link CoarseMap#WATER}に分類される最小の水の割合（{@code LiveCoarseSampler}の閾値）。 */
    private static final double WATER_CELL_MIN_FRACTION = 0.5;

    /** {@link CoarseMap#LAVA_MIXED}に分類される最小の溶岩の割合（{@code LiveCoarseSampler}の閾値）。 */
    private static final double LAVA_MIXED_CELL_MIN_FRACTION = 0.25;

    /**
     * セル内の起伏（{@code maxHeight - minHeight}）がこれを超えたら崖とみなす。
     * バニラの{@code SAFE_FALL_DISTANCE}既定値（{@link ActionCosts#SAFE_FALL_BLOCKS}）をそのまま使う。
     * これより緩やかな起伏は、平均高さの差分で表現される通常の坂として扱えば十分。
     */
    private static final int CLIFF_THRESHOLD_BLOCKS = ActionCosts.SAFE_FALL_BLOCKS;

    /**
     * 崖セルへ踏み込む追加コスト。平均高さは周囲と同じでも、セル内部の起伏が大きいチャンクは
     * 「崖の途中に平地が乗っている」可能性が高く、詳細探索が大きく迂回・掘削する羽目になりやすい。
     * 平均だけを見る{@link #HEIGHT_COST_PER_BLOCK}では、山腹の急斜面チャンクと緩斜面チャンクが
     * 同じ扱いになってしまうのを補う。
     */
    private static final double CLIFF_COST_PER_BLOCK = ActionCosts.JUMP_ONE_BLOCK;

    /**
     * 崖ペナルティの上限。線形加算のままだと、起伏が激しい地形（ネザーの3D迷路では常態）で
     * {@link #LAVA_MIXED_MULTIPLIER}による溶岩の追加コストをあっさり上回り、「平坦な溶岩の海」が
     * 「起伏のある本物の地形」より安く見えてしまう（実測: 起伏30ブロック程度でLAND側が逆転する）。
     *
     * <p>不変条件として「崖ペナルティの上限 &lt; 溶岩混じりセルの追加コスト」を保つ。直進1セルぶんの
     * 追加コスト（{@code STRAIGHT_COST * (LAVA_MIXED_MULTIPLIER - 1)}）を基準にする——斜めより
     * 直進の方が基準コストが小さく、条件が厳しい側なのでここで揃えておけば両方で成り立つ。
     * 安全マージンとして9割に抑える。
     */
    private static final double CLIFF_PENALTY_CAP = STRAIGHT_COST * (LAVA_MIXED_MULTIPLIER - 1.0) * 0.9;

    /**
     * 同じセル内で階層をまたぐ（床i↔床i+1）移動の割増倍率。粗い地図はその階層間に実際に
     * 通れる縦穴があるかまでは分からない——{@code Δheight × HEIGHT_COST_PER_BLOCK}に、
     * 「本当に繋がっているか分からない」ぶんの割増を掛ける。{@link #UNKNOWN_MULTIPLIER}や
     * {@link #LAVA_MIXED_MULTIPLIER}と同じ「通れなくはないが、確実な道より高くつく」という
     * 設計思想の値で、実測に基づく係数ではない（要調整）。
     */
    private static final double LAYER_TRANSITION_PENALTY = 2.0;

    /**
     * これだけのセル数があれば「大きい島」とみなす（3×3チャンク＝48×48ブロック）。
     * これ以上の陸塊へ渡るときは割増を取らない。
     */
    private static final int LARGE_ISLAND_CELLS = 9;

    /**
     * 1セル（16×16ブロック）だけの陸塊へ渡るときの割増。ユーザー要望
     * 「ジ・エンドでは島と島を渡ることをなるべく避けたい。<b>大きい島を渡りながらのルート</b>にしたい」
     * に応えるためのもの。
     *
     * <p><b>「渡る回数」そのものには課さない。</b>短い飛び石を選ぶのは層3の橋の上限
     * （{@code maxVoidBridgeRunBlocks}）という<b>実現可能性</b>の要請で、
     * {@code CoarseRouterTest#prefersSteppingStoneIslandsOverTheShortestVoidCrossing}が
     * 固定しているとおり正しい挙動——そこを潰すと渡れない長さの奈落を選ぶようになる。
     * 課すのは「どの島を踏むか」だけで、小さい島より大きい島を選ばせる。
     *
     * <p>徒歩4チャンク（64ブロック）相当。これだけ遠回りしてでも大きい島を経由する価値がある、
     * という重み。奈落1セル（{@link #VOID_BRIDGE_MULTIPLIER}≒10倍＝徒歩160ブロック相当）よりは
     * 十分軽いので、<b>大きい島へ渡るために余計な奈落を1セル増やす</b>ような選択にはならない。
     */
    private static final double SMALL_ISLAND_PENALTY = STRAIGHT_COST * 4.0;

    /**
     * 中間目標を置く水平間隔（セル＝チャンク）。
     *
     * <p><b>詳細探索が一度に狙う距離（{@code detailHorizonBlocks}、既定96）より必ず短く保つこと。</b>
     * 間引きは「最大軸の差がこの値に達したら」で判定するので、斜めに続くルートでの実際の間隔は
     * 最大{@code spacing * √2 * 16}ブロックになる——6セルだと最大135.8ブロックで、96を超えた分は
     * 詳細探索が一度に狙えない。そうなると目標は<b>waypointへの直線上</b>に取るしかなくなり
     * （{@code PathfindingState#pointAlongRoute}）、ルートが曲がっている所でその直線が角を切り落として、
     * 層1が避けた溶岩の海のただ中に目標が落ちる。4セルなら最大90.5ブロックで常に96の内側に収まり、
     * 「次の中間目標そのものを狙う」だけで済む。
     *
     * <p>間隔を詰めても詳細探索の回数は増えない。{@code reachableWaypointTarget}は
     * <b>届く範囲で最も遠い</b>中間目標を狙うので、詰めた分は素通りされて解像度だけが上がる。
     */
    private static final int WAYPOINT_SPACING_CELLS = 4;

    /**
     * 中間目標を置く垂直間隔（ブロック）。水平の間隔（{@link #WAYPOINT_SPACING_CELLS}）だけで
     * 間引くと、同じXZで階層を何段も登る区間（水平移動が0のまま）がwaypoint無しの1区間に
     * 圧縮され、詳細探索が「現在地から遥か上」という1つの目標をいきなり狙う羽目になる。
     * {@code PathfindingState#REFINED_WAYPOINT_MIN_SPACING_BLOCKS}と同じ値に揃えてある。
     */
    private static final int WAYPOINT_VERTICAL_SPACING_BLOCKS = 24;

    /**
     * ゴールに届かなかったときの到達点候補を、{@code h + g / 係数}という複数の指標で同時に追う。
     * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder}と同じ考え方・同じ係数列。
     * ヒューリスティック単独（＝ゴールに一番近いセル）で選ぶと、海に突き出した半島の先端のような
     * 「辿り着くのに莫大なコストを払った行き止まり」を掴んでしまう。係数が小さいほど
     * 実際に進んだ距離を重く見る。
     */
    private static final double[] COEFFICIENTS = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0};

    /**
     * これ未満しか進めない暫定ルートは提示する価値がない（セル＝チャンク、XZ平面上の距離）。
     * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder#MIN_DIST_PATH}と同じ役割だが、
     * 単位がブロックではなくチャンクなのでこちらは1セルにしておく。
     */
    private static final double MIN_DIST_CELLS = 1.0;

    private CoarseRouter() {
    }

    /**
     * 中間目標の列。{@code reachedGoal}がfalseなら、目的地まで届かないまま
     * 「その時点で最もゴールに近づけた地点」で終わっている。
     */
    public record Route(List<BlockPos> waypoints, boolean reachedGoal) {

        public boolean isEmpty() {
            return waypoints.isEmpty();
        }
    }

    /**
     * <b>足場を置かないと通れないセル</b>（溶岩・奈落）の扱い。呼び出し側は{@link #AVOID}から
     * 順に試し、届かなかったときだけ緩める。
     *
     * <p>層1が溶岩地帯や奈落を突っ切ると決めると、そのwaypointへは詳細探索が原理的に到達できない
     * （溶岩の上も奈落の上も歩けない）。段階を分けるのは「大きく迂回してでも避ける道」を必ず先に
     * 探させるため——迂回や後戻りはA*が勝手に見つけるので、ここで表現するのは可否だけでよい。
     *
     * <p>溶岩と奈落を1つのつまみにまとめてあるのは、どちらも「橋を架ける前提でなら通れる」という
     * 同じ性質だから。ただし<b>緩む段が違う</b>——溶岩の橋には設定のスイッチがあるのに対し、
     * 奈落の橋にはそれが無い（層3は{@code canPlaceBlocks}だけで判断する）ので、奈落は
     * {@link #ALLOW}の時点で開く。
     */
    public enum BridgePolicy {
        /** 溶岩の混じるセルも奈落も一切通らない。大回りでも避けた道があるならそれを見つける。 */
        AVOID,
        /** 溶岩が混じるセルと奈落は通れるが高い。過半数が溶岩のセルだけは通行不能。 */
        ALLOW,
        /** 過半数が溶岩のセルも含めて、橋を架けて渡る前提で通す。最後の手段。 */
        BRIDGE
    }

    public static Route findRoute(CoarseMap map, BlockPos start, BlockPos goal, boolean boatAvailable,
                                   BridgePolicy bridgePolicy) {
        int startX = start.getX() >> 4;
        int startZ = start.getZ() >> 4;
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        if (!map.containsChunk(startX, startZ) || !map.containsChunk(goalX, goalZ)) {
            return new Route(List.of(), false);
        }
        double waterMultiplier = boatAvailable ? BOAT_MULTIPLIER : WATER_MULTIPLIER;

        int cells = map.chunksX() * map.chunksZ();
        int states = cells * CoarseMap.MAX_FLOORS;
        double[] cost = new double[states];
        int[] previous = new int[states];
        boolean[] closed = new boolean[states];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);

        int startFloor = resolveFloor(map, startX, startZ, start.getY());
        int goalFloor = resolveFloor(map, goalX, goalZ, goal.getY());
        int startIndex = stateIndex(map, startX, startZ, startFloor);
        int goalIndex = stateIndex(map, goalX, goalZ, goalFloor);
        cost[startIndex] = 0.0;

        PriorityQueue<Candidate> open =
                new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimatedTotal));
        open.add(new Candidate(startIndex, heuristic(map, startX, startZ, goalX, goalZ, waterMultiplier)));

        int[] bestSoFar = new int[COEFFICIENTS.length];
        double[] bestHeuristic = new double[COEFFICIENTS.length];
        Arrays.fill(bestSoFar, startIndex);
        Arrays.fill(bestHeuristic, heuristic(map, startX, startZ, goalX, goalZ, waterMultiplier));

        while (!open.isEmpty()) {
            Candidate current = open.poll();
            // decrease-keyの代わりに同じ状態を複数回積むので、古い方はここで捨てる
            if (closed[current.index()]) {
                continue;
            }
            closed[current.index()] = true;
            if (current.index() == goalIndex) {
                return buildRoute(map, previous, goalIndex, startIndex, true, start.getY());
            }

            int x = stateChunkX(map, current.index());
            int z = stateChunkZ(map, current.index());
            int floor = stateFloor(current.index());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    relaxHorizontal(map, cost, previous, closed, open, x, z, floor, dx, dz, goalX, goalZ,
                            bestSoFar, bestHeuristic, waterMultiplier, bridgePolicy);
                }
            }
            relaxVertical(map, cost, previous, closed, open, x, z, floor, goalX, goalZ,
                    bestSoFar, bestHeuristic, waterMultiplier);
        }

        return buildRoute(map, previous, selectFallback(map, bestSoFar, startIndex), startIndex, false,
                start.getY());
    }

    /**
     * ゴールから逆向きに全セル・全床への実コスト下限を計算する。層3のヒューリスティックが
     * 併用するguide（{@link net.prason.xaeronav.pathfinding.astar.CostToGo}）の実体——壁や
     * 溶岩の海を回避した見積もりを、幾何学的な直線距離の代わりに使えるようにする。
     *
     * <p>{@link #findRoute}と違い、ヒューリスティックを使わない素のDijkstraで
     * openが尽きるまで（＝到達可能な全状態への最短距離が確定するまで）回す。
     * {@link #stepCost}系は非対称（進入先セルの性質で決まる）なので、ゴールから逆走するときは
     * 呼び出しの{@code from}/{@code to}を入れ替える——「セルAからBへ入るコスト」を、
     * Bに立ってAへ向かって数える形になる。
     *
     * <p><b>近似であることの注意</b>: {@link #findRoute}の水平移動は隣接セルの最も高さが近い
     * 床<b>だけ</b>に繋ぐ（階層をまたぐ移動が垂直遷移の割増を迂回しないため）。この関数を
     * 正確に逆走するには「どの床がどの床から『最寄り』として選ばれうるか」を逆算する必要があり
     * 複雑になるため、ここでは隣接セルの全床を候補にする単純化を採用する。結果は
     * 「本当に繋がる場合より広く見積もる」方向にしか外れない——admissibleな
     * {@link net.prason.xaeronav.pathfinding.astar.Heuristic}とのmaxを取って使う設計
     * （{@code AStarPathfinder#node}参照）なので、この近似が探索を壊すことはない
     * （最悪でも幾何学的下限まで自然に落ちる）。
     *
     * <p><b>ガイド（この表）は実コストの下限でなければならない、という約束。</b>
     *
     * <p>{@code AStarPathfinder#node}はガイドと幾何学的な{@code Heuristic}の<b>大きい方</b>をhに使う。
     * ガイドが実コストを上回った瞬間、A*はその方向を実際より高く見積もって<b>経路の形を変える</b>——
     * しかも層1はチャンク単位なので、変わり方は地形ではなくチャンク格子に従う。
     *
     * <p>そこで<b>同じ地図を2通りに値付けする</b>。{@link #findRoute}（どの谷を通るかの計画）は
     * 好みを含んだ値、この表（詳細探索のガイド）は下限だけ。
     * 下限側で落とすのは次の5つで、いずれも<b>実際にかかる時間ではない</b>:
     *
     * <ul>
     * <li>{@link #cliffPenalty} — セル内の起伏が大きいだけで、平坦な棚を通れることもある</li>
     * <li>{@link #SMALL_ISLAND_PENALTY} — 「大きい島を渡りたい」という人間の好み</li>
     * <li>{@link #UNKNOWN_MULTIPLIER} — 分からないことは高くつく理由にならない</li>
     * <li>{@link #LAYER_TRANSITION_PENALTY} — 縦穴があるか分からないぶんの割増</li>
     * <li>下りの{@link #HEIGHT_COST_PER_BLOCK} — 降りは走り抜けられて実コストが増えない</li>
     * </ul>
     *
     * <p>残す水・溶岩・奈落の倍率は実時間だが、セルの<b>一部</b>がその地形でも全体に掛かるので、
     * 分類の閾値ぶん（{@link #WATER_CELL_MIN_FRACTION}・{@link #LAVA_MIXED_CELL_MIN_FRACTION}）
     * まで薄めて下限にする。
     *
     * <p><b>代償は探索の広さ。</b>実機エンドの島渡り（{@code RealEndTerrainTest}の区間）で
     * 69,159→260,176ノード、並列の深い予算まで含めた実時間で約1.2秒→約3.2秒。好みを戻すほど
     * 速くなるが経路は悪くなる（崖ペナルティを戻すと147,073ノード・約1.4秒だが、地上で許容を
     * 超える経路が7本→34本）。<b>案内の速さより経路の質を採った</b>のがこの選択。
     *
     * <p>実機の保存データ3次元×16方向×2距離で測った、基準（重み1.0・ガイド無し・予算無制限）
     * との経路コスト比（{@code PathOptimalityTest}）:
     *
     * <pre>
     *          好みを含んだ値         下限だけ
     * 地上   平均1.129 最悪1.473 → 平均1.034 最悪1.223
     * ネザー 平均1.043 最悪1.215 → 平均0.995 最悪1.096
     * エンド 平均1.041 最悪1.482 → 平均0.998 最悪1.417
     * </pre>
     */
    public static CostToGo costToGo(CoarseMap map, BlockPos goal, boolean boatAvailable, BridgePolicy bridgePolicy) {
        int goalX = goal.getX() >> 4;
        int goalZ = goal.getZ() >> 4;
        int states = map.chunksX() * map.chunksZ() * CoarseMap.MAX_FLOORS;
        double[] cost = new double[states];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        double goalOffset = centerOffsetCost(goal.getX(), goal.getZ(), goalX, goalZ);
        if (!map.containsChunk(goalX, goalZ)) {
            return new CoarseCostToGo(map, cost, goalOffset);
        }
        double waterMultiplier = boatAvailable ? BOAT_MULTIPLIER : WATER_MULTIPLIER;
        int goalFloor = resolveFloor(map, goalX, goalZ, goal.getY());
        int goalIndex = stateIndex(map, goalX, goalZ, goalFloor);
        cost[goalIndex] = 0.0;
        boolean[] closed = new boolean[states];

        PriorityQueue<Candidate> open =
                new PriorityQueue<>(Comparator.comparingDouble(Candidate::estimatedTotal));
        open.add(new Candidate(goalIndex, 0.0));

        while (!open.isEmpty()) {
            Candidate current = open.poll();
            if (closed[current.index()]) {
                continue;
            }
            closed[current.index()] = true;
            int x = stateChunkX(map, current.index());
            int z = stateChunkZ(map, current.index());
            int floor = stateFloor(current.index());

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    relaxBackwardHorizontal(map, cost, closed, open, x, z, floor, dx, dz, waterMultiplier,
                            bridgePolicy);
                }
            }
            relaxBackwardVertical(map, cost, closed, open, x, z, floor);
        }
        return new CoarseCostToGo(map, cost, goalOffset);
    }

    /**
     * セル中心の値をブロック座標へ落とすときに差し引く量（tick）。
     *
     * <p>表が持っているのは<b>セル中心から目的地セルの中心まで</b>の値だけなので、素のまま引くと
     * セルの中のどこにいても同じ値になる。実際には、引く側の座標も目的地もセルの中で最大
     * 半セル対角ぶん中心からずれていて、その分だけ表の値は実コストを上回る。
     *
     * <p>差し引かないと、{@code AStarPathfinder#node}のmaxが幾何学的な下限（{@code Heuristic}）より
     * 大きい値を拾い、<b>hに16ブロック周期の鋸歯が乗る</b>。重み1.5・closedを開き直さない探索と
     * 組み合わさると、詳細経路はチャンク境界へ吸い寄せられて<b>長い直線と直角</b>だけになる。
     * 同じ始終点での実測（左が鋸歯の乗った経路、右がガイド無しの最適経路と一致する現在の経路）:
     *
     * <pre>
     * 合成の平地   60手(斜め22/直進38)          → 40手(全部斜め)
     * 合成の起伏   150手(斜め30/直進120)         → 100手(斜め80)
     * 実機エンド島 148手(斜め32/直進116)         → 101手(斜め79)
     * </pre>
     *
     * <p><b>一律に1セル対角ぶん（{@link #DIAGONAL_COST}）引くのでは引きすぎる。</b>上振れの上限では
     * あるが、ガイド全体が弱まって奈落越えに要る展開ノード数が既定予算(10万)を超える。ずれは
     * 座標ごとに分かっているので、その実測値だけを引く。
     */
    private static double centerOffsetCost(int x, int z, int chunkX, int chunkZ) {
        int dx = Math.abs(x - (chunkX * CELL_BLOCKS + CELL_BLOCKS / 2));
        int dz = Math.abs(z - (chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2));
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        return (diagonal * ActionCosts.DIAGONAL_DISTANCE + straight) * ActionCosts.SPRINT_ONE_BLOCK;
    }

    /**
     * {@link #costToGo}の結果をブロック座標で引けるようにする薄いラッパー。範囲外・データ無しの
     * 座標は0を返す（層1に情報が無いだけで、{@code AStarPathfinder}側は幾何学的な
     * {@link net.prason.xaeronav.pathfinding.astar.Heuristic}とのmaxを取るので、0を返しても
     * 「情報が無いので寄与しない」以上の害は無い——{@link Double#POSITIVE_INFINITY}を返すと、
     * layer3の探索範囲がこの地図の読み取り範囲より広いだけで無限大に汚染されてしまう）。
     *
     * @param goalOffset 目的地がその所属セルの中心からずれているぶん（{@link #centerOffsetCost}）。
     *                   座標ごとのずれと違って探索中は変わらないので、表を作るときに1度だけ求める
     */
    private record CoarseCostToGo(CoarseMap map, double[] cost, double goalOffset) implements CostToGo {
        @Override
        public double estimate(int x, int y, int z) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (!map.containsChunk(chunkX, chunkZ)) {
                return 0.0;
            }
            int floor = map.nearestFloor(chunkX, chunkZ, y);
            if (floor < 0) {
                return 0.0;
            }
            double value = cost[stateIndex(map, chunkX, chunkZ, floor)];
            if (Double.isInfinite(value)) {
                return 0.0;
            }
            return Math.max(0.0, value - centerOffsetCost(x, z, chunkX, chunkZ) - goalOffset);
        }
    }

    private static void relaxBackwardHorizontal(CoarseMap map, double[] cost, boolean[] closed,
                                                PriorityQueue<Candidate> open, int x, int z, int floor,
                                                int dx, int dz, double waterMultiplier, BridgePolicy bridgePolicy) {
        int neighborX = x + dx;
        int neighborZ = z + dz;
        if (!map.containsChunk(neighborX, neighborZ)) {
            return;
        }
        boolean diagonal = dx != 0 && dz != 0;
        int neighborFloorCount = Math.max(map.floorCount(neighborX, neighborZ), 1);
        for (int neighborFloor = 0; neighborFloor < neighborFloorCount; neighborFloor++) {
            // from/toを入れ替え: 「neighborからxへ入るコスト」を計算する（逆走なので）
            double step = horizontalStepCost(map, neighborX, neighborZ, neighborFloor, x, z, floor, diagonal,
                    waterMultiplier, bridgePolicy, true);
            if (Double.isInfinite(step)) {
                continue;
            }
            offerBackward(map, cost, closed, open, x, z, floor, neighborX, neighborZ, neighborFloor, step);
        }
    }

    private static void relaxBackwardVertical(CoarseMap map, double[] cost, boolean[] closed,
                                              PriorityQueue<Candidate> open, int x, int z, int floor) {
        int floorCount = map.floorCount(x, z);
        if (floorCount == 0) {
            return;
        }
        for (int neighborFloor : new int[] {floor - 1, floor + 1}) {
            if (neighborFloor < 0 || neighborFloor >= floorCount) {
                continue;
            }
            // 逆走なので「neighborFloorからfloorへ上がる」ぶん。下りに値段を付けず、
            // LAYER_TRANSITION_PENALTYも掛けないのはcostToGoのjavadocのとおり
            double climb = Math.max(0,
                    map.heightAtFloor(x, z, floor) - map.heightAtFloor(x, z, neighborFloor));
            offerBackward(map, cost, closed, open, x, z, floor, x, z, neighborFloor,
                    climb * GUIDE_ASCEND_COST_PER_BLOCK);
        }
    }

    private static void offerBackward(CoarseMap map, double[] cost, boolean[] closed,
                                      PriorityQueue<Candidate> open, int fromX, int fromZ, int fromFloor,
                                      int toX, int toZ, int toFloor, double step) {
        int nextIndex = stateIndex(map, toX, toZ, toFloor);
        if (closed[nextIndex]) {
            return;
        }
        double tentative = cost[stateIndex(map, fromX, fromZ, fromFloor)] + step;
        if (tentative >= cost[nextIndex]) {
            return;
        }
        cost[nextIndex] = tentative;
        open.add(new Candidate(nextIndex, tentative));
    }

    /**
     * 始点・終点の座標が実際にどの床を指すかを解決する。既知セルなら実際のYに最も近い床、
     * 未知セルなら唯一の状態（{@code floor=0}、{@link #stateKind}が{@code NO_DATA}を返す）。
     */
    private static int resolveFloor(CoarseMap map, int chunkX, int chunkZ, int y) {
        int floor = map.nearestFloor(chunkX, chunkZ, y);
        return floor < 0 ? 0 : floor;
    }

    /**
     * 水平方向の隣接セルへは、今の床に高さが最も近い床<b>だけ</b>へ繋ぐ（隣接セルの全床へではない）。
     *
     * <p>全床へ繋ぐと、階層をまたぐはずの移動が{@link #LAYER_TRANSITION_PENALTY}を経由せず、
     * 普通の坂と同じ{@code heightPenalty}だけで隣のセルの遠い階層へ「水平移動のふりをして」
     * 渡れてしまう——{@link #relaxVertical}で明示的に払わせているはずの「本当に繋がっているか
     * 分からない」割増を、水平移動が迂回して素通りする抜け道になる。最寄りの床だけに絞れば、
     * 緩やかな坂はそのまま辿れる一方、階層が急に変わる箇所は必ず垂直遷移を経由することになる。
     */
    private static void relaxHorizontal(CoarseMap map, double[] cost, int[] previous, boolean[] closed,
                                        PriorityQueue<Candidate> open, int x, int z, int floor, int dx, int dz,
                                        int goalX, int goalZ, int[] bestSoFar, double[] bestHeuristic,
                                        double waterMultiplier, BridgePolicy bridgePolicy) {
        int nextX = x + dx;
        int nextZ = z + dz;
        if (!map.containsChunk(nextX, nextZ)) {
            return;
        }
        boolean diagonal = dx != 0 && dz != 0;
        int nextFloor = nearestConnectableFloor(map, x, z, floor, nextX, nextZ);
        double step = horizontalStepCost(map, x, z, floor, nextX, nextZ, nextFloor, diagonal, waterMultiplier,
                bridgePolicy, false);
        if (Double.isInfinite(step)) {
            return;
        }
        offer(map, cost, previous, closed, open, x, z, floor, nextX, nextZ, nextFloor, step, goalX, goalZ,
                bestSoFar, bestHeuristic, waterMultiplier);
    }

    /** 隣接セルのうち、今の床の高さに最も近い床。相手が未知セルなら唯一の状態（floor=0）。 */
    private static int nearestConnectableFloor(CoarseMap map, int fromX, int fromZ, int fromFloor,
                                               int toX, int toZ) {
        if (map.floorCount(toX, toZ) == 0) {
            return 0;
        }
        short fromHeight = stateHeight(map, fromX, fromZ, fromFloor);
        if (fromHeight == CoarseMap.UNKNOWN_HEIGHT) {
            return 0;
        }
        return map.nearestFloor(toX, toZ, fromHeight);
    }

    /**
     * 同じセル内で1つ上・1つ下の床への移動。床は高さ昇順に並んでいるので、隣接インデックスが
     * そのまま「次に近い階層」になる。未知セル（床数0）には床が無いので発生しない。
     */
    private static void relaxVertical(CoarseMap map, double[] cost, int[] previous, boolean[] closed,
                                      PriorityQueue<Candidate> open, int x, int z, int floor,
                                      int goalX, int goalZ, int[] bestSoFar, double[] bestHeuristic,
                                      double waterMultiplier) {
        int floorCount = map.floorCount(x, z);
        if (floorCount == 0) {
            return;
        }
        for (int nextFloor : new int[] {floor - 1, floor + 1}) {
            if (nextFloor < 0 || nextFloor >= floorCount) {
                continue;
            }
            double deltaHeight =
                    Math.abs(map.heightAtFloor(x, z, nextFloor) - map.heightAtFloor(x, z, floor));
            double step = deltaHeight * HEIGHT_COST_PER_BLOCK * LAYER_TRANSITION_PENALTY;
            offer(map, cost, previous, closed, open, x, z, floor, x, z, nextFloor, step, goalX, goalZ,
                    bestSoFar, bestHeuristic, waterMultiplier);
        }
    }

    private static void offer(CoarseMap map, double[] cost, int[] previous, boolean[] closed,
                              PriorityQueue<Candidate> open, int fromX, int fromZ, int fromFloor,
                              int toX, int toZ, int toFloor, double step, int goalX, int goalZ,
                              int[] bestSoFar, double[] bestHeuristic, double waterMultiplier) {
        int nextIndex = stateIndex(map, toX, toZ, toFloor);
        if (closed[nextIndex]) {
            return;
        }
        int fromIndex = stateIndex(map, fromX, fromZ, fromFloor);
        double tentative = cost[fromIndex] + step;
        if (tentative >= cost[nextIndex]) {
            return;
        }
        cost[nextIndex] = tentative;
        previous[nextIndex] = fromIndex;
        double remaining = heuristic(map, toX, toZ, goalX, goalZ, waterMultiplier);
        open.add(new Candidate(nextIndex, tentative + remaining));

        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double candidateHeuristic = remaining + tentative / COEFFICIENTS[i];
            if (candidateHeuristic < bestHeuristic[i]) {
                bestHeuristic[i] = candidateHeuristic;
                bestSoFar[i] = nextIndex;
            }
        }
    }

    /**
     * ゴールに届かなかったときの到達点を選ぶ。係数の小さい（＝実際に進んだ距離を重く見る）ものから順に、
     * 始点からXZ平面上で{@link #MIN_DIST_CELLS}以上離れている候補を採用する。どれも届かない場合は
     * 始点自身を返し、空のルート＝「提示できるルートなし」として扱う。
     */
    private static int selectFallback(CoarseMap map, int[] bestSoFar, int startIndex) {
        int startX = stateChunkX(map, startIndex);
        int startZ = stateChunkZ(map, startIndex);
        double thresholdSquared = MIN_DIST_CELLS * MIN_DIST_CELLS;
        for (int candidate : bestSoFar) {
            double dx = stateChunkX(map, candidate) - startX;
            double dz = stateChunkZ(map, candidate) - startZ;
            if (dx * dx + dz * dz > thresholdSquared) {
                return candidate;
            }
        }
        return startIndex;
    }

    /**
     * 1セル進むコスト。
     *
     * @param lowerBound 実コストの<b>下限</b>として使う値を求める（{@link #costToGo}のガイド用）。
     *                   {@code false}なら好みを含んだ計画用の値（{@link #findRoute}用）。
     *                   違いは{@link #costToGo}のjavadoc参照
     */
    private static double horizontalStepCost(CoarseMap map, int fromX, int fromZ, int fromFloor,
                                             int toX, int toZ, int toFloor, boolean diagonal,
                                             double waterMultiplier, BridgePolicy bridgePolicy,
                                             boolean lowerBound) {
        byte kind = stateKind(map, toX, toZ, toFloor);
        double bridgeMultiplier = bridgeMultiplier(kind, bridgePolicy);
        if (Double.isInfinite(bridgeMultiplier)) {
            return ActionCosts.INFEASIBLE;
        }
        double base = diagonal ? DIAGONAL_COST : STRAIGHT_COST;
        double multiplier = switch (kind) {
            case CoarseMap.WATER -> lowerBound
                    ? atLeast(waterMultiplier, WATER_CELL_MIN_FRACTION) : waterMultiplier;
            // 「分からない」は下限を上げる理由にならない。1.6のまま使うと、読み取り範囲の外側が
            // 一律に高く見えて経路が範囲の内側へ引き寄せられる
            case CoarseMap.NO_DATA -> lowerBound ? 1.0 : UNKNOWN_MULTIPLIER;
            case CoarseMap.LAVA_MIXED -> lowerBound
                    ? atLeast(bridgeMultiplier, LAVA_MIXED_CELL_MIN_FRACTION) : bridgeMultiplier;
            case CoarseMap.LAVA, CoarseMap.VOID -> bridgeMultiplier;
            default -> 1.0;
        };

        double heightPenalty = 0.0;
        short fromHeight = stateHeight(map, fromX, fromZ, fromFloor);
        short toHeight = stateHeight(map, toX, toZ, toFloor);
        // 片方でも高さが分からなければ段差は測れない。分からないことを段差0として扱うと、
        // 未知の領域が「平坦な近道」に見えてしまう
        if (fromHeight != CoarseMap.UNKNOWN_HEIGHT && toHeight != CoarseMap.UNKNOWN_HEIGHT) {
            heightPenalty = lowerBound
                    ? Math.max(0, toHeight - fromHeight) * GUIDE_ASCEND_COST_PER_BLOCK
                    : Math.abs(toHeight - fromHeight) * HEIGHT_COST_PER_BLOCK;
        }
        if (lowerBound) {
            return base * multiplier + heightPenalty;
        }
        return base * multiplier + heightPenalty + cliffPenalty(map, toX, toZ, toFloor)
                + smallIslandPenalty(map, fromX, fromZ, toX, toZ);
    }

    /** セルの{@code fraction}だけがその地形だと分かっているときの、倍率の下限。 */
    private static double atLeast(double multiplier, double fraction) {
        return 1.0 + (multiplier - 1.0) * fraction;
    }

    /**
     * 別の陸塊へ移るときだけ、その島の小ささに応じて課す割増。
     *
     * <p><b>島に入る一歩でだけ課金する</b>のが要点。セルごとに課すと、小さい島を横切るあいだ
     * 何度も払うことになり「小さい島は通り抜けるのも高い」という別の歪みが出る。
     * 知りたいのは「どの島へ降りるか」だけなので、陸塊IDが変わる辺で1回だけ見る。
     */
    private static double smallIslandPenalty(CoarseMap map, int fromX, int fromZ, int toX, int toZ) {
        int toIsland = map.islandIdAt(toX, toZ);
        if (toIsland == CoarseMap.NO_ISLAND || toIsland == map.islandIdAt(fromX, fromZ)) {
            return 0.0;
        }
        int size = map.islandSizeAt(toX, toZ);
        if (size >= LARGE_ISLAND_CELLS) {
            return 0.0;
        }
        return SMALL_ISLAND_PENALTY * (LARGE_ISLAND_CELLS - size) / (double) (LARGE_ISLAND_CELLS - 1);
    }

    /**
     * 足場を置かないと通れないセル（溶岩・奈落）の倍率。それ以外のセルには1.0を返す
     * （呼び出し側が他の倍率を使う）。{@link ActionCosts#INFEASIBLE}なら通行不能。
     */
    private static double bridgeMultiplier(byte kind, BridgePolicy bridgePolicy) {
        if (kind == CoarseMap.LAVA) {
            return bridgePolicy == BridgePolicy.BRIDGE ? LAVA_BRIDGE_MULTIPLIER : ActionCosts.INFEASIBLE;
        }
        if (kind == CoarseMap.LAVA_MIXED) {
            return bridgePolicy == BridgePolicy.AVOID ? ActionCosts.INFEASIBLE : LAVA_MIXED_MULTIPLIER;
        }
        if (kind == CoarseMap.VOID) {
            // 奈落を通行不能にするのは{@link BridgePolicy#AVOID}だけ。ALLOWでも橋で通す。
            //
            // 溶岩と非対称なのは、<b>溶岩の橋には設定のスイッチがあるのに奈落には無い</b>から
            // （層3の{@code addBridge}は奈落を{@code canPlaceBlocks}だけで判断する）。
            // ALLOWで奈落まで通行不能にすると、層3の区間分割（{@code solveCoarseGuided}）が
            // ジ・エンドで区間を1つも作れず、島間を1回の探索で渡ろうとして予算を焼く——
            // {@code PathfindingExecutor}に「一律ALLOWにすると溶岩の海の縁で同じことが起きる」と
            // 実機の記録つきで書いてある、その奈落版になる。
            return bridgePolicy == BridgePolicy.AVOID ? ActionCosts.INFEASIBLE : VOID_BRIDGE_MULTIPLIER;
        }
        return 1.0;
    }

    /** 踏み込み先の床の起伏が大きいときの追加コスト。起伏が分からなければ平坦扱い（0）。 */
    private static double cliffPenalty(CoarseMap map, int chunkX, int chunkZ, int floor) {
        short min = stateMinHeight(map, chunkX, chunkZ, floor);
        short max = stateMaxHeight(map, chunkX, chunkZ, floor);
        if (min == CoarseMap.UNKNOWN_HEIGHT || max == CoarseMap.UNKNOWN_HEIGHT) {
            return 0.0;
        }
        int relief = max - min;
        if (relief <= CLIFF_THRESHOLD_BLOCKS) {
            return 0.0;
        }
        return Math.min((relief - CLIFF_THRESHOLD_BLOCKS) * CLIFF_COST_PER_BLOCK, CLIFF_PENALTY_CAP);
    }

    /**
     * 残りコストの下限。XZ平面上の距離だけを見る——垂直方向（階層をまたぐコスト）を無視するのは
     * 過小評価にしかならないので、下限としての正しさ（admissibility）は保たれる。
     * {@code waterMultiplier}(ボート所持時は{@link #BOAT_MULTIPLIER}<1.0)を掛けておかないと、
     * 経路が丸ごとボート水域だった場合の実コストがこの下限を下回り非許容になる。
     */
    private static double heuristic(CoarseMap map, int x, int z, int goalX, int goalZ, double waterMultiplier) {
        int dx = Math.abs(goalX - x);
        int dz = Math.abs(goalZ - z);
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        double multiplier = Math.min(1.0, waterMultiplier);
        return (diagonal * DIAGONAL_COST + straight * STRAIGHT_COST) * multiplier;
    }

    /**
     * 経路を中間目標へ間引く。全セルを返すと詳細探索が数チャンクごとに呼ばれることになり、
     * 粗い線をなぞるだけの案内になってしまう。水平・垂直どちらかの間隔を超えたら区切る——
     * 階層を何段も登る区間は水平に進まないので、垂直側の間隔が無いと丸ごと1区間に潰れる。
     */
    private static Route buildRoute(CoarseMap map, int[] previous, int endIndex, int startIndex,
                                    boolean reachedGoal, int startY) {
        List<Integer> states = new ArrayList<>();
        for (int cursor = endIndex; cursor != -1; cursor = previous[cursor]) {
            states.add(cursor);
            if (cursor == startIndex) {
                break;
            }
        }
        Collections.reverse(states);
        if (states.size() <= 1) {
            return new Route(List.of(), reachedGoal);
        }

        List<BlockPos> waypoints = new ArrayList<>();
        int lastX = stateChunkX(map, states.get(0));
        int lastZ = stateChunkZ(map, states.get(0));
        // 高さが分からない状態のフォールバックは、直前に分かった高さを引き継ぐ（無ければ出発点）。
        // 固定の0だと、ネザーのように地形の主要な高さ帯が0から遠い次元で、詳細探索が
        // 奈落の底へ経路を引こうとしてノード上限を焼き切る
        int fallbackHeight = startY;
        int lastWaypointHeight = startY;
        for (int i = 1; i < states.size(); i++) {
            int state = states.get(i);
            int x = stateChunkX(map, state);
            int z = stateChunkZ(map, state);
            int floor = stateFloor(state);
            boolean last = i == states.size() - 1;
            int spanX = Math.abs(x - lastX);
            int spanZ = Math.abs(z - lastZ);
            short height = stateHeight(map, x, z, floor);
            int spanY = height == CoarseMap.UNKNOWN_HEIGHT ? 0 : Math.abs(height - lastWaypointHeight);
            if (last || Math.max(spanX, spanZ) >= WAYPOINT_SPACING_CELLS
                    || spanY >= WAYPOINT_VERTICAL_SPACING_BLOCKS) {
                BlockPos waypoint = toBlockPos(x, z, height, fallbackHeight);
                waypoints.add(waypoint);
                fallbackHeight = waypoint.getY();
                lastWaypointHeight = waypoint.getY();
                lastX = x;
                lastZ = z;
            }
        }
        return new Route(List.copyOf(waypoints), reachedGoal);
    }

    /** セルの中心。高さが分からない状態は{@code fallbackHeight}を使う。 */
    private static BlockPos toBlockPos(int chunkX, int chunkZ, short height, int fallbackHeight) {
        return new BlockPos(chunkX * CELL_BLOCKS + CELL_BLOCKS / 2,
                height == CoarseMap.UNKNOWN_HEIGHT ? fallbackHeight : height,
                chunkZ * CELL_BLOCKS + CELL_BLOCKS / 2);
    }

    /**
     * {@code floor}が実在するか（{@code floorCount>0}）で、未知セルの「未知」状態と
     * 既知の床を区別する。未知セルは常に{@code floor==0}の1状態しか持たない。
     */
    private static boolean isUnknownState(CoarseMap map, int chunkX, int chunkZ) {
        return map.floorCount(chunkX, chunkZ) == 0;
    }

    private static byte stateKind(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return isUnknownState(map, chunkX, chunkZ) ? CoarseMap.NO_DATA : map.kindAtFloor(chunkX, chunkZ, floor);
    }

    private static short stateHeight(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return isUnknownState(map, chunkX, chunkZ) ? CoarseMap.UNKNOWN_HEIGHT
                : map.heightAtFloor(chunkX, chunkZ, floor);
    }

    private static short stateMinHeight(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return isUnknownState(map, chunkX, chunkZ) ? CoarseMap.UNKNOWN_HEIGHT
                : map.minHeightAtFloor(chunkX, chunkZ, floor);
    }

    private static short stateMaxHeight(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return isUnknownState(map, chunkX, chunkZ) ? CoarseMap.UNKNOWN_HEIGHT
                : map.maxHeightAtFloor(chunkX, chunkZ, floor);
    }

    private static int stateIndex(CoarseMap map, int chunkX, int chunkZ, int floor) {
        return cellIndex(map, chunkX, chunkZ) * CoarseMap.MAX_FLOORS + floor;
    }

    private static int cellIndex(CoarseMap map, int chunkX, int chunkZ) {
        return (chunkZ - map.minChunkZ()) * map.chunksX() + (chunkX - map.minChunkX());
    }

    private static int stateChunkX(CoarseMap map, int stateIndex) {
        int cellIndex = stateIndex / CoarseMap.MAX_FLOORS;
        return map.minChunkX() + cellIndex % map.chunksX();
    }

    private static int stateChunkZ(CoarseMap map, int stateIndex) {
        int cellIndex = stateIndex / CoarseMap.MAX_FLOORS;
        return map.minChunkZ() + cellIndex / map.chunksX();
    }

    private static int stateFloor(int stateIndex) {
        return stateIndex % CoarseMap.MAX_FLOORS;
    }

    private record Candidate(int index, double estimatedTotal) {
    }
}
