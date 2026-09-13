package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * 足場を置く移動候補生成（橋・柱）。{@link AStarPathfinder}の分割の一部（ARCH-02）——
 * {@link GroundMoves}のクラスJavadoc参照。
 */
final class BuildMoves {

    private final AStarPathfinder owner;

    BuildMoves(AStarPathfinder owner) {
        this.owner = owner;
    }

    /**
     * 床が存在しない空洞（ジ・エンドの島間など）をブロックを置いて渡る移動。Pillarの水平版。
     * 掘削とは逆に、床セルが完全な空虚（{@code passableEmpty}）である場合のみ許可する — 水面の
     * 上には置かない（{@code PathSafetyChecker}の事後チェックとは別に、そもそも設置対象として扱わない）。
     *
     * <p>水面のすぐ上も空気なので、床セルだけを見ても空虚と区別がつかない。海の上にブロックを敷いて
     * 渡るのは泳いで渡れる場所にわざわざ足場を作ることになるので、下に水が見えたらこの移動を作らない。
     *
     * <p>溶岩は{@code CellSource#lavaBridgingEnabled()}のときだけ、
     * {@link ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}を上乗せして許可する。床セルが溶岩そのものでも
     * よい——置いたブロックが溶岩を置き換えるバニラの橋架けなので、身体が溶岩に入るわけではない
     * （入る経路は{@code AStarPathfinder#standingBodyCost}が既にINFEASIBLEで弾く）。
     */
    void addBridge(PathNode from, int dx, int dz, int obstacleY) {
        if (!canPlace()) {
            return;
        }
        int x = from.x + dx;
        int y = from.y;
        int z = from.z + dz;

        long floorCell = owner.view.cell(x, y - 1, z);
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
            if (obstacleY == ColumnScans.UNREADABLE_BELOW) {
                // 未ロードチャンクで走査が止まった。下に何があるか本当に分からないので置かない
                // ——水面の上に足場を敷けと言い出すのはこの取り違えから起きる
                return;
            }
            if (obstacleY == ColumnScans.NOTHING_BELOW) {
                // 読めるセルだけを辿って何にも当たらなかった＝底が無い。外せば助からないので、
                // 溶岩と同じ扱いにする
                voidBelow = true;
                dropBelow = owner.view.fatalFallBlocks();
            } else {
                long obstacle = owner.view.cell(x, obstacleY, z);
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
                // 落差の測り方はGroundMoves#missDropと同じ（水は上で弾いてある）
                dropBelow = y - obstacleY - 1;
                fatalDropBelow = !lavaFarBelow && dropBelow >= owner.view.fatalFallBlocks();
            }
        }
        // 水に接する場所へは置かない。流れ込んで足場ごと押し流される
        if (owner.hasAdjacentWater(x, y - 1, z)) {
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
        if (voidBelow && Math.abs(x - owner.goalX) + Math.abs(z - owner.goalZ)
                >= Math.abs(from.x - owner.goalX) + Math.abs(from.z - owner.goalZ)) {
            return;
        }
        boolean lavaNearby = overLava || lavaFarBelow || hasAdjacentLava(x, y - 1, z);
        if (lavaNearby && !owner.view.lavaBridgingEnabled()) {
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
        // 空中では掘れないので{@link GroundMoves#addJumpGap}や斜め移動が{@code clearWithoutDigging}を
        // 要求しているのと同じ規律。底のある空洞には掛けない——掘って落ちても1マス下の床に
        // 着くだけで、結末がまるで違う
        // 致死落差もここに含める。「底のある空洞には掛けない——掘って落ちても1マス下の床に
        // 着くだけ」という上の理由づけは<b>浅い底にしか成り立たない</b>。43マス下の床は
        // 底があるうちに入らない。ただし詰みを増やさないよう、跳躍と同じ緩和の梯子
        // （{@code avoidRiskyJumps}）に載せる——奈落・溶岩は従来どおり無条件
        if ((voidBelow || lavaNearby || (fatalDropBelow && owner.avoidRiskyJumps))
                && !owner.clearWithoutDigging(x, y, z)) {
            return;
        }
        // 連続した橋の長さで打ち切る。ここで「重いコスト」ではなく「移動を作らない」を選ぶのが要点——
        // 重みで抑えると、A*は安い辺から展開するので橋に手を伸ばす前に周囲を展開し尽くし、
        // 展開ノード数を焼き切ったうえで結局その先に進めない（ActionCosts#LAVA_BRIDGE_PENALTY_TICKS
        // に記録された実測そのもの）。辺を作らなければ、探索は最初から迂回路だけを見る。
        //
        // 溶岩と奈落の上だけは別（より短い）上限で切る。底のある空洞なら足場を外しても落ちるだけだが、
        // この2つでは死ぬので、同じ長さの橋でも許してよい範囲が違う。両方に当たる橋は厳しい方で切る
        int cap = owner.maxBridgeRun;
        if (lavaNearby) {
            cap = RunCaps.stricter(cap, owner.maxLavaBridgeRun);
        }
        if (voidBelow || fatalDropBelow) {
            cap = RunCaps.stricter(cap, owner.maxVoidBridgeRun);
        }
        int bridgeRun = from.bridgeRun + 1;
        if (cap > 0 && bridgeRun > cap) {
            owner.bridgeRunCapBlocked = true;
            return;
        }
        // 持ち物の枚数で経路全体の設置数を切る。連続長（上の cap）は「1本の橋が何マス続いてよいか」
        // なので、短い橋を何度も架ける経路は素通りする——途中で尽きると、そこから先の案内は
        // 実行できない
        if (placedBudgetExceeded(from)) {
            return;
        }
        double bodyCost = owner.standingBodyCost(x, y, z, null);
        if (Double.isInfinite(bodyCost)) {
            return;
        }
        // 進む1マスぶんだけ踏み切り地点の倍率で割る。置いたブロックの上は等速なので、遅いのは
        // ソウルサンド等の上から踏み出す分だけ。設置の手間（placementCostTicks）は
        // 立っているブロックと無関係なので割らない。
        //
        // 走行を中断するぶんの割増は、奈落・溶岩の上では乗せない。あちらの値段の上限を握っているのは
        // 人間の好みではなく「探索が橋に手を届かせられるか」で、実測済みの窓
        // （ActionCosts#LAVA_BRIDGE_PENALTY_TICKS）から外れると経路そのものが出なくなる
        // ——ジ・エンドの島渡りが60万ノードでも解けなくなることをRealEndTerrainTestで確認した。
        // 迂回させたいという意図も、そこでは迂回路が探索の箱の中に無いので買えるものが無い
        double interruption = voidBelow || lavaNearby
                ? 0.0
                : ActionCosts.TERRAIN_EDIT_INTERRUPTION_TICKS;
        double cost = ActionCosts.SPRINT_ONE_BLOCK / owner.takeoffSpeedFactor(from.x, from.y, from.z)
                + owner.placementCostTicks + interruption
                + (lavaNearby ? ActionCosts.LAVA_BRIDGE_PENALTY_TICKS : 0.0)
                // 遥か下が溶岩なら落差は測らない。外したときの結末は既に溶岩の割増が表しているので、
                // 深さで二重に取ると測っていないネザーの橋の値段まで動く
                + (lavaFarBelow ? 0.0 : ActionCosts.dropRiskPenalty(dropBelow, owner.view.fatalFallBlocks()))
                + owner.submerged(from, bodyCost, x, y + 1, z);
        owner.relax(from, x, y, z, cost, MoveKind.BRIDGE, bridgeRun);
    }

    /**
     * 跳びながら足元にブロックを置いて真上へ1マス登る（Pillar）。{@link #addBridge}の垂直版で、
     * これが無いと断崖はどれだけ低くても迂回するしかない。
     *
     * <p>置く先は自分がいま立っているセルそのものなので、そこが本当の空気であることを求める。
     * 水に浮いた状態では踏み切れず、梯子に掴まっている場所には置けない。
     *
     * <p>足場は要求しない。連続して積み上げる2手目以降は、自分が直前に置いたブロックの上に
     * 立っている——地形データにはまだ存在しないセルなので、足場を求めると1マスしか登れなくなる。
     *
     * <p>新しい頭になるセル（2つ上）だけが未検証。新しい足元は元の頭で、そこに立っている時点で
     * 通行可能性は確認済み。{@link GroundMoves#addAscend}と同じく、天井が塞がっていても掘れるなら
     * 掘って上がる。
     */
    void addPillar(PathNode from) {
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
        if (owner.maxBridgeRun > 0 && pillarRun > owner.maxBridgeRun) {
            owner.bridgeRunCapBlocked = true;
            return;
        }
        if (placedBudgetExceeded(from)) {
            return;
        }
        long standing = owner.view.cell(from.x, from.y, from.z);
        // 置く先は自分がいるセルそのもの。梯子・ツタに掴まっている間は onGround() が false で
        // jumpFromGround() が呼ばれず（LivingEntity#aiStep）、掴まったまま接地していても
        // handleOnClimbable が水平・下向きの速度を±0.15に固定するので、跳んで積む動作が成立しない
        // 水はreplaceableなので、水中も「置ける場所」として素通りしていた。実際には浮いたまま
        // 踏み切れないので、案内した通りに積み上げることはできない
        if (!CellData.replaceable(standing) || CellData.climbable(standing)
                || CellData.water(standing)) {
            return;
        }
        double clearanceCost = owner.columnCost(from.x, from.y + 2, from.y + 2, from.z, null);
        if (Double.isInfinite(clearanceCost)) {
            return;
        }
        // 柱は必ず実在の足場の上から始まる（上のreplaceable判定）ので、走行を中断するぶんの割増は
        // 常に乗る。橋の側にある免除は「奈落・溶岩の上に迂回路が無い」ことを根拠にしたもので、
        // 地面の上から1マス上がる話には当てはまらない
        double cost = ActionCosts.ascendOneBlock(owner.takeoffSpeedFactor(from.x, from.y, from.z))
                + owner.placementCostTicks + ActionCosts.TERRAIN_EDIT_INTERRUPTION_TICKS
                + owner.submerged(from, clearanceCost, from.x, from.y + 2, from.z);
        // 積んだブロックの上は自分が置いた足場であって地形ではないので、橋の連続を断たない。
        // 0に戻していた頃は「橋を上限まで架ける→1マス積む→また上限まで架ける」が合法だった。
        // 実際に発動するかは展開順しだいで（bridgeRunはノードの同一性に入らないので、柱の上の
        // ノードへ別経路が同コストで届けばそちらの連続長が残る）、そのぶん質が悪い——同じ地形でも
        // 上限が効いたり効かなかったりし、効かなかった回は bridgeRunCapBlocked が立たないので
        // PathfindingExecutorの上限緩和も走らないまま階段状の経路が確定する
        owner.relax(from, from.x, from.y + 1, from.z, cost, MoveKind.PILLAR, pillarRun);
    }

    /**
     * 足場を置く移動を作ってよいか。持ち物にブロックが無くても、詰み回避で開けられていれば作る
     * （{@code Tolerances#placeWithoutBlocks()}）——出さないとジ・エンドの島渡りのように
     * 橋以外に道が無い地形で経路が原理的に出ず、しかも案内には何も現れない。
     */
    private boolean canPlace() {
        if (owner.view.canPlaceBlocks()) {
            return true;
        }
        // 設定で断られている場合は開けない。開けてよいのは「持っていないだけ」のときだけ
        if (!owner.view.bridgingAllowedBySettings()) {
            return false;
        }
        if (!owner.placeWithoutBlocks) {
            owner.placementBlockedByEmptyInventory = true;
            return false;
        }
        return true;
    }

    /**
     * {@code from}からもう1つ足場を置くと持ち物の予算を超えるか。超えるなら
     * {@code placedBudgetBlocked}を立てて、予算を外した探し直しが要ることを呼び出し側へ伝える。
     */
    private boolean placedBudgetExceeded(PathNode from) {
        if (owner.placedBudget <= 0 || from.placedTotal + 1 <= owner.placedBudget) {
            return false;
        }
        owner.placedBudgetBlocked = true;
        return true;
    }

    /**
     * ブロックを置くセルの中か周りに、掴まれるもの（ツタ・しだれツタ・梯子）があるか。
     *
     * <p>水・溶岩の隣接判定と違って<b>真上も見る</b>。真上は足場を置いた後に自分が立つセルで、
     * そこにツタが垂れていれば、置く先を狙う視線はまずそれに当たる。
     */
    private boolean climbableNear(int x, int y, int z) {
        return CellData.climbable(owner.view.cell(x, y, z))
                || CellData.climbable(owner.view.cell(x, y + 1, z))
                || adjacentClimbable(x, y, z);
    }

    /** ブロックを置くセルの周り（真上を除く5面）に溶岩があるか。 */
    private boolean hasAdjacentLava(int x, int y, int z) {
        return adjacentLava(x, y, z);
    }

    private boolean adjacentLava(int x, int y, int z) {
        return CellData.lava(owner.view.cell(x, y - 1, z))
                || CellData.lava(owner.view.cell(x + 1, y, z)) || CellData.lava(owner.view.cell(x - 1, y, z))
                || CellData.lava(owner.view.cell(x, y, z + 1)) || CellData.lava(owner.view.cell(x, y, z - 1));
    }

    private boolean adjacentClimbable(int x, int y, int z) {
        return CellData.climbable(owner.view.cell(x, y - 1, z))
                || CellData.climbable(owner.view.cell(x + 1, y, z))
                || CellData.climbable(owner.view.cell(x - 1, y, z))
                || CellData.climbable(owner.view.cell(x, y, z + 1))
                || CellData.climbable(owner.view.cell(x, y, z - 1));
    }
}
