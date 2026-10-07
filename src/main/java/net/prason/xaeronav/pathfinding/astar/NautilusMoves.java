package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.pathfinding.world.StanceFinder;

/**
 * オウムガイ・ゾンビオウムガイに乗ったまま水中を進む移動候補。
 *
 * <p><b>ノードの座標はオウムガイのいる水のセル</b>。オウムガイは0.875×0.95で1マスに収まり、乗り手の足は
 * その+0.54（乗る位置1.1375−{@code DEFAULT_VEHICLE_ATTACHMENT}0.6）、頭は+2.34なので、上2マスも空いている必要がある。
 *
 * <p>水中では重力がかからず（{@code AbstractNautilus#travelInWater}）、入力は視線の向きの単位ベクトル
 * （{@code getRiddenInput}）なので、上下も含めどの向きにも同じ速さで進む。乗り手には
 * {@code BREATH_OF_THE_NAUTILUS}が付き続けるので息は減らない（{@link AStarPathfinder}の息の勘定は乗っている
 * ノードを数えない）。
 *
 * <p>陸へは上がらない。陸では加速が{@code 0.02×}に落ちて歩きより遅く、段差も上がれないので、水が尽きたら
 * 降りて歩く。ジャンプキーの突進（40tickごと）は数えない——使えば早く着くだけで経路は変わらない。
 */
final class NautilusMoves implements RiddenMoves {

    /** スニークして降りる手間（tick）。{@link MountMoves}と同じ推定値。 */
    private static final double DISMOUNT_TICKS = 10.0;
    /** 水中での加速の係数（{@code AbstractNautilus#getRiddenSpeed}）。これに{@code MOVEMENT_SPEED}を掛ける。 */
    private static final double WATER_ACCELERATION = 0.0325F;
    /** 水中の毎tickの減衰（{@code AbstractNautilus#travelInWater}）。 */
    private static final double WATER_DRAG = 0.9;
    /** オウムガイのセルから乗り手の頭のセルまで。 */
    private static final int HEIGHT = 3;
    private static final double DIAGONAL = Math.sqrt(2.0);
    private static final double SPACE_DIAGONAL = Math.sqrt(3.0);
    /** 始点を探して上下に寄せる幅。乗り手の足はオウムガイの1つ上のセルに入っていることもある。 */
    private static final int START_SEARCH = 3;

    private final AStarPathfinder owner;
    private final double ticksPerBlock;

    NautilusMoves(AStarPathfinder owner, MountState mount) {
        this.owner = owner;
        this.ticksPerBlock = (1.0 - WATER_DRAG) / (WATER_ACCELERATION * mount.movementSpeed());
    }

    @Override
    public double ticksPerBlock() {
        return ticksPerBlock;
    }

    @Override
    public int span() {
        return 1;
    }

    /** ゴールが乗り手の足のセル（オウムガイのセルかその1つ上）なら着いた。 */
    @Override
    public boolean reachesGoalHeight(int nodeY, int goalY) {
        return nodeY == goalY || nodeY + 1 == goalY;
    }

    @Override
    public int safeFallBlocks() {
        return 0;
    }

    @Override
    public void expand(PathNode from) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        addSwim(from, dx, dy, dz);
                    }
                }
            }
        }
        addDismount(from);
    }

    private void addSwim(PathNode from, int dx, int dy, int dz) {
        int x = from.x + dx;
        int y = from.y + dy;
        int z = from.z + dz;
        if (!CellData.water(owner.view.cell(x, y, z))) {
            return;
        }
        // 斜め・上下を含む移動では、元と先の外接箱の全セルを体が掠める（角をすり抜けさせない）
        if (!clear(Math.min(from.x, x), Math.min(from.y, y), Math.min(from.z, z),
                Math.max(from.x, x), Math.max(from.y, y) + HEIGHT - 1, Math.max(from.z, z))) {
            return;
        }
        int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        double distance = axes == 1 ? 1.0 : axes == 2 ? DIAGONAL : SPACE_DIAGONAL;
        owner.relaxMounted(from, x, y, z, distance * ticksPerBlock, MoveKind.MOUNT_SWIM);
    }

    /** 箱の中が全部、掘らずに体が入れるセル（水か空気）か。 */
    private boolean clear(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (!CellData.occupiableWithoutDigging(owner.view.cell(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 降りて、ここから先は歩く・泳ぐ。乗り直しは無い（{@link MountMoves}と同じく置いていく割増を払う）。
     * 降りる先は乗り手の足の高さ（オウムガイのセルとその上2つ）で、その列か周りの8列のうち立てる・泳げるセル。
     * バニラは乗り物の周りの空いた所へ降ろすので、岸に寄せたオウムガイからは岸へ上がれる。
     */
    private void addDismount(PathNode from) {
        double cost = DISMOUNT_TICKS + owner.view.mountLeaveBehindTicks();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = from.y; y < from.y + HEIGHT; y++) {
                    if (StanceFinder.isStance(owner.view, from.x + dx, y, from.z + dz)) {
                        owner.relax(from, from.x + dx, y, from.z + dz, cost, MoveKind.DISMOUNT);
                        break;
                    }
                }
            }
        }
    }

    /** 乗り手のいるセルから、オウムガイのいる水のセルを上下に探す。 */
    @Override
    public @Nullable BlockPos resolveStart(BlockPos start) {
        for (int dy = 0; dy <= START_SEARCH; dy++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                if (dy == 0 && sign == 1) {
                    continue;
                }
                int y = start.getY() + sign * dy;
                if (CellData.water(owner.view.cell(start.getX(), y, start.getZ()))
                        && clear(start.getX(), y, start.getZ(), start.getX(), y + HEIGHT - 1, start.getZ())) {
                    return new BlockPos(start.getX(), y, start.getZ());
                }
            }
        }
        return null;
    }

    @Override
    public List<BlockPos> bodyCells(PathNode from, PathNode to) {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = Math.min(from.x, to.x); x <= Math.max(from.x, to.x); x++) {
            for (int z = Math.min(from.z, to.z); z <= Math.max(from.z, to.z); z++) {
                for (int y = Math.min(from.y, to.y); y <= Math.max(from.y, to.y) + HEIGHT - 1; y++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(cells);
    }
}
