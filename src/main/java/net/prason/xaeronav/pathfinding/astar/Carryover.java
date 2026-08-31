package net.prason.xaeronav.pathfinding.astar;

import java.util.List;

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
public record Carryover(int bridgeRun, int placedBlocks) {

    public static final Carryover NONE = new Carryover(0, 0);

    /** 確定済みのステップ列の続きを解く探索へ渡す引き継ぎ。 */
    public static Carryover after(List<PathStep> steps) {
        return new Carryover(trailingBridgeRun(steps), placements(steps, 0));
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
