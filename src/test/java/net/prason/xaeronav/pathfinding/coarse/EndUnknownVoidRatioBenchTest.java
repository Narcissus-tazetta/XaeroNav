package net.prason.xaeronav.pathfinding.coarse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>計測専用。番人ではない</b>（`bench`タスクで明示的に回す。{@link net.prason.xaeronav.pathfinding.astar.SearchProfileTest}と同じ形）。
 *
 * <p>{@link CoarseRouter}は未探索セル（{@code NO_DATA}）の倍率を、既知セルの陸:奈落比から
 * 較正する（下限は陸に近い1.6、上限は奈落と同じ≒10）。<b>その較正が実機で何倍あたりに
 * 着地するのかを測るのがここ。</b>実機のジ・エンド地形ダンプ（{@code src/test/resources/end_*.txt.gz}、
 * いずれも実機保存データから書き出したもの）を材料にする——ジ・エンドでは「まだ地図に無い」の
 * 実体がほぼ奈落なので、較正前の固定1.6がどれだけ楽観的だったかも同時に見える。
 *
 * <p><b>ここでの「陸/奈落」の判定方法。</b>ダンプは実機の固体ブロックの列だけを書き出したもの
 * なので、1チャンク(16×16)の中に{@link CellData#standable}なセルが1つでもあれば陸、
 * 1つも無ければ奈落として数える——{@code XaeroMapReader#markVoidCells}の定義（不透明ブロックを
 * 1つも見なかった列＝奈落）と同じ考え方。水・溶岩はジ・エンドの地形にほぼ出現しないので
 * 区別しない（この簡略化は対象をジ・エンドに絞っているからこそ成り立つ）。
 */
@Tag("bench")
class EndUnknownVoidRatioBenchTest {

    private static final String[] RESOURCES = {
            "/end_terrain_columns.txt.gz",
            "/end_terrain_columns_2481.txt.gz",
            "/end_player_area.txt.gz",
    };

    private static FakeCells terrain(String resource) throws IOException {
        return TerrainFixture.load(resource, FakeCells::empty);
    }

    @Test
    void measuresHowOftenUnexploredEndChunksTurnOutToBeVoid() throws IOException {
        List<String> report = new ArrayList<>();
        int totalLand = 0;
        int totalVoid = 0;
        for (String resource : RESOURCES) {
            FakeCells cells = terrain(resource);
            SearchBounds bounds = cells.bounds();
            int[] counts = countLandAndVoidChunks(cells, bounds);
            totalLand += counts[0];
            totalVoid += counts[1];
            report.add(String.format(Locale.ROOT, "%s: 陸=%d 奈落=%d 奈落比=%.3f",
                    resource, counts[0], counts[1], voidRatio(counts[0], counts[1])));
        }
        double overallVoidRatio = voidRatio(totalLand, totalVoid);
        double landRatio = 1.0 - overallVoidRatio;
        double calibrated = landRatio * 1.0 + overallVoidRatio * voidBridgeMultiplier();
        report.add("");
        report.add(String.format(Locale.ROOT, "全体: 陸=%d 奈落=%d 奈落比=%.3f",
                totalLand, totalVoid, overallVoidRatio));
        report.add(String.format(Locale.ROOT,
                "較正の下限=1.600 / 実測の奈落比をそのまま使った倍率=%.3f"
                        + " (CoarseRouterは更に事前分を混ぜるのでこれより下限寄りに出る,"
                        + " VOID_BRIDGE_MULTIPLIER=%.3f)",
                calibrated, voidBridgeMultiplier()));
        String text = String.join("\n", report);
        System.out.println(text);
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("xaeronav.profileOut", System.getProperty("java.io.tmpdir")));
            java.nio.file.Files.createDirectories(out);
            java.nio.file.Files.writeString(out.resolve("end-unknown-void-ratio.txt"), text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code CoarseRouter#VOID_BRIDGE_MULTIPLIER}と同じ式（そちらはprivateなのでここで再計算する）。 */
    private static double voidBridgeMultiplier() {
        return (ActionCosts.SPRINT_ONE_BLOCK + ActionCosts.PLACE_BLOCK_AIM_TICKS
                + ActionCosts.VOID_BRIDGE_PENALTY_TICKS) / ActionCosts.SPRINT_ONE_BLOCK;
    }

    private static double voidRatio(int land, int voidCount) {
        int total = land + voidCount;
        return total == 0 ? 0.0 : (double) voidCount / total;
    }

    /** @return {index 0: 陸チャンク数, index 1: 奈落チャンク数} */
    private static int[] countLandAndVoidChunks(FakeCells cells, SearchBounds bounds) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        int land = 0;
        int voidCount = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (chunkHasFloor(cells, bounds, chunkX, chunkZ)) {
                    land++;
                } else {
                    voidCount++;
                }
            }
        }
        return new int[] {land, voidCount};
    }

    private static boolean chunkHasFloor(FakeCells cells, SearchBounds bounds, int chunkX, int chunkZ) {
        int minX = Math.max(chunkX << 4, bounds.minX());
        int maxX = Math.min((chunkX << 4) + 15, bounds.maxX());
        int minZ = Math.max(chunkZ << 4, bounds.minZ());
        int maxZ = Math.min((chunkZ << 4) + 15, bounds.maxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    if (CellData.standable(cells.cell(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
