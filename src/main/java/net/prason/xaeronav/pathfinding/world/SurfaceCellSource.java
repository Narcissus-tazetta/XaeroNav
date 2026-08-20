package net.prason.xaeronav.pathfinding.world;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;

/**
 * {@link SurfaceGrid}（廊下限定・ブロック解像度のXaero地表データ）を{@link CellSource}として
 * {@link net.prason.xaeronav.pathfinding.astar.AStarPathfinder}に渡すためのアダプタ（長距離ルート層2）。
 *
 * <p>実ブロックの3D構造（洞窟・張り出し・建物）は見えないので、1列(x,z)につき地表高さ1つだけから
 * 「立てる／通れる／水／溶岩」を合成する。地表より下は実体不明として立てるが掘れない扱いにし、
 * 詳細探索（層3）が前提とする掘削は層2に持ち込まない——{@code CellData}のビットだけを共有するので、
 * 既存の{@code AStarPathfinder}の移動生成（Traverse/Ascend/Descend/Fall/Swim/JumpGap）が無改修で動く。
 */
public final class SurfaceCellSource implements CellSource {

    private final SurfaceGrid grid;
    private final SearchBounds bounds;
    private final boolean jumpGapEnabled;
    private final int maxSubmergedRunBlocks;

    public SurfaceCellSource(SurfaceGrid grid, SearchBounds bounds, boolean jumpGapEnabled,
                             int maxSubmergedRunBlocks) {
        this.grid = grid;
        this.bounds = bounds;
        this.jumpGapEnabled = jumpGapEnabled;
        this.maxSubmergedRunBlocks = maxSubmergedRunBlocks;
    }

    @Override
    public long cell(int x, int y, int z) {
        byte kind = grid.kindAt(x, z);
        short ground = grid.groundHeightAt(x, z);
        if (kind == CoarseMap.NO_DATA || ground == SurfaceGrid.UNKNOWN_HEIGHT) {
            return CellData.ABSENT;
        }
        short ceiling = kind == CoarseMap.WATER ? grid.surfaceHeightAt(x, z) : ground;
        if (y > ceiling) {
            return air();
        }
        if (kind == CoarseMap.WATER && y > ground) {
            return CellData.withDigTicks(CellData.PRESENT | CellData.WATER, 0.0);
        }
        if (y == ground && kind == CoarseMap.LAVA) {
            return CellData.withDigTicks(CellData.PRESENT | CellData.LAVA, Double.POSITIVE_INFINITY);
        }
        // 地表そのもの、または地表より下——どちらも「実体不明の固い地面」として同じ扱いにする
        return solidGround();
    }

    private static long air() {
        return CellData.withDigTicks(CellData.PRESENT | CellData.PASSABLE_EMPTY, 0.0);
    }

    /** 実体不明の地面。立てるが掘れない——層2は掘削を扱わない。 */
    private static long solidGround() {
        return CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE, Double.POSITIVE_INFINITY);
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return bounds;
    }

    /** 層2はブロック設置による橋渡しを提案しない——実体不明の地形の上に何を置けるかは分からない。 */
    @Override
    public boolean canPlaceBlocks() {
        return false;
    }

    @Override
    public boolean jumpGapEnabled() {
        return jumpGapEnabled;
    }

    /** {@link #canPlaceBlocks()}がfalseなので橋自体を提示しない。 */
    @Override
    public boolean lavaBridgingEnabled() {
        return false;
    }

    /** 橋を提示しないので上限に意味は無い。 */
    @Override
    public int maxBridgeRunBlocks() {
        return 0;
    }

    /**
     * 層2も潜水の上限を持つ。空気の量はプレイヤーの状態ではなくバニラの固定値なので、
     * 層2が知らない情報（体力・持ち物）に依存しない——{@link #maxFallDamagePoints}のように
     * 0で無効化する理由が無い。層2の水柱は{@code (水底, 水面]}として持っているので、
     * ここで切らないと廊下の解が水底沿いに潜る経路を返し、層3と食い違う。
     */
    @Override
    public int maxSubmergedRunBlocks() {
        return maxSubmergedRunBlocks;
    }

    /** 層2はプレイヤーの状態（体力・持ち物）を知らないので、痛い降下も水バケツMLGも提案しない。 */
    @Override
    public int maxFallDamagePoints() {
        return 0;
    }

    /** 層2は次元も水の有無も知らないので、どこでも安全な終端速度の下限に留める。 */
    @Override
    public double minDescentTicksPerBlock() {
        return ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK;
    }

    @Override
    public boolean canMlgWaterBucket() {
        return false;
    }

    @Override
    public int openSkyY(int x, int z) {
        byte kind = grid.kindAt(x, z);
        short height = kind == CoarseMap.WATER ? grid.surfaceHeightAt(x, z) : grid.groundHeightAt(x, z);
        return height == SurfaceGrid.UNKNOWN_HEIGHT ? Integer.MAX_VALUE : height + 1;
    }
}
