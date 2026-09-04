package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.BlockPos;

/**
 * <b>プレイヤーの周りだけが読み込まれている世界。</b>窓の外は未ロード（{@link CellData#ABSENT}）を返す。
 *
 * <p>実機で経路が組み立てられていく過程——歩くにつれてチャンクが読み込まれ、見えた分だけ経路が
 * 伸びていく——をオフラインで再現するために要る。{@link FakeCells}をそのまま渡すと世界全体が
 * 最初から見えていることになり、<b>継ぎ足しの継ぎ目</b>という一番出やすい崩れが再現できない。
 *
 * <p>窓は正方形。バニラの描画距離が正方形にチャンクを読むのに合わせてある。
 */
public record WindowedCells(FakeCells all, BlockPos player, int radius) implements CellSource {

    @Override
    public long cell(int x, int y, int z) {
        if (Math.abs(x - player.getX()) > radius || Math.abs(z - player.getZ()) > radius) {
            return CellData.ABSENT;
        }
        return all.cell(x, y, z);
    }

    /**
     * 窓の外も「範囲内」と答える。{@code AStarPathfinder}はこの2つの組み合わせで
     * 「未ロード」と「ここまで空気しか無いと分かっている」を区別しており、範囲外にすると
     * 未ロードのチャンクが<b>底無しの奈落</b>に見える。
     */
    @Override
    public boolean isInBounds(int x, int y, int z) {
        return all.isInBounds(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return all.bounds();
    }

    @Override
    public boolean canPlaceBlocks() {
        return all.canPlaceBlocks();
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return all.bridgingAllowedBySettings();
    }

    @Override
    public int placedBlockBudget() {
        return all.placedBlockBudget();
    }

    @Override
    public boolean jumpGapEnabled() {
        return all.jumpGapEnabled();
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return all.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return all.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return all.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return all.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return all.maxSubmergedTicks();
    }

    @Override
    public int maxFallDamagePoints() {
        return all.maxFallDamagePoints();
    }

    @Override
    public int fatalFallBlocks() {
        return all.fatalFallBlocks();
    }

    @Override
    public boolean avoidRiskyJumps() {
        return all.avoidRiskyJumps();
    }

    @Override
    public double minDescentTicksPerBlock() {
        return all.minDescentTicksPerBlock();
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return all.minDescentTicksPerBlock(maxFallDamagePoints);
    }

    @Override
    public boolean canMlgWaterBucket() {
        return all.canMlgWaterBucket();
    }

    @Override
    public boolean boatAvailable() {
        return all.boatAvailable();
    }

    @Override
    public boolean ridingBoat() {
        return all.ridingBoat();
    }

    @Override
    public int openSkyY(int x, int z) {
        return all.openSkyY(x, z);
    }
}
