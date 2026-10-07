package net.prason.xaeronav.pathfinding.navgraph;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.prason.xaeronav.rail.RailNetwork;

/**
 * 窓のガイドに足す、トロッコで線路を走る手の材料。
 *
 * @param network  見たことのあるレール
 * @param carrying トロッコを持っている（乗っている）。どのレールからでも置いて乗れる
 * @param parked   空のトロッコが置いてあるレールのセル（{@link net.minecraft.core.BlockPos#asLong}）。持っていなくても乗れる
 */
public record WindowRides(RailNetwork network, boolean carrying, LongSet parked) {

    public static final WindowRides NONE = new WindowRides(RailNetwork.EMPTY, false, LongSets.EMPTY_SET);

    boolean usable() {
        return !network.isEmpty() && (carrying || !parked.isEmpty());
    }
}
