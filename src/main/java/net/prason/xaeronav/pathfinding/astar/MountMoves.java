package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.cost.MountPhysics;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.pathfinding.world.StanceFinder;

/**
 * 馬の仲間・ラクダに乗ったままの移動候補。
 *
 * <p><b>ノードの座標は2×2の足場の角</b>（{@code x..x+1, z..z+1}の4列）で、乗り物の中心はセルの境目
 * {@code (x+1, z+1)}に来る。馬は幅1.4、ラクダは1.7なので、セルの中心に置くと3マス幅を要求してしまい、
 * 2マス幅の通路を通れない。高さは足場の4列の床のうち一番高いもの（当たり判定はどこか1点で支えれば立つ）。
 *
 * <p>体の高さは乗り手の目まで。乗っている間もプレイヤーは立った姿勢のまま（{@code Player#updatePlayerPose}）で、
 * 目が固体に入ると窒息する（{@code Entity#isInWall}）。馬は乗る位置1.44375−{@code DEFAULT_VEHICLE_ATTACHMENT}0.6で
 * 目が足場から2.46＝3マス目、ラクダは乗る位置2.0で目が3.0を超え4マス目。
 *
 * <p>掘らない・置かない・梯子を使わない。乗ったまま掘ると1/5の速さで1歩6セル以上になるので、
 * 掘る道は降りて歩く方に任せる。値段は徒歩と同じ表で、乗り物の速さはまだ数えていない
 * （ガイドと下限を徒歩のまま保てる）。
 */
final class MountMoves {

    /** スニークして降りる手間（tick）。押してから降りるまでは数tickだが、降りた位置の向き直りを見込む。推定値 */
    private static final double DISMOUNT_TICKS = 10.0;
    /** 落下ダメージの無い落差（{@code AbstractHorse}の{@code SAFE_FALL_DISTANCE 6}）。 */
    static final int SAFE_FALL_BLOCKS = 6;
    /** ダメージ＝{@code ceil((落差 - 6) × 0.5)}（{@code FALL_DAMAGE_MULTIPLIER 0.5}）。 */
    private static final double FALL_DAMAGE_MULTIPLIER = 0.5;
    /** 歩いて上がれる段。馬は{@code STEP_HEIGHT 1.0}、ラクダは1.5だがセル単位では同じ1マス。 */
    private static final int STEP_RISE = 1;
    /** 着地を探して下へ辿る深さ。落下ダメージの許容を緩めたときの深い落下まで届くよう、徒歩の柱の走査と同じく打ち切りだけ置く。 */
    private static final int MAX_FALL_SCAN = 64;
    /** 跳び越える隙間を探す列数。 */
    private static final int MAX_GAP_ROWS = 8;
    /**
     * 跳ぶ距離に足す余裕（ブロック）。踏み切り・着地の位置合わせは人間がやるので、計算ちょうどの跳躍は案内しない。
     * 届く距離は手元の計算だけで実機では測っていない。
     */
    private static final double JUMP_MARGIN_BLOCKS = 0.5;

    private final AStarPathfinder owner;
    private final MountPhysics physics;
    /** 足場から乗り手の目のセルまでを含む体の高さ。 */
    private final int height;
    /** 当たり判定の幅の半分。隙間を跳ぶ距離から引く。 */
    private final double halfWidth;
    private final int maxRise;

    MountMoves(AStarPathfinder owner, MountState mount) {
        this.owner = owner;
        this.physics = new MountPhysics(mount);
        boolean camel = mount.kind() == MountState.Kind.CAMEL;
        this.height = camel ? 4 : 3;
        this.halfWidth = camel ? 0.85 : 0.7;
        this.maxRise = Math.max(STEP_RISE, physics.maxRise());
    }

    void expand(PathNode from) {
        for (int i = 0; i < AStarPathfinder.CARDINAL_DX.length; i++) {
            addStep(from, AStarPathfinder.CARDINAL_DX[i], AStarPathfinder.CARDINAL_DZ[i]);
        }
        for (int i = 0; i < AStarPathfinder.DIAGONAL_DX.length; i++) {
            addDiagonal(from, AStarPathfinder.DIAGONAL_DX[i], AStarPathfinder.DIAGONAL_DZ[i]);
        }
        addDismount(from);
    }

    /**
     * 降りて、ここから先は歩く。乗り直しは無い（歩きのノードは乗っている手を作らない）——降りた後に乗り物が
     * 付いて来る保証は無いので、置いていく前提で割増（{@code mountLeaveBehindTicks}）を払う。
     *
     * <p>降りて立つのは足場の4列のうち人が立てるセル。バニラは乗り物の横の空いた所へ降ろす
     * （{@code AbstractHorse#getDismountLocationForPassenger}）ので1マスほどずれうるが、そこから先は歩きの経路の
     * 逸脱の幅に収まる。
     */
    private void addDismount(PathNode from) {
        double cost = DISMOUNT_TICKS + owner.view.mountLeaveBehindTicks();
        for (int x = from.x; x <= from.x + 1; x++) {
            for (int z = from.z; z <= from.z + 1; z++) {
                if (StanceFinder.isStance(owner.view, x, from.y, z)) {
                    owner.relax(from, x, from.y, z, cost, MoveKind.DISMOUNT);
                }
            }
        }
    }

    /**
     * 乗っている始点。{@code start}はプレイヤーのいるセルなので、乗り物の中心がどの角かは近くの4つを試して決める
     * （呼び出し側が角そのものを渡していればそれが最初に当たる）。高さは近い方から上下へ寄せる。
     * 乗ったままでは立てない（深い水の中など）なら{@code null}——歩きで始める。
     */
    @Nullable BlockPos resolveStart(BlockPos start) {
        int[][] corners = {{0, 0}, {-1, 0}, {0, -1}, {-1, -1}};
        for (int dy = 0; dy <= 32; dy++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int y = start.getY() - sign * dy;
                if (sign == -1 && (dy == 0 || dy > 3)) {
                    continue;
                }
                for (int[] corner : corners) {
                    int x = start.getX() + corner[0];
                    int z = start.getZ() + corner[1];
                    if (stance(x, y, z)) {
                        return new BlockPos(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    /** ゴールとの距離・ガイドを引くときに使う、足場の4列のうち{@code (targetX, targetZ)}に一番近い列。 */
    static int nearest(int corner, int target) {
        return Math.max(corner, Math.min(corner + 1, target));
    }

    /**
     * 始点に立てるか。水は体の高さだけ見て、足場の下の水は許す——水際に立っている乗り物から引くとき、
     * {@link #dry}のままだと乗ったままの始点が見つからず、降りる経路しか出なくなる。
     */
    private boolean stance(int x, int y, int z) {
        if (Double.isInfinite(bodyCost(x, z, y, y + height - 1)) || !supported(x, z, y)) {
            return false;
        }
        for (int cx = x; cx <= x + 1; cx++) {
            for (int cz = z; cz <= z + 1; cz++) {
                for (int cy = y; cy < y + height; cy++) {
                    if (CellData.water(owner.view.cell(cx, cy, cz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void addStep(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int z = from.z + dz;
        int y = from.y;
        double body = bodyCost(x, z, y, y + height - 1);
        if (Double.isInfinite(body)) {
            climb(from, x, z);
            return;
        }
        if (supported(x, z, y)) {
            if (dry(x, z, y)) {
                owner.relaxMounted(from, x, y, z, walkCost(x, z, y) + body, MoveKind.MOUNT_WALK);
            }
            return;
        }
        fall(from, x, z, body);
        addGapJump(from, dx, dz);
    }

    /** 体の入らない段へ、歩いて（1段）か跳んで上がる。 */
    private void climb(PathNode from, int x, int z) {
        int y = from.y;
        for (int rise = 1; rise <= maxRise; rise++) {
            int top = y + rise;
            // 上がる間、元の足場の上も体が通る
            if (Double.isInfinite(bodyCost(from.x, from.z, top + height - 1, top + height - 1))) {
                return;
            }
            double body = bodyCost(x, z, top, top + height - 1);
            if (Double.isInfinite(body)) {
                continue;
            }
            if (!supported(x, z, top) || !dry(x, z, top)) {
                return;
            }
            if (rise <= STEP_RISE) {
                owner.relaxMounted(from, x, top, z, ActionCosts.ascendOneBlock(1.0) + body, MoveKind.MOUNT_WALK);
                return;
            }
            // 跳ぶ。頂点まで頭上が空いていること
            int charge = weakestChargeReaching(rise);
            int headroom = (int) Math.ceil(physics.apex(charge));
            if (Double.isInfinite(bodyCost(from.x, from.z, y + height, y + headroom + height - 1))
                    || Double.isInfinite(bodyCost(x, z, top + height, y + headroom + height - 1))) {
                return;
            }
            owner.relaxMounted(from, x, top, z, rise * ActionCosts.ASCEND_ONE_BLOCK + body, MoveKind.MOUNT_JUMP);
            return;
        }
    }

    private int weakestChargeReaching(int rise) {
        for (int charge = 0; charge < physics.charges(); charge++) {
            if (physics.apex(charge) >= rise) {
                return charge;
            }
        }
        return physics.charges() - 1;
    }

    /** 支えの無い足場へ踏み出して、着くところまで落ちる。 */
    private void fall(PathNode from, int x, int z, double body) {
        int y = from.y;
        int level = y;
        while (!supported(x, z, level)) {
            if (y - level >= MAX_FALL_SCAN || Double.isInfinite(bodyCost(x, z, level - 1, level - 1))) {
                return;
            }
            level--;
        }
        if (!dry(x, z, level)) {
            return;
        }
        int drop = y - level;
        double cost;
        MoveKind kind;
        if (drop == 1) {
            cost = ActionCosts.descendOneBlock(1.0);
            kind = MoveKind.MOUNT_WALK;
        } else {
            int damage = fallDamage(drop);
            if (damage > owner.maxFallDamagePoints) {
                owner.markFallDamageCapBlocked(true);
                return;
            }
            cost = ActionCosts.fallCost(drop) + damage * ActionCosts.FALL_DAMAGE_PENALTY_PER_POINT;
            kind = MoveKind.MOUNT_FALL;
        }
        owner.relaxMounted(from, x, level, z, cost + body, kind);
    }

    private static int fallDamage(int drop) {
        return drop <= SAFE_FALL_BLOCKS ? 0 : (int) Math.ceil((drop - SAFE_FALL_BLOCKS) * FALL_DAMAGE_MULTIPLIER);
    }

    /**
     * 斜め1マス。段差は歩いて越えられる1段まで。中心が斜めに進む間、当たり判定は2つの足場の外側の角2列にも掛かる
     * （中間で中心が{@code (x+1.5, z+1.5)}に来ると幅1.4の箱が3×3の9列全部に触れる）。
     */
    private void addDiagonal(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int z = from.z + dz;
        int y = from.y;
        int level;
        if (!Double.isInfinite(bodyCost(x, z, y, y + height - 1))) {
            if (supported(x, z, y)) {
                level = y;
            } else if (!Double.isInfinite(bodyCost(x, z, y - 1, y - 1)) && supported(x, z, y - 1)) {
                level = y - 1;
            } else {
                return;
            }
        } else if (!Double.isInfinite(bodyCost(x, z, y + 1, y + height)) && supported(x, z, y + 1)
                && !Double.isInfinite(bodyCost(from.x, from.z, y + height, y + height))) {
            level = y + 1;
        } else {
            return;
        }
        if (!dry(x, z, level)) {
            return;
        }
        int high = Math.max(y, level);
        // 2つの足場に入らない角の2列
        if (!clearWithoutDigging(from.x + (dx > 0 ? 2 : -1), from.z + (dz > 0 ? 0 : 1), high, high + height - 1)
                || !clearWithoutDigging(from.x + (dx > 0 ? 0 : 1), from.z + (dz > 0 ? 2 : -1), high, high + height - 1)) {
            return;
        }
        double body = bodyCost(x, z, level, level + height - 1);
        double cost = level > y ? ActionCosts.diagonalAscendOneBlock(1.0)
                : level < y ? ActionCosts.diagonalDescendOneBlock(1.0)
                : walkCost(x, z, level) * ActionCosts.DIAGONAL_DISTANCE;
        owner.relaxMounted(from, x, level, z, cost + body, MoveKind.MOUNT_WALK);
    }

    /**
     * 同じ高さの向こう岸へ隙間を跳び越える（東西南北のみ）。
     *
     * <p>列は進む向きに数える。元の足場の前の列を0、後ろを-1として、最後に支えのある列{@code s}から次に支えの
     * ある列{@code e}までの{@code e-s-1}列が隙間。当たり判定は幅の半分だけ縁からはみ出して立てるので、跳ぶ
     * 距離は{@code 隙間 - 幅}。隙間1列は足場のどこかが支えているので歩いて渡る（{@link #addStep}）。
     */
    private void addGapJump(PathNode from, int dx, int dz) {
        if (!owner.view.jumpGapEnabled()) {
            return;
        }
        int y = from.y;
        int last = rowSupported(from, dx, dz, 0, y) ? 0 : -1;
        if (rowSupported(from, dx, dz, 1, y)) {
            return;
        }
        double dropRisk = 0.0;
        for (int row = 1; row <= MAX_GAP_ROWS; row++) {
            if (rowSupported(from, dx, dz, row, y)) {
                int gap = row - last - 1;
                if (gap < 2) {
                    return;
                }
                land(from, dx, dz, row, gap, dropRisk);
                return;
            }
            for (int lane = 0; lane < 2; lane++) {
                int cx = rowX(from, dx, dz, row, lane);
                int cz = rowZ(from, dx, dz, row, lane);
                if (!clearWithoutDigging(cx, cz, y, y + height - 1) || owner.scans.lavaOrUnknownBelow(cx, y, cz)
                        || waterBelow(cx, y, cz)) {
                    return;
                }
                int equivalentDrop = missDropAsWalker(cx, y, cz);
                if (owner.avoidRiskyJumps && equivalentDrop >= owner.view.fatalFallBlocks()) {
                    owner.markRiskyJumpBlocked();
                    return;
                }
                dropRisk = Math.max(dropRisk, ActionCosts.dropRiskPenalty(equivalentDrop, owner.view.fatalFallBlocks()));
            }
        }
    }

    private void land(PathNode from, int dx, int dz, int row, int gap, double dropRisk) {
        int y = from.y;
        // 着く足場は支えのある列とその先の列。角は進む向きの手前側
        int shift = row + 1;
        int x = from.x + dx * shift;
        int z = from.z + dz * shift;
        double body = bodyCost(x, z, y, y + height - 1);
        if (Double.isInfinite(body) || !dry(x, z, y)) {
            return;
        }
        double flight = gap - 2.0 * halfWidth + JUMP_MARGIN_BLOCKS;
        int charge = -1;
        for (int c = 0; c < physics.charges(); c++) {
            if (physics.reach(c) >= flight) {
                charge = c;
                break;
            }
        }
        if (charge < 0) {
            return;
        }
        // 頂点まで、踏み切りから着地までの列の上が空いていること
        int headroom = (int) Math.ceil(physics.apex(charge));
        int topLow = y + height;
        int topHigh = y + headroom + height - 1;
        if (topHigh >= topLow) {
            for (int r = -1; r <= shift; r++) {
                for (int lane = 0; lane < 2; lane++) {
                    if (!clearWithoutDigging(rowX(from, dx, dz, r, lane), rowZ(from, dx, dz, r, lane), topLow, topHigh)) {
                        return;
                    }
                }
            }
        }
        double cost = Math.max(ActionCosts.jumpAcrossGap(gap), shift * ActionCosts.SPRINT_ONE_BLOCK) + dropRisk;
        owner.relaxMounted(from, x, y, z, cost + body, MoveKind.MOUNT_JUMP);
    }

    /** 跳び損ねたら水に落ちるか。徒歩なら無傷で済むが、乗り物は深い水で乗り手を降ろす。 */
    private boolean waterBelow(int x, int y, int z) {
        int obstacleY = owner.scans.firstNonAirBelow(x, y - 1, z);
        return obstacleY != ColumnScans.NOTHING_BELOW && obstacleY != ColumnScans.UNREADABLE_BELOW
                && CellData.water(owner.view.cell(x, obstacleY, z));
    }

    /**
     * 跳び損ねたときの落差を、徒歩の落差に直した値（ダメージが同じになる落差）。
     * 徒歩の割増の表（{@link ActionCosts#dropRiskPenalty}）と死ぬ落差の判定をそのまま使うため。
     */
    private int missDropAsWalker(int x, int y, int z) {
        int obstacleY = owner.scans.firstNonAirBelow(x, y - 1, z);
        if (obstacleY == ColumnScans.NOTHING_BELOW || obstacleY == ColumnScans.UNREADABLE_BELOW) {
            return owner.view.fatalFallBlocks();
        }
        int damage = fallDamage(y - obstacleY - 1);
        return damage == 0 ? 0 : ActionCosts.SAFE_FALL_BLOCKS + damage;
    }

    /** 元の足場から進む向きに{@code row}列目（前の列が0、後ろが-1）の{@code lane}本目のセル。 */
    private static int rowX(PathNode from, int dx, int dz, int row, int lane) {
        if (dx == 0) {
            return from.x + lane;
        }
        return dx > 0 ? from.x + 1 + row : from.x - row;
    }

    private static int rowZ(PathNode from, int dx, int dz, int row, int lane) {
        if (dz == 0) {
            return from.z + lane;
        }
        return dz > 0 ? from.z + 1 + row : from.z - row;
    }

    private boolean rowSupported(PathNode from, int dx, int dz, int row, int y) {
        for (int lane = 0; lane < 2; lane++) {
            long floor = owner.view.cell(rowX(from, dx, dz, row, lane), y - 1, rowZ(from, dx, dz, row, lane));
            if (CellData.standable(floor) && !CellData.sneakRequired(floor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 足場の4列の{@code bottom..top}を体が占められるか。開けて通るドア・ゲートがあれば開ける手間を1回だけ、
     * 塞がっていれば{@link ActionCosts#INFEASIBLE}。
     */
    private double bodyCost(int x, int z, int bottom, int top) {
        boolean door = false;
        for (int cx = x; cx <= x + 1; cx++) {
            for (int cz = z; cz <= z + 1; cz++) {
                for (int y = bottom; y <= top; y++) {
                    long cell = owner.view.cell(cx, y, cz);
                    if (CellData.occupiableWithoutDigging(cell)) {
                        continue;
                    }
                    if (!CellData.openable(cell)) {
                        return ActionCosts.INFEASIBLE;
                    }
                    door = true;
                }
            }
        }
        return door ? ActionCosts.OPEN_DOOR_OVERHEAD_TICKS : 0.0;
    }

    private boolean clearWithoutDigging(int x, int z, int bottom, int top) {
        for (int y = bottom; y <= top; y++) {
            if (!CellData.occupiableWithoutDigging(owner.view.cell(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 4列のどれかの床に立てるか。マグマブロックは支えに数えない——乗り物はスニークできないので、
     * 踏んでいる間ずっと焼ける。
     */
    private boolean supported(int x, int z, int y) {
        boolean standing = false;
        for (int cx = x; cx <= x + 1; cx++) {
            for (int cz = z; cz <= z + 1; cz++) {
                long floor = owner.view.cell(cx, y - 1, cz);
                if (CellData.sneakRequired(floor)) {
                    return false;
                }
                standing |= CellData.standable(floor);
            }
        }
        return standing;
    }

    /**
     * 足場の4列のどこにも水が無いか。体の高さに加えて足場の1つ下も見る——水の上に張り出した列があると、
     * 足場どうしの境目を進む乗り物は少し逸れただけで深みに入り、目が浸かると乗り手が降ろされる
     * （{@code dismounts_underwater}）。浅瀬も渡らない: 岸のなだらかな湖では浅瀬の縁がすぐ深みになる。
     */
    private boolean dry(int x, int z, int y) {
        for (int cx = x; cx <= x + 1; cx++) {
            for (int cz = z; cz <= z + 1; cz++) {
                for (int cy = y - 1; cy < y + height; cy++) {
                    if (CellData.water(owner.view.cell(cx, cy, cz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 足場の4列で一番遅い進み方（蜘蛛の巣・ソウルサンド）。 */
    private double walkCost(int x, int z, int y) {
        double slowest = 0.0;
        for (int cx = x; cx <= x + 1; cx++) {
            for (int cz = z; cz <= z + 1; cz++) {
                slowest = Math.max(slowest, owner.stepCost(cx, y, cz));
            }
        }
        return slowest;
    }

    /**
     * 移動で体が通るセル。着いた足場は着いた高さから、元の足場との外接矩形のうち2つの足場の外の列
     * （斜めの角・跳び越える隙間）は高い方の段から、どちらも高い方の頭まで。斜めに下りる手の角の列は
     * 元の段の高さでしか空きを確かめていない（{@link #addDiagonal}）ので、下りた先の高さを含めると塞がった
     * セルが混ざる。
     */
    List<BlockPos> bodyCells(PathNode from, PathNode to) {
        int minX = Math.min(from.x, to.x);
        int maxX = Math.max(from.x, to.x) + 1;
        int minZ = Math.min(from.z, to.z);
        int maxZ = Math.max(from.z, to.z) + 1;
        int top = Math.max(from.y, to.y) + height - 1;
        List<BlockPos> cells = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean origin = x >= from.x && x <= from.x + 1 && z >= from.z && z <= from.z + 1;
                boolean target = x >= to.x && x <= to.x + 1 && z >= to.z && z <= to.z + 1;
                if (origin && !target) {
                    continue;
                }
                for (int y = target ? to.y : Math.max(from.y, to.y); y <= top; y++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(cells);
    }
}
