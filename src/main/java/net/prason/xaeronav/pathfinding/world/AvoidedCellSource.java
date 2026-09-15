package net.prason.xaeronav.pathfinding.world;

import java.util.Collection;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.BlockPos;

/**
 * 指定したセルだけを「無い」ことにした地形。<b>直前に経路の再確認が不成立と判定したセルを、
 * 次の探索が選び直さないようにする。</b>
 *
 * <p>探索側のセル判定と{@code PathValidator}の判定が同じ座標で食い違うと、探索が通した経路が
 * 即座に無効と判断され、引き直した経路がまた同じセルを通る——実機報告(#47)では、同じ座標
 * ({@code 2051,63,1283}の足場)で「地形が変わった→迂回→合流失敗→引き直し」が14秒間繰り返された。
 * 食い違いそのものを無くすのが本筋だが、<b>食い違いが残っていても輪を止められる</b>のがここ。
 *
 * <p>{@link CellData#ABSENT}を返すのは、それがこのコードベースで既に「触れない・立てない・
 * 掘れない」を表す値だから（未ロードチャンクと同じ扱い）。掘って開けることもできないので、
 * 探索は必ずそのセルを避けた線を引く。
 *
 * <p>ワールドは書き換えない。構築時に委譲先も読まない（差分だけを持つ）——{@link PlannedCellSource}
 * と同じ構成。
 */
public final class AvoidedCellSource implements CellSource {

    private final CellSource source;
    private final LongSet avoided;

    private AvoidedCellSource(CellSource source, LongSet avoided) {
        this.source = source;
        this.avoided = avoided;
    }

    /**
     * 避けるセルが1つも無いなら委譲先をそのまま返す。{@link #cell}は探索1回で数百万回呼ばれるので、
     * 避けるものが無い通常時にまで1段の間接参照を挟まない。
     */
    public static CellSource wrap(CellSource source, Collection<BlockPos> avoided) {
        if (avoided.isEmpty()) {
            return source;
        }
        LongSet keys = new LongOpenHashSet(avoided.size());
        for (BlockPos pos : avoided) {
            keys.add(pos.asLong());
        }
        return new AvoidedCellSource(source, keys);
    }

    @Override
    public long cell(int x, int y, int z) {
        if (avoided.contains(BlockPos.asLong(x, y, z))) {
            return CellData.ABSENT;
        }
        return source.cell(x, y, z);
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
