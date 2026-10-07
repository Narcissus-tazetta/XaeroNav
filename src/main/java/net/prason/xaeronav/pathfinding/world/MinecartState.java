package net.prason.xaeronav.pathfinding.world;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

/**
 * 探索から見たトロッコの状態。トロッコで走る移動は、持っているか・乗っているか・線路上に空のトロッコが
 * 置いてあるときだけ出す（旧来の挙動のワールドに限る）。
 *
 * @param carrying 持ち物にトロッコがある。どのレールからでも置いて乗れる
 * @param riding   いま乗っている。乗っているなら{@code railX/Y/Z}のレールから始まる
 * @param speed    乗っているトロッコの水平の速さ（ブロック/tick、溜めた惰性を含む）
 * @param dirX     進んでいる向き（乗っていて動いているときだけ非0）
 * @param dirZ     同上
 * @param parked   空のトロッコが置いてあるレールのセル（{@link net.minecraft.core.BlockPos#asLong}）
 */
public record MinecartState(boolean carrying, boolean riding, int railX, int railY, int railZ, double speed,
                            double dirX, double dirZ, LongSet parked) {

    public static final MinecartState UNAVAILABLE =
            new MinecartState(false, false, 0, 0, 0, 0.0, 0.0, 0.0, LongSets.EMPTY_SET);

    public static MinecartState carryingOne() {
        return new MinecartState(true, false, 0, 0, 0, 0.0, 0.0, 0.0, LongSets.EMPTY_SET);
    }

    public static MinecartState parkedAt(LongSet parked) {
        return new MinecartState(false, false, 0, 0, 0, 0.0, 0.0, 0.0, parked);
    }

    public boolean available() {
        return carrying || riding || !parked.isEmpty();
    }
}
