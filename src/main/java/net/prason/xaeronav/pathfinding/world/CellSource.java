package net.prason.xaeronav.pathfinding.world;

/**
 * 探索が世界に対して行う問い合わせのすべて。
 *
 * <p>経路探索（{@code AStarPathfinder} / {@link StanceFinder} /
 * {@code PathSafetyChecker}）がブロックについて知りたいのはここにある4つだけで、
 * チャンクの引き方・ホットバーの複製・掘削コストの求め方といった話は一切要らない。
 *
 * <p>唯一の本番実装は{@link ChunkView}。テストからは{@link CellData}のビット列を直接組み立てた
 * 実装を渡す（{@code ChunkView}は{@code Level}と{@code Player}が無いと作れないので、
 * この境界が無いと探索コアはテストから一切動かせない）。
 *
 * <p><b>スレッド契約:</b> 実装は単一のワーカースレッドが占有してよい（{@link ChunkView}は
 * キャッシュを可変フィールドに持つため、複数スレッドで共有してはならない）。
 */
public interface CellSource {

    /**
     * 指定座標のセルデータ（{@link CellData}のビット列）。
     * 探索範囲外・未ロードチャンクは{@link CellData#ABSENT}。
     */
    long cell(int x, int y, int z);

    boolean isInBounds(int x, int y, int z);

    SearchBounds bounds();

    /**
     * 足場を置く移動（Bridge・Pillar）を提示してよいか。持ち物に置けるブロックが1つも無いなら、
     * どれだけ近道でも「ここにブロックを置け」という案内は実行できない指示にしかならない。
     */
    boolean canPlaceBlocks();

    /**
     * 経路全体で置いてよい足場の総数。0なら無制限（{@link #maxBridgeRunBlocks}と同じ規約）。
     *
     * <p>{@link #maxBridgeRunBlocks}が<b>連続長</b>なのに対しこちらは<b>累積</b>。連続長だけでは
     * 「1本30マスの橋を5回架ける」経路を止められず、途中で持ち物が尽きると案内の続きが実行できない
     * ——ユーザー報告「結局掘る羽目になって、掘った方が早かった」の正体。
     *
     * <p>あちらと同じく<b>移動の生成そのもの</b>で切る。重い値段で表そうとすると、A*は安い辺から
     * 展開するので橋に手を伸ばす前に周囲を展開し尽くす。
     */
    default int placedBlockBudget() {
        return 0;
    }

    /**
     * 隙間を飛び越える移動を提示してよいか。跳躍は着地を外すと落ちるので、
     * それを避けたいプレイヤーのために設定で切れるようにしてある。
     */
    boolean jumpGapEnabled();

    /**
     * 溶岩に足場を置いて渡る移動を提示してよいか。設置を1回でも外せば死ぬので、
     * 溶岩を避けた道が一切無い場合の最後の手段として、大きなコストと併せて使う。
     */
    boolean lavaBridgingEnabled();

    /**
     * 空中に足場を置いて渡る橋を何マスまで連続させてよいか。0なら無制限。
     *
     * <p>コストの重みではなく<b>移動の生成そのもの</b>で切るための値。重みで抑えると、A*は安い辺から
     * 展開するので「橋に手を伸ばす前に周辺の地形を展開し尽くす」ことになり、展開ノード数を
     * 焼き切ってしまう（{@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}参照）。辺を作らなければ
     * その代償は一切生じない。
     */
    int maxBridgeRunBlocks();

    /**
     * そのうち<b>溶岩の上</b>に架けてよい長さ。0なら{@link #maxBridgeRunBlocks}だけが効く。
     *
     * <p>空洞に架ける橋と分けて持つのは、外したときの結末が違うから——空洞なら落ちるだけだが、
     * 溶岩の上では即死する。橋の連続長そのものは共通なので、実際に効くのは両者の小さい方になる。
     */
    default int maxLavaBridgeRunBlocks() {
        return maxBridgeRunBlocks();
    }

    /**
     * そのうち<b>底の無い空虚</b>（ジ・エンドの奈落、探索範囲より深い大空洞）の上に架けてよい長さ。
     * 0なら{@link #maxBridgeRunBlocks}だけが効く。
     *
     * <p>溶岩と分けずに一括りにしないのは、地形として出会う頻度がまるで違うから——ジ・エンドでは
     * ほぼ全ての橋がこれに当たるので、溶岩と同じ感覚で締めると島間の移動が丸ごと消える。
     * 外したときの結末（即死）が同じなので、重み（{@code ActionCosts#VOID_BRIDGE_PENALTY_TICKS}）の方は
     * 溶岩と同値にしてある。
     */
    default int maxVoidBridgeRunBlocks() {
        return maxBridgeRunBlocks();
    }

    /**
     * 頭を水に浸けたまま何tickまで続けてよいか。0なら無制限。
     *
     * <p>{@link #maxBridgeRunBlocks}と同じく<b>移動の生成そのもの</b>で切る。溺れる危険を重い
     * コストで表そうとすると、A*が水面の迂回路を展開し尽くしてから潜水に手を伸ばすことになり、
     * 展開ノード数を焼く（{@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}に記録された実測と同じ話）。
     *
     * <p>単位がマス数ではなく<b>tick</b>なのが要点。水中の移動は種類ごとに速さが違う——泳ぎ・浮上・
     * 潜降・採掘で数倍の開きがある——ので、マス数では息の減りを正しく数えられない。実際、
     * マス数で持っていた頃は水底から水面へ浮上するだけで上限を超え、深い水中の目的地が
     * <b>到達不能</b>になっていた。
     *
     * <p>ここは物理的な限界（空気1回分）を表す線で、「なるべく潜らない」という好みは
     * {@code ActionCosts#SUBMERGED_TRAVEL_PENALTY}が受け持つ。両方をここに込めると、
     * 安全側に寄せたぶんだけ本当に通れる経路まで消える。
     *
     * <p>上限のせいで道が一本も無くなったときだけ、呼び出し側が上限を外して探し直す
     * （「溺れる危険は最後の手段だが、詰みよりはマシ」）。
     */
    int maxSubmergedTicks();

    /**
     * 落下ダメージを何点(0.5ハート単位)まで許容してよいか。0なら安全高さを超える落下を一切提示しない。
     * 体力に依存するので、探索を組み立てる時点のプレイヤーの状態から決まる。
     */
    int maxFallDamagePoints();

    /**
     * 跳躍を外して落ちたときに死ぬ落差（ブロック）。これ以上の落差の上は「外したら取り返しがつかない」。
     * {@link #maxFallDamagePoints()}と同じく、探索を組み立てる時点のプレイヤーの体力から決まる。
     *
     * <p>あちらと役割が違う。{@code maxFallDamagePoints}は「<b>意図して</b>降りてよい高さ」で、
     * 痛くても着地は約束されている。こちらは「跳んで<b>外した</b>ときにどうなるか」で、
     * 経路としては着地する前提の跳躍にしか関わらない。
     */
    int fatalFallBlocks();

    /**
     * 底の無い空虚の上や、外したら死ぬ落差の上での跳躍を避けるか。
     *
     * <p>避ける設定でも、経路が一本も引けなかったときだけ緩和の梯子（{@code PathfindingExecutor}）が
     * 開ける——ユーザーの意図は「回り込めるならそちらを通れ」であって「絶対に跳ぶな」ではない。
     */
    boolean avoidRiskyJumps();

    /**
     * この探索で生成されうる下降移動のうち、1ブロックあたり最も安いもの（tick）。
     * {@link net.prason.xaeronav.pathfinding.astar.Heuristic}の下降成分の下限に使う。
     *
     * <p>終端速度からの下限(0.2551)は「任意の深さの落下が起きうる」前提の値。実際に生成される
     * 最大の落差は設定と次元で決まる——{@code FALL_TO_WATER}は着水先に水があるときだけ生成され、
     * <b>ultraWarmな次元（ネザー）には水が存在しない</b>（置いても蒸発する）。落下ダメージ許容が
     * offなら安全高さ3マスが上限になり、下限は4.392まで締まる（17倍）。
     */
    double minDescentTicksPerBlock();

    /**
     * 落下ダメージの許容量を{@code maxFallDamagePoints}へ置き換えたときの下降1ブロックあたりの下限。
     *
     * <p>詰み時に許容量を段階的に緩める探し直し（{@code PathfindingExecutor}）のために要る。
     * <b>許容量を上げたら下限も一緒に緩めないとヒューリスティックが非許容になる</b>——
     * 許せる落差が伸びるほど1ブロックあたりの実コストは終端速度へ近づいて<b>安く</b>なるので、
     * 元の（きつい）落差で求めた下限は実コストを上回りうる。
     *
     * <p>既定は{@link #minDescentTicksPerBlock()}をそのまま返す。落差に依らない下限
     * （終端速度）を返す実装ではこれで正しく、緩めても下回ることがない。
     */
    default double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return minDescentTicksPerBlock();
    }

    /**
     * 着地寸前に水バケツを置いて落下ダメージを消す移動を提示してよいか。
     * 水バケツを持っていなければ実行できない指示にしかならない。
     */
    boolean canMlgWaterBucket();

    /**
     * ボートを持っているか。水面をボートで渡る移動を提示してよいかの判断に使う。
     *
     * <p>{@link #canMlgWaterBucket()}と同じくプレイヤーの持ち物に依存するので、
     * 持ち物を知らない層2は常にfalseを返す。
     */
    boolean boatAvailable();

    /**
     * いまボートに乗っているか。探索の始点を「もう乗っている状態」にするために使う。
     *
     * <p>{@link #boatAvailable()}と分けるのは、乗り降りの手間を二重に払わせないため。乗車中に
     * 岸から漕ぎ出す1手ぶんのコストをもう一度計上すると、残りの水面が短い場面で
     * 「降りて泳いだ方が安い」という案内に化ける。
     */
    default boolean ridingBoat() {
        return false;
    }

    /**
     * この列で頭上に何も無くなる最小のY。{@code y >= openSkyY(x, z)}なら、そのセルは空の下にある。
     *
     * <p>「地上に出た」を高さだけで判定すると、天井の下にある洞窟も地上に数えてしまう。
     * 深い洞窟は水平坑道が長く、既定の地上高(60)より上を通ることが珍しくない。
     *
     * <p>高さの分からない列（未ロードチャンク）は{@link Integer#MAX_VALUE}を返す。
     * 空の下だと言い切れない以上、地上として扱ってはいけない。
     */
    int openSkyY(int x, int z);

    /**
     * この列で「地上に出た」とみなしてよい最小のY。陸では{@link #openSkyY}と同じ。
     *
     * <p>水柱だけが1マス下がる。{@code openSkyY}が使うMOTION_BLOCKINGハイトマップは
     * <b>流体を含む</b>ので、海では水面の1つ上＝水の外を指す。そこは空気で足場が無く、
     * 泳いでいるプレイヤーが立てるノードにならない——外洋では「地上へ出る」中継探索が
     * 原理的に成功できなくなっていた。水面に顔を出せていればそこはもう地上として扱う。
     */
    default int surfacedY(int x, int z) {
        int sky = openSkyY(x, z);
        if (sky == Integer.MAX_VALUE) {
            return sky;
        }
        return CellData.water(cell(x, sky - 1, z)) ? sky - 1 : sky;
    }
}
