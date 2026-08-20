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
     * 足場を置く移動（Bridge・Pillar）を提示してよいか。ホットバーに置けるブロックが1つも無いなら、
     * どれだけ近道でも「ここにブロックを置け」という案内は実行できない指示にしかならない。
     */
    boolean canPlaceBlocks();

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
     * 頭を水に浸けたまま何マスまで続けてよいか。0なら無制限。
     *
     * <p>{@link #maxBridgeRunBlocks}と同じく<b>移動の生成そのもの</b>で切る。溺れる危険を重い
     * コストで表そうとすると、A*が水面の迂回路を展開し尽くしてから潜水に手を伸ばすことになり、
     * 展開ノード数を焼く（{@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}に記録された実測と同じ話）。
     *
     * <p>上限のせいで道が一本も無くなったときだけ、呼び出し側が上限を外して探し直す
     * （「溺れる危険は最後の手段だが、詰みよりはマシ」）。
     */
    int maxSubmergedRunBlocks();

    /**
     * 落下ダメージを何点(0.5ハート単位)まで許容してよいか。0なら安全高さを超える落下を一切提示しない。
     * 体力に依存するので、探索を組み立てる時点のプレイヤーの状態から決まる。
     */
    int maxFallDamagePoints();

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
     * 着地寸前に水バケツを置いて落下ダメージを消す移動を提示してよいか。
     * 水バケツを持っていなければ実行できない指示にしかならない。
     */
    boolean canMlgWaterBucket();

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
