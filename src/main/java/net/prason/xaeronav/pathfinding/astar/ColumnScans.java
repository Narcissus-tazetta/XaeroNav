package net.prason.xaeronav.pathfinding.astar;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 足元より下へ向かう縦走査と、その結果の覚え書き。
 *
 * <p>1ノードを展開するたびに、4方向ぶんの「踏み出した先の下に何があるか」と、跳躍の
 * 「外したら溶岩か」を辿る。床がすぐ下にある地形なら1〜2マスで止まるが、<b>下が開けていると
 * {@link #SCAN_DEPTH}マス下まで辿る</b>——ジ・エンドの奈落やネザーの溶岩の海がその形で、
 * 実測では探索時間の23%がここに集まっていた（1ノードあたりのセル読みは236〜630回で、
 * その31〜47%がこの走査）。
 *
 * <p>隣り合うノードは同じ列を何度も辿り直す。そこで<b>読んだセルの分類だけを列ごとのビットに
 * 残し、次からは語単位で空気を飛ばす</b>。地形を先読みして表を作るのではなく、
 * 走査が実際に読んだ範囲だけを覚えるので、<b>浅い列では余分な読みが1回も増えない</b>——
 * 1列あたりの走査回数は現世53回に対しエンド3,500回・溶岩の海6,000回と2桁違うため、
 * 一律に列全体の索引を組むと現世では損になる。
 *
 * <p><b>探索範囲の外は覚えない。</b>{@link CellSource}が範囲外に何を返すかは実装ごとの約束で
 * （{@code WindowedCells}は窓の外を未ロードとして返しつつ{@code isInBounds}は真を返す）、
 * ここで先回りすると走査の意味が変わる。
 */
final class ColumnScans {

    /**
     * 下へ辿る最大の深さ（ブロック）。落下・設置・跳躍の判断はどれもこの深さで打ち切る。
     *
     * <p>層1の{@code LiveCoarseSampler}が同じ理由で使っている値に揃えてある。以前は32だったため、
     * 32マスを超える空洞の下にある溶岩を見逃し、溶岩の上へ跳躍を提示することがあった。
     *
     * <p><b>落下・設置と跳躍で値を分けてはいけない。</b>分けると、落下では見える深さの溶岩が
     * 跳躍では見えないという食い違いが起きる（実機で、深い割れ目の底の溶岩へ跳び損ねて死んだ）。
     * 「落ちても平気な高さ」で切るのも誤り——溶岩は深さに関わらず落ちれば死ぬ。
     */
    static final int SCAN_DEPTH = 128;

    /**
     * 読めるセルだけを辿った末に、何にも当たらなかった（＝底が無い）。
     *
     * <p>{@link #UNREADABLE_BELOW}と分けるのが要点——{@code ChunkView}は探索範囲外も未ロードも同じ
     * {@link CellData#ABSENT}で表すので、セルの値だけでは「奈落」と「分からない」を区別できない。
     */
    static final int NOTHING_BELOW = Integer.MIN_VALUE;

    /**
     * 未ロードチャンクに当たって走査が止まった（＝下に何があるか分からない）。
     *
     * <p>区別せずに「読めなかったら諦める」としていた頃は、<b>奈落の上に橋の辺が一本も
     * 生成されなかった</b>（ジ・エンドの島間で経路が岸で切れる正体）。
     */
    static final int UNREADABLE_BELOW = Integer.MIN_VALUE + 1;

    private static final int KNOWN = 0;
    /** 空気ではない（{@code passableEmpty}でない）。未ロード・範囲外もここに入る。 */
    private static final int BLOCKER = 1;
    private static final int PRESENT = 2;
    private static final int STANDABLE = 3;
    private static final int LAVA = 4;
    private static final int PLANES = 5;

    private final CellSource view;
    private final Long2ObjectOpenHashMap<long[]> columns = new Long2ObjectOpenHashMap<>();
    private final int minY;
    private final int maxY;
    private final int words;

    ColumnScans(CellSource view) {
        this.view = view;
        this.minY = view.bounds().minY();
        this.maxY = view.bounds().maxY();
        this.words = ((maxY - minY) >> 6) + 1;
    }

    /**
     * {@code topY}から下へ、空気ではない最初のセルのYを返す。水・地面・梯子のどれで止まったかは
     * 呼び出し側がそのセルを見て判断する。
     */
    int firstNonAirBelow(int x, int topY, int z) {
        int lowestY = topY - SCAN_DEPTH + 1;
        long[] column = null;
        int y = topY;
        while (y >= lowestY) {
            if (y < minY || y > maxY) {
                long cell = view.cell(x, y, z);
                if (CellData.passableEmpty(cell)) {
                    y--;
                    continue;
                }
                if (CellData.present(cell)) {
                    return y;
                }
                return view.isInBounds(x, y, z) ? UNREADABLE_BELOW : NOTHING_BELOW;
            }
            if (column == null) {
                column = column(x, z);
            }
            int index = y - minY;
            if (!bit(column, KNOWN, index)) {
                remember(column, index, view.cell(x, y, z));
            }
            if (bit(column, BLOCKER, index)) {
                return bit(column, PRESENT, index) ? y
                        : view.isInBounds(x, y, z) ? UNREADABLE_BELOW : NOTHING_BELOW;
            }
            y = skipDown(column, index, Math.max(lowestY, minY), false);
        }
        return NOTHING_BELOW;
    }

    /**
     * 足元から{@link #SCAN_DEPTH}マス下までに溶岩があるか、それとも見通せないか。
     *
     * <p>奈落（読めるセルだけを辿って底に当たらない）は{@code false}を返す——落ちれば死ぬのは
     * 溶岩と同じだが、そちらは{@code PathSafetyChecker#assessJumpRisk}が
     * {@link PathRisk#VOID_BELOW}で警告する担当になっている。ここで一律に禁止すると、
     * ジ・エンドでは全ての隙間が奈落の上なので跳ぶ移動が丸ごと消える。
     */
    boolean lavaOrUnknownBelow(int x, int y, int z) {
        int lowestY = y - SCAN_DEPTH;
        long[] column = null;
        int cellY = y - 1;
        while (cellY >= lowestY) {
            if (cellY < minY || cellY > maxY) {
                long cell = view.cell(x, cellY, z);
                if (CellData.lava(cell)) {
                    return true;
                }
                if (CellData.standable(cell)) {
                    return false;
                }
                if (!CellData.present(cell)) {
                    return view.isInBounds(x, cellY, z);
                }
                cellY--;
                continue;
            }
            if (column == null) {
                column = column(x, z);
            }
            int index = cellY - minY;
            if (!bit(column, KNOWN, index)) {
                remember(column, index, view.cell(x, cellY, z));
            }
            if (bit(column, LAVA, index)) {
                return true;
            }
            if (bit(column, STANDABLE, index)) {
                return false;
            }
            if (!bit(column, PRESENT, index)) {
                return view.isInBounds(x, cellY, z);
            }
            cellY = skipDown(column, index, Math.max(lowestY, minY), true);
        }
        return false;
    }

    /**
     * {@code index}のすぐ下から{@code lowestY}までを語単位で飛ばし、次に見るべきセルのYを返す。
     * 途中が全部「見る必要のない既知のセル」なら{@code lowestY - 1}を返す（＝走査の終わり）。
     */
    private int skipDown(long[] column, int index, int lowestY, boolean lavaScan) {
        int lowIndex = lowestY - minY;
        int from = index - 1;
        if (from < lowIndex) {
            return lowestY - 1;
        }
        int topWord = from >> 6;
        for (int word = topWord; word >= (lowIndex >> 6); word--) {
            long stops = stopMask(column, word, lavaScan);
            if (word == topWord) {
                int top = from & 63;
                stops &= top == 63 ? -1L : (1L << (top + 1)) - 1;
            }
            if (stops != 0L) {
                int found = (word << 6) + (63 - Long.numberOfLeadingZeros(stops));
                return found >= lowIndex ? found + minY : lowestY - 1;
            }
        }
        return lowestY - 1;
    }

    /** その語のうち、走査が立ち止まるべきセル（まだ読んでいないセルも含む）。 */
    private long stopMask(long[] column, int word, boolean lavaScan) {
        if (lavaScan) {
            // 未読のセルはPRESENTが0なので、~PRESENTがそのまま「読み直し」も兼ねる
            return column[LAVA * words + word] | column[STANDABLE * words + word]
                    | ~column[PRESENT * words + word];
        }
        return column[BLOCKER * words + word] | ~column[KNOWN * words + word];
    }

    private long[] column(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        long[] column = columns.get(key);
        if (column == null) {
            column = new long[PLANES * words];
            columns.put(key, column);
        }
        return column;
    }

    private void remember(long[] column, int index, long cell) {
        set(column, KNOWN, index);
        if (!CellData.passableEmpty(cell)) {
            set(column, BLOCKER, index);
        }
        if (CellData.present(cell)) {
            set(column, PRESENT, index);
        }
        if (CellData.standable(cell)) {
            set(column, STANDABLE, index);
        }
        if (CellData.lava(cell)) {
            set(column, LAVA, index);
        }
    }

    private boolean bit(long[] column, int plane, int index) {
        return (column[plane * words + (index >> 6)] & (1L << (index & 63))) != 0L;
    }

    private void set(long[] column, int plane, int index) {
        column[plane * words + (index >> 6)] |= 1L << (index & 63);
    }
}
