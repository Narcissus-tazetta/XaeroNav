package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * 地上の移動候補生成（歩行・斜め・昇降・跳躍・梯子・落下）。{@link AStarPathfinder}の
 * 分割の一部（ARCH-02）——探索ループ・open set・ノード表は{@link AStarPathfinder}に残し、
 * 候補生成だけをここへ切り出した。
 *
 * <p>探索1回につき1つだけ生成する（{@link AStarPathfinder}のコンストラクタ参照）。展開のたびに
 * 新しく作らないので、ホットパスへのアロケーションは増えない。{@code owner}経由で触る
 * {@link AStarPathfinder}側のフィールド・ヘルパーは元のクラスと同じ意味を持つ——ロジックは
 * 移動のみで、挙動を変えていない。
 */
final class GroundMoves {

    /**
     * 飛び越えられる隙間の最大幅（着地点は隙間の1マス先）。疾走ジャンプは滞空約12.5tickの間に
     * 水平4マス弱しか進めないので、3マスの隙間＝4マス先への着地がバニラの到達限界になる。
     */
    private static final int MAX_JUMP_GAP_BLOCKS = 3;

    private final AStarPathfinder owner;

    GroundMoves(AStarPathfinder owner) {
        this.owner = owner;
    }

    void addTraverse(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, y - 1, z))) {
            return;
        }
        double bodyCost = owner.standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        boolean inWater = CellData.water(owner.view.cell(x, y, z));
        owner.relax(from, x, y, z, owner.stepCost(x, y, z) + owner.submerged(from, bodyCost, x, y + 1, z),
                inWater ? MoveKind.SWIM : MoveKind.TRAVERSE);
    }

    /**
     * 同一高度での斜め移動。カーディナル4方向のみだと、斜めに続く地形で
     * 本来なら1手で行ける区間を2手のジグザグで迂回することになり不必要に遠回りになる。
     * 角の2セル（{@link AStarPathfinder#clearWithoutDigging}）が両方とも掘削なしで通行可能な場合のみ許可し、
     * 体が壁の角をすり抜ける経路を生成しないようにする。
     */
    void addDiagonalTraverse(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, y - 1, z))) {
            return;
        }
        if (!owner.clearWithoutDigging(from.x + dx, y, from.z) || !owner.clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        double bodyCost = owner.standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        boolean inWater = CellData.water(owner.view.cell(x, y, z));
        owner.relax(from, x, y, z,
                owner.stepCost(x, y, z) * ActionCosts.DIAGONAL_DISTANCE + owner.submerged(from, bodyCost, x, y + 1, z),
                inWater ? MoveKind.SWIM : MoveKind.DIAGONAL);
    }

    void addAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, from.y, z))) {
            return;
        }
        // 踏み切り地点の頭上。塞がっていればそのままではジャンプできないが、洞窟では天井を1マス
        // 崩して上がるのが普通の手段なので、掘れるなら掘るという選択肢として残す
        double clearanceCost = owner.columnCost(from.x, from.y + 2, from.y + 2, from.z, null);
        if (Double.isInfinite(clearanceCost)) {
            return;
        }
        double bodyCost = owner.standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        owner.relax(from, x, y, z,
                ActionCosts.ascendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z))
                        + owner.submerged(from, clearanceCost + bodyCost, x, y + 1, z),
                MoveKind.ASCEND);
    }

    void addDescend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y - 1;
        int z = from.z + dz;

        // 水面へ踏み込む場合は足場が要らない。海岸は水面より1マス高いのが普通なので、
        // これが無いと岸から海に入る手段そのものが無くなる
        boolean intoWater = CellData.water(owner.view.cell(x, y, z));
        if (!intoWater && !CellData.standable(owner.view.cell(x, y - 1, z))) {
            return;
        }
        double bodyCost = owner.descendingBodyCost(x, from.y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        double baseCost = intoWater ? ActionCosts.SWIM_ONE_BLOCK
                : ActionCosts.descendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z));
        owner.relax(from, x, y, z, baseCost + owner.submerged(from, bodyCost, x, y + 1, z),
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
    void addDiagonalAscend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y + 1;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, from.y, z))) {
            return;
        }
        // 角2列を到着高さで見る。踏み出し高さの角は段差そのものなので塞がっていて構わない
        if (!owner.clearWithoutDigging(from.x + dx, y, from.z) || !owner.clearWithoutDigging(from.x, y, from.z + dz)) {
            return;
        }
        // 踏み切り地点の頭上。塞がっていると跳べない
        if (!CellData.occupiableWithoutDigging(owner.view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        if (!owner.clearWithoutDigging(x, y, z)) {
            return;
        }
        owner.relax(from, x, y, z,
                ActionCosts.diagonalAscendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z)),
                MoveKind.DIAGONAL_ASCEND);
    }

    /**
     * 斜め1マスで1段降りながら進む。{@link #addDiagonalAscend}と同じ狙い。掘削は許可しない。
     *
     * <p>{@link #addDescend}と違い水面への踏み込みは扱わない（床は{@code standable}限定）。
     * 海岸線の水際はカーディナル側が既に扱っており、斜めまで足すと水際で経路が細かく揺れる。
     */
    void addDiagonalDescend(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y - 1;
        int z = from.z + dz;

        if (!CellData.standable(owner.view.cell(x, y - 1, z))) {
            return;
        }
        // 角2列を踏み出し高さで見る
        if (!owner.clearWithoutDigging(from.x + dx, from.y, from.z)
                || !owner.clearWithoutDigging(from.x, from.y, from.z + dz)) {
            return;
        }
        // 到着地点の身体3セル分（Descendと同じ縦一列）。2回に分けて呼ぶことで
        // y-1〜y+1（着地の足元・頭、踏み出し地点の足元と同じ高さ）をまとめて確認する
        if (!owner.clearWithoutDigging(x, y, z) || !owner.clearWithoutDigging(x, y + 1, z)) {
            return;
        }
        // 身体が水中にある斜め下降は泳いで進むので、疾走を前提にした値段では速すぎる——
        // 泳ぎの斜め(7.857)より安くなり、水中で上下にジグザグして進む経路が出る。
        // 水面へ踏み込む側は上のstandable要求で既に除いてあるので、ここで見るのは
        // 「もう水の中にいる」場合だけ
        boolean swimming = CellData.water(owner.view.cell(from.x, from.y, from.z))
                || CellData.water(owner.view.cell(x, y, z));
        double cost = swimming
                ? ActionCosts.SWIM_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE
                : ActionCosts.diagonalDescendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z));
        owner.relax(from, x, y, z, cost, swimming ? MoveKind.SWIM_DESCEND : MoveKind.DIAGONAL_DESCEND);
    }

    /**
     * 隙間を飛び越える（同一高度、カーディナル方向のみ）。
     *
     * <p>これが無いと、誰でも何も考えずに跨げる1マスの割れ目（小川・洞窟の裂け目・峡谷の枝）で、
     * ブロックを置いて渡るか大きく迂回することになる。
     *
     * <p>{@link #MAX_JUMP_GAP_BLOCKS}マスまで。これは疾走ジャンプの到達限界そのもので、
     * これ以上は助走をどれだけ取っても届かない。跳躍は外せば落ちるので、そもそも提示するかどうかを
     * {@code CellSource#jumpGapEnabled()}で切れるようにしてある。
     *
     * <p>近い隙間から順に試し、最初に着地できた距離で確定する。同じ方向に複数の着地点があるとき、
     * 手前に降りられるなら遠くまで跳ぶ理由が無い（{@link ActionCosts#jumpAcrossGap}も遠いほど高い）。
     *
     * <p>空中では掘れないので、通り抜ける空間は掘削なしで通れることを求める。頭上も見る —
     * ジャンプは1.25マス上がるので、天井があると跳べずに隙間へ落ちる。
     */
    void addJumpGap(PathNode from, int dx, int dz) {
        if (!owner.view.jumpGapEnabled()) {
            return;
        }
        int y = from.y;
        if (CellData.standable(owner.view.cell(from.x + dx, y - 1, from.z + dz))) {
            // 隙間ではなく床がある。歩いて行けるならTraverseの方が安い
            return;
        }
        // 踏み切り地点の頭上。ここが塞がっていると跳躍そのものが成立しない
        if (!CellData.occupiableWithoutDigging(owner.view.cell(from.x, from.y + 2, from.z))) {
            return;
        }
        // ソウルサンド・蜂蜜の上からは疾走の最高速度が出ない。到達距離は踏み切り時の水平速度で
        // 決まる（滞空時間は距離に依らず一定）ので、減速したまま跳ぶと必ず隙間に落ちる。
        // 倍率の探し方は歩行コスト（{@code AStarPathfinder#stepCost}）と同じくバニラの
        // {@code getBlockSpeedFactor}に倣う
        if (slowedTakeoff(from.x, y, from.z)) {
            return;
        }
        // 梯子・ツタに掴まったままでは跳べない。onGround()がfalseなのでjumpFromGround()自体が
        // 呼ばれず（LivingEntity#aiStep）、掴まったまま接地していてもhandleOnClimbableが
        // 水平速度を±0.15に固定するので、疾走の0.286も踏み切り加算の0.2も残らない
        if (CellData.climbable(owner.view.cell(from.x, y, from.z))
                || !CellData.standable(owner.view.cell(from.x, y - 1, from.z))) {
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

        // 跳び越す隙間の下がどれだけ深いか。addBridgeと同じ値段表で危険料を積む
        double dropRisk = 0.0;
        for (int gap = 1; gap <= MAX_JUMP_GAP_BLOCKS; gap++) {
            int gapX = from.x + gap * dx;
            int gapZ = from.z + gap * dz;
            // 跳び越える空間が塞がっていれば、その先へはどれだけ助走しても届かない
            if (!owner.clearWithoutDigging(gapX, y, gapZ)
                    || !CellData.occupiableWithoutDigging(owner.view.cell(gapX, y + 2, gapZ))) {
                return;
            }
            // 下が溶岩の隙間は跳ばない。跳躍は外せば落ちるという前提でコストを積んであるが、
            // 溶岩ではその「外したとき」が死なので、コストの多寡で釣り合う話ではなくなる。
            // 下が読めない（未ロード）隙間も同じ扱いにする——溶岩でないと言い切れない
            if (owner.scans.lavaOrUnknownBelow(gapX, y, gapZ)) {
                return;
            }
            int gapDrop = missDrop(gapX, y, gapZ);
            if (owner.avoidRiskyJumps && gapDrop >= owner.view.fatalFallBlocks()) {
                // 外したら死ぬ隙間。溶岩と違って「その隙間の上を跳ぶ手そのものを永久に消す」のではなく、
                // 回り込む道が一本も無いと分かったときだけ緩和の梯子が開ける（riskyJumpBlocked）
                owner.riskyJumpBlocked = true;
                return;
            }
            dropRisk += ActionCosts.dropRiskPenalty(gapDrop, owner.view.fatalFallBlocks());
            int x = from.x + (gap + 1) * dx;
            int z = from.z + (gap + 1) * dz;
            if (!CellData.standable(owner.view.cell(x, y - 1, z))) {
                // まだ着地できない。隙間はもう1マス続く
                continue;
            }
            if (!owner.clearWithoutDigging(x, y, z)) {
                return;
            }
            owner.relax(from, x, y, z, ActionCosts.jumpAcrossGap(gap) + dropRisk, MoveKind.JUMP);
            return;
        }
    }

    /**
     * この隙間を跳び損ねたら何マス落ちるか。底が無い（奈落）なら{@code CellSource#fatalFallBlocks()}を返す。
     *
     * <p>落差の測り方は{@link #addFall}・{@code BuildMoves#addBridge}と揃えてある——あちらが
     * 「意図して降りる」高さを見るのに対し、こちらは同じ落差を「跳んで外したとき」として見る。
     * 溶岩と未ロードは呼び出し側（{@code lavaOrUnknownBelow}）が先に弾いている。
     */
    private int missDrop(int x, int y, int z) {
        int obstacleY = owner.scans.firstNonAirBelow(x, y - 1, z);
        if (obstacleY == ColumnScans.NOTHING_BELOW || obstacleY == ColumnScans.UNREADABLE_BELOW) {
            // 未ロードは呼び出し側が既に弾いている。ここへは来ない想定だが、
            // 「読めない＝危険ではない」と倒さないよう明示しておく
            return owner.view.fatalFallBlocks();
        }
        long obstacle = owner.view.cell(x, obstacleY, z);
        if (CellData.water(obstacle)) {
            // 着水はバニラが落下距離をリセットするので、どれだけ落ちても死なない
            return 0;
        }
        return y - obstacleY - 1;
    }

    /** 踏み切り地点が減速ブロックの上か（バニラの{@code Entity#getBlockSpeedFactor}と同じ探し方）。 */
    private boolean slowedTakeoff(int x, int y, int z) {
        return owner.takeoffSpeedFactor(x, y, z) < 1.0;
    }

    /** 踏み切り地点の手前（跳躍方向の逆側）に、走り込める足場が1マスあるか。 */
    private boolean hasRunUp(PathNode from, int y, int dx, int dz) {
        int x = from.x - dx;
        int z = from.z - dz;
        return CellData.standable(owner.view.cell(x, y - 1, z)) && owner.clearWithoutDigging(x, y, z);
    }

    /**
     * 梯子・ツタに横から取り付く。足場を要求しないのが{@link #addTraverse}との違いで、
     * 縦穴の途中に張られた梯子へ移るにはこれが要る。
     */
    void addClimb(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        if (CellData.standable(owner.view.cell(x, y - 1, z))) {
            // 足場があるなら同じ移動をTraverse側が作る
            return;
        }
        if (!CellData.climbable(owner.view.cell(x, y, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        owner.relax(from, x, y, z, ActionCosts.WALK_ONE_BLOCK, MoveKind.CLIMB);
    }

    /** 梯子・ツタを登る。上り切った先へは、そこから水平移動で降りる（頂上より上には行けない）。 */
    void addClimbUp(PathNode from) {
        int y = from.y + 1;
        if (!CellData.climbable(owner.view.cell(from.x, y, from.z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(from.x, y + 1, from.z))) {
            return;
        }
        owner.relax(from, from.x, y, from.z, ActionCosts.LADDER_UP_ONE_BLOCK, MoveKind.CLIMB_UP);
    }

    void addClimbDown(PathNode from) {
        int y = from.y - 1;
        if (!CellData.climbable(owner.view.cell(from.x, y, from.z))) {
            return;
        }
        owner.relax(from, from.x, y, from.z, ActionCosts.LADDER_DOWN_ONE_BLOCK, MoveKind.CLIMB_DOWN);
    }

    /**
     * 縁から踏み出して落ちる。1マス下は{@link #addDescend}が扱うので、ここは2マス以上の落下だけ。
     *
     * <p>既定では落下ダメージを受ける高さを提示しない（{@code ActionCosts#SAFE_FALL_BLOCKS}まで）。降りる
     * 手段は掘り下げ（Descend + 掘削）もあるので、痛い近道を勧めるより階段状に降りる経路を出す方がよい。
     * ただし着水はバニラが落下距離をリセットするので、高さを問わず安全に降りられる。
     *
     * <p>設定で許可された場合だけ、体力から決まる上限までのダメージ落下と、水バケツMLGによる無傷の
     * 落下を候補に加える（{@code CellSource#maxFallDamagePoints}／{@code CellSource#canMlgWaterBucket}）。
     */
    void addFall(PathNode from, int dx, int dz, int obstacleY) {
        if (obstacleY == ColumnScans.NOTHING_BELOW || obstacleY == ColumnScans.UNREADABLE_BELOW) {
            // 底が無い（奈落）か、下に何があるか読めない。どちらも着地点を約束できない
            return;
        }
        int x = from.x + dx;
        int z = from.z + dz;
        // 踏み出す先の2マスが空いていないと縁から出られない。落下中は掘れないので空気であること
        if (!CellData.passableEmpty(owner.view.cell(x, from.y, z))
                || !CellData.passableEmpty(owner.view.cell(x, from.y + 1, z))) {
            return;
        }

        // 縁を踏み出す動作も足元のブロックに減速される（落下中と着地後は無関係）
        double takeoff = owner.takeoffSpeedFactor(from.x, from.y, from.z);
        long obstacle = owner.view.cell(x, obstacleY, z);
        if (CellData.water(obstacle)) {
            owner.relax(from, x, obstacleY, z, ActionCosts.fallCost(from.y - obstacleY, takeoff),
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
            owner.relax(from, x, obstacleY + 1, z, ActionCosts.fallCost(drop, takeoff), MoveKind.FALL);
            return;
        }

        // バニラのダメージは ceil(落下距離 - SAFE_FALL_DISTANCE)。落下距離が整数マスなのでそのまま引き算になる
        int damage = drop - ActionCosts.SAFE_FALL_BLOCKS;
        boolean mlg = owner.view.canMlgWaterBucket();
        if (mlg) {
            owner.relax(from, x, obstacleY + 1, z,
                    ActionCosts.fallCost(drop, takeoff) + ActionCosts.MLG_WATER_OVERHEAD_TICKS,
                    MoveKind.FALL_MLG);
        }
        if (damage > owner.maxFallDamagePoints) {
            // 立てる床はそこにあり、届きもする。許容量だけが足りない——緩めれば道になる可能性がある。
            // ここまで来ている時点で奈落でも未ロードでもないので、フラグは「緩める意味がある」を正しく指す。
            //
            // 水バケツMLGで同じ着地を既に作れているなら立てない。その辺は許容量に関わらず通れるので、
            // 緩めても増える移動が無い——立てると、緩和の梯子が何も変えずに探索を繰り返すだけになる
            owner.fallDamageCapBlocked |= !mlg;
            return;
        }
        owner.relax(from, x, obstacleY + 1, z,
                ActionCosts.fallCost(drop, takeoff) + damage * ActionCosts.FALL_DAMAGE_PENALTY_PER_POINT,
                MoveKind.FALL_DAMAGE);
    }
}
