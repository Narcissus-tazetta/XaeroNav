package net.prason.xaeronav.rail;

import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;

/** ブロック状態からレールを読む。覚える側（{@code RailMemory}）と探索側（{@code ChunkView}）で同じ読み方をする。 */
public final class RailBlocks {

    private RailBlocks() {
    }

    public static boolean isRail(BlockState state) {
        return state.getBlock() instanceof BaseRailBlock;
    }

    /** {@link #isRail}が真の状態だけを渡すこと。 */
    public static int encode(BlockState state, int localX, int y, int localZ) {
        Block block = state.getBlock();
        TrackShape shape = TrackShape.valueOf(shapeOf(state).name());
        RailKind kind;
        boolean powered = false;
        if (block == Blocks.RAIL) {
            kind = RailKind.RAIL;
        } else if (block == Blocks.POWERED_RAIL) {
            kind = RailKind.POWERED;
            powered = state.getValue(PoweredRailBlock.POWERED);
        } else if (block == Blocks.ACTIVATOR_RAIL) {
            kind = RailKind.ACTIVATOR;
            powered = state.getValue(PoweredRailBlock.POWERED);
        } else if (block == Blocks.DETECTOR_RAIL) {
            kind = RailKind.DETECTOR;
            powered = state.getValue(DetectorRailBlock.POWERED);
        } else {
            kind = RailKind.OTHER;
        }
        return RailCell.pack(localX, y, localZ, shape, kind, powered);
    }

    /**
     * {@code BaseRailBlock#getShapeProperty}はNeoForgeで非推奨なので、状態の持つ値から向きを探す。
     * 向きの値はどのレールにも必ずある（{@code getShapeProperty}が抽象メソッド）。
     */
    private static RailShape shapeOf(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() == RailShape.class) {
                return (RailShape) state.getValue(property);
            }
        }
        throw new IllegalStateException("Rail block without a shape property: " + state);
    }
}
