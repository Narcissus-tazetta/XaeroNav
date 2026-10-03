package net.prason.xaeronav.pathfinding.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import net.minecraft.world.phys.Vec3;

/**
 * 継ぎ足しが<b>手前の経路へ戻ってきた</b>ときの切り落とし（歩行のV字の切り落としと同じ）。
 *
 * <p>末端の先が行き止まりだと、継ぎ足しは末端から来た道を戻って別の方角へ向かう。それをそのまま繋ぐと、
 * プレイヤーは末端まで行ってから引き返す（「東へ行ってから自分の所へ戻ってくる」線）。戻ってきた所で
 * 繋ぎ直せば、行って戻る区間ごと消える。探索し直さないので、引き直しのように行き止まりの間を
 * 往復することもない。
 */
public final class TurnBack {

    /** 継ぎ足しの点が、プレイヤーより先の経路にこれだけ近づいたら戻ってきたとみなす（ブロック）。 */
    private static final double RETURN_RADIUS_BLOCKS = 24.0;

    /** 末端の手前この長さは「戻ってきた」の判定から外す。継ぎ足しの出だしは当然そこに近い（ブロック）。 */
    private static final double TAIL_GRACE_BLOCKS = 48.0;

    private TurnBack() {
    }

    /** {@link #cut}の結果。{@code aheadKept}はプレイヤーから戻ってきた点まで、{@code rest}はそこから先。 */
    public record Cut(List<Vec3> aheadKept, List<Vec3> rest) {
    }

    /**
     * 継ぎ足しが手前の経路へ戻ってきていたら、戻ってきた所で繋ぎ直して<b>行って戻る区間を消す</b>。
     *
     * <p>繋ぎ目は、プレイヤーより先の経路のなるべく手前の点と、継ぎ足しのなるべく先の点の組で、
     * 間が飛べる（{@code clearLine}）もの。無ければ{@code null}。
     */
    public static Cut cut(List<Vec3> ahead, List<Vec3> extension, BiPredicate<Vec3, Vec3> clearLine) {
        if (ahead.size() < 2 || extension.size() < 2) {
            return null;
        }
        double total = 0.0;
        for (int i = 1; i < ahead.size(); i++) {
            total += ahead.get(i - 1).distanceTo(ahead.get(i));
        }
        double guarded = total - TAIL_GRACE_BLOCKS;
        if (guarded <= 0.0) {
            return null;
        }
        List<Vec3> aheadSamples = new ArrayList<>();
        List<Integer> aheadSegment = new ArrayList<>();
        aheadSamples.add(ahead.get(0));
        aheadSegment.add(0);
        double walked = 0.0;
        for (int i = 1; i < ahead.size() && walked < guarded; i++) {
            Vec3 a = ahead.get(i - 1);
            Vec3 b = ahead.get(i);
            int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 8.0));
            for (int k = 1; k <= steps && walked + a.distanceTo(b) * k / steps <= guarded; k++) {
                aheadSamples.add(a.add(b.subtract(a).scale(k / (double) steps)));
                aheadSegment.add(i - 1);
            }
            walked += a.distanceTo(b);
        }
        List<Vec3> extensionSamples = new ArrayList<>();
        List<Integer> extensionSegment = new ArrayList<>();
        for (int i = 1; i < extension.size(); i++) {
            Vec3 a = extension.get(i - 1);
            Vec3 b = extension.get(i);
            int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 8.0));
            for (int k = 1; k <= steps; k++) {
                extensionSamples.add(a.add(b.subtract(a).scale(k / (double) steps)));
                extensionSegment.add(i - 1);
            }
        }
        for (int ai = 0; ai < aheadSamples.size(); ai++) {
            Vec3 a = aheadSamples.get(ai);
            for (int ei = extensionSamples.size() - 1; ei >= 0; ei--) {
                Vec3 e = extensionSamples.get(ei);
                if (a.distanceTo(e) > RETURN_RADIUS_BLOCKS || !clearLine.test(a, e)) {
                    continue;
                }
                List<Vec3> kept = new ArrayList<>(ahead.subList(0, aheadSegment.get(ai) + 1));
                if (!kept.get(kept.size() - 1).equals(a)) {
                    kept.add(a);
                }
                List<Vec3> rest = new ArrayList<>();
                rest.add(e);
                rest.addAll(extension.subList(extensionSegment.get(ei) + 1, extension.size()));
                return new Cut(kept, rest);
            }
        }
        return null;
    }
}
