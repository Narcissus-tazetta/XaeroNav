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
     * 足場を置く移動（Bridge）を提示してよいか。ホットバーに置けるブロックが1つも無いなら、
     * どれだけ近道でも「ここにブロックを置け」という案内は実行できない指示にしかならない。
     */
    boolean canPlaceBlocks();
}
