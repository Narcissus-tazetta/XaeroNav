package net.prason.xaeronav.pathfinding.world;

import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 既に計画済みの区間を歩き終えた時点の地形。経路を継ぎ足す探索は、その末端へ着くまでに
 * <b>掘ってあるはず・置いてあるはず</b>の変化を見なければならない——見ないと、まだ塞がっている
 * セルを通る前提や、既に空いているセルを掘り直す前提の経路が出る。
 *
 * <p>ワールドは書き換えない。構築時に委譲先も読まない（差分だけを持つ）。
 */
public final class PlannedCellSource implements CellSource {
    private static final long REMOVED = CellData.PRESENT | CellData.PASSABLE_EMPTY | CellData.REPLACEABLE;
    private static final long PLACED = CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE,
            Double.POSITIVE_INFINITY);
    private final CellSource source;
    private final Long2LongOpenHashMap changes = new Long2LongOpenHashMap();

    public PlannedCellSource(CellSource source, List<PathStep> prefix, int fromStep) {
        this.source = source;
        for (int i = Math.max(0, fromStep); i < prefix.size(); i++) {
            PathStep step = prefix.get(i);
            for (BlockPos dug : step.digCells()) {
                changes.put(dug.asLong(), REMOVED);
            }
            if (step.placedBlockPos() != null) {
                changes.put(step.placedBlockPos().asLong(), PLACED);
            }
        }
    }

    @Override
    public long cell(int x, int y, int z) {
        long actual = source.cell(x, y, z);
        if (!CellData.present(actual)) {
            return actual;
        }
        long planned = changes.get(BlockPos.asLong(x, y, z));
        if (planned == REMOVED) {
            // A newly placed unbreakable obstacle must not disappear in the plan.
            if (CellData.occupiableWithoutDigging(actual)) {
                return actual; // Keep water, ladders and other newly exposed passable terrain.
            }
            return Double.isFinite(CellData.digTicks(actual)) ? REMOVED : actual;
        }
        if (planned == PLACED && CellData.passableEmpty(actual) && CellData.replaceable(actual)) {
            return PLACED;
        }
        return actual;
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return source.isInBounds(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return source.bounds();
    }

    @Override
    public boolean canPlaceBlocks() {
        return source.canPlaceBlocks();
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return source.bridgingAllowedBySettings();
    }

    @Override
    public int placedBlockBudget() {
        return source.placedBlockBudget();
    }

    @Override
    public boolean jumpGapEnabled() {
        return source.jumpGapEnabled();
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return source.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return source.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return source.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return source.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return source.maxSubmergedTicks();
    }

    @Override
    public int maxFallDamagePoints() {
        return source.maxFallDamagePoints();
    }

    @Override
    public int fatalFallBlocks() {
        return source.fatalFallBlocks();
    }

    @Override
    public boolean avoidRiskyJumps() {
        return source.avoidRiskyJumps();
    }

    @Override
    public double minDescentTicksPerBlock() {
        return source.minDescentTicksPerBlock();
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return source.minDescentTicksPerBlock(maxFallDamagePoints);
    }

    @Override
    public boolean canMlgWaterBucket() {
        return source.canMlgWaterBucket();
    }

    @Override
    public boolean boatAvailable() {
        return source.boatAvailable();
    }

    @Override
    public boolean ridingBoat() {
        return source.ridingBoat();
    }

    @Override
    public int openSkyY(int x, int z) {
        return source.openSkyY(x, z);
    }

    @Override
    public int surfacedY(int x, int z) {
        return source.surfacedY(x, z);
    }
}
