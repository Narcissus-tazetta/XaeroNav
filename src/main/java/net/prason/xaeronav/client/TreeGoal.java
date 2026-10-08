package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * 自然の木・巨大キノコの上に寄せられた目的地を、木の下の地面へ下ろす。
 *
 * <p>Xaeroの地図の高さは葉の上で止まるので、地図クリックや地図から作ったウェイポイントで森を指すと目的地が樹冠の上になり、
 * 木に登らないと到着にならない。プレイヤーが置いた葉は{@link LeavesBlock#PERSISTENT}がtrueになるので、葉で作った家や
 * 木の上の家を指したときは下ろさない。原木・キノコブロックにはそのフラグが無いので、原木は自然の葉と接しているときだけ、
 * キノコブロックは常に自然物と見なす。<b>メインスレッド専用</b>（{@link Level}を読む）。
 */
final class TreeGoal {
    // 到着半径の既定3より1広い。幹の真上を指したとき、根元の地面がこの範囲に見つかれば着いたことになる
    private static final int SEARCH_RADIUS = 4;
    // 2×2の大木（ジャングル・トウヒ）の高さに余裕を持たせた
    private static final int MAX_DESCENT = 64;

    private TreeGoal() {
    }

    /** {@code goal}の足元が自然の木なら、木を抜けた先の地面に立てる位置。そうでなければ{@code goal}そのもの。 */
    static BlockPos groundBelow(Level level, BlockPos goal) {
        BlockPos below = goal.below();
        if (!onTree(level, below, level.getBlockState(below))) {
            return goal;
        }
        List<int[]> columns = new ArrayList<>();
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                if (dx * dx + dz * dz <= SEARCH_RADIUS * SEARCH_RADIUS) {
                    columns.add(new int[] {dx, dz});
                }
            }
        }
        columns.sort(Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));
        for (int[] c : columns) {
            BlockPos ground = groundInColumn(level, goal.getX() + c[0], goal.getY(), goal.getZ() + c[1]);
            if (ground != null) {
                return ground;
            }
        }
        return goal;
    }

    /** 列を{@code fromY - 1}から下へ、木と通り抜けられるセルを抜けた先の地面。幹に当たるか立てなければ{@code null}。 */
    private static BlockPos groundInColumn(Level level, int x, int fromY, int z) {
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            return null;
        }
        int minY = fromY - MAX_DESCENT;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = fromY - 1; y > minY; y--) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (trunk(state)) {
                return null;
            }
            if (canopy(state) || CellData.occupiableWithoutDigging(CellData.flagsOf(state))) {
                continue;
            }
            return PathfindingState.standableAt(level, x, y + 1, z) ? new BlockPos(x, y + 1, z) : null;
        }
        return null;
    }

    /** 足元のブロックが自然の木の一部か。原木は木の上に建てた家の床と区別するため、自然の葉と接しているときだけ。 */
    private static boolean onTree(Level level, BlockPos pos, BlockState state) {
        if (canopy(state) || state.is(Blocks.MUSHROOM_STEM)) {
            return true;
        }
        if (!state.is(BlockTags.LOGS)) {
            return false;
        }
        BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (canopy(level.getBlockState(near.setWithOffset(pos, dx, dy, dz)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 木の上を覆う部分: 自然の葉・巨大キノコの傘・ネザーの巨大菌の傘。 */
    private static boolean canopy(BlockState state) {
        if (state.getBlock() instanceof LeavesBlock) {
            return !state.getValue(LeavesBlock.PERSISTENT);
        }
        return state.getBlock() instanceof HugeMushroomBlock && !state.is(Blocks.MUSHROOM_STEM)
                || state.is(BlockTags.WART_BLOCKS);
    }

    /** 幹: 原木（ネザーの菌の柄を含む）・巨大キノコの柄。この上に下ろすと木に登るのと変わらない。 */
    private static boolean trunk(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(Blocks.MUSHROOM_STEM);
    }
}
