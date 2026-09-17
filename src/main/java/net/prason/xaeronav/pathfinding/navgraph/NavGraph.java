package net.prason.xaeronav.pathfinding.navgraph;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.util.MonotonicTime;

/**
 * 1つの目的地に対する航法グラフ。読み込み済みの範囲をセクション（16³）ごとに、探索と同じ移動生成で組んで覚える。
 *
 * <p><b>目的地ごとに作り直すこと。</b>奈落の上の橋は目的地へ近づく向きにしか張られないので
 * （{@code BuildMoves#addBridge}）、辺そのものが目的地に依存する。
 *
 * <p>セクションの構築（{@link #build}）はワーカースレッドから並行に呼んでよい。ただし{@link CellSource}は
 * 呼び出しごとに、そのスレッドが占有するものを渡すこと。
 */
public final class NavGraph {

    private final BlockPos goal;
    private final int minSectionY;
    private final int maxSectionY;
    private final NaturalColumns naturals;
    private final MoveTable moves = new MoveTable();
    private final ConcurrentHashMap<Long, SectionEdges> sections = new ConcurrentHashMap<>();

    /**
     * 読み込み範囲の縁で、周りが欠けたまま組んだセクションと、組んだときに読めていた列の数。
     * 今の読み込み範囲の方が多く読めるなら組み直す。
     *
     * <p><b>縁を組まずに空けておいてはいけない。</b>空けた帯ではガイドが外の値（無ければ幾何下限）へ落ち、
     * 内側の正確な値より低くなって探索と部分経路の終点選びを吸い寄せる（実測: エンド1.009→1.068倍）。
     *
     * <p><b>「完全に読めるまで組み直さない」でもいけない。</b>窓が近づいて半分読めるようになったセクションが、
     * 端の数列しか読めなかった頃の辺のまま残り、目的地の周りに穴が空く（実測: 本物48に対して1308）。
     */
    private final ConcurrentHashMap<Long, Integer> provisional = new ConcurrentHashMap<>();

    /** セクションの外を読む幅（ブロック）。殻の水平幅と、移動が隣のセルを読む数ブロック。 */
    static final int READ_MARGIN = SectionShell.HORIZONTAL + 4;

    /** @param minY グラフに入れる最小の高さ（世界の底）。{@code maxY}まで含む */
    public NavGraph(BlockPos goal, int minY, int maxY) {
        this.goal = goal.immutable();
        this.minSectionY = Math.floorDiv(minY, SectionMoves.SIZE);
        this.maxSectionY = Math.floorDiv(maxY, SectionMoves.SIZE);
        this.naturals = new NaturalColumns(minY, maxY);
    }

    public BlockPos goal() {
        return goal;
    }

    static long key(int sectionX, int sectionY, int sectionZ) {
        return BlockPos.asLong(sectionX, sectionY, sectionZ);
    }

    /**
     * 窓（中心から水平{@code radius}の正方形）に掛かるセクションのうち、組む必要があるものの鍵。
     * まだ組んでいないものと、周りが欠けたまま組んだが今の方が多く読める（{@link #readableColumns}）もの。
     */
    public long[] missingSections(int centerX, int centerZ, int radius, LoadedArea loaded) {
        LongArrayList missing = new LongArrayList();
        forEachWindowSection(centerX, centerZ, radius, (sx, sy, sz) -> {
            long key = key(sx, sy, sz);
            if (!sections.containsKey(key)) {
                missing.add(key);
                return;
            }
            Integer readable = provisional.get(key);
            if (readable == null) {
                return;
            }
            int now = readableColumns(sx, sz, loaded);
            // 読める列が少し増えるたびに組み直すと、窓が動くたびに縁の帯を丸ごと組み直すことになる
            if (now >= FULLY_READABLE || now - readable >= FULLY_READABLE / REBUILD_STEPS) {
                missing.add(key);
            }
        });
        return missing.toLongArray();
    }

    /** セクションの列とその外を読む幅のうち、読める列の数。全部読めれば{@link #FULLY_READABLE}。 */
    static int readableColumns(int sectionX, int sectionZ, LoadedArea loaded) {
        return loaded.columns(sectionX * SectionMoves.SIZE - READ_MARGIN,
                (sectionX + 1) * SectionMoves.SIZE - 1 + READ_MARGIN, sectionZ * SectionMoves.SIZE - READ_MARGIN,
                (sectionZ + 1) * SectionMoves.SIZE - 1 + READ_MARGIN);
    }

    /** 仮のセクションを組み直す刻み。読める列がこの割合ぶん増えるか、全部読めるようになったら組み直す。 */
    private static final int REBUILD_STEPS = 4;

    static final int FULLY_READABLE = (SectionMoves.SIZE + 2 * READ_MARGIN) * (SectionMoves.SIZE + 2 * READ_MARGIN);

    /**
     * {@code keys[from..to)}のセクションを組む。{@code cells}はこの呼び出しのスレッドが占有するビュー。
     * 周り{@link #READ_MARGIN}の列が全部は読めないセクションは、周りが欠けた仮のものとして覚える。
     *
     * @return 打ち切られたら{@code false}（組み終えたセクションは覚えている）
     */
    public boolean build(CellSource cells, long[] keys, int from, int to, LoadedArea loaded,
                         BooleanSupplier cancelled) {
        SectionShell shell = null;
        int shellX = Integer.MIN_VALUE;
        int shellZ = Integer.MIN_VALUE;
        for (int i = from; i < to; i++) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            long key = keys[i];
            int sx = BlockPos.getX(key);
            int sy = BlockPos.getY(key);
            int sz = BlockPos.getZ(key);
            // 殻は列（sx, sz）ごとに同じ。鍵は列ごとに並んでいることが多いので直前のものを使い回す
            if (shell == null || sx != shellX || sz != shellZ) {
                shell = SectionShell.of(naturals, cells, sx, sz);
                shellX = sx;
                shellZ = sz;
            }
            SectionEdges edges = SectionEdges.build(cells, shell, moves, sx, sy, sz, goal.getX(), goal.getZ(), cancelled);
            if (edges == null) {
                return false;
            }
            sections.put(key, edges);
            int readable = readableColumns(sx, sz, loaded);
            if (readable >= FULLY_READABLE) {
                provisional.remove(key);
            } else {
                provisional.put(key, readable);
            }
        }
        return true;
    }

    /**
     * チャンクの中身が変わった。そのチャンクと周りのチャンクの列のセクションを捨てる——殻は水平
     * {@link SectionShell#HORIZONTAL}ブロック先の立てる点で決まり、移動もセクションの外を読むので、
     * 変わったチャンクだけを捨てても隣の辺が古いまま残る。
     */
    public void invalidateChunk(int chunkX, int chunkZ) {
        naturals.invalidateChunk(chunkX, chunkZ);
        for (int sx = chunkX - 1; sx <= chunkX + 1; sx++) {
            for (int sz = chunkZ - 1; sz <= chunkZ + 1; sz++) {
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    sections.remove(key(sx, sy, sz));
                    provisional.remove(key(sx, sy, sz));
                }
            }
        }
    }

    /** 中心から水平{@code radius}より遠いセクションを捨てる。 */
    public void retainWithin(int centerX, int centerZ, int radius) {
        int minX = Math.floorDiv(centerX - radius, SectionMoves.SIZE);
        int maxX = Math.floorDiv(centerX + radius, SectionMoves.SIZE);
        int minZ = Math.floorDiv(centerZ - radius, SectionMoves.SIZE);
        int maxZ = Math.floorDiv(centerZ + radius, SectionMoves.SIZE);
        provisional.keySet().removeIf(key -> {
            int sx = BlockPos.getX(key);
            int sz = BlockPos.getZ(key);
            return sx < minX || sx > maxX || sz < minZ || sz > maxZ;
        });
        sections.keySet().removeIf(key -> {
            int sx = BlockPos.getX(key);
            int sz = BlockPos.getZ(key);
            return sx < minX || sx > maxX || sz < minZ || sz > maxZ;
        });
    }

    /** 覚えているセクションと、ガイドの組み立て用の配列のおおよそのバイト数。 */
    public long bytes() {
        long total;
        synchronized (this) {
            total = fieldBuffers.bytes();
        }
        for (SectionEdges edges : sections.values()) {
            total += edges == SectionEdges.EMPTY ? 16 : edges.bytes();
        }
        return total;
    }

    /** 覚えている辺の総数。 */
    public long edgeCount() {
        long total = 0;
        for (SectionEdges edges : sections.values()) {
            total += edges.size();
        }
        return total;
    }

    /** {@link #refresh}の結果と、実機のログに出す内訳。 */
    public record Refreshed(WindowField field, int sectionsBuilt, long buildMillis) {
    }

    /** 窓からこれより離れたセクションは捨てる。窓が少し戻っただけで縁の帯を組み直さずに済む幅。 */
    static final int RETAIN_MARGIN = 32;

    /** 1つのビューで続けて組むセクションの数。セルを覚えるビューを渡されても、窓全体ぶん膨らまないように小分けで取り直す。 */
    private static final int SECTIONS_PER_VIEW = 16;

    /**
     * 窓の中の足りないセクションを並列に組み、ガイドを作り直す。
     *
     * @param views    呼ぶたびに、そのスレッドが占有してよいビューを返す
     * @param pool     {@code null}なら呼び出し元のスレッドだけで組む。プールのスレッドから呼んではいけない
     * @param workers  呼び出し元を含めた並列度
     * @return 打ち切られたら{@code null}
     */
    public @Nullable Refreshed refresh(Supplier<CellSource> views, int centerX, int centerZ, int radius,
                                       LoadedArea loaded, FarField far, @Nullable Executor pool, int workers,
                                       BooleanSupplier cancelled) {
        long began = MonotonicTime.millis();
        retainWithin(centerX, centerZ, radius + RETAIN_MARGIN);
        long[] missing = missingSections(centerX, centerZ, radius, loaded);
        Parallel parallel = new Parallel(pool, workers);
        boolean built = parallel.forEach(missing.length, SECTIONS_PER_VIEW, cancelled,
                (from, to) -> build(views.get(), missing, from, to, loaded, cancelled));
        if (!built) {
            return null;
        }
        long buildMillis = MonotonicTime.millis() - began;
        WindowField field = field(centerX, centerZ, radius, far, parallel, cancelled);
        return field == null ? null : new Refreshed(field, missing.length, buildMillis);
    }

    /**
     * 窓の中を逆Dijkstraしてガイドを作る。窓の外・まだ組んでいないセクションへ出る辺の先には{@code far}の値を置く。
     *
     * @return 打ち切られたら{@code null}
     */
    public @Nullable WindowField field(int centerX, int centerZ, int radius, FarField far,
                                       BooleanSupplier cancelled) {
        return field(centerX, centerZ, radius, far, Parallel.INLINE, cancelled);
    }

    private synchronized @Nullable WindowField field(int centerX, int centerZ, int radius, FarField far,
                                                     Parallel parallel, BooleanSupplier cancelled) {
        return WindowField.build(this, fieldBuffers, centerX, centerZ, radius, far, parallel, cancelled);
    }

    /** {@link #field}の組み立て用の配列。{@code field}は同期しているので1組でよい。 */
    private final WindowField.Buffers fieldBuffers = new WindowField.Buffers();

    MoveTable moves() {
        return moves;
    }

    @Nullable SectionEdges section(long key) {
        return sections.get(key);
    }

    @FunctionalInterface
    interface SectionVisitor {
        void visit(int sectionX, int sectionY, int sectionZ);
    }

    void forEachWindowSection(int centerX, int centerZ, int radius, SectionVisitor visitor) {
        int minX = Math.floorDiv(centerX - radius, SectionMoves.SIZE);
        int maxX = Math.floorDiv(centerX + radius, SectionMoves.SIZE);
        int minZ = Math.floorDiv(centerZ - radius, SectionMoves.SIZE);
        int maxZ = Math.floorDiv(centerZ + radius, SectionMoves.SIZE);
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sz = minZ; sz <= maxZ; sz++) {
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    visitor.visit(sx, sy, sz);
                }
            }
        }
    }
}
