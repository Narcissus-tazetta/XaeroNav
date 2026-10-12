package net.prason.xaeronav.client;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.client.PathfindingState.DetailTarget;
import net.prason.xaeronav.util.BlockDistance;
import net.prason.xaeronav.xaero.XaeroPresence;

/**
 * 詳細探索（層3）のゴールを決める。目的地をそのまま狙うか、長距離ルートの中間目標を挟むか、
 * 一度に狙える距離の手前で切るか。{@link PathfindingState}から切り出したもので、長距離ルートは
 * {@link CoarseRoutes}に問い合わせ、ルート上で後ろへ戻らないための歯止めだけをここで持つ。
 *
 * <p>呼ぶのはクライアントスレッド（地図を読むことがある）。
 */
final class DetailTargets {

    /**
     * 長距離ルートのwaypointをゴールとして許す半径（ブロック）。
     *
     * <p>層1のwaypointはチャンク(16ブロック)の代表点、層2で精緻化しても地表データ由来の推定でしかない。
     * <b>中継地点は通る場所ではなく向かう方角</b>——層1の役割の定義そのものなので、座標ぴったりを
     * 要求すると本来不要な遠回りが経路に乗る。層2の精緻版がある場合を見込んで、セルの半幅(8)より
     * やや小さく取る。
     */
    static final int WAYPOINT_GOAL_RADIUS_BLOCKS = 6;

    /**
     * waypointへ向かう線上の補間点（{@link #pointAlong}）をゴールとして許す半径（ブロック）。
     *
     * <p>こちらは地形をまったく見ていない<b>人工的な点</b>——waypointが遠すぎて一度に狙えないときに、
     * その方角へ進むためだけに置いている。ここを厳密なゴールにするのが「遠回り」の最大の発生源なので、
     * waypoint本体より大きく取る。
     */
    static final int INTERPOLATED_GOAL_RADIUS_BLOCKS = 16;

    /**
     * 長距離ルートを同じ場所から引き直さないための移動距離（ブロック）。
     *
     * <p>層1の引き直しは{@code XaeroMapReader#readSurface}（メインスレッド）と{@code CoarseRouter}の
     * A*2回で、目的地までの距離に比例して重くなる。届く中間目標が1つも無い間はこれが毎回の再計算で
     * 走るが、<b>同じ場所から引き直せば同じルートになる</b>——結果が変わりうるのは、地図に載る範囲が
     * 変わる程度に動いたときだけ。
     */
    private static final double COARSE_ROUTE_RETRY_MOVE_BLOCKS = 32.0;

    private final CoarseRoutes coarseRoutes;
    /** 詰みかけているか（{@link StuckTracker#stranded}）。詰みかけている間は後戻りの歯止めを外す。 */
    private final BooleanSupplier stranded;
    // 前回のdetail-targetが向いていたwaypoint（生座標）。ルート上で後ろへ戻らないための歯止め。
    // 添字ではなく座標で覚えるのは、ルートを引き直すと添字の意味が変わるため——新しい列に同じ点が
    // 無ければ歯止めは自動的に外れる
    private volatile BlockPos lastAimedWaypoint;

    DetailTargets(CoarseRoutes coarseRoutes, BooleanSupplier stranded) {
        this.coarseRoutes = coarseRoutes;
        this.stranded = stranded;
    }

    /** 目的地を捨てたとき。歯止めは目的地ごとのもの。 */
    void reset() {
        lastAimedWaypoint = null;
    }

    /**
     * 詳細探索のゴールを決める。目的地が詳細探索の届く範囲の外にあり、Xaeroの地図データが
     * あるときだけ長距離ルートの中間目標を挟む。地図が無い・範囲外・引き直してもなお中間目標が
     * 一つも届く範囲に無い、のいずれでも本来の目的地へ直接向かう従来動作にフォールバックする
     * （発動条件が一つでも欠けたら長距離ルートには入らない）。
     *
     * <p>境界は描画距離ではなく{@code reach}（呼び出し側が渡す一度に狙える距離）。描画距離を
     * 使うと「圏内なら詳細探索がフル解像度データで解けるはず」を前提にすることになるが、それが
     * 成り立つのは地表を歩くときだけ。実測では描画距離32（512ブロック）のネザーで、目的地まで
     * 502ブロックになった時点で長距離ルートが外れ、以降ずっと500ブロック先を一発で解こうとして
     * 失敗し続けた（粗い経由地チェーンが毎回0ステップ＝「経路が見つかりません」）。
     */
    DetailTarget selectDetailTarget(BlockPos start, BlockPos currentGoal, int renderRadius,
                                             int reach, boolean boatAvailable, boolean playerAnchored,
                                             int minWaypointIndex, boolean ceilingDimension, boolean navGraphGuided) {
        if (BlockDistance.horizontal(start, currentGoal) <= reach) {
            return new DetailTarget(currentGoal, -1, 0);
        }
        if (navGraphGuided) {
            // 航法グラフのガイドがあれば中間目標へ立ち寄らない。窓の中のガイドは探索と同じ移動の本物の残りコストで、
            // 中間目標へ寄ること自体が遠回りになる（歩き通しの実測: 広域長距離1.067→1.017倍、エンド1.122→1.014倍）。
            //
            // 層1は引き続き引く（HUDと地図の点線、窓の外の推定）。層2の精緻化は目標に使わないので掛けない
            if (XaeroPresence.mapPresent()) {
                coarseRoutes.prepareCoarseRoute(start, currentGoal, boatAvailable, playerAnchored, true);
            }
            return new DetailTarget(currentGoal, -1, 0);
        }
        if (!XaeroPresence.mapPresent()) {
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
            coarseRoutes.prepareCoarseRoute(start, currentGoal, boatAvailable, playerAnchored, true);
            return new DetailTarget(currentGoal, -1, 0);
        }
        DetailTarget target = reachableWaypointTarget(start, currentGoal,
                coarseRoutes.cachedOrFreshRoute(start, currentGoal, boatAvailable, playerAnchored, false),
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
        if (coarseRoutes.refiningFor(currentGoal) != null) {
            // 層2の精緻化がまさにこの目的地に対して進行中。ここで引き直すとその結果を捨てて
            // 同じ状況をやり直すだけになる——描画距離が小さい環境では層1の96ブロック間隔が
            // 1つも届かず必ずここへ来るので、素通しにすると「0.5秒ごとにメインスレッドで
            // 地図を読み直しては精緻化を捨てる」ループに入り、精緻版が永久に完成しない。
            // 完成すればpendingRefinedRouteReadyが引き直しをかけ、24ブロック間隔のwaypointが
            // 届くようになる。それまでは長距離ルートを挟まない従来動作へ落とす
            return goalOrPointToward(start, currentGoal, reach);
        }
        CoarseRoutes.CoarseRoute cached = coarseRoutes.route();
        if (cached != null && cached.goal().equals(currentGoal)
                && BlockDistance.horizontal(start, cached.computedFrom()) < COARSE_ROUTE_RETRY_MOVE_BLOCKS) {
            // 引き直したところで同じルートになる。層1の引き直しはメインスレッドでの地図読みと
            // 層1A*2回ぶんで、届く中間目標が1つも無い間はそれを毎回の再計算で払うことになる
            return goalOrPointToward(start, currentGoal, reach);
        }
        // キャッシュ済みのwaypointが1つも描画距離内に届かない＝大きく迂回して経路から外れた。
        // 目的地は変わっていないのでキャッシュは効くはずだが、地形は不変でも自分の位置は変わるので、
        // 今の位置を始点に引き直す（地形が変わらない限り引き直さない、という原則の唯一の例外）
        target = reachableWaypointTarget(start, currentGoal,
                coarseRoutes.freshRoute(start, currentGoal, boatAvailable, false),
                renderRadius, reach, true, minWaypointIndex);
        return target != null ? target : goalOrPointToward(start, currentGoal, reach);
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
        BlockPos resolved = XaeroPresence.mapPresent() ? PathfindingState.resolveWaypointOnSurface(aim) : aim;
        return new DetailTarget(resolved, -1, INTERPOLATED_GOAL_RADIUS_BLOCKS);
    }

    /** 目的地そのもの、または遠すぎるならその方向へ{@code reach}だけ進んだ点。 */
    static BlockPos aimTowardGoal(BlockPos start, BlockPos goal, int reach) {
        double distance = BlockDistance.horizontal(start, goal);
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
        return BlockDistance.horizontal(start, aim)
                < WAYPOINT_GOAL_RADIUS_BLOCKS + CoarseRoutes.REFINED_WAYPOINT_MIN_SPACING_BLOCKS;
    }

    /**
     * 中間目標は順番に1つずつではなく、詳細探索が届く範囲で最も遠い未通過点を選ぶ。
     * waypoint間隔は詳細探索が一度に届く距離より十分短いので、1つずつ渡すと探索能力を捨てる。
     * 1つも描画距離内に無ければ{@code null}（呼び出し側が引き直すかどうかを判断する）。
     *
     * <p>「届く範囲」は描画距離ではなく{@code reach}（呼び出し側が渡す一度に狙える距離）で切る。
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
        boolean strandedHere = stranded.getAsBoolean();
        BlockPos aimedBefore = playerAnchored && !strandedHere ? lastAimedWaypoint : null;
        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos waypoint = waypoints.get(i);
            if (waypoint.equals(aimedBefore)) {
                previouslyAimed = i;
            }
            double distance = BlockDistance.horizontal(start, waypoint);
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
        double distance = BlockDistance.horizontal(start, aim);
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
        // 本来の目的地（replaceLastで置き換わった最終waypoint）は{@code PathfindingState#setGoal}で既に立てる座標へ
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
        return new DetailTarget(PathfindingState.resolveWaypointOnSurface(target), farthestIndex,
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
            double leg = BlockDistance.horizontal(cursor, next);
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
}
