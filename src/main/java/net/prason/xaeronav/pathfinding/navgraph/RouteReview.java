package net.prason.xaeronav.pathfinding.navgraph;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 引いてある経路を、組み直した航法グラフのガイドで見直す。
 *
 * <p>経路は窓の外を推定（3D粗層・層1・直線距離）で狙って引かれ、以後は末端から継ぎ足すだけで手前を見直さない。
 * 歩いて窓が進むと、推定だった所が正確になり「北へ行く方が近かった」と分かることがあるが、その時点の線は西へ伸びたまま残る
 * （実機のネザー）。ガイドの値はそこから目的地までの最小コストなので、線に沿った値段との差がそのまま遠回りの量になる。
 *
 * <p>見直すのは目的地が窓の中にあり、ガイドが外の推定を含まないときだけ。
 */
public final class RouteReview {

    /**
     * 窓の縁からこれより内側の点だけを比べる。縁の近くの値は窓の外の推定から来ていて、比べると推定の誤差を遠回りと取り違える。
     */
    private static final int EDGE_MARGIN_BLOCKS = 32;

    private RouteReview() {
    }

    /**
     * {@code steps[from..]}を辿ったときの、ガイドが知る最短に対する遠回りの量（tick）。比べられる点が無ければ0。
     *
     * <p>見るのは値がグラフのノードから直接引ける点だけ（{@link WindowField#exact}）。置いた・掘ったブロックの上など
     * グラフに無い点は近くの値から延ばした推定で、それを基準にすると線の側に無い遠回りを作り出す。
     *
     * @param start 比べる起点（プレイヤーの足元）
     * @param from  起点の次に踏むステップの添字
     */
    public static Detour detour(WindowField field, BlockPos start, List<PathStep> steps, int from) {
        int limit = field.radius() - EDGE_MARGIN_BLOCKS;
        BlockPos goal = field.goal();
        if (Math.abs(goal.getX() - field.centerX()) > limit || Math.abs(goal.getZ() - field.centerZ()) > limit) {
            // 目的地が窓の外なら、値は窓の縁に置いた外の推定から来る。推定のずれは場所ごとに違うので、差を取ると遠回りでない線を
            // 遠回りとする（実測: ネザーで3D粗層を外の推定にすると、始点の値が実際の最短全体より大きく、引き直して1.003→1.187倍）
            return Detour.NONE;
        }
        double startValue = field.exact(start.getX(), start.getY(), start.getZ());
        if (!Double.isFinite(startValue)) {
            return Detour.NONE;
        }
        double walked = 0.0;
        Detour worst = Detour.NONE;
        for (int i = from; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            BlockPos pos = step.pos();
            if (Math.abs(pos.getX() - field.centerX()) > limit || Math.abs(pos.getZ() - field.centerZ()) > limit) {
                break;
            }
            walked += step.cost();
            double value = field.exact(pos.getX(), pos.getY(), pos.getZ());
            if (Double.isFinite(value) && walked + value - startValue > worst.extraTicks()) {
                worst = new Detour(walked + value - startValue, walked);
            }
        }
        return worst;
    }

    /**
     * 遠回りの量。
     *
     * @param extraTicks  線に沿って{@code walkedTicks}ぶん進んでから最短で行くのに、最短より余計にかかる量
     * @param walkedTicks 比べた点までの線の値段
     */
    public record Detour(double extraTicks, double walkedTicks) {

        static final Detour NONE = new Detour(0.0, 0.0);

        /**
         * 引き直す価値があるか。小さな差で引き直すと、ガイドと探索の細かな食い違い（水中の割増の見積もりなど）で
         * 歩くたびに線が描き変わる。
         */
        public boolean worthReplanning(double minExtraTicks, double minExtraRatio) {
            return extraTicks > minExtraTicks && extraTicks > minExtraRatio * walkedTicks;
        }
    }
}
