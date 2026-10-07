package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * 区間の境目をまたいで引き継ぐ累積カウンタ。経路は区間ごとに別の探索器で解かれるので、
 * 引き継がないと<b>区間の数だけ上限が復活する</b>。
 *
 * <p>{@link PathNode}の同じ名前のフィールドと対になっていて、探索の始点ノードへそのまま入る。
 *
 * @param bridgeRun   始点がすでに橋の途中である場合の、そこまでの連続長。溶岩の海を4区間に割れば、
 *                    上限30でも120マスの橋が通ってしまう
 * @param placedBlocks この経路のこれから先で<b>すでに使うと決まっている</b>足場の数。持ち物の予算
 *                    （{@link Tolerances#placedBlockBudget()}）は探索のたびに手持ちの枚数から
 *                    引き直されるので、引き継がないと区間ごとに予算が満額になる——長距離ルートは
 *                    区間ごとに探索を投げるため、<b>合計では手持ちの何倍も置く経路</b>が出る
 */
public record Carryover(int bridgeRun, int placedBlocks, Ride ride) {

    public static final Carryover NONE = new Carryover(0, 0);

    public Carryover(int bridgeRun, int placedBlocks) {
        this(bridgeRun, placedBlocks, Ride.NONE);
    }

    /**
     * 始点でトロッコに乗ったまま走っている途中か。手前の区間が探索の範囲の端で、降りずに走り続ける点
     * （{@code CartMoves}が降りる手間を払わない点）で終わったときだけ立つ。
     *
     * @param speed その点を通る速さの見積もり（ブロック/tick、{@code CartRide}の速さ）。0なら乗っていない
     * @param dirX  進んでいる向き
     * @param dirZ  同上
     */
    public record Ride(double speed, int dirX, int dirZ) {
        public static final Ride NONE = new Ride(0.0, 0, 0);

        public boolean riding() {
            return speed > 0.0;
        }
    }

    /** 確定済みのステップ列の続きを解く探索へ渡す引き継ぎ。 */
    public static Carryover after(List<PathStep> steps) {
        return new Carryover(trailingBridgeRun(steps), placements(steps, 0), trailingRide(steps));
    }

    /**
     * ステップ列が乗車の途中で終わっているなら、その点の速さと向き。速さは最後のセルにかかったtickから戻す
     * （1ブロックを{@code t}tickなら、移動量{@code 1/t}は速さの0.75倍）。降りる点のステップは降りる手間を含んで
     * 重いので、走っている途中のセルと見分けられる。
     */
    public static Ride trailingRide(List<PathStep> steps) {
        int last = steps.size() - 1;
        if (last < 1 || steps.get(last).movement() != MovementType.CART
                || steps.get(last - 1).movement() != MovementType.CART
                || steps.get(last).cost() >= ActionCosts.CART_STOW_TICKS) {
            return Ride.NONE;
        }
        BlockPos before = steps.get(last - 1).pos();
        BlockPos end = steps.get(last).pos();
        double ticks = Math.max(1.0, steps.get(last).cost());
        return new Ride(Math.min(2.0, 1.0 / (0.75 * ticks)), Integer.signum(end.getX() - before.getX()),
                Integer.signum(end.getZ() - before.getZ()));
    }

    /** ステップ列の末尾で連続している橋のブロック数。 */
    public static int trailingBridgeRun(List<PathStep> steps) {
        int run = 0;
        for (int i = steps.size() - 1; i >= 0 && steps.get(i).bridging(); i--) {
            run++;
        }
        return run;
    }

    /**
     * {@code from}番目のステップ以降で置くことになる足場の数。
     *
     * <p><b>「経路全体で何個か」ではなく「ここから先で何個か」を数える。</b>手前のぶんは既に
     * 置き終わっていて持ち物からも減っているので、いま数え直した手持ちと突き合わせるには
     * 先の分だけを見なければならない——足せば足すほど「足りない」と言い続けることになる。
     * 探索の予算（この記録）とHUDの不足警告が同じ数え方を共有するのはそのため。
     */
    public static int placements(List<PathStep> steps, int from) {
        int placed = 0;
        for (int i = Math.max(0, from); i < steps.size(); i++) {
            if (steps.get(i).bridging()) {
                placed++;
            }
        }
        return placed;
    }
}
