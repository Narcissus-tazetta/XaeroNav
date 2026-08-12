package net.prason.xaeronav.pathfinding.world;

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

    public SurfaceCellSource(SurfaceGrid grid, SearchBounds bounds) {
        this.grid = grid;
        this.bounds = bounds;
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
    public int openSkyY(int x, int z) {
        byte kind = grid.kindAt(x, z);
        short height = kind == CoarseMap.WATER ? grid.surfaceHeightAt(x, z) : grid.groundHeightAt(x, z);
        return height == SurfaceGrid.UNKNOWN_HEIGHT ? Integer.MAX_VALUE : height + 1;
    }
}
