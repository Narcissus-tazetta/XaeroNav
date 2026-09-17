package net.prason.xaeronav.pathfinding.navgraph;

import java.util.concurrent.ConcurrentHashMap;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 列ごとの「掘らず・置かずに立てる高さ」のビット。チャンク単位で覚え、殻（{@link SectionShell}）の芯にする。
 *
 * <p>水は<b>全深さ</b>を数える。水面だけにすると窓の辺は3割減る（広域の窓で3,445万→2,459万辺）が、
 * 海底から水面までの水中がグラフから抜け、海底にいる始点が目的地に繋がらなくなる
 * （実測: 広域長距離で閉包の窓1.015倍に対し1.619倍、最悪2.632倍）。
 *
 * <p>ワーカースレッドから並行に呼んでよい。
 */
public final class NaturalColumns {

    private final int minY;
    private final int height;
    private final int words;
    private final ConcurrentHashMap<Long, long[]> chunks = new ConcurrentHashMap<>();

    /** @param minY ビット0に当たる高さ。{@code maxY}まで含む */
    public NaturalColumns(int minY, int maxY) {
        this.minY = minY;
        this.height = maxY - minY + 1;
        this.words = (height + 63) >> 6;
    }

    int minY() {
        return minY;
    }

    int words() {
        return words;
    }

    /**
     * 列({@code x},{@code z})のビットの{@code word}語目。
     *
     * <p>読み込まれていないセルを含むチャンクは<b>覚えない</b>。覚えると、後で読み込まれても空のまま残り、
     * そこだけ殻が無い＝グラフに穴が空く。
     */
    long word(CellSource cells, int x, int z, int word) {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        long[] bits = chunks.get(key);
        if (bits == null) {
            bits = scan(cells, chunkX, chunkZ, key);
        }
        return bits[(Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16) * words + word];
    }

    /** チャンクの中身が変わった。次に引かれたときに読み直す。 */
    public void invalidateChunk(int chunkX, int chunkZ) {
        chunks.remove(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
    }

    public void clear() {
        chunks.clear();
    }

    private long[] scan(CellSource cells, int chunkX, int chunkZ, long key) {
        long[] bits = new long[256 * words];
        boolean absent = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = chunkX * 16 + lx;
                int z = chunkZ * 16 + lz;
                int base = (lx + lz * 16) * words;
                long below = cells.cell(x, minY, z);
                long feet = cells.cell(x, minY + 1, z);
                absent |= !CellData.present(below);
                for (int y = minY + 1; y < minY + height - 1; y++) {
                    long head = cells.cell(x, y + 1, z);
                    if (natural(below, feet, head)) {
                        bits[base + ((y - minY) >> 6)] |= 1L << (y - minY);
                    }
                    below = feet;
                    feet = head;
                }
            }
        }
        if (!absent) {
            chunks.put(key, bits);
        }
        return bits;
    }

    /** 足・頭のセルに掘らずに入れて、足元が床か、水の中か、掴まれるもの。 */
    static boolean natural(long below, long feet, long head) {
        if (!CellData.occupiableWithoutDigging(feet) || !CellData.occupiableWithoutDigging(head)) {
            return false;
        }
        return CellData.standable(below) || CellData.water(feet) || CellData.climbable(feet);
    }
}
