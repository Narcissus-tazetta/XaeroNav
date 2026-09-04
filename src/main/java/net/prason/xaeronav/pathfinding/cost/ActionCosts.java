package net.prason.xaeronav.pathfinding.cost;

/**
 * 移動コストの基準値（単位: tick）。
 * 徒歩・スプリント・ジャンプ・落下の数値はBaritone(ActionCosts.java, LGPL)で使われている実測値と同一だが、
 * ここではアイデア・数値のみを参考にし、コード自体は独自実装している。
 * 昇降・水中採掘はMinecraft本体の実装から導出し、設置・ドア開閉のオーバーヘッドは本MOD独自の見積もり。
 */
public final class ActionCosts {

    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;

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
     * <p><b>水底に足が着いていても同じ値段。</b>{@code travel}の水中分岐は{@code isInWater()}だけで
     * 入り、{@code onGround()}は装備（水中歩行）の係数にしか使われない——立っていようが泳いでいようが
     * v*は{@code isSprinting()}だけで決まる。かつて「水底を歩く」用に別の定数(2.2 blocks/秒)を
     * 持っていたが、この式からはどちらの姿勢でも出てこない値で、浅瀬を1.64倍に過大評価していた。
     * その結果、浅瀬を避けて深い方へ潜る／沼地を大きく迂回する経路が出ていた。
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

    /**
     * 隣のマスへ1マス降りる。<b>落下の時間は計上しない。</b>縁を踏み出した後も水平速度は空中の
     * 疾走の定常値(0.2889 blocks/tick)で保たれ、地上の疾走(0.2863)を<b>上回る</b>ので、走り続ける限り
     * 平地と同じ速さで進む——{@code LivingEntity#travel}の非流体分岐をそのまま回した実測で、
     * 1マスずつ64マス下って3.45 tick/マス（平地は3.48）、着地までの落下距離も最大2.69マスで無傷。
     *
     * <p>以前は{@link #fallCost(int)}(1)＝9.321で、<b>疾走2.6マス相当</b>だった。深さ12の谷を
     * 越えるのに平坦な迂回20マス分の値段が付いていた計算になる。式はBaritoneの{@code MovementDescend}
     * と同じだが、<b>あちらは1手ずつ実行するボットで実際に減速する</b>。こちらは人間に
     * 「そのまま走って降りろ」と指示するだけなので、減速しない側が正しい。
     *
     * <p>{@link #SPRINT_ONE_BLOCK}を<b>下回らせてはいけない</b>——
     * {@link net.prason.xaeronav.pathfinding.astar.Heuristic}が水平の下限に疾走を置いているので、
     * 割ると非許容になる。実測がわずかに速いぶんは切り捨てて疾走ちょうどに置く。
     *
     * <p>意図して大きく落ちる{@code Fall}は従来どおり{@link #fallCost(int)}で、あちらは
     * 落下時間そのものが律速なので変わらない。
     */
    public static final double DESCEND_ONE_BLOCK = SPRINT_ONE_BLOCK;

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
     * 水中を進みながら1マス浮上する。{@link #ASCEND_ONE_BLOCK}と同じ「同時にこなす2つの成分のうち
     * 遅い方」というmaxモデルを踏襲する（浮きながら前へ進んでいるので加算ではない）。
     *
     * <p>結果として真上への浮上（{@link #SWIM_UP_ONE_BLOCK}）と同値になり、<b>水平の進みが
     * ただで付いてくる</b>。陸の{@code Ascend}が水平1歩をジャンプ時間に相乗りさせているのと同じ形で、
     * これが無いと「真上へ上がってから横へ」というL字の経路しか作れない。
     */
    public static final double SWIM_ASCEND_ONE_BLOCK =
            Math.max(SWIM_UP_ONE_BLOCK, SWIM_ONE_BLOCK * DIAGONAL_DISTANCE);

    /**
     * 斜め1マスで1段浮上するコスト（tick）。{@link #SWIM_ASCEND_ONE_BLOCK}の斜め版で、水平の変位が
     * 1ではなく√2になるぶん3次元の変位は√3になる。
     *
     * <p>これが無いと浮上はカーディナル4方向に縛られ、「斜めに進む」と「上がる」を別々の手で
     * 払うことになる——水面へ向かう区間だけ経路が直角に折れる。
     */
    public static final double DIAGONAL_SWIM_ASCEND_ONE_BLOCK =
            Math.max(SWIM_UP_ONE_BLOCK, SWIM_ONE_BLOCK * Math.sqrt(3.0));

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

    /**
     * 斜め1マスで1段降りるコスト（tick）。
     *
     * <p>水平側に{@link #SPRINT_ONE_BLOCK}を使うのが{@link #DIAGONAL_ASCEND_ONE_BLOCK}との違いで、
     * これは非対称で正しい。<b>昇りは段差ごとに跳ぶので疾走を維持できない</b>のに対し、
     * <b>降りは走り抜けられる</b>（{@link #DESCEND_ONE_BLOCK}の実測）。
     */
    public static final double DIAGONAL_DESCEND_ONE_BLOCK =
            Math.max(DESCEND_ONE_BLOCK, SPRINT_ONE_BLOCK * DIAGONAL_DISTANCE);

    /**
     * 大きく落下する場合、tick/マスはterminal velocity(3.92 blocks/tick)に漸近しこれを下回らない。
     * A*ヒューリスティックの下降成分に使う安全な下限値。
     */
    public static final double FALL_ASYMPTOTIC_MIN_PER_BLOCK = 1.0 / 3.92;

    /**
     * 横へ1ブロックずれて元の進行方向へ戻る迂回の値段（tick）。疾走の斜め2手が直進2手を
     * 置き換えるので{@code 2·(√2−1)·}{@link #SPRINT_ONE_BLOCK}。
     *
     * <p><b>地形を触る手間の単位</b>としてこれを使う——{@link #DIG_OVERHEAD_TICKS}や
     * {@link #PLACE_BLOCK_OVERHEAD_TICKS}を「迂回何ブロックと釣り合うか」で置けるようになり、
     * 値の妥当性を人間が判断できる形になる。時間そのものは同じ単位のtickなので換算は要らない。
     */
    public static final double SIDESTEP_ONE_BLOCK = 2.0 * (DIAGONAL_DISTANCE - 1.0) * SPRINT_ONE_BLOCK;

    /**
     * ブロックを1つ壊すときの、破壊時間そのもの以外の手間。立ち止まって向き直り、狙いを付け、
     * 壊し終えてから走り出し直すまで。
     *
     * <p><b>値は「1セル掘るか、横へ迂回するか」の釣り合いで置いてある。</b>単位は
     * {@link #SIDESTEP_ONE_BLOCK}(2.95)。2マスの壁（登れないので掘るか迂回するかしかない）を
     * 実測すると、22.0では<b>迂回7ブロック</b>で掘る側に倒れる。
     *
     * <p>算術上の比（22.0/2.95＝7.5）よりわずかに手前で倒れるのは、探索が
     * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder#DEFAULT_HEURISTIC_WEIGHT}で
     * 重み付けされていて、目的地から一度離れる手を系統的に嫌うため。<b>釣り合いを動かすときは
     * 比ではなく実測の倒れる位置を見ること</b>（{@code TerrainEditVersusDetourTest}）。
     *
     * <p><b>この釣り合いが低すぎると、自然地形では歩くたびに掘ることになる。</b>2マスの段差や
     * 幅1の壁は数ブロックおきにあるので、迂回3ブロックで掘る側に倒れる値（＝8.0）では
     * そのほとんどが掘削になる——ユーザー報告「地上を歩いてる時に無駄なブロックを掘る動作が多い」
     * がこれ。逆に上げすぎると、少し掘れば通れる洞窟や崖で大回りの案内が出る。
     *
     * <p>下限側は自動的に満たされる: 1マスの出っ張りを跨ぐのは
     * {@link #ASCEND_ONE_BLOCK}+{@link #DESCEND_ONE_BLOCK}＝8.20tickで、掘って通るのは
     * 破壊時間＋この値＋{@link #SPRINT_ONE_BLOCK}なので必ず跨ぐ方が安い。
     *
     * <p><b>これは1セルあたりの値段</b>なので、身体2セルを掘り抜く移動は2回払う。同じ停止で
     * 2つ壊す場合には過大だが、地上を歩く場面では1セルで済む（2マスの壁は上のセルだけ崩して
     * 跨げる）ので、効くのは天井のある坑道を掘り進む経路だけ。そこでは掘る以外の道が無いことが
     * 多く、経路の形は変わらない。
     *
     * <p>{@link #PLACE_BLOCK_OVERHEAD_TICKS}より軽いままにしてあるのは意図的で、
     * 「掘るのと積むのが同じ手数に見えるなら掘る方を選ばせたい」という順序を保つ。
     * 置く方が重いのは、狙う先が<b>特定のブロックの特定の面</b>で、しかも空洞へ後ろ向きに
     * 下がりながらやることになるため。
     */
    public static final double DIG_OVERHEAD_TICKS = 22.0;

    /**
     * 水底に足を着けたまま、頭が水に浸かった状態で掘る遅さ。{@code Player#getDigSpeed}は
     * {@code isEyeInFluid(WATER)}のとき採掘速度に{@code Attributes.SUBMERGED_MINING_SPEED}
     * （既定0.2＝5倍遅い。水中採掘のエンチャントが付くと1.0になり帳消し）を掛ける。
     */
    public static final double SUBMERGED_DIG_PENALTY = 5.0;

    /**
     * 泳ぎながら掘る遅さ。{@code Player#getDigSpeed}は上の水中判定に加えて
     * <b>{@code !onGround()}ならさらに{@code f /= 5.0F}</b>を掛けるので、足が着いていない水中では
     * 合わせて25倍遅くなる。
     *
     * <p>足場の有無で5倍違うのに一律5倍で見積もっていたため、開けた海の中を掘り進む経路が
     * 実際の1/5のコストに見えていた。掘って進む案内は「泳いで迂回する」より遥かに高くつく。
     */
    public static final double SWIMMING_DIG_PENALTY = 25.0;

    /**
     * 頭を水に浸けたまま<b>高さを稼がずに</b>進むときの割増。潜ったまま横断せず、先に水面へ
     * 出てから渡る経路を選ばせるための重み。浮上（{@link #SWIM_ASCEND_ONE_BLOCK}）だけが対象外で、
     * 水平移動にも潜降にも掛かる。
     *
     * <p>浮上を対象外にするのは、息を減らしながら進んでいるのが水平移動の方で、浮上はその
     * 解消手段だから——全部に掛けると「上がるのも高い」ことになり、潜ったまま進む経路と差が付かない。
     *
     * <p><b>値は上下2つの条件で挟まれる。</b>どちらもA*の展開のされ方から決まるもので、
     * 重さの好みではない。既定の1.3は窓の真ん中:
     *
     * <ul>
     * <li><b>下限は1.0（＝割増があること）</b>。1.0では潜ったまま横断し、目的地の真下に来てから
     *     ようやく浮上する（実測: 水底から58マス先の水面を目指して、57手ぶん潜ったまま進んでいた）。
     *     1.05まで上げれば斜めに上がり始める</li>
     * <li><b>上限は{@link #DIAGONAL_DISTANCE}（√2 ≒ 1.414）</b>。これを超えると<b>上下に跳ねて
     *     割増を回避できてしまう</b>——斜め浮上だけが対象外なので、「斜めに上がって斜めに降りる」を
     *     繰り返せば水平に進める。往復が水平2手より安くならない条件が
     *     {@code SWIM_ASCEND_ONE_BLOCK + SWIM_ONE_BLOCK·P ≧ 2·SWIM_ONE_BLOCK·P}、
     *     つまり {@code P ≦ SWIM_ASCEND_ONE_BLOCK / SWIM_ONE_BLOCK}。
     *     実測でも1.4は平坦、1.45から跳ね始める</li>
     * </ul>
     *
     * <p><b>免除は水平1マス以内の浮上に限っている</b>（{@code AStarPathfinder#relax}）。
     * {@link #DIAGONAL_SWIM_ASCEND_ONE_BLOCK}まで免除すると、斜めに進むべき区間で同じ跳ねが
     * 斜めの形（√3 + √2·P vs 2·√2·P）で戻ってきて、この上限では抑えられない。
     *
     * <p>水面へ出られない場所（水没した洞窟・天井のある水路）では、割増が一様に乗るだけで
     * 経路の形は変わらない。「水中洞窟は除く」がこの形で自然に満たされる。
     */
    public static final double SUBMERGED_TRAVEL_PENALTY = 1.3;

    /**
     * 狙って1つ置く動作そのものの値段（tick）。立ち止まって<b>特定のブロックの特定の面</b>へ
     * 向き直り、狙って置くまで。
     *
     * <p><b>「走行を中断すること」の割増（{@link #TERRAIN_EDIT_INTERRUPTION_TICKS}）を含まない</b>
     * のがここの要点。それを含まない値が要る場所が3つある——奈落・溶岩の上の橋
     * （{@code AStarPathfinder#addBridge}）、既に落下している最中に置く水バケツMLG
     * （{@link #MLG_WATER_OVERHEAD_TICKS}）、区間の入口で1度だけ払うボート
     * （{@link #BOAT_OVERHEAD_TICKS}）。
     */
    public static final double PLACE_BLOCK_AIM_TICKS = 16.0;

    /**
     * 走っている途中で地形を触ることの割増（tick）。疾走を切り、進行方向から目を離し、
     * 済んでから走り出し直すまで——<b>破壊時間や設置動作そのものとは別に必ず掛かる分</b>。
     *
     * <p>掛かるのは<b>設置だけ</b>（{@link #PLACE_BLOCK_OVERHEAD_TICKS}・{@code addPillar}）。
     * 掘削は同じ理由の割増を{@link #DIG_OVERHEAD_TICKS}に畳み込んである——あちらはセル単位で
     * 掛かるうえ釣り合わせる相手も違うので、共通化すると片方の実測がもう片方を動かしてしまう。
     * 値は{@link #PLACE_BLOCK_OVERHEAD_TICKS}の側で実測して置いてある。
     *
     * <p><b>奈落・溶岩の上の橋には掛けない。</b>あちらでは橋の値段の上限を握っているのが
     * 人間の好みではなく<b>探索が橋に手を届かせられるか</b>で、実測済みの窓
     * （{@link #LAVA_BRIDGE_PENALTY_TICKS}）から外れると経路そのものが出なくなる。
     * 迂回させたいという意図も、そこでは迂回路が探索の箱の中に無いので買えるものが無い。
     */
    public static final double TERRAIN_EDIT_INTERRUPTION_TICKS = 20.0;

    /**
     * ブロックを設置して空洞を渡る際の照準・設置オーバーヘッド（Pillarの水平版）。
     *
     * <p>橋を架けながらの前進は「一度止まって足元の縁へ向き直り、狙って置く」の繰り返しなので、
     * 走るのに比べて1/3程度の速さしか出ない。
     *
     * <p><b>値は{@link #DIG_OVERHEAD_TICKS}と同じ釣り合いで置いてある。</b>2マスの段差を
     * 柱1本で越える場合を実測すると、36.0では<b>迂回11ブロック</b>で積む側に倒れる
     * （幅4の溝を橋で渡る場合は設置2本＋跳躍1回ぶんなので迂回21ブロック）。
     * 掘削(7ブロック)より遠くまで迂回させるのは、設置が手持ちのブロックを消費するうえ、
     * 置いた足場がそのまま地形として残る（次に通ったときの地形が変わる）ため——掘る方は
     * 素材が増える側に働く。
     *
     * <p><b>2つの成分に分かれている。</b>{@link #PLACE_BLOCK_AIM_TICKS}が置く動作そのもので、
     * {@link #TERRAIN_EDIT_INTERRUPTION_TICKS}が走行を切ることの割増。奈落・溶岩の上の橋では
     * 後者を払わない（{@code AStarPathfinder#addBridge}）——理由はあちらの説明に書いてある。
     */
    public static final double PLACE_BLOCK_OVERHEAD_TICKS =
            PLACE_BLOCK_AIM_TICKS + TERRAIN_EDIT_INTERRUPTION_TICKS;

    /**
     * 落下ダメージ許容時、ダメージ1点(0.5ハート)あたりの追加ペナルティ。{@link #JUMP_REACH_PENALTY}と
     * 同じ「安い迂回があるならそちらを選ばせる」ための重み。本MOD独自の見積もり。
     */
    public static final double FALL_DAMAGE_PENALTY_PER_POINT = 2.0;

    /**
     * 水バケツMLG（着地寸前に水を設置し直後に回収して落下ダメージを無効化する）の照準・設置・回収
     * オーバーヘッド。
     *
     * <p>{@link #PLACE_BLOCK_AIM_TICKS}（狙って置く動作そのもの）と同値。
     * {@link #TERRAIN_EDIT_INTERRUPTION_TICKS}を含めないのは、MLGが<b>既に落下している最中</b>の
     * 動作で、走行を中断して始めるものではないため。
     */
    public static final double MLG_WATER_OVERHEAD_TICKS = PLACE_BLOCK_AIM_TICKS;

    /**
     * ボートを出して乗り、渡り終えて降りて回収するまでの手間。区間の入口で1度だけ払う。
     *
     * <p>{@link #PLACE_BLOCK_AIM_TICKS}（狙って置く1動作）の2回分——出す・乗るで1往復、
     * 降りる・回収するで1往復。降りる側を別の移動として作らず入口にまとめるのは、A*のノードが
     * 座標だけをキーにしていて「いま乗っているか」を状態として持てないため。
     *
     * <p><b>{@link #TERRAIN_EDIT_INTERRUPTION_TICKS}を含めてはいけない。</b>区間の入口で1度だけ
     * 払うここに「1マスごとの中断」を2回分掛けると中断を二重に数えることになり、下の損益分岐
     * （水面10マス強）が意図せず倍以上に動く。
     *
     * <p>この値が損益分岐を決める: 泳ぎ({@link #SWIM_ONE_BLOCK})とボート({@link #PADDLE_ONE_BLOCK})の
     * 差は1マスあたり約3tickなので、10マスちょっと以上の水面を渡るときだけボートが選ばれる。
     * 小川を渡るのにいちいちボートを出せとは言わない、という線引きになる。
     */
    public static final double BOAT_OVERHEAD_TICKS = 2.0 * PLACE_BLOCK_AIM_TICKS;

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
     * <p>{@link #PLACE_BLOCK_AIM_TICKS}と同値にすると橋1ブロックは約35.6tick＝徒歩10ブロック相当。
     * 20ブロックの溶岩横断が徒歩200ブロックの迂回と釣り合い、詳細探索の箱（描画距離）に収まる
     * 迂回路を一通り試してから橋を選ぶ、というちょうどの重みになる。
     *
     * <p><b>{@link #TERRAIN_EDIT_INTERRUPTION_TICKS}を含めてはいけない。</b>あの割増は
     * 「1回設置するか横へ迂回するか」の釣り合いで決まる値で、上限を握っているのは人間の好み。
     * こちらの上限を握っているのは<b>探索が橋に手を届かせられるか</b>という別の軸なので、
     * 連動させると人間側の理由で動かしたときに上の実測の窓から外れる。
     *
     * <p>これより大きく迂回すべきかどうかは層1（{@code CoarseRouter.BridgePolicy}）が決める。
     * 描画距離の外の迂回路は詳細探索にはそもそも見えないので、ここで表現しようとしてはいけない。
     *
     * <p>「橋が長くなりすぎたら諦めて迂回する」はコストではなく
     * {@code CellSource#maxBridgeRunBlocks()}（移動を生成しない上限）で表す。ここを重くして
     * 表そうとすると、上の理由でそのまま展開ノード数の浪費になる。
     */
    public static final double LAVA_BRIDGE_PENALTY_TICKS = PLACE_BLOCK_AIM_TICKS;

    /**
     * 底の無い空虚（ジ・エンドの奈落、探索範囲より深い大空洞）の上に足場を置いて渡る1ブロックあたりの
     * 追加ペナルティ。{@link #LAVA_BRIDGE_PENALTY_TICKS}と同値にしてある——<b>設置を1回でも外したときの
     * 結末が同じ</b>（即死＋持ち物の全損）だから、重みを分ける理由が無い。
     *
     * <p>上限が探索アルゴリズムの側から決まるという{@link #LAVA_BRIDGE_PENALTY_TICKS}の議論も
     * そのまま当てはまる。「橋が長くなりすぎたら諦める」は
     * {@code CellSource#maxVoidBridgeRunBlocks()}（移動を生成しない上限）が受け持つ。
     *
     * <p>ジ・エンドの島間は<b>ほぼ全ての橋がこれに当たる</b>ので、この値は経路の形をほとんど変えない
     * （迂回路が存在しないため）。効くのは地上世界の深い渓谷・洞窟で、「渡るより回り込む」を
     * 選ばせる場面。
     */
    public static final double VOID_BRIDGE_PENALTY_TICKS = LAVA_BRIDGE_PENALTY_TICKS;

    /**
     * 足場を外したときに落ちる高さに応じた危険料（tick）。橋を1マス架けるのと、隙間を1マス跳び越すのが
     * 共有する。落差{@code dropBlocks}は「足を置く高さの1つ下から床までの空きマス数」で、
     * 底が無いなら{@code fatalFallBlocks}以上を渡す。
     *
     * <p><b>二値ではなく傾斜にするのが要点。</b>以前は「致死落差以上なら{@link #VOID_BRIDGE_PENALTY_TICKS}、
     * さもなくば0」だったので、深さ3マスの窪みと深さ16マスの峡谷が同じ値段だった——実機ジ・エンド
     * (2481,-488)で、11〜16マス下に床のある谷を7マス連続で橋渡しし、そのまま奈落へ繋げる経路が出ていた。
     * 落ちたときの結末は落差に対して連続なので、値段も連続にする。
     *
     * <p>両端は従来どおりに固定してある——{@link #SAFE_FALL_BLOCKS}以下は0（1マスの窪みを埋めるのは安い）、
     * 致死落差以上は{@link #VOID_BRIDGE_PENALTY_TICKS}（奈落と同額）。<b>変わるのはその間だけ</b>なので、
     * 「浅い窪みは埋める」「奈落は高い」というどちらの既存の振る舞いも動かない。
     *
     * <p>通行可否（{@code maxVoidBridgeRunBlocks}の上限・掘削の禁止）は従来どおり<b>致死かどうかの二値</b>で
     * 決める。傾斜にするのは値段だけ——実現可能性まで連続にすると、どこから先が「渡り切れる橋」なのかを
     * 探索が判定できなくなる。
     */
    public static double dropRiskPenalty(int dropBlocks, int fatalFallBlocks) {
        if (dropBlocks <= SAFE_FALL_BLOCKS) {
            return 0.0;
        }
        if (dropBlocks >= fatalFallBlocks) {
            return VOID_BRIDGE_PENALTY_TICKS;
        }
        return VOID_BRIDGE_PENALTY_TICKS * (dropBlocks - SAFE_FALL_BLOCKS)
                / (double) (fatalFallBlocks - SAFE_FALL_BLOCKS);
    }

    /**
     * 落差が{@code maxDrop}マスで頭打ちのとき、下降1ブロックあたりの最小コスト（tick）。
     * {@link net.prason.xaeronav.pathfinding.astar.Heuristic}の下降成分の下限に使う。
     *
     * <p>{@code fallCost(d)/d}は{@code d}について単調減少（終端速度に漸近する）なので、
     * 生成されうる<b>最大の</b>落差での値が下限になる。<b>この単調性が要点</b>——落下ダメージの
     * 許容量を緩めて{@code maxDrop}が伸びると下限は必ず下がるので、緩めた探索へ元の下限を
     * 渡し続けると見積もりが実コストを上回る（＝非許容）。
     *
     * <p>梯子（{@link #LADDER_DOWN_ONE_BLOCK}）はこれを上回りうるが、下限として取り違えないよう
     * 明示的に比べる。
     */
    public static double descentBoundForMaxDrop(int maxDrop) {
        return Math.min(fallCost(maxDrop) / maxDrop, LADDER_DOWN_ONE_BLOCK);
    }

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

    /**
     * {@link #ascendOneBlock}の1段降り版。{@link #DESCEND_ONE_BLOCK}のとおり落下の時間は
     * 計上せず、水平移動そのもの（{@code AStarPathfinder#stepCost}と同じ形）にする。
     */
    public static double descendOneBlock(double speedFactor) {
        return SPRINT_ONE_BLOCK / speedFactor;
    }

    /** {@link #ascendOneBlock}の斜め版。 */
    public static double diagonalAscendOneBlock(double speedFactor) {
        return Math.max(ascendOneBlock(speedFactor), WALK_ONE_BLOCK * DIAGONAL_DISTANCE / speedFactor);
    }

    /** {@link #descendOneBlock}の斜め版。 */
    public static double diagonalDescendOneBlock(double speedFactor) {
        return Math.max(descendOneBlock(speedFactor), SPRINT_ONE_BLOCK * DIAGONAL_DISTANCE / speedFactor);
    }

    /** {@code gapBlocks}マスの隙間を飛び越えるコスト。着地点は{@code gapBlocks + 1}マス先になる。 */
    public static double jumpAcrossGap(int gapBlocks) {
        return JUMP_ACROSS_GAP + (gapBlocks - 1) * JUMP_REACH_PENALTY;
    }
}
