package net.prason.xaeronav.pathfinding.navgraph;

import net.prason.xaeronav.pathfinding.astar.RunCaps;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 1セクションのうち、グラフに入れるセル。自然に立てる点（{@link NaturalColumns}）から水平{@link #HORIZONTAL}・
 * 垂直{@link #VERTICAL}以内の体積だけ。
 *
 * <p>閉包の99%は掘削と空中の体積で、それを丸ごと持つと窓の辺が数千万本になる。この幅なら質は落ちない
 * （実測: 窓160の中を殻にしてもネザー1.021・山岳1.000で殻なしと同じ、全体に掛けてもネザー1.012・エンド1.010）。
 * <b>水平8より狭めるとエンドの橋が切れる</b>（水平2で1.108）。
 *
 * <p>それより広い奈落・溶岩の海は、橋が通りうる列の高さだけを足す（{@link NaturalColumns#bridgeCorridor}）。
 */
final class SectionShell implements SectionMoves.Mask {

    static final int HORIZONTAL = 8;
    static final int VERTICAL = 2;

    private final int minX;
    private final int minZ;
    private final int minY;
    /** セクションの列（16×16）ごとの、残してよい高さのビット。 */
    private final long[][] allowed;

    private SectionShell(int minX, int minZ, int minY, long[][] allowed) {
        this.minX = minX;
        this.minZ = minZ;
        this.minY = minY;
        this.allowed = allowed;
    }

    /**
     * 奈落を渡る橋の途中を探す距離の上限（ブロック）。設定の橋の長さが無制限（0）のときに使う。窓の直径より長い橋は窓の中に収まらない。
     */
    private static final int UNLIMITED_BRIDGE_REACH = 320;

    static SectionShell of(NaturalColumns naturals, CellSource cells, int sectionX, int sectionZ, int goalX,
                           int goalZ) {
        int words = naturals.words();
        int span = SectionMoves.SIZE + 2 * HORIZONTAL;
        int originX = sectionX * SectionMoves.SIZE - HORIZONTAL;
        int originZ = sectionZ * SectionMoves.SIZE - HORIZONTAL;
        // 垂直に膨らませてから、X方向・Z方向の順に水平へ膨らませる（分離できるので2回で済む）
        long[][] grown = new long[span * span][];
        for (int ax = 0; ax < span; ax++) {
            for (int az = 0; az < span; az++) {
                long[] column = new long[words];
                for (int w = 0; w < words; w++) {
                    column[w] |= naturals.word(cells, originX + ax, originZ + az, w);
                }
                long[] vertical = column.clone();
                for (int k = 1; k <= VERTICAL; k++) {
                    for (int w = 0; w < words; w++) {
                        vertical[w] |= column[w] << k;
                        vertical[w] |= column[w] >>> k;
                        // 語の境目をまたいで膨らむぶん
                        if (w > 0) {
                            vertical[w] |= column[w - 1] >>> (64 - k);
                        }
                        if (w + 1 < words) {
                            vertical[w] |= column[w + 1] << (64 - k);
                        }
                    }
                }
                grown[ax + az * span] = vertical;
            }
        }
        long[][] alongX = new long[SectionMoves.SIZE * span][];
        for (int lx = 0; lx < SectionMoves.SIZE; lx++) {
            for (int az = 0; az < span; az++) {
                long[] merged = new long[words];
                for (int d = 0; d <= 2 * HORIZONTAL; d++) {
                    long[] bits = grown[lx + d + az * span];
                    for (int w = 0; w < words; w++) {
                        merged[w] |= bits[w];
                    }
                }
                alongX[lx + az * SectionMoves.SIZE] = merged;
            }
        }
        int reach = cells.maxVoidBridgeRunBlocks() > 0 ? cells.maxVoidBridgeRunBlocks() : UNLIMITED_BRIDGE_REACH;
        int lavaCap = RunCaps.stricter(cells.maxBridgeRunBlocks(), cells.maxLavaBridgeRunBlocks());
        int lavaReach = !cells.canPlaceBlocks() || !cells.lavaBridgingEnabled() ? 0
                : lavaCap > 0 ? lavaCap : UNLIMITED_BRIDGE_REACH;
        long[][] allowed = new long[SectionMoves.SIZE * SectionMoves.SIZE][];
        for (int lx = 0; lx < SectionMoves.SIZE; lx++) {
            for (int lz = 0; lz < SectionMoves.SIZE; lz++) {
                long[] merged = new long[words];
                for (int d = 0; d <= 2 * HORIZONTAL; d++) {
                    long[] bits = alongX[lx + (lz + d) * SectionMoves.SIZE];
                    for (int w = 0; w < words; w++) {
                        merged[w] |= bits[w];
                    }
                }
                long[] corridor = naturals.bridgeCorridor(cells, sectionX * SectionMoves.SIZE + lx,
                        sectionZ * SectionMoves.SIZE + lz, goalX, goalZ, reach, lavaReach);
                for (int w = 0; w < words; w++) {
                    merged[w] |= corridor[w];
                }
                allowed[lx + lz * SectionMoves.SIZE] = merged;
            }
        }
        return new SectionShell(sectionX * SectionMoves.SIZE, sectionZ * SectionMoves.SIZE, naturals.minY(),
                allowed);
    }

    @Override
    public boolean contains(int x, int y, int z) {
        int lx = x - minX;
        int lz = z - minZ;
        int bit = y - minY;
        if (lx < 0 || lx >= SectionMoves.SIZE || lz < 0 || lz >= SectionMoves.SIZE || bit < 0) {
            return false;
        }
        long[] bits = allowed[lx + lz * SectionMoves.SIZE];
        return bit < bits.length * 64 && (bits[bit >> 6] >>> bit & 1L) != 0;
    }
}
