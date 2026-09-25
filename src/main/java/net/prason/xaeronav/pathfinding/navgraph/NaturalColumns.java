package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

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
    /**
     * 列ごとの岸の高さと橋の長さ（{@link #shore}）。後ろの列の中身に依存するので、どこかが変われば全部捨てる。
     * 読み込み中の列を含むことがあるので、{@link #forgetIncomplete}でも捨てる。
     */
    private final ConcurrentHashMap<Long, AtomicReferenceArray<int[]>> shores = new ConcurrentHashMap<>();

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

    /**
     * 列({@code x},{@code z})の底から続く空っぽのセルの数（ビット）。浮いた島・自分で架けた橋の下もここまでは奈落。
     * 底まで空っぽなら高さ全部、底が埋まっていれば0。
     */
    private int voidBelow(CellSource cells, int x, int z) {
        long[] bits = chunkBits(cells, x, z);
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        return (int) bits[256 * words + 4 + 256 + column];
    }

    /** 列({@code x},{@code z})のいちばん上のブロックの1つ上のビット。ここから上は空っぽ。列が空っぽなら0。 */
    private int voidAbove(CellSource cells, int x, int z) {
        long[] bits = chunkBits(cells, x, z);
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        return (int) bits[256 * words + 4 + 512 + column];
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
     * 下が奈落の列({@code x},{@code z})のうち、目的地へ向かう橋が通りうる高さのビット。底が埋まった列は溶岩の海だけを見る。
     *
     * <p>{@link SectionShell}は自然に立てる点から水平8ブロックしか持たないので、それより広い奈落を渡る橋の途中が
     * グラフから抜け、向こう岸の島が目的地へ繋がらない（実機のエンドの外側の島: 島の間が80〜100ブロックで、
     * ガイドが幾何下限に落ちて経路が1本も出なかった）。
     *
     * <p>奈落の上の橋は目的地へ近づく向きにしか張られない（{@code BuildMoves#addBridge}）ので、橋がこの列に来られるのは、
     * 目的地から遠い側（各軸で後ろ）に{@code reach}以内で岸があるときだけ。L字に折れる橋もあるので、後ろの岸は軸の上だけでなく
     * 後ろ側の象限から探す（{@link #behind}）。岸の高さが揃っていないと着いた先で柱を積むので、目的地側の軸上の岸の高さまで含め、
     * 上下{@link SectionShell#VERTICAL}まで埋める。
     */
    long[] bridgeCorridor(CellSource cells, int x, int z, int goalX, int goalZ, int reach, int lavaReach) {
        long[] corridor = new long[words];
        int voidBelow = voidBelow(cells, x, z);
        if (voidBelow < 3) {
            return lavaReach > 0 ? lavaCorridor(cells, x, z, lavaReach, corridor) : corridor;
        }
        long[] pass = passable(cells, x, z);
        int[] behind = behind(cells, x, z, goalX, goalZ, reach, pass);
        if (behind.length == 0) {
            return corridor;
        }
        int nearLow = Integer.MAX_VALUE;
        int nearHigh = Integer.MIN_VALUE;
        int[][] axes = {{Integer.signum(goalX - x), 0}, {0, Integer.signum(goalZ - z)}};
        for (int[] axis : axes) {
            if (axis[0] == 0 && axis[1] == 0) {
                continue;
            }
            int[] span = nearestShoreSpan(cells, x, z, axis[0], axis[1], reach, voidBelow - 2);
            if (span != null) {
                nearLow = Math.min(nearLow, span[0]);
                nearHigh = Math.max(nearHigh, span[1]);
            }
        }
        int closest = -1;
        for (int entry : behind) {
            int bit = shoreHeight(entry);
            fill(corridor, bit - SectionShell.VERTICAL, bit + SectionShell.VERTICAL);
            if (nearLow <= nearHigh && (closest < 0 || gap(bit, nearLow, nearHigh) < gap(closest, nearLow, nearHigh))) {
                closest = bit;
            }
        }
        // 岸の高さが揃っていないと着いた先で柱を積むので、いちばん近い高さから向こう岸の高さまでを埋める
        if (closest >= 0) {
            fill(corridor, Math.min(closest, nearLow) - SectionShell.VERTICAL,
                    Math.max(closest, nearHigh) + SectionShell.VERTICAL);
        }
        for (int w = 0; w < words; w++) {
            corridor[w] &= pass[w];
        }
        return corridor;
    }

    private static int gap(int bit, int low, int high) {
        return bit < low ? low - bit : bit > high ? bit - high : 0;
    }

    private void fill(long[] bits, int from, int to) {
        for (int bit = Math.max(0, from); bit <= Math.min(height - 1, to); bit++) {
            bits[bit >> 6] |= 1L << bit;
        }
    }

    /**
     * 列({@code x},{@code z})で橋が通れる高さ（足場を置くセルと体の2セルが空いている）。下が底まで空いている高さか、
     * いちばん上のブロックより上。浮いた島の下を潜る橋も、島の上空を渡る橋もある（実機のエンド: 小島の4ブロック下、
     * 島の頂上の12ブロック上を渡る橋がグラフから抜け、その先が目的地へ繋がらなかった）。
     */
    private long[] passable(CellSource cells, int x, int z) {
        long[] pass = new long[words];
        fill(pass, 0, voidBelow(cells, x, z) - 2);
        fill(pass, voidAbove(cells, x, z) + 1, height - 2);
        return pass;
    }

    /**
     * 1列が覚えておく岸の高さの数。近い岸から残す。
     *
     * <p>絞らないと、奈落を渡るうちに後ろの象限にある島の高さが次々に運ばれて殻が膨らむ（高さを区間で持った試作では、
     * 実機のエンドの窓のグラフが2.3倍の約530MBになった。8個なら+13〜37%）。
     */
    private static final int MAX_SHORE_HEIGHTS = 8;

    /** 橋が届く後ろの岸が無い。 */
    private static final int[] NO_SHORE = new int[0];

    private static int shoreEntry(int height, int distance) {
        return distance << 16 | height;
    }

    private static int shoreHeight(int entry) {
        return entry & 0xFFFF;
    }

    private static int shoreDistance(int entry) {
        return entry >>> 16;
    }

    /**
     * 列({@code x},{@code z})へ、目的地から遠い側（各軸で後ろ）の隣の列から橋で来られる高さと、その高さの岸からの橋の長さ。
     * この列で通れない高さ（{@code pass}の外）と、橋の長さが{@code reach}を超える高さは落とす。
     */
    private int[] behind(CellSource cells, int x, int z, int goalX, int goalZ, int reach, long[] pass) {
        int sx = Integer.signum(goalX - x);
        int sz = Integer.signum(goalZ - z);
        int[] merged = new int[2 * MAX_SHORE_HEIGHTS];
        int count = 0;
        for (int axis = 0; axis < 2; axis++) {
            if ((axis == 0 ? sx : sz) == 0) {
                continue;
            }
            int[] shore = shore(cells, axis == 0 ? x - sx : x, axis == 0 ? z : z - sz, goalX, goalZ, reach);
            for (int entry : shore) {
                int bit = shoreHeight(entry);
                int distance = shoreDistance(entry) + 1;
                if (distance > reach || (pass[bit >> 6] >>> bit & 1L) == 0) {
                    continue;
                }
                count = addShore(merged, count, bit, distance);
            }
        }
        return nearest(merged, count);
    }

    /** 同じ高さは短い方を残して足す。 */
    private static int addShore(int[] entries, int count, int bit, int distance) {
        for (int i = 0; i < count; i++) {
            if (shoreHeight(entries[i]) == bit) {
                if (shoreDistance(entries[i]) > distance) {
                    entries[i] = shoreEntry(bit, distance);
                }
                return count;
            }
        }
        entries[count] = shoreEntry(bit, distance);
        return count + 1;
    }

    /** 近い岸から{@link #MAX_SHORE_HEIGHTS}個。 */
    private static int[] nearest(int[] entries, int count) {
        if (count == 0) {
            return NO_SHORE;
        }
        int[] sorted = Arrays.copyOf(entries, count);
        // 距離が上位ビットなので、そのまま並べれば近い順
        Arrays.sort(sorted);
        return sorted.length > MAX_SHORE_HEIGHTS ? Arrays.copyOf(sorted, MAX_SHORE_HEIGHTS) : sorted;
    }

    /**
     * 列({@code x},{@code z})を橋の岸として見たときの高さと橋の長さ。立てる高さは長さ0、下か上が空いていれば、後ろから通り抜けて
     * 来られる高さ（{@link #behind}）もその長さのまま足す。後ろの列から順に引き継ぐので、L字に折れる橋の岸も列ごとに隣を2つ見るだけで分かる
     * ——探すたびに軸の上を辿るとL字を拾えず、拾おうとすれば後ろの象限全体（約4,600列）を読むことになる。
     */
    private int[] shore(CellSource cells, int x, int z, int goalX, int goalZ, int reach) {
        if (!cells.isInBounds(x, minY, z)) {
            return NO_SHORE;
        }
        long key = ((long) Math.floorDiv(x, 16) << 32) | (Math.floorDiv(z, 16) & 0xFFFFFFFFL);
        AtomicReferenceArray<int[]> memo = shores.computeIfAbsent(key, k -> new AtomicReferenceArray<>(256));
        int column = Math.floorMod(x, 16) + Math.floorMod(z, 16) * 16;
        int[] known = memo.get(column);
        if (known != null) {
            return known;
        }
        int[] through = voidBelow(cells, x, z) >= 3 ? behind(cells, x, z, goalX, goalZ, reach, passable(cells, x, z))
                : NO_SHORE;
        int[] entries = new int[MAX_SHORE_HEIGHTS * 2 + height];
        int count = 0;
        for (int w = 0; w < words; w++) {
            long bits = word(cells, x, z, w);
            while (bits != 0) {
                count = addShore(entries, count, (w << 6) + Long.numberOfTrailingZeros(bits), 0);
                bits &= bits - 1;
            }
        }
        for (int entry : through) {
            count = addShore(entries, count, shoreHeight(entry), shoreDistance(entry));
        }
        int[] result = nearest(entries, count);
        memo.set(column, result);
        return result;
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

    /**
     * ({@code x},{@code z})から({@code dx},{@code dz})の向きに最初に当たる岸の列の、立てる高さの最小・最大ビット。
     *
     * <p>{@code ceiling}は、この列で橋が通れるいちばん上のビット。立てる所がすべてそれより上で、自分も下が空いている列
     * （奈落に浮いた小島・自分で架けた橋）は岸に数えずその先を探す——そこの下を潜る橋の高さを決めるのは、さらに先の岸
     * （実機のエンド: 小島の上の高さ66を岸にして、4ブロック下の高さ60を通る橋が抜けていた）。
     */
    private int @Nullable [] nearestShoreSpan(CellSource cells, int x, int z, int dx, int dz, int reach,
                                              int ceiling) {
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
            if (low <= high && low > ceiling + SectionShell.VERTICAL && voidBelow(cells, px, pz) >= 3) {
                continue;
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
        shores.clear();
    }

    /** 読み込みの途中だったチャンクを忘れる。読める範囲が変わる前（組み直しの頭）に呼ぶ。 */
    public void forgetIncomplete() {
        incomplete.clear();
        shores.clear();
    }

    public void clear() {
        chunks.clear();
        incomplete.clear();
        shores.clear();
    }

    private long[] scan(CellSource cells, int chunkX, int chunkZ, long key) {
        // 末尾の4語は列ごとの「底まで空っぽ」の印、その後の256語は列ごとの溶岩の面（lavaSurfaceの値+1）、
        // さらに256語は列ごとの底から続く空っぽの数（voidBelow）、256語はいちばん上のブロックの1つ上（voidAbove）
        long[] bits = new long[256 * words + 4 + 256 + 256 + 256];
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
                int voidBelow = !CellData.passableEmpty(below) ? 0 : !CellData.passableEmpty(feet) ? 1 : -1;
                int voidAbove = !CellData.passableEmpty(feet) ? 2 : !CellData.passableEmpty(below) ? 1 : 0;
                int lavaTop = -1;
                for (int y = minY + 1; y < minY + height - 1; y++) {
                    long head = cells.cell(x, y + 1, z);
                    empty &= CellData.passableEmpty(head);
                    if (!CellData.passableEmpty(head)) {
                        if (voidBelow < 0) {
                            voidBelow = y + 1 - minY;
                        }
                        voidAbove = y + 2 - minY;
                    }
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
                bits[256 * words + 4 + 256 + column] = voidBelow < 0 ? height : voidBelow;
                bits[256 * words + 4 + 512 + column] = voidAbove;
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
