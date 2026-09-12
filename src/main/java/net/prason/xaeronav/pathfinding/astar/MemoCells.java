package net.prason.xaeronav.pathfinding.astar;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * 読んだセルを小さな立方体（ページ）の配列に覚えておく{@link CellSource}のラッパー。
 *
 * <p>探索は1ノードあたり197〜413回セルを読むが、触れる列は探索全体で2万本ほどしかない
 * ——ほとんどが<b>同じセルの読み直し</b>で、その1回ごとに数百万件のハッシュ表への
 * ランダムアクセスが走っていた（実測で探索時間の約50%）。座標から直に配列の添字を作れば、
 * 2回目以降の読みは表を引かずに済む。
 *
 * <p>ページを{@link #PAGE_BITS}辺の立方体にしてあるのは、探索範囲全体を一枚の配列で持つと
 * 実際に読まない場所まで確保してしまうから（ネザーの探索範囲は1億セルを超える）。
 * 読んだ場所の周りだけが確保される。
 *
 * <p>ページの表引きすら省くため、直近に使ったページを{@link #RECENT}枚だけ配列に留めておく。
 * <b>数枚では足りない</b>——縦走査は4マスごとに、移動の生成はx/z±1でページ境界をまたぐので、
 * 2枚だとネザーの計測が1815→2158msまで戻る。64枚にしても変わらないので16枚で頭打ち。
 *
 * <p><b>前提: 委譲先の{@code cell}は探索中ずっと同じ値を返す。</b>{@code ChunkView}も
 * {@code PlannedCellSource}も生成後にワールドを読み直さないのでこれは成り立つ。
 * 途中で地形が変わる{@code CellSource}を足すなら、ここを通してはいけない。
 *
 * <p><b>スレッド契約は委譲先と同じ</b>で、単一のワーカースレッドが占有する。
 */
final class MemoCells implements CellSource {

    /**
     * まだ読んでいないセルを表す番兵。上位32bitは掘削tick数のfloatビット列で、全ビットが立つ＝
     * 負のNaNになる値は生成されないため、実際のセルと衝突しない（{@code ChunkView}のセル
     * キャッシュが使っているのと同じ根拠）。{@code CellData#ABSENT}が0なので、0は使えない。
     */
    private static final long UNREAD = -1L;

    /**
     * ページ1辺の大きさ（2の冪の指数）。2＝4×4×4＝64セル。8×8×8にしても速さは計測誤差の内で、
     * 1ページあたりの確保が8倍になるだけだった。
     */
    private static final int PAGE_BITS = 2;
    private static final int PAGE_MASK = (1 << PAGE_BITS) - 1;
    private static final int PAGE_CELLS = 1 << (PAGE_BITS * 3);

    /** 直近のページを覚えておく枚数（2の冪の指数）。0にするとスロットの算出が64bitシフト＝無シフトになる。 */
    private static final int RECENT_BITS = 4;
    private static final int RECENT = 1 << RECENT_BITS;

    /** 黄金比から作る乗数。ページのキーは座標をビット詰めしただけで、下位ビットがYにしか動かない。 */
    private static final long MIX = 0x9E3779B97F4A7C15L;

    private final CellSource source;
    private final Long2ObjectOpenHashMap<long[]> pages = new Long2ObjectOpenHashMap<>();
    private final long[] recentKeys = new long[RECENT];
    private final long[][] recentPages = new long[RECENT][];

    MemoCells(CellSource source) {
        this.source = source;
    }

    @Override
    public long cell(int x, int y, int z) {
        long key = BlockPos.asLong(x >> PAGE_BITS, y >> PAGE_BITS, z >> PAGE_BITS);
        int slot = (int) ((key * MIX) >>> (64 - RECENT_BITS));
        long[] page = recentPages[slot];
        if (page == null || recentKeys[slot] != key) {
            page = pages.get(key);
            if (page == null) {
                page = new long[PAGE_CELLS];
                Arrays.fill(page, UNREAD);
                pages.put(key, page);
            }
            recentKeys[slot] = key;
            recentPages[slot] = page;
        }
        int index = ((y & PAGE_MASK) << (PAGE_BITS * 2))
                | ((x & PAGE_MASK) << PAGE_BITS)
                | (z & PAGE_MASK);
        long cached = page[index];
        if (cached != UNREAD) {
            return cached;
        }
        long computed = source.cell(x, y, z);
        page[index] = computed;
        return computed;
    }

    // ここから下は素通し。defaultメソッドも含めて全て書くこと——委譲先が上書きしている
    // default（surfacedYなど）を継承に任せると、包んだ瞬間に挙動が変わる。

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return source.isInBounds(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return source.bounds();
    }

    @Override
    public boolean canPlaceBlocks() {
        return source.canPlaceBlocks();
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return source.bridgingAllowedBySettings();
    }

    @Override
    public int placedBlockBudget() {
        return source.placedBlockBudget();
    }

    @Override
    public boolean jumpGapEnabled() {
        return source.jumpGapEnabled();
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return source.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return source.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return source.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return source.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return source.maxSubmergedTicks();
    }

    @Override
    public int maxFallDamagePoints() {
        return source.maxFallDamagePoints();
    }

    @Override
    public int fatalFallBlocks() {
        return source.fatalFallBlocks();
    }

    @Override
    public boolean avoidRiskyJumps() {
        return source.avoidRiskyJumps();
    }

    @Override
    public double minDescentTicksPerBlock() {
        return source.minDescentTicksPerBlock();
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return source.minDescentTicksPerBlock(maxFallDamagePoints);
    }

    @Override
    public boolean canMlgWaterBucket() {
        return source.canMlgWaterBucket();
    }

    @Override
    public boolean boatAvailable() {
        return source.boatAvailable();
    }

    @Override
    public boolean ridingBoat() {
        return source.ridingBoat();
    }

    @Override
    public int openSkyY(int x, int z) {
        return source.openSkyY(x, z);
    }

    @Override
    public int surfacedY(int x, int z) {
        return source.surfacedY(x, z);
    }
}
