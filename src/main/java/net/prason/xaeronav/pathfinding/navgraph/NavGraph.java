package net.prason.xaeronav.pathfinding.navgraph;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellSource;

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
    public long[] missingSections(int centerX, int centerZ, int radius) {
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
            int now = readableColumns(sx, sz, centerX, centerZ, radius);
            // 読める列が少し増えるたびに組み直すと、窓が動くたびに縁の帯を丸ごと組み直すことになる
            if (now >= FULLY_READABLE || now - readable >= FULLY_READABLE / REBUILD_STEPS) {
                missing.add(key);
            }
        });
        return missing.toLongArray();
    }

    /**
     * セクションの列とその外を読む幅のうち、中心から水平{@code radius}の正方形に入る列の数。
     * 全部入っていれば{@link #FULLY_READABLE}。
     */
    static int readableColumns(int sectionX, int sectionZ, int centerX, int centerZ, int radius) {
        int minX = Math.max(sectionX * SectionMoves.SIZE - READ_MARGIN, centerX - radius);
        int maxX = Math.min((sectionX + 1) * SectionMoves.SIZE - 1 + READ_MARGIN, centerX + radius);
        int minZ = Math.max(sectionZ * SectionMoves.SIZE - READ_MARGIN, centerZ - radius);
        int maxZ = Math.min((sectionZ + 1) * SectionMoves.SIZE - 1 + READ_MARGIN, centerZ + radius);
        return Math.max(0, maxX - minX + 1) * Math.max(0, maxZ - minZ + 1);
    }

    /** 仮のセクションを組み直す刻み。読める列がこの割合ぶん増えるか、全部読めるようになったら組み直す。 */
    private static final int REBUILD_STEPS = 4;

    static final int FULLY_READABLE = (SectionMoves.SIZE + 2 * READ_MARGIN) * (SectionMoves.SIZE + 2 * READ_MARGIN);

    /**
     * {@code keys[from..to)}のセクションを組む。{@code cells}はこの呼び出しのスレッドが占有するビュー。
     * 読める範囲（{@code loaded*}の正方形）から{@link #READ_MARGIN}以内のセクションは、周りが欠けた仮のものとして覚える。
     *
     * @return 打ち切られたら{@code false}（組み終えたセクションは覚えている）
     */
    public boolean build(CellSource cells, long[] keys, int from, int to, int loadedCenterX, int loadedCenterZ,
                         int loadedRadius, BooleanSupplier cancelled) {
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
            SectionEdges edges = SectionEdges.build(cells, shell, sx, sy, sz, goal.getX(), goal.getZ(), cancelled);
            if (edges == null) {
                return false;
            }
            sections.put(key, edges);
            int readable = readableColumns(sx, sz, loadedCenterX, loadedCenterZ, loadedRadius);
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

    /** 覚えている辺の総数。 */
    public long edgeCount() {
        long total = 0;
        for (SectionEdges edges : sections.values()) {
            total += edges.size();
        }
        return total;
    }

    /**
     * 窓の中を逆Dijkstraしてガイドを作る。窓の外・まだ組んでいないセクションへ出る辺の先には{@code far}の値を置く。
     *
     * @return 打ち切られたら{@code null}
     */
    public synchronized @Nullable WindowField field(int centerX, int centerZ, int radius, FarField far,
                                                    BooleanSupplier cancelled) {
        return WindowField.build(this, fieldBuffers, centerX, centerZ, radius, far, cancelled);
    }

    /** {@link #field}の組み立て用の配列。{@code field}は同期しているので1組でよい。 */
    private final WindowField.Buffers fieldBuffers = new WindowField.Buffers();

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
