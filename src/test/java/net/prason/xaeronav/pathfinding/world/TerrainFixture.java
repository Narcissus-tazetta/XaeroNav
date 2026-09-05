package net.prason.xaeronav.pathfinding.world;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;

/**
 * 実機のワールド保存データから書き出した地形を{@link FakeCells}へ読み込む。
 * 書き出しは{@code tools/dump_terrain_columns.py}。
 *
 * <p>形式は1行目が探索範囲({@code minX minY minZ maxX maxY maxZ})、以降が
 * {@code x z <種別>fromY,toY <種別>fromY,toY …}。種別は{@link FakeCells}の記号1文字で、
 * <b>数字（または負のYの{@code -}）で始まるランは種別なし＝{@link FakeCells#STONE}</b>——
 * 種別を持たなかった頃に書き出したフィクスチャをそのまま読めるようにしてある。
 * 地上・ネザーは水と溶岩が経路そのものを決めるので、あちらのフィクスチャには種別が要る。
 *
 * <p>設定（設置の可否・橋の上限・落下の許容など）は再現したい実機の条件ごとに違うので、
 * 空の{@link FakeCells}を組む所だけ呼び出し側へ渡す。
 */
public final class TerrainFixture {

    /** 読み取った探索範囲から、設定を載せた空の{@link FakeCells}を組む。 */
    @FunctionalInterface
    public interface Configure {
        FakeCells apply(SearchBounds bounds);
    }

    private TerrainFixture() {
    }

    public static FakeCells load(String resource, Configure configure) throws IOException {
        try (InputStream in = TerrainFixture.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("地形データが見つからない: " + resource);
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] header = reader.readLine().trim().split(" ");
            FakeCells cells = configure.apply(new SearchBounds(
                    Integer.parseInt(header[0]), Integer.parseInt(header[1]), Integer.parseInt(header[2]),
                    Integer.parseInt(header[3]), Integer.parseInt(header[4]), Integer.parseInt(header[5])));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(" ");
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[1]);
                for (int i = 2; i < parts.length; i++) {
                    String run = parts[i];
                    char kind = FakeCells.STONE;
                    if (!Character.isDigit(run.charAt(0)) && run.charAt(0) != '-') {
                        kind = run.charAt(0);
                        run = run.substring(1);
                    }
                    int comma = run.indexOf(',');
                    int from = Integer.parseInt(run.substring(0, comma));
                    int to = Integer.parseInt(run.substring(comma + 1));
                    for (int y = from; y <= to; y++) {
                        cells.set(x, y, z, kind);
                    }
                }
            }
            return cells;
        }
    }

    /** {@code x,z}の列で立てるいちばん高いY。立てる場所が無ければ{@link Integer#MIN_VALUE}。 */
    public static int standableY(CellSource cells, SearchBounds bounds, int x, int z) {
        for (int y = bounds.maxY() - 1; y > bounds.minY(); y--) {
            if (CellData.standable(cells.cell(x, y - 1, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * {@code p}のX/Zで立てる高さへ下ろす。
     *
     * @throws IllegalStateException 立てる場所が無いとき。地形データと座標がずれている
     */
    public static BlockPos onGround(CellSource cells, SearchBounds bounds, BlockPos p) {
        int y = standableY(cells, bounds, p.getX(), p.getZ());
        if (y == Integer.MIN_VALUE) {
            throw new IllegalStateException(p.toShortString() + " に立てない＝地形データがずれている");
        }
        return new BlockPos(p.getX(), y, p.getZ());
    }

    /**
     * 箱の中から立てる点を種固定の乱数で拾い、{@code minBlocks}〜{@code maxBlocks}離れた組を作る。
     *
     * <p><b>中心から放射状に振るのではなく散らす</b>のは、1つの中心の周りだけを見るとその地点の
     * 地形の癖しか測れないため。始点も終点も箱の縁から16ブロック内側に収める。
     *
     * <p><b>種を固定するのが要点</b>——毎回違う経路を測ると、落ちたときに再現できないうえ、
     * たまたま厳しい組が引かれただけなのか本当に悪化したのかを区別できない。
     */
    public static List<BlockPos[]> randomRoutes(CellSource cells, SearchBounds bounds, long seed,
                                                int count, int minBlocks, int maxBlocks) {
        Random random = new Random(seed);
        List<BlockPos[]> routes = new ArrayList<>();
        int attempts = 0;
        while (routes.size() < count && attempts++ < 4000) {
            BlockPos start = randomStandable(cells, bounds, random);
            if (start == null) {
                continue;
            }
            double angle = random.nextDouble() * 2.0 * Math.PI;
            int distance = minBlocks + random.nextInt(maxBlocks - minBlocks + 1);
            int x = start.getX() + (int) Math.round(distance * Math.cos(angle));
            int z = start.getZ() + (int) Math.round(distance * Math.sin(angle));
            int y = standableY(cells, bounds, x, z);
            if (y == Integer.MIN_VALUE) {
                continue;
            }
            routes.add(new BlockPos[] {start, new BlockPos(x, y, z)});
        }
        return routes;
    }

    private static BlockPos randomStandable(CellSource cells, SearchBounds bounds, Random random) {
        int x = bounds.minX() + 16 + random.nextInt(Math.max(1, bounds.maxX() - bounds.minX() - 32));
        int z = bounds.minZ() + 16 + random.nextInt(Math.max(1, bounds.maxZ() - bounds.minZ() - 32));
        int y = standableY(cells, bounds, x, z);
        return y == Integer.MIN_VALUE ? null : new BlockPos(x, y, z);
    }
}
