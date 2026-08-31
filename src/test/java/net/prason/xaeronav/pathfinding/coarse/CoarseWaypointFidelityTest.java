package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import org.junit.jupiter.api.Test;

/**
 * <b>層1が出す中間目標に、層2が必ず「立てる場所」を見つけられること</b>を実機のジ・エンド
 * 保存データで固定する。
 *
 * <p>{@code CoarseRouter#toBlockPos}はセルの種別に関わらず常に<b>チャンク中心</b>を返す。
 * エンドには床が数％しかないセルが密なセルと同じ値段で並ぶので、中間目標の座標が<b>奈落の
 * 真上</b>になりうる。層3はその点へ届くために奈落へ橋を架ける——これが「謎にわたらせる」の
 * 機構として実在する。
 *
 * <p><b>ただし実データではこの機構は発火しない。</b>107ルート・中間目標190個を調べると、層2の
 * 8ブロック寄せ（{@code CorridorLegSolver.ENDPOINT_FALLBACK_RADIUS_BLOCKS}）が<b>全部を
 * 救っていた</b>。層2が使える限り、生のチャンク中心が層3へ渡ることは無い。
 *
 * <p>残る容疑は「層2が使えないとき」——Xaeroの地図データがその区間に無いと
 * {@code CorridorLegSolver.prepare}が{@code view=null}を返し、{@code PathfindingState#solveLeg}が
 * 生のチャンク中心へフォールバックする。<b>そこはオフラインでは測れない</b>（Xaeroのリージョン
 * 読み込み状態に依存する）。
 */
class CoarseWaypointFidelityTest {

    /**
     * 層2が中間目標を立てる場所へ寄せる半径（{@code CorridorLegSolver.ENDPOINT_FALLBACK_RADIUS_BLOCKS}）。
     * ここで見つからなければ{@code prepare}が{@code view=null}を返す。
     */
    private static final int LAYER2_SNAP_RADIUS = 8;

    private static FakeCells endTerrain() throws IOException {
        // 実機の既定に合わせる（maxBridgeRunBlocks/maxVoidBridgeRunBlocks=96、落下許容6）
        return TerrainFixture.load("/end_terrain_columns.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    /** {@code waypoint}から、実際に立てる最寄りの列までの水平距離。無ければ{@link Double#NaN}。 */
    private static double distanceToNearestStandable(CellSource cells, SearchBounds bounds,
                                                      BlockPos waypoint, int searchRadius) {
        double best = Double.NaN;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                int x = waypoint.getX() + dx;
                int z = waypoint.getZ() + dz;
                if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) {
                    continue;
                }
                if (TerrainFixture.standableY(cells, bounds, x, z) == Integer.MIN_VALUE) {
                    continue;
                }
                double d = Math.hypot(dx, dz);
                if (Double.isNaN(best) || d < best) {
                    best = d;
                }
            }
        }
        return best;
    }

    /**
     * 実機の始点・目的地の格子から多数のルートを引き、<b>層2が救えない中間目標が1つも出ない</b>
     * ことを見る。層1のコスト・間引き間隔・層2の寄せ半径のどれかを変えてここが崩れたら、
     * 「立てない中間目標」が実データでも成立する条件に変わっている。
     */
    @Test
    void layer2AlwaysSnapsCoarseWaypointsOntoStandableGround() throws IOException {
        FakeCells terrain = endTerrain();
        SearchBounds b = terrain.bounds();
        CoarseMap map = LiveCoarseSampler.sample(terrain, b);

        // データのある範囲(1130..1390 x 990..1250)に始点・目的地の格子を張る
        List<BlockPos> anchors = new ArrayList<>();
        for (int x = 1150; x <= 1370; x += 55) {
            for (int z = 1010; z <= 1230; z += 55) {
                int y = TerrainFixture.standableY(terrain, b, x, z);
                if (y != Integer.MIN_VALUE) {
                    anchors.add(new BlockPos(x, y, z));
                }
            }
        }

        int intermediates = 0;
        int layer2WouldFail = 0;
        List<String> examples = new ArrayList<>();
        for (BlockPos from : anchors) {
            for (BlockPos to : anchors) {
                if (from.equals(to)) {
                    continue;
                }
                CoarseRouter.Route route = CoarseRouter.findRoute(map, from, to, false,
                        CoarseRouter.BridgePolicy.ALLOW);
                if (!route.reachedGoal() || route.waypoints().size() < 2) {
                    continue;
                }
                // 最後は PathfindingState#freshRoute の replaceLast が本来の目的地で上書きする
                for (int i = 0; i < route.waypoints().size() - 1; i++) {
                    BlockPos w = route.waypoints().get(i);
                    intermediates++;
                    if (Double.isNaN(distanceToNearestStandable(terrain, b, w, LAYER2_SNAP_RADIUS))) {
                        layer2WouldFail++;
                        if (examples.size() < 6) {
                            examples.add(w.toShortString());
                        }
                    }
                }
            }
        }

        assertEquals(0, layer2WouldFail,
                "層2の寄せ半径" + LAYER2_SNAP_RADIUS + "で救えない中間目標が" + layer2WouldFail
                        + "/" + intermediates + "個出た（例: " + examples + "）＝"
                        + "「立てない中間目標」仮説が実データで成立する条件に変わった");
    }
}
