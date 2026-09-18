package net.prason.xaeronav.pathfinding.astar;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * <b>実験用。</b>Xaeroの地図が持っている情報だけから組み直した世界。
 *
 * <p>航法グラフを窓の外で作るとき、実機で使えるのはこれだけ——ここで作ったグラフが
 * 本物の地形のグラフにどこまで近いかが、抽象グラフの到達点を決める。
 *
 * <ul>
 * <li><b>地表</b>（現世・エンド）: 列ごとに最上面のブロックだけ。水面の下の底も分かる
 *     （{@code XaeroMapReader#readSurfaceDetailed}）。最上面より下は「普通の石」、何も無い列は奈落</li>
 * <li><b>床</b>（ネザー）: 列ごとに「空気の下にある固体の面」を上から最大8枚
 *     （{@code XaeroMapModel}の濃いモデルと同じ規則）。床の上の空洞がどこまで続くかは分からないので
 *     {@link #FLOOR_HEADROOM}だけ空気にし、それ以外は石。列は2ブロックおき（{@code SAMPLE_STEP}）</li>
 * </ul>
 */
final class XaeroSynthCells implements CellSource {

    /** 床の上に空気として置く高さ。立った姿勢（2）＋跳躍の頭上（1）。 */
    private static final int FLOOR_HEADROOM = 3;

    private static final int MAX_FLOORS = 8;

    /** ネザーの床を読む列の間隔（{@code XaeroMapReader#SAMPLE_STEP}の床版）。 */
    private static final int FLOOR_SAMPLE_STEP = 2;

    private static final FakeCells SAMPLE = FakeCells.empty(new SearchBounds(0, 0, 0, 1, 1, 1))
            .set(0, 0, 0, FakeCells.STONE);
    private static final long STONE = SAMPLE.cell(0, 0, 0);
    private static final long AIR = SAMPLE.cell(1, 1, 1);

    private final CellSource real;
    private final SearchBounds bounds;
    private final boolean floors;
    private final int sizeX;
    /** 地表: [最上面Y, 底のY]。床: 床のY×{@link #MAX_FLOORS}（無ければMIN_VALUE）。 */
    private final int[] heights;
    private final long[] surfaceCells;

    private XaeroSynthCells(CellSource real, boolean floors) {
        this.real = real;
        this.bounds = real.bounds();
        this.floors = floors;
        this.sizeX = bounds.maxX() - bounds.minX() + 1;
        int sizeZ = bounds.maxZ() - bounds.minZ() + 1;
        int per = floors ? MAX_FLOORS : 2;
        this.heights = new int[sizeX * sizeZ * per];
        this.surfaceCells = floors ? new long[sizeX * sizeZ * MAX_FLOORS] : new long[sizeX * sizeZ];
        java.util.Arrays.fill(heights, Integer.MIN_VALUE);
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                int column = (x - bounds.minX()) + (z - bounds.minZ()) * sizeX;
                if (floors) {
                    readFloors(column, x & ~(FLOOR_SAMPLE_STEP - 1), z & ~(FLOOR_SAMPLE_STEP - 1));
                } else {
                    readSurface(column, x, z);
                }
            }
        }
    }

    /** 地表だけの世界（現世・エンド）。 */
    static XaeroSynthCells surface(CellSource real) {
        return new XaeroSynthCells(real, false);
    }

    /** 洞窟の床だけの世界（ネザー）。 */
    static XaeroSynthCells floors(CellSource real) {
        return new XaeroSynthCells(real, true);
    }

    private void readSurface(int column, int x, int z) {
        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            long cell = real.cell(x, y, z);
            if (!CellData.present(cell) || CellData.passableEmpty(cell) && !CellData.water(cell)) {
                continue;
            }
            heights[column * 2] = y;
            surfaceCells[column] = cell;
            int bottom = y;
            if (CellData.water(cell)) {
                while (bottom > bounds.minY() && CellData.water(real.cell(x, bottom - 1, z))) {
                    bottom--;
                }
                bottom--;
            }
            heights[column * 2 + 1] = bottom;
            return;
        }
    }

    private void readFloors(int column, int x, int z) {
        if (x < bounds.minX() || z < bounds.minZ()) {
            x = Math.max(x, bounds.minX());
            z = Math.max(z, bounds.minZ());
        }
        boolean airSeen = false;
        int found = 0;
        for (int y = bounds.maxY(); y >= bounds.minY() && found < MAX_FLOORS; y--) {
            long cell = real.cell(x, y, z);
            if (!CellData.present(cell)) {
                airSeen = false;
                continue;
            }
            if (CellData.passableEmpty(cell)) {
                airSeen = true;
                continue;
            }
            if (airSeen) {
                heights[column * MAX_FLOORS + found] = y;
                surfaceCells[column * MAX_FLOORS + found] = cell;
                found++;
            }
            airSeen = false;
        }
    }

    @Override
    public long cell(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return CellData.ABSENT;
        }
        int column = (x - bounds.minX()) + (z - bounds.minZ()) * sizeX;
        if (floors) {
            for (int i = 0; i < MAX_FLOORS; i++) {
                int floor = heights[column * MAX_FLOORS + i];
                if (floor == Integer.MIN_VALUE) {
                    break;
                }
                if (y == floor) {
                    return surfaceCells[column * MAX_FLOORS + i];
                }
                if (y > floor && y <= floor + FLOOR_HEADROOM) {
                    return AIR;
                }
            }
            return STONE;
        }
        int top = heights[column * 2];
        if (top == Integer.MIN_VALUE || y > top) {
            return AIR;
        }
        long surface = surfaceCells[column];
        if (CellData.water(surface)) {
            return y > heights[column * 2 + 1] ? surface : STONE;
        }
        return y == top ? surface : STONE;
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return real.isInBounds(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return bounds;
    }

    @Override
    public boolean canPlaceBlocks() {
        return real.canPlaceBlocks();
    }

    @Override
    public boolean bridgingAllowedBySettings() {
        return real.bridgingAllowedBySettings();
    }

    @Override
    public int placedBlockBudget() {
        return real.placedBlockBudget();
    }

    @Override
    public boolean jumpGapEnabled() {
        return real.jumpGapEnabled();
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return real.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return real.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return real.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return real.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return real.maxSubmergedTicks();
    }

    @Override
    public int maxFallDamagePoints() {
        return real.maxFallDamagePoints();
    }

    @Override
    public int fatalFallBlocks() {
        return real.fatalFallBlocks();
    }

    @Override
    public boolean avoidRiskyJumps() {
        return real.avoidRiskyJumps();
    }

    @Override
    public double minDescentTicksPerBlock() {
        return real.minDescentTicksPerBlock();
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return real.minDescentTicksPerBlock(maxFallDamagePoints);
    }

    @Override
    public boolean canMlgWaterBucket() {
        return real.canMlgWaterBucket();
    }

    @Override
    public boolean boatAvailable() {
        return real.boatAvailable();
    }

    @Override
    public int openSkyY(int x, int z) {
        return real.openSkyY(x, z);
    }

    /** 窓の中は本物、外は合成という世界（読み込み済みチャンクとXaeroの地図の組み合わせ）。 */
    static CellSource hybrid(CellSource real, CellSource synth, BlockPos center, int radius) {
        return new CellSource() {
            @Override
            public long cell(int x, int y, int z) {
                boolean inside = Math.abs(x - center.getX()) <= radius && Math.abs(z - center.getZ()) <= radius;
                return inside ? real.cell(x, y, z) : synth.cell(x, y, z);
            }

            @Override
            public boolean isInBounds(int x, int y, int z) {
                return real.isInBounds(x, y, z);
            }

            @Override
            public SearchBounds bounds() {
                return real.bounds();
            }

            @Override
            public boolean canPlaceBlocks() {
                return real.canPlaceBlocks();
            }

            @Override
            public boolean bridgingAllowedBySettings() {
                return real.bridgingAllowedBySettings();
            }

            @Override
            public int placedBlockBudget() {
                return real.placedBlockBudget();
            }

            @Override
            public boolean jumpGapEnabled() {
                return real.jumpGapEnabled();
            }

            @Override
            public boolean lavaBridgingEnabled() {
                return real.lavaBridgingEnabled();
            }

            @Override
            public int maxBridgeRunBlocks() {
                return real.maxBridgeRunBlocks();
            }

            @Override
            public int maxLavaBridgeRunBlocks() {
                return real.maxLavaBridgeRunBlocks();
            }

            @Override
            public int maxVoidBridgeRunBlocks() {
                return real.maxVoidBridgeRunBlocks();
            }

            @Override
            public int maxSubmergedTicks() {
                return real.maxSubmergedTicks();
            }

            @Override
            public int maxFallDamagePoints() {
                return real.maxFallDamagePoints();
            }

            @Override
            public int fatalFallBlocks() {
                return real.fatalFallBlocks();
            }

            @Override
            public boolean avoidRiskyJumps() {
                return real.avoidRiskyJumps();
            }

            @Override
            public double minDescentTicksPerBlock() {
                return real.minDescentTicksPerBlock();
            }

            @Override
            public double minDescentTicksPerBlock(int maxFallDamagePoints) {
                return real.minDescentTicksPerBlock(maxFallDamagePoints);
            }

            @Override
            public boolean canMlgWaterBucket() {
                return real.canMlgWaterBucket();
            }

            @Override
            public boolean boatAvailable() {
                return real.boatAvailable();
            }

            @Override
            public int openSkyY(int x, int z) {
                return real.openSkyY(x, z);
            }
        };
    }
}
