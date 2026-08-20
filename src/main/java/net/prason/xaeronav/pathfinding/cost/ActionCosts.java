package net.prason.xaeronav.pathfinding.cost;

/**
 * 移動コストの基準値（単位: tick）。design doc §3-1/§4-1参照。
 * 徒歩・スプリント・ジャンプ・落下の数値はBaritone(ActionCosts.java, LGPL)で使われている実測値と同一だが、
 * ここではアイデア・数値のみを参考にし、コード自体は独自実装している。
 * 昇降・水中採掘はMinecraft本体の実装から導出し、設置・ドア開閉のオーバーヘッドは本MOD独自の見積もり。
 */
public final class ActionCosts {

    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;
    public static final double WALK_ONE_IN_WATER = 20.0 / 2.2;

    /**
     * 水中を泳いで1マス進む。{@code LivingEntity#travel}の水中分岐は
     * {@code moveRelative(f5)}で速度に{@code f5}を加え、そのあと{@code f4}を掛けるので
     * 漸化式は v_(n+1) = (v_n + f5)·f4、収束先は v* = f4·f5/(1−f4)。
     *
     * <p>{@code f4}は{@code isSprinting()}なら0.9、そうでなければ{@code getWaterSlowDown()}=0.8。
     * {@code f5}は0.02。<b>海を渡るプレイヤーは必ずうつ伏せ泳ぎ</b>（{@code Entity#updateSwimming}の
     * 継続条件が「疾走中かつ体が水中」）なので0.9側を採る:
     * v* = 0.9·0.02/0.1 = 0.18 blocks/tick = 3.6 blocks/秒。
     *
     * <p>{@link #WALK_ONE_IN_WATER}(2.2 blocks/秒)と分けるのは、あちらが<b>水底を歩く</b>速度だから。
     * 泳ぎに流用すると1.6倍の過大評価になり、水面を泳ぐより陸を大きく迂回する方が安く見える。
     */
    public static final double SWIM_ONE_BLOCK = 20.0 / 3.6;

    /**
     * 水中を1マス浮上する（ジャンプキー長押し）。{@code LivingEntity#jumpInLiquid}が毎tick
     * y速度に+0.04を足し、{@code travel}がそれに0.8を掛けてから重力
     * （{@code Attributes.GRAVITY}=0.08の1/16＝0.005）を引く。
     * v* = 0.8·(v* + 0.04) − 0.005 → v* = 0.135 blocks/tick = 2.7 blocks/秒。
     */
    public static final double SWIM_UP_ONE_BLOCK = 20.0 / 2.7;

    /**
     * 水中を1マス潜降する（スニーク長押し）。浮上と同じ漸化式で加算が−0.04になる。
     * v* = 0.8·(v* − 0.04) − 0.005 → v* = −0.185 blocks/tick = 3.7 blocks/秒。
     *
     * <p><b>浮上より潜降の方が速い</b>のは、重力が浮上では減速側・潜降では加速側に効くため。
     * 上下を同じ値にすると、A*が「潜ってから浮上する」経路を実際より安く見積もる。
     */
    public static final double SWIM_DOWN_ONE_BLOCK = 20.0 / 3.7;

    /**
     * 空気が尽きるまでのtick数（{@code Entity#getMaxAirSupply}）。目が水に浸かっている間
     * {@code decreaseAirSupply}が毎tick1ずつ減らし、−20に達した時点で溺れダメージが入る。
     * 水面に出れば{@code increaseAirSupply}が毎tick4ずつ戻す。
     */
    public static final int AIR_SUPPLY_TICKS = 300;

    /**
     * ボートで直進し続けたときの定常速度（1ブロックあたりのtick数）。
     * {@code Boat#floatBoat()}（水上時 invFriction=0.9F）と{@code Boat#controlBoat()}
     * （前進キー押下時、friction適用後に f=0.04F を加算）から、速度の漸化式
     * v_(n+1) = 0.9 * v_n + 0.04 の収束先 v* = 0.04/(1-0.9) = 0.4 blocks/tick = 8.0 blocks/秒を
     * 導出。時定数 1/(1-0.9)=10 tick(0.5秒) なので静水上ではほぼ瞬時に収束する。
     */
    public static final double PADDLE_ONE_BLOCK = 20.0 / 8.0;

    /**
     * 蜘蛛の巣の中を1マス進む。{@code WebBlock#entityInside}が移動量そのものに0.25を掛けるため
     * （{@code Entity#move}）、走っていても素の1/4しか進まない。
     */
    public static final double SPRINT_ONE_IN_COBWEB = SPRINT_ONE_BLOCK / 0.25;

    private static final double WALK_OFF_BLOCK = WALK_ONE_BLOCK * 0.8;
    private static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_BLOCK;

    /**
     * ジャンプで1マス登るのに要するtick数。放物線の対称性から
     * 「1.25マス分落下する時間」と「最後の0.25マス分落下する時間」の差として導出する
     * （踏み切ってから頂点=1.25マスに達するまでの上昇時間 = 対称な下降時間という関係を使う）。
     */
    public static final double JUMP_ONE_BLOCK = FallPhysics.ticksToFall(1.25) - FallPhysics.ticksToFall(0.25);

    public static final double ASCEND_ONE_BLOCK = Math.max(JUMP_ONE_BLOCK, WALK_ONE_BLOCK);

    /**
     * 隙間を飛び越えるときの滞空時間（約12.5tick）。踏み切ってから着地するまでは滞空時間そのもので、
     * 頂点1.25マスの上昇と下降が対称なので、その往復として求める。
     *
     * <p>同じ高さへ着地する跳躍は、隙間が1マスでも3マスでも<b>まったく同じ放物線</b>を描く。
     * 変わるのは踏み切り時の水平速度だけなので、滞空時間は距離に依らずこの値になる。
     * 距離ごとの差は{@link #JUMP_REACH_PENALTY}で表す。
     *
     * <p>走って2マス進む（約7tick）より高くつくため、平地では選ばれない。迂回が4マス以上に
     * なるときだけ跳ぶ、という現実に近い判断になる。
     */
    public static final double JUMP_ACROSS_GAP = 2.0 * FallPhysics.ticksToFall(1.25);

    /**
     * 隙間が1マス広がるごとの追加コスト。滞空時間が同じでも、遠くへ跳ぶには踏み切りまでに
     * 疾走の最高速度が乗っている必要があり、助走の取り直しや踏み切り位置の調整が要る。
     * 3マスの隙間（着地は4マス先）は疾走ジャンプの到達限界そのもので、外せば落ちる。
     *
     * <p>Baritoneの{@code jumpPenalty}（既定2.0）より重くしてある。Baritoneは自分で操作するので
     * 踏み切り位置を1ブロック単位で合わせられるが、こちらは人間に「跳べ」と指示するだけで、
     * 外したときに落ちるのは人間の方。同じ距離なら回り込む案内の方が親切なので、迂回を選ぶ範囲を
     * 広げている（隙間3マス: 跳躍20.5 ≒ 徒歩5.7マス。以前は4.6マスで釣り合っていた）。
     */
    public static final double JUMP_REACH_PENALTY = 4.0;

    public static final double DESCEND_ONE_BLOCK = fallCost(1);

    /**
     * 落下ダメージを受けずに降りられる高さ。バニラは{@code ceil((落下距離 - SAFE_FALL_DISTANCE) × 倍率)}を
     * ダメージとし、{@code SAFE_FALL_DISTANCE}属性の既定値は3。
     */
    public static final int SAFE_FALL_BLOCKS = 3;

    /**
     * 梯子・ツタを1マス登る。バニラの登坂速度2.35ブロック/秒より。
     * 降りる方は{@code LivingEntity#handleOnClimbable}が下向き速度を0.15ブロック/tickで頭打ちにするため、
     * ちょうど3ブロック/秒になる。
     */
    public static final double LADDER_UP_ONE_BLOCK = 20.0 / 2.35;

    public static final double LADDER_DOWN_ONE_BLOCK = 20.0 / 3.0;

    /** ドア・フェンスゲートを開けて通る追加コスト。立ち止まって向き直り、開けるまで。ドアは上下2マスぶん掛かる。 */
    public static final double OPEN_DOOR_OVERHEAD_TICKS = 5.0;

    /** 斜め1マス（水平√2マス分）の移動距離倍率。Diagonal移動のコスト・ヒューリスティック双方で使う。 */
    public static final double DIAGONAL_DISTANCE = Math.sqrt(2.0);

    /**
     * 斜め1マスで1段登るコスト（tick）。{@link #ASCEND_ONE_BLOCK}と同じ「跳ぶ時間と水平移動時間の
     * 大きい方」というmaxモデルを踏襲する（跳んでいる間も水平には進んでいるので加算ではない）。
     *
     * <p>水平側に{@link #SPRINT_ONE_BLOCK}ではなく{@link #WALK_ONE_BLOCK}を使うのは、
     * {@link #ASCEND_ONE_BLOCK}に合わせるため。段差を登るジャンプの繰り返しでは疾走を維持できないので
     * カーディナルの昇りは徒歩速度で見積もっており、斜めだけ疾走を前提にすると登坂ペナルティが
     * 消える（斜め移動と同コスト＝ただで高さが稼げる）。すると
     * {@link net.prason.xaeronav.pathfinding.astar.Heuristic}の上昇成分まで丸ごと0になり、
     * 山の上を目指す経路で探索が不必要に広がる。
     */
    public static final double DIAGONAL_ASCEND_ONE_BLOCK =
            Math.max(ASCEND_ONE_BLOCK, WALK_ONE_BLOCK * DIAGONAL_DISTANCE);

    /** 斜め1マスで1段降りるコスト（tick）。{@link #DIAGONAL_ASCEND_ONE_BLOCK}と同じ考え方。 */
    public static final double DIAGONAL_DESCEND_ONE_BLOCK =
            Math.max(DESCEND_ONE_BLOCK, WALK_ONE_BLOCK * DIAGONAL_DISTANCE);

    /**
     * 大きく落下する場合、tick/マスはterminal velocity(3.92 blocks/tick)に漸近しこれを下回らない。
     * A*ヒューリスティックの下降成分に使う安全な下限値（design doc §4-2参照）。
     */
    public static final double FALL_ASYMPTOTIC_MIN_PER_BLOCK = 1.0 / 3.92;

    public static final double DIG_OVERHEAD_TICKS = 2.0;

    /**
     * 水中での採掘の遅さ。バニラは頭が水に浸かっている間、採掘速度に{@code SUBMERGED_MINING_SPEED}
     * （水中採掘のエンチャントが無ければ0.2）を掛ける。
     */
    public static final double SUBMERGED_DIG_PENALTY = 5.0;

    /**
     * ブロックを設置して空洞を渡る際の照準・設置オーバーヘッド（design doc §4-1 Pillar水平版）。
     *
     * <p>橋を架けながらの前進は「一度止まって足元の縁へ向き直り、狙って置く」の繰り返しなので、
     * 走るのに比べて1/3程度の速さしか出ない。ここを数tickに見積もると1マスあたり徒歩(4.63)より
     * 安くなり、数マス迂回すれば済む場所でも常に設置が選ばれてしまう。
     *
     * <p>{@link #DIG_OVERHEAD_TICKS}より十分重くしてあるのは、掘るのと積むのが同じくらいの手数に
     * 見える場面では掘る方を選ばせたいから。設置は手持ちのブロックを消費するうえ、置いた足場が
     * そのまま地形として残る（次に通ったときの地形が変わる）。掘る方は素材が増える側に働く。
     */
    public static final double PLACE_BLOCK_OVERHEAD_TICKS = 16.0;

    /**
     * 落下ダメージ許容時、ダメージ1点(0.5ハート)あたりの追加ペナルティ。{@link #JUMP_REACH_PENALTY}と
     * 同じ「安い迂回があるならそちらを選ばせる」ための重み。本MOD独自の見積もり。
     */
    public static final double FALL_DAMAGE_PENALTY_PER_POINT = 2.0;

    /**
     * 水バケツMLG（着地寸前に水を設置し直後に回収して落下ダメージを無効化する）の照準・設置・回収
     * オーバーヘッド。{@link #PLACE_BLOCK_OVERHEAD_TICKS}と同じ「照準して設置」動作なので同値を採用。
     */
    public static final double MLG_WATER_OVERHEAD_TICKS = PLACE_BLOCK_OVERHEAD_TICKS;

    /**
     * ボートを出して乗り、渡り終えて降りて回収するまでの手間。区間の入口で1度だけ払う。
     *
     * <p>{@link #PLACE_BLOCK_OVERHEAD_TICKS}（狙って置く1動作）の2回分にしてある——
     * 出す・乗るで1往復、降りる・回収するで1往復。降りる側を別の移動として作らず入口にまとめるのは、
     * A*のノードが座標だけをキーにしていて「いま乗っているか」を状態として持てないため。
     *
     * <p>この値が損益分岐を決める: 泳ぎ({@link #SWIM_ONE_BLOCK})とボート({@link #PADDLE_ONE_BLOCK})の
     * 差は1マスあたり約3tickなので、10マスちょっと以上の水面を渡るときだけボートが選ばれる。
     * 小川を渡るのにいちいちボートを出せとは言わない、という線引きになる。
     */
    public static final double BOAT_OVERHEAD_TICKS = 2.0 * PLACE_BLOCK_OVERHEAD_TICKS;

    /**
     * 溶岩の上に足場を置いて渡る1ブロックあたりの追加ペナルティ。設置を1回でも外せば死ぬので
     * 通常の設置より重くするが、<b>詳細探索が現実的な予算で橋を見つけられる範囲に収める</b>。
     *
     * <p>この上限は探索アルゴリズムの側から決まる。A*は安い辺から順に展開するので、橋1本が徒歩
     * {@code n}ブロック相当なら、{@code m}ブロックの溶岩を渡る経路に手が届く前に「徒歩{@code n×m}
     * ブロック分の陸地」を展開し尽くす。ここを徒歩28ブロック相当（ペナルティ80）にしたところ、
     * 20ブロックの溶岩を渡るのに半径559ブロック相当の展開が先に必要になり、ネザーの3D迷路では
     * 20万ノードを焼いても橋に一度も到達しなかった（実機で確認）。
     *
     * <p>{@link #PLACE_BLOCK_OVERHEAD_TICKS}と同値にすると1ブロック約35.6tick＝徒歩10ブロック相当。
     * 20ブロックの溶岩横断が徒歩200ブロックの迂回と釣り合い、詳細探索の箱（描画距離）に収まる
     * 迂回路を一通り試してから橋を選ぶ、というちょうどの重みになる。
     *
     * <p>これより大きく迂回すべきかどうかは層1（{@code CoarseRouter.LavaPolicy}）が決める。
     * 描画距離の外の迂回路は詳細探索にはそもそも見えないので、ここで表現しようとしてはいけない。
     *
     * <p>「橋が長くなりすぎたら諦めて迂回する」はコストではなく
     * {@code CellSource#maxBridgeRunBlocks()}（移動を生成しない上限）で表す。ここを重くして
     * 表そうとすると、上の理由でそのまま展開ノード数の浪費になる。
     */
    public static final double LAVA_BRIDGE_PENALTY_TICKS = PLACE_BLOCK_OVERHEAD_TICKS;

    public static final double INFEASIBLE = Double.POSITIVE_INFINITY;

    private ActionCosts() {
    }

    /** 縁から踏み出して{@code blocks}マス落ち、着地してマスの中心に戻るまで。 */
    public static double fallCost(int blocks) {
        return fallCost(blocks, 1.0);
    }

    /**
     * 踏み切り地点の水平速度倍率を織り込んだ版。
     *
     * <p>減速するのは<b>縁を踏み出す0.8マスだけ</b>。落下している間と着地後に中心へ戻る時間は
     * 足元のブロックとは無関係なので倍率を掛けない。
     */
    public static double fallCost(int blocks, double speedFactor) {
        return WALK_OFF_BLOCK / speedFactor + Math.max(FallPhysics.ticksToFall(blocks), CENTER_AFTER_FALL);
    }

    /**
     * 踏み切り地点の水平速度倍率（{@code speedFactor}、1.0以下）を織り込んだ1段昇り。
     *
     * <p>跳んで上がる時間そのものは倍率の影響を受けない（バニラの{@code jumpFactor}は別の値で、
     * ソウルサンドには設定されていない）。遅くなるのは水平成分だけなので、maxの中の水平側だけを割る。
     *
     * <p><b>1.0を超える倍率（氷）を渡してはいけない</b>。{@link net.prason.xaeronav.pathfinding.astar.Heuristic}
     * は昇りの下限に{@link #ASCEND_ONE_BLOCK}を置いているので、そこを割ると非許容になる。
     */
    public static double ascendOneBlock(double speedFactor) {
        return Math.max(JUMP_ONE_BLOCK, WALK_ONE_BLOCK / speedFactor);
    }

    /** {@link #ascendOneBlock}の1段降り版。 */
    public static double descendOneBlock(double speedFactor) {
        return fallCost(1, speedFactor);
    }

    /** {@link #ascendOneBlock}の斜め版。 */
    public static double diagonalAscendOneBlock(double speedFactor) {
        return Math.max(ascendOneBlock(speedFactor), WALK_ONE_BLOCK * DIAGONAL_DISTANCE / speedFactor);
    }

    /** {@link #descendOneBlock}の斜め版。 */
    public static double diagonalDescendOneBlock(double speedFactor) {
        return Math.max(descendOneBlock(speedFactor), WALK_ONE_BLOCK * DIAGONAL_DISTANCE / speedFactor);
    }

    /** {@code gapBlocks}マスの隙間を飛び越えるコスト。着地点は{@code gapBlocks + 1}マス先になる。 */
    public static double jumpAcrossGap(int gapBlocks) {
        return JUMP_ACROSS_GAP + (gapBlocks - 1) * JUMP_REACH_PENALTY;
    }
}
