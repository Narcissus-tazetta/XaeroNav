package net.prason.xaeronav.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.sable.SableVehicles;
import net.prason.xaeronav.util.GameCompat;

/**
 * 地上の案内に使う位置。Create Aeronautics（Sable）の乗り物に座っている・上に立っているなら、乗り手の真下の地面。
 *
 * <p>乗り手は構造物の上（座席・甲板）にいて、足は地面から数マス〜数十マス上にある。そのまま探索の始点・到着・
 * 逸脱の判定に使うと、始点が甲板の高さの空中になり（「経路なし」）、目的地の真上を通っても上下の差で着かない。
 * 構造物のブロックはワールドの遠く（plot）にあるので、乗り手の真下のワールドのブロックを下へ辿れば地面に当たる。
 *
 * <p>空中の案内（飛行中）はプレイヤーの位置のままでよい。ここは地上の案内だけが使う。
 */
final class VehicleGuide {

    /** 真下の地面を探す深さ。停めた大型船の甲板に立っている場合まで届くように深く取る。 */
    private static final int GROUND_SEARCH_BLOCKS = 96;

    private static long cachedTick = Long.MIN_VALUE;
    private static int cachedPlayer;
    private static Vec3 cachedPosition = Vec3.ZERO;

    private VehicleGuide() {
    }

    /** 案内に使う位置。Sableの乗り物に乗っていなければプレイヤーの位置。メインスレッド専用。 */
    static Vec3 position(Player player) {
        if (SableVehicles.ride(player) == null) {
            return player.position();
        }
        // 古い版の{@code Player}は{@code level()}を持たないので、乗り手と同じクライアントのLevelを使う
        Level level = Minecraft.getInstance().level;
        long tick = level.getGameTime();
        if (tick != cachedTick || player.getId() != cachedPlayer) {
            cachedTick = tick;
            cachedPlayer = player.getId();
            cachedPosition = groundBelow(level, player);
        }
        return cachedPosition;
    }

    static BlockPos block(Player player) {
        Vec3 position = position(player);
        return new BlockPos(Mth.floor(position.x), Mth.floor(position.y), Mth.floor(position.z));
    }

    /** 乗り手の真下で最初に足場になるブロックの上面。見つからなければプレイヤーの位置。 */
    private static Vec3 groundBelow(Level level, Player player) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int x = Mth.floor(player.getX());
        int z = Mth.floor(player.getZ());
        int top = Mth.floor(player.getY());
        int bottom = Math.max(GameCompat.minBuildHeight(level), top - GROUND_SEARCH_BLOCKS);
        for (int y = top; y >= bottom; y--) {
            BlockState state = level.getBlockState(cursor.set(x, y, z));
            if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, cursor).isEmpty()) {
                return new Vec3(player.getX(), y + 1, player.getZ());
            }
        }
        return player.position();
    }
}
