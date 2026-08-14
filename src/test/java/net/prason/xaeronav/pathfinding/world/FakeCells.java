package net.prason.xaeronav.pathfinding.world;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.core.BlockPos;

/**
 * テスト用の{@link CellSource}。地形を文字で書けるようにする。
 *
 * <p>本番の{@link ChunkView}は{@code Level}と{@code Player}が無いと作れず、
 * {@link CellData#flagsOf}は{@code BlockState}を要求する（＝Minecraftのレジストリ起動が要る）。
 * ここではフラグを直接組み立てることで、レジストリ抜きで探索コアを動かせるようにしている。
 *
 * <p>地形は上の行が高いYになるよう書く。実際の見た目と同じ向きで読めた方が、
 * 「この経路が出てほしい」をテストに書き写すときに間違えにくい。
 *
 * <pre>{@code
 * FakeCells.of(0, 60, 0, """
 *     ...
 *     ...
 *     ###""");   // y=60 が床、y=61/62 が空気
 * }</pre>
 */
public final class FakeCells implements CellSource {

    /** 空気。掘削不要で通れる。 */
    public static final char AIR = '.';
    /** 石。掘れば通れる（掘削コストは{@link #STONE_DIG_TICKS}）。 */
    public static final char STONE = '#';
    /** 掘れない岩盤。 */
    public static final char BEDROCK = 'B';
    /** 水。足場なしで通れる。 */
    public static final char WATER = '~';
    /** 溶岩。 */
    public static final char LAVA = 'L';
    /** 梯子。掴んで上下できる。 */
    public static final char LADDER = 'H';
    /** 範囲外・未ロード扱い（{@link CellData#ABSENT}）。 */
    public static final char ABSENT = '?';

    public static final double STONE_DIG_TICKS = 40.0;

    private final Long2LongOpenHashMap cells = new Long2LongOpenHashMap();
    private SearchBounds bounds;
    private boolean canPlaceBlocks;
    /** 設定の既定値に合わせてtrue。跳躍を禁じたいテストだけが明示的に切る。 */
    private boolean jumpGapEnabled = true;
    /** 設定の既定値に合わせて0（＝痛い落下は提示しない）。 */
    private int maxFallDamagePoints;
    private boolean canMlgWaterBucket;
    /** 書かれていない座標の既定。空虚（passableEmpty）にしておくと、床を書いた行だけが地形になる。 */
    private long fill = air();

    private FakeCells() {
        cells.defaultReturnValue(Long.MIN_VALUE);
    }

    public static FakeCells empty(SearchBounds bounds) {
        FakeCells fake = new FakeCells();
        fake.bounds = bounds;
        return fake;
    }

    /**
     * 文字で書いた縦断面から地形を組む。{@code originX}/{@code originZ}の列に、
     * 最下行が{@code baseY}になるよう積む（X方向に1文字＝1ブロック）。
     */
    public static FakeCells of(int originX, int baseY, int originZ, String diagram) {
        List<String> rows = new ArrayList<>(List.of(diagram.stripTrailing().split("\n")));
        int height = rows.size();
        int width = rows.stream().mapToInt(String::length).max().orElse(1);

        FakeCells fake = new FakeCells();
        fake.bounds = new SearchBounds(originX - 32, baseY - 32, originZ - 32,
                originX + width + 32, baseY + height + 32, originZ + 32);
        for (int row = 0; row < height; row++) {
            // 最上行がいちばん高いYになるよう、行を上下反転して読む
            int y = baseY + (height - 1 - row);
            String line = rows.get(row);
            for (int col = 0; col < line.length(); col++) {
                fake.set(originX + col, y, originZ, line.charAt(col));
            }
        }
        return fake;
    }

    public FakeCells set(int x, int y, int z, char symbol) {
        cells.put(BlockPos.asLong(x, y, z), flagsFor(symbol));
        return this;
    }

    /** {@code z}方向へ同じ断面を厚く広げる。斜め移動や跳躍を試すときに要る。 */
    public FakeCells extrudeZ(int fromZ, int toZ) {
        Long2LongOpenHashMap copy = new Long2LongOpenHashMap(cells);
        copy.long2LongEntrySet().forEach(entry -> {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            for (int z = fromZ; z <= toZ; z++) {
                cells.put(BlockPos.asLong(pos.getX(), pos.getY(), z), entry.getLongValue());
            }
        });
        return this;
    }

    /** 書かれていない座標を{@code symbol}で埋める（既定は空気）。 */
    public FakeCells fillWith(char symbol) {
        this.fill = flagsFor(symbol);
        return this;
    }

    public FakeCells canPlaceBlocks(boolean value) {
        this.canPlaceBlocks = value;
        return this;
    }

    public FakeCells jumpGapEnabled(boolean value) {
        this.jumpGapEnabled = value;
        return this;
    }

    public FakeCells maxFallDamagePoints(int value) {
        this.maxFallDamagePoints = value;
        return this;
    }

    public FakeCells canMlgWaterBucket(boolean value) {
        this.canMlgWaterBucket = value;
        return this;
    }

    public FakeCells bounds(SearchBounds value) {
        this.bounds = value;
        return this;
    }

    private static long flagsFor(char symbol) {
        return switch (symbol) {
            case AIR -> air();
            // 掘れば通れる普通の固体。掘る前は足場でもある
            case STONE -> CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE, STONE_DIG_TICKS);
            case BEDROCK -> CellData.withDigTicks(CellData.PRESENT | CellData.STANDABLE, Double.POSITIVE_INFINITY);
            // 水は当たり判定を持たないので足場にはならないが、掘らずに体を置ける
            case WATER -> CellData.withDigTicks(CellData.PRESENT | CellData.WATER, 0.0);
            case LAVA -> CellData.withDigTicks(CellData.PRESENT | CellData.LAVA, Double.POSITIVE_INFINITY);
            case LADDER -> CellData.withDigTicks(CellData.PRESENT | CellData.CLIMBABLE, 0.0);
            case ABSENT -> CellData.ABSENT;
            default -> throw new IllegalArgumentException("未知の地形記号: " + symbol);
        };
    }

    private static long air() {
        return CellData.withDigTicks(CellData.PRESENT | CellData.PASSABLE_EMPTY, 0.0);
    }

    @Override
    public long cell(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return CellData.ABSENT;
        }
        long value = cells.get(BlockPos.asLong(x, y, z));
        return value == Long.MIN_VALUE ? fill : value;
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    @Override
    public SearchBounds bounds() {
        return bounds;
    }

    @Override
    public boolean canPlaceBlocks() {
        return canPlaceBlocks;
    }

    @Override
    public boolean jumpGapEnabled() {
        return jumpGapEnabled;
    }

    @Override
    public int maxFallDamagePoints() {
        return maxFallDamagePoints;
    }

    @Override
    public boolean canMlgWaterBucket() {
        return canMlgWaterBucket;
    }

    /**
     * 本番の{@link ChunkView}はハイトマップを引くが、ここには地形しか無いので列を上から舐めて求める。
     * 頭上を塞ぐのは空気でも水でもないセル（＝{@code MOTION_BLOCKING}に相当）。
     */
    @Override
    public int openSkyY(int x, int z) {
        for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
            long flags = cell(x, y, z);
            if (CellData.present(flags) && !CellData.passableEmpty(flags)) {
                return y + 1;
            }
        }
        return bounds.minY();
    }
}
