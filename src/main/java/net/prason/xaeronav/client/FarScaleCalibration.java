package net.prason.xaeronav.client;

import java.util.ArrayDeque;
import java.util.Iterator;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.navgraph.WindowField;
import net.prason.xaeronav.util.MathSupport;

/**
 * 到着時間の表示で、ガイドの「窓の外の推定」に掛ける倍率を、この道のりで実際に見た値から学ぶ。
 *
 * <p>窓の外の推定は場所ごとにずれ方が違い、ネザーでは真値の0.45〜0.61倍しかない。そのまま足すと、窓が進むたびに
 * 推定だった区間が実費に置き換わり、歩いているのに到着時間が延びていく。
 *
 * <p>学び方: 組み上がるたびに、窓の縁で読んだ外の推定{@code O}（点{@code X}）を覚えておく。後の窓で{@code X}が窓の奥に
 * 入ったら、そこからの値は「窓の中を辿った実費{@code I}＋新しい縁の推定{@code O'}」になる。真値を{@code k}倍の推定と置くと
 * {@code k·O = I + k·O'}なので、{@code k = I / (O - O')}。1回ごとの比は揺れるので、和の比を取る。
 *
 * <p><b>経路探索のガイドには掛けない。</b>探索に掛けると質が変わる（自己較正は模型で試して不採用）。表示だけに使う。
 * <b>段取りの1本だけが{@link #observe}・{@link #reset}を呼ぶ。</b>
 */
final class FarScaleCalibration {

    /** 見本が無いうちの倍率。推定を信じる。 */
    private static final double PRIOR_SCALE = 1.0;
    /**
     * 事前の倍率を、この値段（tick）ぶんの見本として最初に持たせる。最初の1回で倍率が大きく振れないように。
     * 実際の見本と一緒に減っていくので、歩くうちに効かなくなる。
     */
    private static final double PRIOR_TICKS = 200.0;
    /** 1回ごとに古い見本を減らす割合。地形が変わればずれ方も変わるので、最近の見本を重く見る。 */
    private static final double DECAY = 0.8;
    /** これより分母が小さい見本は捨てる。窓が少ししか進んでいないと、比が雑音だけで決まる。 */
    private static final double MIN_SAMPLE_TICKS = 40.0;
    /** 覚えておく点の数。目的地から外れて歩くと、覚えた点は窓の奥に入らないまま溜まる。 */
    private static final int MAX_PENDING = 16;
    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 4.0;

    private record Pending(BlockPos exit, double outside) {
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private double inside = PRIOR_SCALE * PRIOR_TICKS;
    private double predicted = PRIOR_TICKS;
    private volatile double scale = PRIOR_SCALE;

    /** 今の倍率。どのスレッドから読んでもよい。 */
    double scale() {
        return scale;
    }

    /** 組み上がった窓で、覚えていた点を答え合わせし、{@code center}から新しく点を覚える。 */
    void observe(WindowField field, BlockPos center) {
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending point = it.next();
            BlockPos exit = point.exit();
            if (!field.measuredInWindow(exit.getX(), exit.getZ())) {
                continue;
            }
            it.remove();
            WindowField.Descent descent = field.descend(exit.getX(), exit.getY(), exit.getZ());
            if (descent == null) {
                continue;
            }
            double denominator = point.outside() - descent.outside();
            if (denominator < MIN_SAMPLE_TICKS) {
                continue;
            }
            inside = inside * DECAY + descent.inside();
            predicted = predicted * DECAY + denominator;
            scale = MathSupport.clamp(inside / predicted, MIN_SCALE, MAX_SCALE);
        }
        WindowField.Descent descent = field.descend(center.getX(), center.getY(), center.getZ());
        if (descent == null || descent.reachedGoal()) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            pending.removeFirst();
        }
        pending.addLast(new Pending(descent.exit(), descent.outside()));
    }

    void reset() {
        pending.clear();
        inside = PRIOR_SCALE * PRIOR_TICKS;
        predicted = PRIOR_TICKS;
        scale = PRIOR_SCALE;
    }
}
