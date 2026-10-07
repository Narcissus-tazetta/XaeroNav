package net.prason.xaeronav.pathfinding.navgraph;

import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatMaps;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * 組んだ窓の値を、窓が動いたあとも覚えておき、窓の外の推定の下限にする（LSS-LRTA*の学習を、窓全体・ノードの細かさで）。
 *
 * <p>覚えないと、窓が離れた所は元の過小な推定（ネザーの3D粗層は真値の0.3〜0.6倍）に戻る。近づいた側の出口だけが実費に置き換わるので、
 * 反対側の出口が安く見えて2つの出口を交互に指し、同じ一帯を行き来する（実測: ネザー長距離で1.841→1.189倍、踏み直し367手→0）。
 * 窓の値は「窓の中を辿った実費＋縁の外の推定」なので、推定が真値以下なら覚えた値も真値以下に留まる。
 *
 * <p>窓の全ノードを、水平{@link #STEP}おきの格子の上で覚える。粗い格子（4×4×8）の最小で覚えると壁の向こうの抜け道の安い値に
 * 埋もれ、道筋の点だけを覚えるとくぼみを1点ずつしか埋められずにうろつく。
 *
 * <p><b>組んだ窓が使う値は後から変えない。</b>窓の値と縁の推定が食い違うと{@link WindowField#descend}が下りきれない。
 * 覚えるたびに表を作り直し、{@link #over}は呼んだ時点の表を掴む。{@link #record}・{@link #clear}は1本のスレッドから呼ぶこと。
 */
public final class LearnedFar {

    /** 覚える格子の水平の間隔。引くときは周りの4本の列を見る。 */
    static final int STEP = 4;

    /** 引くときに見る上下の幅。床の高さが格子の列ごとに少しずつ違っても拾えるように。 */
    private static final int REACH_Y = 2;

    private volatile Long2FloatOpenHashMap values = new Long2FloatOpenHashMap();

    /**
     * 窓の中心から窓の半径のこの倍より遠い点は忘れる。行き来は窓2つぶんの幅に収まり、全部覚えると長距離で260万点・
     * 1回の記録に約100msかかっていた。
     */
    private static final int RETAIN_WINDOWS = 2;

    /** 窓の値を覚える。同じ点を前にも覚えていたら大きい方を残す。 */
    public void record(WindowField field) {
        int reach = field.radius() * RETAIN_WINDOWS;
        Long2FloatOpenHashMap current = values;
        // 大きさを決めずに作ると、別の表を順になめて詰め直すときに値が偏り、1回の記録に数秒かかる（実測377秒/90回）
        Long2FloatOpenHashMap next = new Long2FloatOpenHashMap(current.size() + field.nodes() / (STEP * STEP));
        for (Long2FloatMap.Entry entry : Long2FloatMaps.fastIterable(current)) {
            long key = entry.getLongKey();
            if (Math.abs(BlockPos.getX(key) - field.centerX()) <= reach
                    && Math.abs(BlockPos.getZ(key) - field.centerZ()) <= reach) {
                next.put(key, entry.getFloatValue());
            }
        }
        field.forEachLatticeValue(STEP, (x, y, z, value) -> {
            long key = BlockPos.asLong(x, y, z);
            float stored = next.getOrDefault(key, Float.NaN);
            if (Float.isNaN(stored) || value > stored) {
                next.put(key, (float) value);
            }
        });
        values = next;
    }

    public void clear() {
        values = new Long2FloatOpenHashMap();
    }

    public int size() {
        return values.size();
    }

    /** {@code base}と覚えた値の大きい方。{@code base}が分からない点は分からないまま。 */
    public FarField over(FarField base) {
        Long2FloatOpenHashMap frozen = values;
        if (frozen.isEmpty()) {
            return base;
        }
        return new FarField() {
            @Override
            public double at(int x, int y, int z) {
                double estimate = base.at(x, y, z);
                return Double.isFinite(estimate) ? Math.max(estimate, lookup(frozen, x, y, z)) : estimate;
            }

            @Override
            public boolean onlyWhenGoalOutside() {
                return base.onlyWhenGoalOutside();
            }

            @Override
            public FarField whenGoalInside() {
                FarField inside = base.whenGoalInside();
                return inside == base ? this : inside;
            }
        };
    }

    /** 周りの格子の列（水平に1目、上下{@link #REACH_Y}以内）で覚えた値のうち小さい方。無ければ0。 */
    private static double lookup(Long2FloatOpenHashMap values, int x, int y, int z) {
        int baseX = Math.floorDiv(x, STEP) * STEP;
        int baseZ = Math.floorDiv(z, STEP) * STEP;
        float best = Float.POSITIVE_INFINITY;
        for (int gx = baseX; gx <= baseX + STEP; gx += STEP) {
            for (int gz = baseZ; gz <= baseZ + STEP; gz += STEP) {
                for (int gy = y - REACH_Y; gy <= y + REACH_Y; gy++) {
                    float value = values.getOrDefault(BlockPos.asLong(gx, gy, gz), Float.POSITIVE_INFINITY);
                    if (value < best) {
                        best = value;
                    }
                }
            }
        }
        return Float.isFinite(best) ? best : 0.0;
    }
}
