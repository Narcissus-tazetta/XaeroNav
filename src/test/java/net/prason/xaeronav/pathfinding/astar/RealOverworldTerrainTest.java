package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 実機のオーバーワールド保存データ（{@code run/saves/test/region/r.0.0.mca} の起伏の小さい一帯、
 * 地表Y≒47〜49）で層3の探索を再現し、「平地で線がL字/階段になる」を観測する。
 *
 * <p>固体ブロックの列を{@code overworld_flat_columns.txt.gz}へ書き出してある（生成は
 * {@code scratchpad/anvil.py}）。ブロック種別は問わないので全て{@link FakeCells#STONE}。
 */
class RealOverworldTerrainTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static FakeCells terrain() throws IOException {
        try (InputStream in = RealOverworldTerrainTest.class
                .getResourceAsStream("/overworld_flat_columns.txt.gz")) {
            assertNotNull(in, "地形データが見つからない");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] h = reader.readLine().trim().split(" ");
            SearchBounds bounds = new SearchBounds(
                    Integer.parseInt(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                    Integer.parseInt(h[3]), Integer.parseInt(h[4]), Integer.parseInt(h[5]));
            FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR)
                    .canPlaceBlocks(true).maxFallDamagePoints(6);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split(" ");
                int x = Integer.parseInt(p[0]);
                int z = Integer.parseInt(p[1]);
                for (int i = 2; i < p.length; i++) {
                    int comma = p[i].indexOf(',');
                    int from = Integer.parseInt(p[i].substring(0, comma));
                    int to = Integer.parseInt(p[i].substring(comma + 1));
                    for (int y = from; y <= to; y++) {
                        cells.set(x, y, z, FakeCells.STONE);
                    }
                }
            }
            return cells;
        }
    }

    @Test
    void flatOverworld_shapeUnderDefaultWeight() throws IOException {
        FakeCells cells = terrain();
        // 地表Y≒48。dx≫dz で「まっすぐ→曲がる」を誘う配置を数通り。
        int[][] legs = {
                {360, 49, 348, 88, 22},
                {360, 49, 360, 70, 40},
                {400, 49, 300, 96, 30},
        };
        for (int[] leg : legs) {
            BlockPos start = new BlockPos(leg[0], leg[1], leg[2]);
            BlockPos goal = new BlockPos(leg[0] + leg[3], leg[1], leg[2] + leg[4]);
            for (double weight : new double[] {AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT, 1.0}) {
                SearchLimits limits = new SearchLimits(1_000_000, 20_000, weight);
                PathResult result = new AStarPathfinder(cells, limits).search(start, goal, NEVER, 2);
                FlatGroundDiagonalReproTest.report(
                        "OW " + start.getX() + "," + start.getZ() + "->+"
                                + leg[3] + "," + leg[4] + " w=" + weight,
                        start, goal, result, cells);
                assertTrue(result.steps().size() > 0, "経路が出ること: " + result.termination());
            }
        }
    }
}
