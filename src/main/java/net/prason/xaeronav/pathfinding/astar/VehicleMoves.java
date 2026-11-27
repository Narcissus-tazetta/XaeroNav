package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.pathfinding.world.StanceFinder;

/**
 * Create Aeronautics（Sable）の車輪の付いた車に乗ったままの移動候補。
 *
 * <p><b>ノードの座標は幅×幅の足場の真ん中の列</b>で、足場は各軸で{@code c - (幅-1)/2}から幅だけ並ぶ。
 * 幅が奇数なら乗り物の中心がそのセルの中心に来て、徒歩の経路と同じ描き方（セルの中心を結ぶ）でずれない。
 * 偶数なら本当の中心は半マス大きい側の境目だが、案内の線としては足りる。
 *
 * <p>車は向きと旋回半径を持つ長方形だが、探索は幅×幅の正方形で近似する（向きを状態に持つと探索の状態が増える）。
 * 長い車が直角に曲がれない所も通れることにはなるが、その幅の道は塞がっていないので、運転で回り込める。
 *
 * <p>段差は1段まで歩くように昇り降りし、跳ばない・掘らない・置かない・扉を開けない・水に入らない。
 * 立てるかは足場を4つの象限に分けて、どの象限にも床のある列があること（車輪は四隅にあるので、
 * 馬のように「どこか1列」で支えると崖へ半分はみ出した足場を許してしまう）。象限の中のどの列でもよいので、
 * 道の両側に半分弱ずつははみ出せる。
 *
 * <p>速さは疾走の2倍で固定する。実際の速さは作りで大きく変わり、読み取れない。経路の値段で効くのは
 * 降りて歩く手との比較だけなので、速い側（降りにくい側）に置く。
 */
final class VehicleMoves implements RiddenMoves {

    /** 疾走の1ブロックに対する、車の1ブロックの時間の比。 */
    private static final double PACE = 0.5;
    /** 降りる手間（tick）。{@link MountMoves}と同じ見込み。推定値 */
    private static final double DISMOUNT_TICKS = 10.0;
    /** 始点を探す深さ。座席の高さと関係なく、案内に使う位置は呼び出し側が地面へ下ろしてある。 */
    private static final int START_SEARCH_DOWN = 4;

    private final AStarPathfinder owner;
    private final int width;
    /** 足場から車の上端（乗り手の目）までを含む体の高さ。 */
    private final int height;
    /** ノードの座標から足場の始まりまで。 */
    private final int back;
    /** 足場の中で、一番高い床より何段低い列まで支えに数えるか（坂・段の縁で傾いた車）。 */
    private final int tilt;

    VehicleMoves(AStarPathfinder owner, MountState mount) {
        this.owner = owner;
        this.width = mount.width();
        this.height = mount.height();
        this.back = (width - 1) / 2;
        this.tilt = Math.max(1, (width - 1) / 2);
    }

    @Override
    public double ticksPerBlock() {
        return ActionCosts.SPRINT_ONE_BLOCK * PACE;
    }

    @Override
    public int span() {
        return width;
    }

    @Override
    public int low(int coordinate) {
        return coordinate - back;
    }

    /** 足場の床は列ごとに1段まで違いうる（象限のどこかで支えればよい）ので、ゴールの列の高さとは1段ずれうる。 */
    @Override
    public boolean reachesGoalHeight(int nodeY, int goalY) {
        return Math.abs(nodeY - goalY) <= 1;
    }

    @Override
    public int safeFallBlocks() {
        return 1;
    }

    @Override
    public void expand(PathNode from) {
        for (int i = 0; i < AStarPathfinder.CARDINAL_DX.length; i++) {
            addStep(from, AStarPathfinder.CARDINAL_DX[i], AStarPathfinder.CARDINAL_DZ[i]);
        }
        for (int i = 0; i < AStarPathfinder.DIAGONAL_DX.length; i++) {
            addDiagonal(from, AStarPathfinder.DIAGONAL_DX[i], AStarPathfinder.DIAGONAL_DZ[i]);
        }
        addDismount(from);
    }

    /**
     * 乗っている始点。{@code start}は案内に使う位置（乗り手の真下の地面）のセルで、そこを真ん中にした足場から
     * 試す。車の中心は乗り手の位置から少しずれうるので、隣の列と1段上・数段下まで寄せる。
     * 乗ったままでは居られない（足場が狭い・水の中）なら{@code null}——降りて歩く経路になる。
     */
    @Override
    public @Nullable BlockPos resolveStart(BlockPos start) {
        int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int dy : new int[] {0, 1, -1, -2, -3, -START_SEARCH_DOWN}) {
            int y = start.getY() + dy;
            for (int[] offset : offsets) {
                int x = start.getX() + offset[0];
                int z = start.getZ() + offset[1];
                if (fits(x, z, y)) {
                    return new BlockPos(x, y, z);
                }
            }
        }
        return null;
    }

    /**
     * 降りて、ここから先は歩く。降りて立つのは足場の真ん中から近い順に、人が立てるセル。乗り直しは無く、
     * 車を置いていく割増（{@code mountLeaveBehindTicks}）を払う。
     */
    private void addDismount(PathNode from) {
        double cost = DISMOUNT_TICKS + owner.view.mountLeaveBehindTicks();
        for (int ring = 0; ring <= back + 1; ring++) {
            boolean found = false;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = 0; dy >= -1; dy--) {
                        int x = from.x + dx;
                        int y = from.y + dy;
                        int z = from.z + dz;
                        if (StanceFinder.isStance(owner.view, x, y, z)) {
                            owner.relax(from, x, y, z, cost, MoveKind.DISMOUNT);
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (found) {
                return;
            }
        }
    }

    private void addStep(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int z = from.z + dz;
        int y = from.y;
        int level = sameLevelAhead(from, dx, dz) ? y : levelAfterStep(x, z, y);
        if (level == Integer.MIN_VALUE) {
            return;
        }
        if (level > y && !clear(from.x, from.z, y + height, y + height)) {
            // 1段上がる間、元の足場の上も一段高い体が通る
            return;
        }
        owner.relaxMounted(from, x, level, z, cost(x, z, level, 1.0, level != y), MoveKind.DRIVE);
    }

    /**
     * 斜め1マス。足場の外接矩形の残り2つの角も通るので、縦横それぞれへ1マス進んだ足場も空いていること
     * （どちらも同じ高さで通れる場合だけ。段差の角を斜めに切ると車体が角に乗り上げる）。
     */
    private void addDiagonal(PathNode from, int dx, int dz) {
        int x = from.x + dx;
        int z = from.z + dz;
        int y = from.y;
        if (sameLevelDiagonal(from, dx, dz)) {
            owner.relaxMounted(from, x, y, z, cost(x, z, y, ActionCosts.DIAGONAL_DISTANCE, false), MoveKind.DRIVE);
            return;
        }
        int level = levelAfterStep(x, z, y);
        if (level == Integer.MIN_VALUE) {
            return;
        }
        int high = Math.max(y, level);
        if (!clear(from.x + dx, from.z, high, high + height - 1) || !clear(from.x, from.z + dz, high, high + height - 1)) {
            return;
        }
        if (level > y && !clear(from.x, from.z, y + height, y + height)) {
            return;
        }
        owner.relaxMounted(from, x, level, z, cost(x, z, level, ActionCosts.DIAGONAL_DISTANCE, level != y), MoveKind.DRIVE);
    }

    /**
     * 東西南北へ1マス、同じ高さのまま進めるか。元の足場は着いたときに体の高さが空いていて水も無いと確かめてあるので、
     * 前に出る1列だけを見れば足りる（幅7で1手あたりのセルの読み出しが約7分の1になる）。支えは足場全体で見直す。
     */
    private boolean sameLevelAhead(PathNode from, int dx, int dz) {
        int y = from.y;
        int x = from.x + dx;
        int z = from.z + dz;
        int lowX = x - back;
        int lowZ = z - back;
        int highX = lowX + width - 1;
        int highZ = lowZ + width - 1;
        // 前に出る列: 進む軸では新しい端の1列、もう一方の軸では足場の幅全部
        int x0 = dx > 0 ? highX : lowX;
        int x1 = dx < 0 ? lowX : highX;
        int z0 = dz > 0 ? highZ : lowZ;
        int z1 = dz < 0 ? lowZ : highZ;
        return clearRect(x0, x1, z0, z1, y, y + height - 1) && dryRect(x0, x1, z0, z1, y) && supported(x, z, y);
    }

    /**
     * 斜めに1マス、同じ高さのまま進めるか。通る4つの足場（元・縦・横・行き先）を合わせた矩形のうち、元の足場の外に
     * 出るL字の2本だけを見る。水はL字の全部で見る（行き先の足場だけでなく角の列も車体が通る）。
     */
    private boolean sameLevelDiagonal(PathNode from, int dx, int dz) {
        int y = from.y;
        int lowX = from.x - back;
        int lowZ = from.z - back;
        int highX = lowX + width - 1;
        int highZ = lowZ + width - 1;
        int edgeX = dx > 0 ? highX + 1 : lowX - 1;
        int edgeZ = dz > 0 ? highZ + 1 : lowZ - 1;
        int fromX = Math.min(lowX, edgeX);
        int toX = Math.max(highX, edgeX);
        int fromZ = Math.min(lowZ, edgeZ);
        int toZ = Math.max(highZ, edgeZ);
        int top = y + height - 1;
        return clearRect(edgeX, edgeX, fromZ, toZ, y, top) && clearRect(fromX, toX, edgeZ, edgeZ, y, top)
                && dryRect(edgeX, edgeX, fromZ, toZ, y) && dryRect(fromX, toX, edgeZ, edgeZ, y)
                && supported(from.x + dx, from.z + dz, y);
    }

    /** 隣の足場へ進んだあとの高さ。同じ高さ・1段上・1段下のどれでも立てなければ{@link Integer#MIN_VALUE}。 */
    private int levelAfterStep(int x, int z, int y) {
        if (clear(x, z, y, y + height - 1)) {
            if (supported(x, z, y)) {
                return dry(x, z, y) ? y : Integer.MIN_VALUE;
            }
            // 1段下りる。下りた足場の一番下の段も空いていること
            if (clear(x, z, y - 1, y - 1) && supported(x, z, y - 1)) {
                return dry(x, z, y - 1) ? y - 1 : Integer.MIN_VALUE;
            }
            return Integer.MIN_VALUE;
        }
        if (clear(x, z, y + 1, y + height) && supported(x, z, y + 1)) {
            return dry(x, z, y + 1) ? y + 1 : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    /** 始点にそのまま居られるか。 */
    private boolean fits(int x, int z, int y) {
        return clear(x, z, y, y + height - 1) && supported(x, z, y) && dry(x, z, y);
    }

    private double cost(int x, int z, int y, double distance, boolean changesLevel) {
        // 床の遅さ（ソウルサンド・蜘蛛の巣）は真ん中の列で代表する。足場全体の一番遅い列を見ると、
        // 広い車ほど道の端の1列で値段が跳ねる
        double walk = owner.stepCost(x, y, z) * distance;
        if (changesLevel) {
            walk += ActionCosts.STEP_TRANSITION_TICKS;
        }
        return walk * PACE;
    }

    /** 足場の全列の{@code bottom..top}が、掘らず扉も開けずに空いているか。 */
    private boolean clear(int x, int z, int bottom, int top) {
        return clearRect(x - back, x - back + width - 1, z - back, z - back + width - 1, bottom, top);
    }

    private boolean clearRect(int x0, int x1, int z0, int z1, int bottom, int top) {
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                for (int cy = bottom; cy <= top; cy++) {
                    if (!CellData.occupiableWithoutDigging(owner.view.cell(cx, cy, cz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 高さ{@code y}の足場に立てるか。4つの象限のどれにも支えの列があり、少なくとも1列は床がちょうど
     * {@code y - 1}にあること（足場の高さは一番高い床）。支えの列は床が{@code y - 1}か、その1段下
     * （段の縁をまたいで傾いた車）。
     *
     * <p>幅が奇数なら真ん中の行・列はどの象限にも数えない——数えると真ん中の1列だけの橋でも全象限が
     * 支えられていることになり、幅7の車が1マス幅の橋を渡れてしまう。幅1は1列そのもの。
     * マグマブロックがあれば立たない（座っていても焼けるかは確かめていないので、馬と同じく避ける）。
     */
    private boolean supported(int x, int z, int y) {
        int lowX = x - back;
        int lowZ = z - back;
        if (width == 1) {
            long floor = owner.view.cell(lowX, y - 1, lowZ);
            return CellData.standable(floor) && !CellData.sneakRequired(floor);
        }
        int half = width / 2;
        boolean level = false;
        boolean[] quadrant = new boolean[4];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < width; j++) {
                int cx = lowX + i;
                int cz = lowZ + j;
                long floor = owner.view.cell(cx, y - 1, cz);
                boolean support;
                if (CellData.standable(floor)) {
                    if (CellData.sneakRequired(floor)) {
                        return false;
                    }
                    level = true;
                    support = true;
                } else {
                    support = false;
                    for (int depth = 1; depth <= tilt && CellData.occupiableWithoutDigging(floor); depth++) {
                        floor = owner.view.cell(cx, y - 1 - depth, cz);
                        if (CellData.standable(floor)) {
                            if (CellData.sneakRequired(floor)) {
                                return false;
                            }
                            support = true;
                            break;
                        }
                    }
                }
                if (!support) {
                    continue;
                }
                boolean west = i < half;
                boolean east = i >= width - half;
                boolean north = j < half;
                boolean south = j >= width - half;
                quadrant[0] |= west && north;
                quadrant[1] |= east && north;
                quadrant[2] |= west && south;
                quadrant[3] |= east && south;
            }
        }
        return level && quadrant[0] && quadrant[1] && quadrant[2] && quadrant[3];
    }

    /** 足場の全列の体の高さと、その1つ下に水が無いか。水際の1列が深みでも、車は少し逸れただけで入る。 */
    private boolean dry(int x, int z, int y) {
        return dryRect(x - back, x - back + width - 1, z - back, z - back + width - 1, y);
    }

    private boolean dryRect(int x0, int x1, int z0, int z1, int y) {
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                for (int cy = y - 1; cy < y + height; cy++) {
                    if (CellData.water(owner.view.cell(cx, cy, cz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 着いた足場の体の通るセル。元の足場と重なる所も含める（車は動く間ずっとその列を占める）。 */
    @Override
    public List<BlockPos> bodyCells(PathNode from, PathNode to) {
        int lowX = to.x - back;
        int lowZ = to.z - back;
        int top = Math.max(from.y, to.y) + height - 1;
        List<BlockPos> cells = new ArrayList<>(width * width * height);
        for (int x = lowX; x < lowX + width; x++) {
            for (int z = lowZ; z < lowZ + width; z++) {
                for (int y = to.y; y <= top; y++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(cells);
    }
}
