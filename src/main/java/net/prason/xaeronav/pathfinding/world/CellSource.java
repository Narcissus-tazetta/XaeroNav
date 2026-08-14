package net.prason.xaeronav.pathfinding.world;

/**
 * 探索が世界に対して行う問い合わせのすべて。
 *
 * <p>経路探索（{@code AStarPathfinder} / {@code ElytraPathfinder} / {@link StanceFinder} /
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
     * 落下ダメージを何点(0.5ハート単位)まで許容してよいか。0なら安全高さを超える落下を一切提示しない。
     * 体力に依存するので、探索を組み立てる時点のプレイヤーの状態から決まる。
     */
    int maxFallDamagePoints();

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
}
