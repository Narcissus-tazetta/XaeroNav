package net.prason.xaeronav.pathfinding.world;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.prason.xaeronav.pathfinding.cost.DigCost;

/**
 * 探索範囲のブロック状態を軽量キャッシュしたスナップショット（design doc §4-5）。
 *
 * <p>{@link #capture}は必ずメインスレッドから呼ぶこと。作成後の{@link #get}はワーカースレッドから
 * 安全に呼べる（Level/Playerには一切アクセスしない、読み取り専用のMapのみを参照する）。
 */
public final class WorldSnapshot {

    private final Map<BlockPos, BlockSnapshotData> cells;
    private final SearchBounds bounds;

    private WorldSnapshot(Map<BlockPos, BlockSnapshotData> cells, SearchBounds bounds) {
        this.cells = cells;
        this.bounds = bounds;
    }

    public static WorldSnapshot capture(Level level, Player player, SearchBounds bounds, boolean diggingEnabled) {
        Map<BlockPos, BlockSnapshotData> cells = new HashMap<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    mutable.set(x, y, z);
                    cells.put(mutable.immutable(), captureCell(level, player, mutable, diggingEnabled));
                }
            }
        }
        return new WorldSnapshot(cells, bounds);
    }

    private static BlockSnapshotData captureCell(Level level, Player player, BlockPos pos, boolean diggingEnabled) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        boolean water = fluid.is(FluidTags.WATER);
        boolean lava = fluid.is(FluidTags.LAVA);
        boolean passableEmpty = !lava && !water && state.getCollisionShape(level, pos).isEmpty();
        boolean standable = state.isFaceSturdy(level, pos, Direction.UP);
        boolean fallingBlock = state.getBlock() instanceof FallingBlock;

        double digTicks;
        if (passableEmpty || water) {
            digTicks = 0.0;
        } else if (lava || !diggingEnabled) {
            // 液体は掘削対象ではないので、進入不可を素手のdigTicksとして表現する。
            // diggingEnabled=falseの場合も同様に「掘って進入」という選択肢自体を消す。
            digTicks = net.prason.xaeronav.pathfinding.cost.ActionCosts.INFEASIBLE;
        } else {
            // 落下ブロック連鎖のコストはここでは足さない(AStarPathfinder側が必須セル群の最上部から
            // 一度だけスキャンする。ここで各セル個別に連鎖加算すると隣接する必須セル同士で二重計上になる)。
            digTicks = DigCost.compute(player, level, pos, state);
        }
        return new BlockSnapshotData(passableEmpty, water, lava, standable, fallingBlock, digTicks);
    }

    /** ワーカースレッドから呼び出し可。範囲外・未キャプチャの座標は{@code null}を返す。 */
    public BlockSnapshotData get(BlockPos pos) {
        return cells.get(pos);
    }

    public boolean isInBounds(BlockPos pos) {
        return bounds.contains(pos);
    }

    public SearchBounds bounds() {
        return bounds;
    }
}
