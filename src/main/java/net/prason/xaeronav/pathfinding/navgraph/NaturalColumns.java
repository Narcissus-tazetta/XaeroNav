package net.prason.xaeronav.pathfinding.navgraph;

import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

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
    /**
     * 読み込まれていないセルを含んでいたチャンク。{@link #forgetIncomplete}までの間だけ覚える。
     *
     * <p><b>覚えずに毎回読み直してはいけない。</b>殻1つが32×32列を引くので、窓の縁のチャンクを列ごとに丸ごと読み直すことになり、
     * 窓全体の構築が15倍遅くなった（実測: ネザーの窓4,410セクションで41.9秒、読み直さなければ数秒）。
     */
    private final ConcurrentHashMap<Long, long[]> incomplete = new ConcurrentHashMap<>();

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
        return chunkBits(cells, x, z)[(Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16) * words + word];
    }

    /**
     * 溶岩の面から上を見る高さ（ブロック）。溶岩の海を渡る橋は岸の高さに架けるので、面より遥か上の洞窟の床は岸に数えない。
     * 16では足りない——実機のネザーの小島は面から17〜21ブロック上に立つ所があり、その岸が数えられずに通り道が出来なかった。
     */
    private static final int LAVA_BRIDGE_BAND = 32;

    /** 列({@code x},{@code z})のいちばん上の、真上が空いている溶岩の1つ上（立つ高さ）のビット。無ければ-1。 */
    private int lavaSurface(CellSource cells, int x, int z) {
        long[] bits = chunkBits(cells, x, z);
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        return (int) bits[256 * words + 4 + column] - 1;
    }

    /** 列({@code x},{@code z})が底まで空っぽ（ジ・エンドの奈落）か。読み込まれていないセルを含む列は違う。 */
    boolean voidColumn(CellSource cells, int x, int z) {
        long[] bits = chunkBits(cells, x, z);
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        return (bits[256 * words + (column >> 6)] >>> column & 1L) != 0;
    }

    private long[] chunkBits(CellSource cells, int x, int z) {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        long[] bits = chunks.get(key);
        if (bits == null) {
            bits = incomplete.get(key);
        }
        return bits != null ? bits : scan(cells, chunkX, chunkZ, key);
    }

    /**
     * 奈落の列({@code x},{@code z})のうち、目的地へ向かう橋が通りうる高さのビット。奈落でなければ全部0。
     *
     * <p>{@link SectionShell}は自然に立てる点から水平8ブロックしか持たないので、それより広い奈落を渡る橋の途中が
     * グラフから抜け、向こう岸の島が目的地へ繋がらない（実機のエンドの外側の島: 島の間が80〜100ブロックで、
     * ガイドが幾何下限に落ちて経路が1本も出なかった）。
     *
     * <p>橋は目的地へ近づく向きにしか張られない（{@code BuildMoves#addBridge}）ので、見るのは各軸で目的地から遠い側の
     * いちばん近い岸と、近い側のいちばん近い岸だけ。手前の岸が{@code reach}以内に無ければ橋はこの列に届かない。
     * 岸の高さが揃っていないと着いた先で柱を積むので、両岸の立てる高さの間を上下{@link SectionShell#VERTICAL}まで埋める。
     */
    long[] bridgeCorridor(CellSource cells, int x, int z, int goalX, int goalZ, int reach, int lavaReach) {
        long[] corridor = new long[words];
        if (!voidColumn(cells, x, z)) {
            return lavaReach > 0 ? lavaCorridor(cells, x, z, lavaReach, corridor) : corridor;
        }
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        boolean fromShore = false;
        int[][] axes = {{Integer.signum(goalX - x), 0}, {0, Integer.signum(goalZ - z)}};
        for (int[] axis : axes) {
            if (axis[0] == 0 && axis[1] == 0) {
                continue;
            }
            for (int side = -1; side <= 1; side += 2) {
                int[] span = nearestShoreSpan(cells, x, z, axis[0] * side, axis[1] * side, reach);
                if (span == null) {
                    continue;
                }
                // 目的地から遠い側（橋を架け始める岸）が見つかったときだけ、この列を橋が通る
                fromShore |= side < 0;
                low = Math.min(low, span[0]);
                high = Math.max(high, span[1]);
            }
        }
        if (!fromShore) {
            return corridor;
        }
        int from = Math.max(0, low - SectionShell.VERTICAL);
        int to = Math.min(height - 1, high + SectionShell.VERTICAL);
        for (int bit = from; bit <= to; bit++) {
            corridor[bit >> 6] |= 1L << bit;
        }
        return corridor;
    }

    /**
     * 溶岩の海の列で、橋が通りうる高さ。{@link #bridgeCorridor}の奈落と同じ穴が溶岩の海にもある——殻は岸から8ブロックしか持たないので、
     * 16ブロックより広い溶岩を挟んだ島がグラフで繋がらず、ガイドが遠くの迂回路へ誘う（実機のネザー: 溶岩を挟んだ小島から西の島へ渡る
     * 近道が無いことになり、溶岩の海の外周へ出ては戻った。3D粗層はその近道を知っているので、窓の外と中で向きが食い違っていた）。
     *
     * <p>溶岩の橋は目的地の向きに縛られない（{@code BuildMoves#addBridge}）ので、4方向どれかの岸が{@code reach}以内にあれば通る。
     * 岸は溶岩の面から{@link #LAVA_BRIDGE_BAND}以内に立てる列。見つかった岸の高さの間を上下{@link SectionShell#VERTICAL}まで埋める。
     */
    private long[] lavaCorridor(CellSource cells, int x, int z, int reach, long[] corridor) {
        int surface = lavaSurface(cells, x, z);
        if (surface < 0) {
            return corridor;
        }
        int bandTop = Math.min(height - 1, surface + LAVA_BRIDGE_BAND);
        long[] band = new long[words];
        for (int bit = surface; bit <= bandTop; bit++) {
            band[bit >> 6] |= 1L << bit;
        }
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            for (int k = 1; k <= reach; k++) {
                int px = x + direction[0] * k;
                int pz = z + direction[1] * k;
                if (!cells.isInBounds(px, minY, pz)) {
                    break;
                }
                long[] chunk = chunkBits(cells, px, pz);
                int column = Math.floorMod(px, 16) + Math.floorMod(pz, 16) * 16;
                int shoreLow = Integer.MAX_VALUE;
                int shoreHigh = Integer.MIN_VALUE;
                for (int w = 0; w < words; w++) {
                    long bits = chunk[column * words + w] & band[w];
                    if (bits != 0) {
                        shoreLow = Math.min(shoreLow, (w << 6) + Long.numberOfTrailingZeros(bits));
                        shoreHigh = Math.max(shoreHigh, (w << 6) + 63 - Long.numberOfLeadingZeros(bits));
                    }
                }
                if (shoreLow <= shoreHigh) {
                    low = Math.min(low, shoreLow);
                    high = Math.max(high, shoreHigh);
                    break;
                }
                if (chunk[256 * words + 4 + column] == 0) {
                    // 溶岩の海が途切れたのに立てる所が無い（壁・読み込まれていない列）。その先へは架けない
                    break;
                }
            }
        }
        if (low > high) {
            return corridor;
        }
        int from = Math.max(surface, low - SectionShell.VERTICAL);
        int to = Math.min(bandTop, high + SectionShell.VERTICAL);
        for (int bit = from; bit <= to; bit++) {
            corridor[bit >> 6] |= 1L << bit;
        }
        return corridor;
    }

    /** ({@code x},{@code z})から({@code dx},{@code dz})の向きに最初に当たる奈落でない列の、立てる高さの最小・最大ビット。 */
    private int @Nullable [] nearestShoreSpan(CellSource cells, int x, int z, int dx, int dz, int reach) {
        for (int k = 1; k <= reach; k++) {
            int px = x + dx * k;
            int pz = z + dz * k;
            if (!cells.isInBounds(px, minY, pz)) {
                return null;
            }
            if (voidColumn(cells, px, pz)) {
                continue;
            }
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            for (int w = 0; w < words; w++) {
                long bits = word(cells, px, pz, w);
                if (bits != 0) {
                    low = Math.min(low, (w << 6) + Long.numberOfTrailingZeros(bits));
                    high = Math.max(high, (w << 6) + 63 - Long.numberOfLeadingZeros(bits));
                }
            }
            // 立てる所の無い岸（読み込まれていない列・浮いた柱）からは橋を架けない
            return low > high ? null : new int[] {low, high};
        }
        return null;
    }

    /** チャンクの中身が変わった。次に引かれたときに読み直す。 */
    public void invalidateChunk(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        chunks.remove(key);
        incomplete.remove(key);
    }

    /** 読み込みの途中だったチャンクを忘れる。読める範囲が変わる前（組み直しの頭）に呼ぶ。 */
    public void forgetIncomplete() {
        incomplete.clear();
    }

    public void clear() {
        chunks.clear();
        incomplete.clear();
    }

    private long[] scan(CellSource cells, int chunkX, int chunkZ, long key) {
        // 末尾の4語は列ごとの「底まで空っぽ」の印、その後の256語は列ごとの溶岩の面（lavaSurfaceの値+1）
        long[] bits = new long[256 * words + 4 + 256];
        boolean absent = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = chunkX * 16 + lx;
                int z = chunkZ * 16 + lz;
                int base = (lx + lz * 16) * words;
                long below = cells.cell(x, minY, z);
                long feet = cells.cell(x, minY + 1, z);
                absent |= !CellData.present(below);
                boolean empty = CellData.passableEmpty(below) && CellData.passableEmpty(feet);
                int lavaTop = -1;
                for (int y = minY + 1; y < minY + height - 1; y++) {
                    long head = cells.cell(x, y + 1, z);
                    empty &= CellData.passableEmpty(head);
                    if (CellData.lava(below) && CellData.passableEmpty(feet)) {
                        lavaTop = y - minY;
                    }
                    if (natural(below, feet, head)) {
                        bits[base + ((y - minY) >> 6)] |= 1L << (y - minY);
                    }
                    below = feet;
                    feet = head;
                }
                int column = lx + lz * 16;
                if (empty) {
                    bits[256 * words + (column >> 6)] |= 1L << column;
                }
                bits[256 * words + 4 + column] = lavaTop + 1;
            }
        }
        (absent ? incomplete : chunks).put(key, bits);
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
