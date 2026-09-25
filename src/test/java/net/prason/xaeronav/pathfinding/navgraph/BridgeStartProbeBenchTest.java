package net.prason.xaeronav.pathfinding.navgraph;

import java.io.IOException;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.SectionMoves;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.WindowedCells;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/** 調査用: 自分の橋・小島の端から始まるとき、殻と橋の通り道に何が入っているか。 */
@Tag("bench")
class BridgeStartProbeBenchTest {

    @Test
    void edges() throws IOException {
        FakeCells cells = TerrainFixture.load("/end_stuck_probe2.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(0).avoidRiskyJumps(true));
        BlockPos player = new BlockPos(1865, 66, 1505);
        BlockPos goal = new BlockPos(2089, 61, 1517);
        WindowedCells window = new WindowedCells(cells, player, 224);
        NavGraph graph = new NavGraph(goal, cells.bounds().minY(), cells.bounds().maxY());
        WindowField field = graph.refresh(() -> window, player.getX(), player.getZ(), 224,
                LoadedArea.square(player.getX(), player.getZ(), 224), FarField.UNKNOWN, ForkJoinPool.commonPool(),
                Runtime.getRuntime().availableProcessors(), () -> false).field();
        for (int y = 67; y >= 62; y--) {
            StringBuilder row = new StringBuilder();
            for (int x = 1860; x <= 1872; x++) {
                double v = field.exact(x, y, 1505);
                row.append(Double.isNaN(v) ? "    -" : String.format(Locale.ROOT, "%5.0f", v));
            }
            System.out.printf(Locale.ROOT, "z1505 y%d x1860..1872:%s%n", y, row);
        }
        // 始点のセクションから出る辺
        NaturalColumns naturals = new NaturalColumns(cells.bounds().minY(), cells.bounds().maxY());
        SectionShell shell = SectionShell.of(naturals, window, Math.floorDiv(1865, 16), Math.floorDiv(1505, 16),
                goal.getX(), goal.getZ());
        long start = player.asLong();
        SectionMoves.build(window, Math.floorDiv(1865, 16), Math.floorDiv(66, 16), Math.floorDiv(1505, 16), shell,
                goal.getX(), goal.getZ(), (from, to, cost) -> {
                    if (from == start) {
                        System.out.printf(Locale.ROOT, "  辺 %s→%s %.1f%n", player.toShortString(),
                                BlockPos.of(to).toShortString(), cost);
                    }
                }, () -> false);
    }

    @Test
    void probe() throws IOException {
        FakeCells cells = TerrainFixture.load("/end_stuck_probe.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96).maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(0).avoidRiskyJumps(true));
        int minY = cells.bounds().minY();
        NaturalColumns naturals = new NaturalColumns(minY, cells.bounds().maxY());
        int goalX = 1535;
        int goalZ = 1667;
        for (int[] c : new int[][] {{1382, 1443}, {1382, 1444}, {1382, 1450}, {1382, 1472}, {1400, 1474}, {1416, 1474}, {1417, 1474}}) {
            StringBuilder nat = new StringBuilder();
            for (int w = 0; w < naturals.words(); w++) {
                long bits = naturals.word(cells, c[0], c[1], w);
                for (int b = 0; b < 64; b++) {
                    if ((bits >>> b & 1L) != 0) {
                        nat.append(minY + w * 64 + b).append(' ');
                    }
                }
            }
            long[] corridor = naturals.bridgeCorridor(cells, c[0], c[1], goalX, goalZ, 96, 30);
            StringBuilder cor = new StringBuilder();
            for (int w = 0; w < corridor.length; w++) {
                for (int b = 0; b < 64; b++) {
                    if ((corridor[w] >>> b & 1L) != 0) {
                        cor.append(minY + w * 64 + b).append(' ');
                    }
                }
            }
            StringBuilder col = new StringBuilder();
            for (int y = 64; y <= 74; y++) {
                col.append(CellData.passableEmpty(cells.cell(c[0], y, c[1])) ? '.' : '#');
            }
            SectionShell shell = SectionShell.of(naturals, cells, Math.floorDiv(c[0], 16), Math.floorDiv(c[1], 16),
                    goalX, goalZ);
            StringBuilder in = new StringBuilder();
            for (int y = 64; y <= 74; y++) {
                in.append(shell.contains(c[0], y, c[1]) ? 'o' : '-');
            }
            System.out.printf(Locale.ROOT, "%d,%d 自然=%s 橋の通り道=%s 列y58..68=%s 殻y58..68=%s%n", c[0], c[1], nat,
                    cor, col, in);
        }
    }
}
