package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.MinecartState;
import net.prason.xaeronav.rail.CartRide;
import net.prason.xaeronav.rail.RailCell;
import net.prason.xaeronav.rail.TrackShape;

/**
 * トロッコで線路を走る移動候補生成。{@link AStarPathfinder}の分割の一部——{@link GroundMoves}のクラスJavadoc参照。
 *
 * <p>乗っている状態をノードに持たず、<b>乗る点から降りる点までを歩きの状態どうしの1手</b>にする。1ブロックの
 * 値段が手前で溜めた速さで決まるので、セルごとの手に割ると同じセルに速さの違う状態が要る。線路は分岐が
 * レールの向きで決まる1本道なので、乗る点と向きを決めれば走りは1通りに決まり、模擬1回で全ての降りる点が出る。
 */
final class CartMoves {

    /**
     * 1回の乗車で模擬する長さの上限（tick）。詳細探索の範囲の線路を端まで走るには足りる。
     * 輪になった線路では止まらないので、上限が無いと模擬が終わらない。
     */
    private static final int MAX_RIDE_TICKS = 2400;

    /**
     * 降りる点までの値段が、そこまでを一直線に走る時間のこの倍を超えるなら手を作らない。押して進むだけの
     * 線路（廃坑など）は走るより3.7倍遅く、地形が塞いでいても使う価値が無いのに、乗る点×降りる点の数だけ
     * 手を作ることになる。2倍までは山を貫くトンネルのような「歩く道が大回りになる」線路を残す。
     */
    private static final double MAX_DETOUR_FACTOR = 2.0;

    private final AStarPathfinder owner;

    CartMoves(AStarPathfinder owner) {
        this.owner = owner;
    }

    /** {@code from}からトロッコに乗って走る手。いま乗っているなら探索の始点からだけ、乗る手間なしで。 */
    void addRides(PathNode from) {
        if (!owner.view.minecart().available() || from.boating) {
            return;
        }
        Boarding boarding = boarding(from);
        if (boarding == null) {
            return;
        }
        for (int exit : boarding.exits) {
            CartRide.ride(owner.view::track, boarding.x, boarding.y, boarding.z, exit, boarding.speed, true,
                    MAX_RIDE_TICKS, (x, y, z, tick, forcedExit) -> {
                        relaxAlighting(from, boarding, x, y, z, tick);
                        return true;
                    });
        }
    }

    private void relaxAlighting(PathNode from, Boarding boarding, int x, int y, int z, int tick) {
        if (x == from.x && y == from.y && z == from.z) {
            return;
        }
        double cost = boarding.cost + tick + boarding.stowCost;
        double dx = x - from.x;
        double dz = z - from.z;
        if (cost > MAX_DETOUR_FACTOR * Math.sqrt(dx * dx + dz * dz) * ActionCosts.SPRINT_ONE_BLOCK) {
            return;
        }
        // 降りた人はレールのセルに立つ。レールは当たり判定が無いので、頭の高さが空いていれば立てる
        if (!CellData.standable(owner.view.cell(x, y - 1, z))
                || !CellData.occupiableWithoutDigging(owner.view.cell(x, y + 1, z))) {
            return;
        }
        owner.relax(from, x, y, z, cost, MoveKind.CART_RIDE);
    }

    /**
     * 経路を組み立てるときに、{@code from}→{@code to}の乗車で通るセルを乗る点の次から降りる点まで順に返す。
     * 2つの向きのうち{@code to}に早く着く方を採る（探索がその手を作ったときと同じ模擬なので必ず見つかる）。
     *
     * @param ticks 各セルへ着いたtick（乗る点からの経過）を詰める。長さは戻り値と同じ
     */
    List<BlockPos> rideCells(PathNode from, PathNode to, IntArrayList ticks) {
        Boarding boarding = boarding(from);
        List<BlockPos> best = null;
        IntArrayList bestTicks = null;
        for (int exit : boarding.exits) {
            ArrayList<BlockPos> cells = new ArrayList<>();
            IntArrayList arrivals = new IntArrayList();
            boolean[] reached = {false};
            CartRide.ride(owner.view::track, boarding.x, boarding.y, boarding.z, exit, boarding.speed, true,
                    MAX_RIDE_TICKS, (x, y, z, tick, forcedExit) -> {
                        cells.add(new BlockPos(x, y, z));
                        arrivals.add(tick);
                        reached[0] = x == to.x && y == to.y && z == to.z;
                        return !reached[0];
                    });
            if (reached[0] && (bestTicks == null
                    || arrivals.getInt(arrivals.size() - 1) < bestTicks.getInt(bestTicks.size() - 1))) {
                best = cells;
                bestTicks = arrivals;
            }
        }
        ticks.addAll(bestTicks);
        return best;
    }

    double boardingCost(PathNode from) {
        return boarding(from).cost;
    }

    /**
     * @param cost     乗るまでの手間
     * @param stowCost 降りてからの手間。自分のトロッコは壊して拾うが、置いてあったものはそのまま残して行く
     */
    private record Boarding(int x, int y, int z, int[] exits, double speed, double cost, double stowCost) {
    }

    private static final int[] BOTH_EXITS = {0, 1};

    private Boarding boarding(PathNode from) {
        MinecartState cart = owner.view.minecart();
        // 始点だけは前の手を持たない。乗っているなら、乗っているトロッコのレールから今の速さで走り出す
        if (from.previous == null && cart.riding()) {
            int track = owner.view.track(cart.railX(), cart.railY(), cart.railZ());
            if (track == CartRide.NONE) {
                return null;
            }
            int[] exits = BOTH_EXITS;
            if (cart.speed() >= CartRide.PUSH_SPEED) {
                TrackShape shape = RailCell.shape(track);
                double ahead0 = CartRide.exitDx(shape, 0) * cart.dirX() + CartRide.exitDz(shape, 0) * cart.dirZ();
                double ahead1 = CartRide.exitDx(shape, 1) * cart.dirX() + CartRide.exitDz(shape, 1) * cart.dirZ();
                exits = new int[] {ahead0 >= ahead1 ? 0 : 1};
            }
            return new Boarding(cart.railX(), cart.railY(), cart.railZ(), exits, cart.speed(), 0.0,
                    ActionCosts.CART_STOW_TICKS);
        }
        if (owner.view.track(from.x, from.y, from.z) == CartRide.NONE) {
            return null;
        }
        if (cart.parked().contains(BlockPos.asLong(from.x, from.y, from.z))) {
            return new Boarding(from.x, from.y, from.z, BOTH_EXITS, 0.0, ActionCosts.CART_ENTER_TICKS, 0.0);
        }
        if (!cart.carrying()) {
            return null;
        }
        return new Boarding(from.x, from.y, from.z, BOTH_EXITS, 0.0, ActionCosts.CART_BOARD_TICKS,
                ActionCosts.CART_STOW_TICKS);
    }
}
