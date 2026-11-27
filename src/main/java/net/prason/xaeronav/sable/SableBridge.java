package net.prason.xaeronav.sable;

//? if sable {
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Sable Companion（MIT）のAPIで乗っている構造物を読む。{@link SableVehicles#present()}が真のときだけ呼ぶこと。
 *
 * <p>構造物のブロックはワールドの遠く（plot）に普通のチャンクとして置かれ、乗り手と外接箱だけがワールド座標にいる。
 * plot側の箱は外接箱を姿勢の逆変換で戻して得る（傾いていれば実物より大きい箱になるが、走査の範囲としては足りる）。
 */
final class SableBridge {

    /** 車輪の走査をやり直す間隔（tick）。組み替えはまれで、走査は構造物の箱の全セルを読む。 */
    private static final int RESCAN_TICKS = 40;
    /**
     * これより大きい箱は走査せず車輪無しとして扱う。大型船のような構造物を2秒ごとにメインスレッドで
     * 全部読むことになるため。車として案内する上限（7マス幅）の車はこれに十分収まる。
     */
    private static final int MAX_SCAN_CELLS = 65_536;
    private static final ResourceLocation WHEEL_MOUNT = ResourceLocation.parse("offroad:wheel_mount");

    private static long cachedTick = Long.MIN_VALUE;
    private static int cachedPlayer;
    private static @Nullable SableRide cachedRide;

    private static @Nullable UUID scannedSubLevel;
    private static long scannedTick;
    private static int scannedSpan;
    private static int scannedHeight;
    private static @Nullable Block wheelMount;

    private SableBridge() {
    }

    static @Nullable SableRide ride(Player player) {
        Level level = player.level();
        long tick = level.getGameTime();
        if (tick == cachedTick && player.getId() == cachedPlayer) {
            return cachedRide;
        }
        cachedTick = tick;
        cachedPlayer = player.getId();
        cachedRide = read(level, player, tick);
        return cachedRide;
    }

    private static @Nullable SableRide read(Level level, Player player, long tick) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player);
        if (sub == null) {
            return null;
        }
        BoundingBox3dc box = sub.boundingBox();
        if (!sub.getUniqueId().equals(scannedSubLevel) || tick - scannedTick >= RESCAN_TICKS || tick < scannedTick) {
            scan(level, sub, box);
            scannedSubLevel = sub.getUniqueId();
            scannedTick = tick;
        }
        boolean seated = SableCompanion.INSTANCE.getVehicleSubLevel(player) != null;
        return new SableRide(seated, clearance(level, box), scannedSpan, scannedHeight);
    }

    /**
     * 外接箱の下端から地面までの高さ。中央と四隅（半マス内側）の列の地面のうち一番高いもので測る——崖の縁に
     * 半分はみ出して停まった車を浮いているとは言わない。地面は{@code MOTION_BLOCKING}の高さマップで読む。
     * 構造物のブロックはplotにあるのでワールドの高さマップには載らない。屋根や木の下では屋根から測るので
     * 低く出るが、その誤りは地上の案内に倒れる側なので許す。
     */
    private static double clearance(Level level, BoundingBox3dc box) {
        double[] xs = {(box.minX() + box.maxX()) / 2, box.minX() + 0.5, box.maxX() - 0.5};
        double[] zs = {(box.minZ() + box.maxZ()) / 2, box.minZ() + 0.5, box.maxZ() - 0.5};
        int highest = Integer.MIN_VALUE;
        for (int i = 0; i < 5; i++) {
            double x = i == 0 ? xs[0] : (i & 1) == 1 ? xs[1] : xs[2];
            double z = i == 0 ? zs[0] : i <= 2 ? zs[1] : zs[2];
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            if (!level.hasChunk(bx >> 4, bz >> 4)) {
                continue;
            }
            highest = Math.max(highest, level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz));
        }
        return highest == Integer.MIN_VALUE ? Double.POSITIVE_INFINITY : box.minY() - highest;
    }

    /**
     * plot側の箱を走査して、車輪で決まる幅と縦の大きさを出す。
     *
     * <p>Offroadの車輪台（{@code offroad:wheel_mount}）はタイヤを{@code HORIZONTAL_FACING}の先の1マスに出す。
     * 車輪は車体の左右へ向けて付けるので、その向きの軸がそのまま車幅の軸になる。両方の軸に向いた車輪が
     * あれば数の多い方を取る。幅はタイヤのセルも含めた端から端まで。
     */
    private static void scan(Level level, SubLevelAccess sub, BoundingBox3dc box) {
        BoundingBox3d local = box.transformInverse(sub.logicalPose(), new BoundingBox3d());
        int minX = (int) Math.floor(local.minX());
        int minY = (int) Math.floor(local.minY());
        int minZ = (int) Math.floor(local.minZ());
        int maxX = (int) Math.ceil(local.maxX()) - 1;
        int maxY = (int) Math.ceil(local.maxY()) - 1;
        int maxZ = (int) Math.ceil(local.maxZ()) - 1;
        scannedHeight = Math.max(1, (int) Math.ceil(local.maxY() - local.minY() - 1.0e-6));
        scannedSpan = 0;
        long cells = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (cells > MAX_SCAN_CELLS) {
            return;
        }
        Block mount = wheelMount();
        if (mount == Blocks.AIR) {
            return;
        }
        int[] lowX = {Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] highX = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        int[] count = new int[2];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockState state = level.getBlockState(cursor.set(x, y, z));
                    if (!state.is(mount)) {
                        continue;
                    }
                    Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    // 0: タイヤがX方向へ出る車輪、1: Z方向
                    int axis = facing.getAxis() == Direction.Axis.X ? 0 : 1;
                    int along = axis == 0 ? x + facing.getStepX() : z + facing.getStepZ();
                    lowX[axis] = Math.min(lowX[axis], along);
                    highX[axis] = Math.max(highX[axis], along);
                    count[axis]++;
                }
            }
        }
        int axis = count[0] >= count[1] ? 0 : 1;
        if (count[axis] > 0) {
            scannedSpan = highX[axis] - lowX[axis] + 1;
        }
    }

    private static Block wheelMount() {
        Block known = wheelMount;
        if (known == null) {
            // Offroadが無ければAIRが返る（DefaultedRegistry）。そのときは車輪の無い構造物だけになる
            known = BuiltInRegistries.BLOCK.get(WHEEL_MOUNT);
            wheelMount = known;
        }
        return known;
    }
}
//?}
