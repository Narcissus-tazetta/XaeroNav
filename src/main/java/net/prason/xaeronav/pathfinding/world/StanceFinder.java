package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;

/**
 * 探索の始点・終点を「実際に立てる場所」へ寄せる。
 *
 * <p>A*が作る移動はすべて「足場のあるセル・水・梯子」で終わるので、そうでない座標を始点や終点に
 * 渡すと、経路が1本も伸びないか、到達不能として打ち切られる。ところが始点と終点はどちらも
 * 探索の外から降ってくる値で、そのままでは成立しないことが珍しくない:
 *
 * <ul>
 *   <li>始点 — 落下中・蜘蛛の巣の中・トロッコの上。足元に足場が無い状態でも案内は続けたい</li>
 *   <li>終点 — 地図をクリックした座標やウェイポイントのY。地中や空中を指していることがある</li>
 * </ul>
 */
public final class StanceFinder {

    /** 同じ柱を上下に探す範囲（ブロック）。探索範囲の垂直マージンと同程度に留める。 */
    private static final int VERTICAL_SEARCH = 32;

    private StanceFinder() {
    }

    /** そこにプレイヤーが立てる（＝A*の移動の終点になりうる）か。 */
    public static boolean isStance(ChunkView view, int x, int y, int z) {
        long feet = view.cell(x, y, z);
        if (!CellData.occupiableWithoutDigging(feet)
                || !CellData.occupiableWithoutDigging(view.cell(x, y + 1, z))) {
            return false;
        }
        return CellData.standable(view.cell(x, y - 1, z))
                || CellData.water(feet)
                || CellData.climbable(feet);
    }

    /**
     * 探索の始点。足場が無ければ真下の着地点まで下ろす。落下中やトロッコでの移動中でも
     * 「このあと自分が立つ場所」から先の経路が出るようにするためのもの。
     */
    public static BlockPos resolveStart(ChunkView view, BlockPos start) {
        int x = start.getX();
        int z = start.getZ();
        if (isStance(view, x, start.getY(), z)) {
            return start;
        }
        for (int dy = 1; dy <= VERTICAL_SEARCH; dy++) {
            if (isStance(view, x, start.getY() - dy, z)) {
                return new BlockPos(x, start.getY() - dy, z);
            }
        }
        // 足元がブロックに埋まっている場合（半ブロックの中・地面にめり込んだ位置）だけは1マス上を見る
        if (isStance(view, x, start.getY() + 1, z)) {
            return start.above();
        }
        return start;
    }

    /**
     * 探索の終点。原理的に辿り着けない座標なら、同じ柱で最も近い辿り着ける場所へ寄せる。
     *
     * <p>地図やウェイポイントが指すのは「その場所」であって「そのブロック」ではない。Yだけが
     * ずれている目的地を到達不能として扱うと、目の前まで来ているのに経路なしになってしまう。
     *
     * <p>寄せるかどうかの判断に{@link #isStance}ではなく{@link #isReachable}を使うのは、
     * 掘って辿り着ける地中の目的地（そこまでの坑道を出すのが正しい）と、足場が無くて
     * どうやっても立てない空中の目的地を区別するため。
     */
    public static BlockPos resolveGoal(ChunkView view, BlockPos goal) {
        int x = goal.getX();
        int y = goal.getY();
        int z = goal.getZ();
        if (isReachable(view, x, y, z)) {
            return goal;
        }
        for (int dy = 1; dy <= VERTICAL_SEARCH; dy++) {
            if (isReachable(view, x, y - dy, z)) {
                return new BlockPos(x, y - dy, z);
            }
            if (isReachable(view, x, y + dy, z)) {
                return new BlockPos(x, y + dy, z);
            }
        }
        return goal;
    }

    /** そこへ到着する移動が作れるか。身体の通るセルは掘って空けられるので、塞がっていてもよい。 */
    private static boolean isReachable(ChunkView view, int x, int y, int z) {
        long feet = view.cell(x, y, z);
        if (!occupiableOrDiggable(feet) || !occupiableOrDiggable(view.cell(x, y + 1, z))) {
            return false;
        }
        // 足場だけは掘って作れない
        return CellData.standable(view.cell(x, y - 1, z))
                || CellData.water(feet)
                || CellData.climbable(feet);
    }

    private static boolean occupiableOrDiggable(long cell) {
        return CellData.occupiableWithoutDigging(cell)
                || CellData.present(cell) && !Double.isInfinite(CellData.digTicks(cell));
    }
}
