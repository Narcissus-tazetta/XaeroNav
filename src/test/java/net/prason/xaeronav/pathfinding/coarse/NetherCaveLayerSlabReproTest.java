package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>「ネザーのルートがおかしい」のオフライン再現。</b>実機（2026-09-07・
 * {@code 0.1.3+6404f17}）で撮れた事実:
 *
 * <ul>
 * <li>{@code /xaeronav debug mapdata}が<b>洞窟レイヤー3（高さ26〜55）だけに3561セル、
 *     他のレイヤーは全部0セル</b>と答えた</li>
 * <li>層1のルートは中間目標5個で目的地に届かず、そのYは33〜50</li>
 * <li>ところが同じ列を保存データで見ると、<b>立てる場所は溶岩面のy32と、その30ブロック上の
 *     クリムゾンの森(y65〜97)</b>。層1が並べた中間目標は溶岩の海の上だった</li>
 * <li>層2は区間3・4・5を解けなかった（{@code leg 3/5: did not reach}）</li>
 * </ul>
 *
 * <p><b>再現の要点は「地図がYのスラブでしか見えていない」こと。</b>Xaeroは天井のある次元の
 * 地図を洞窟レイヤー（{@code CAVE_MODE_DEPTH}＝30ブロック）ごとに分けて持つので、
 * プレイヤーが歩いた高さ帯のレイヤーしか埋まらない。歩ける地形が別のレイヤーにあると、
 * 層1にはその床が<b>存在しないもの</b>として見える——{@link CoarseMap#NO_DATA}（未知＝ほぼ最安）
 * ですらなく、<b>そのセルには溶岩の床しか無いという「確かな地図」</b>になる。
 *
 * <p>ここでは{@link LiveCoarseSampler}に渡す箱のYを切ってスラブを作る。層1が読むのが
 * Xaeroの地図か実データかは、この症状には関係が無い（{@link CoarseRouter}に何が見えているか
 * だけの話）ので、Xaeroを持ち込まずに同じ地図を作れる。
 */
class NetherCaveLayerSlabReproTest {

    private static final BooleanSupplier NEVER = () -> false;

    /**
     * 見えているレイヤーの高さ帯。実機の{@code layer 3: 3561 cells known, height 26-55}そのまま。
     * この地形では溶岩面(y31前後)と下の洞窟だけが入り、歩けるクリムゾンの森(y61以上)は外れる。
     */
    private static final int SLAB_MIN_Y = 26;
    private static final int SLAB_MAX_Y = 55;

    /** 溶岩の海を挟んだ、上の階（クリムゾンの森）の2点。 */
    private static final BlockPos START_COLUMN = new BlockPos(-400, 0, 456);
    private static final BlockPos GOAL_COLUMN = new BlockPos(-216, 0, 520);

    /** 上の階を探し始める高さ。岩盤天井(y123〜127)の上に乗らない値。 */
    private static final int UPPER_FLOOR_SEARCH_TOP = 90;

    /** 区間ごとの探索の予算。実機の既定（10万ノード・2秒）と同じ。 */
    private static final SearchLimits LEG_LIMITS =
            new SearchLimits(100_000, 2_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    /** 中間目標はチャンク解像度なので、実機と同じく領域ゴールとして狙う。 */
    private static final int LEG_GOAL_RADIUS = 16;

    /**
     * 経路の膨らみの下限。実測は<b>1.52倍</b>（1352tick/200ステップ → 2057tick/258ステップ）。
     * 実機はこれよりさらに悪い——区間が5本ではなく19本あり、HUDは直線294ブロックに対して
     * 残り655ブロックと出ていた。
     */
    private static final double COST_RATIO_FLOOR = 1.35;

    /** 中間目標のYと実地形の床がこれ以上離れていたら、そこへは降りられない。 */
    private static final int STANDABLE_TOLERANCE_BLOCKS = 8;

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load("/nether_lava_sea.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    /**
     * {@code y}以下でいちばん高い「立てるY」。
     *
     * <p>{@code TerrainFixture#standableY}は列のいちばん上を返すので、天井のある次元では
     * <b>岩盤天井の上</b>が返る。ここが要るのは「この高さの近くに床があるか」なので上から切る。
     */
    private static int standableAtOrBelow(CellSource cells, SearchBounds bounds, int x, int z, int y) {
        for (int at = Math.min(y, bounds.maxY() - 2); at > bounds.minY(); at--) {
            if (CellData.standable(cells.cell(x, at - 1, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, at, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, at + 1, z))) {
                return at;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * その中間目標のYの近くに、実地形で立てる場所があるか。
     *
     * <p><b>セル（チャンク）全体で見る。</b>中間目標はチャンク解像度の代表点でしかなく、実機も
     * {@code PathfindingState#resolveOnSurface}でチャンクの中の立てる場所へ寄せてから使う。
     * 指定された列だけを見ると、そのチャンクに床があっても「立てない」と判定してしまう。
     */
    private static boolean standableThere(FakeCells terrain, BlockPos waypoint) {
        int baseX = (waypoint.getX() >> 4) << 4;
        int baseZ = (waypoint.getZ() >> 4) << 4;
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                int ground = standableAtOrBelow(terrain, terrain.bounds(), x, z,
                        waypoint.getY() + STANDABLE_TOLERANCE_BLOCKS);
                if (ground != Integer.MIN_VALUE
                        && Math.abs(ground - waypoint.getY()) <= STANDABLE_TOLERANCE_BLOCKS) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CoarseMap sample(FakeCells terrain, SearchBounds bounds, int referenceY) {
        return LiveCoarseSampler.sample(terrain, bounds, referenceY, NEVER);
    }

    /** 実機（{@code computeCoarseRoute}）と同じ梯子。到達した段と結果を返す。 */
    private static Attempt ladder(CoarseMap map, BlockPos start, BlockPos goal) {
        for (CoarseRouter.BridgePolicy policy : CoarseRouter.BridgePolicy.values()) {
            CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false, policy);
            if (route.reachedGoal()) {
                return new Attempt(policy, route);
            }
        }
        return new Attempt(null,
                CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.BRIDGE));
    }

    private record Attempt(CoarseRouter.BridgePolicy reachedWith, CoarseRouter.Route route) {
    }

    private static List<BlockPos> unstandableWaypoints(FakeCells terrain, CoarseRouter.Route route) {
        List<BlockPos> bad = new ArrayList<>();
        for (BlockPos waypoint : route.waypoints()) {
            if (!standableThere(terrain, waypoint)) {
                bad.add(waypoint);
            }
        }
        return bad;
    }

    @Test
    void theVisibleSlabDecidesWhetherTheRouteWalksOrCrossesLava() throws IOException {
        FakeCells terrain = terrain();
        SearchBounds full = terrain.bounds();
        BlockPos start = new BlockPos(START_COLUMN.getX(),
                standableAtOrBelow(terrain, full, START_COLUMN.getX(), START_COLUMN.getZ(),
                        UPPER_FLOOR_SEARCH_TOP),
                START_COLUMN.getZ());
        BlockPos goal = new BlockPos(GOAL_COLUMN.getX(),
                standableAtOrBelow(terrain, full, GOAL_COLUMN.getX(), GOAL_COLUMN.getZ(),
                        UPPER_FLOOR_SEARCH_TOP),
                GOAL_COLUMN.getZ());

        CoarseMap seen = sample(terrain, full, start.getY());
        SearchBounds slabBox = new SearchBounds(full.minX(), SLAB_MIN_Y, full.minZ(),
                full.maxX(), SLAB_MAX_Y, full.maxZ());
        CoarseMap slab = sample(terrain, slabBox, (SLAB_MIN_Y + SLAB_MAX_Y) / 2);

        // 1. スラブの外にある床は、未知ではなく「無かったこと」になる。始点のセルで
        //    プレイヤーが現に立っている床が消え、層1は30ブロック下の床を「始点」として解く
        int startCellX = start.getX() >> 4;
        int startCellZ = start.getZ() >> 4;
        int seenFloor = seen.nearestFloor(startCellX, startCellZ, start.getY());
        int slabFloor = slab.nearestFloor(startCellX, startCellZ, start.getY());
        assertTrue(Math.abs(seen.heightAtFloor(startCellX, startCellZ, seenFloor) - start.getY()) <= 8,
                "全部見えていれば、始点のセルの床はプレイヤーの足元にある");
        assertTrue(slab.heightAtFloor(startCellX, startCellZ, slabFloor) < start.getY() - 8,
                "スラブしか見えないと、始点の床が足元より大きく下になる（実機: 立っているのはy51、"
                        + "地図の床はy46で1枚だけ）");

        // 2. 全部見えていれば、溶岩を一切通らない道が見つかり、中間目標は全部立てる場所にある
        Attempt whenSeen = ladder(seen, start, goal);
        assertEquals(CoarseRouter.BridgePolicy.AVOID, whenSeen.reachedWith(),
                "上の階が見えていれば溶岩を避けて歩ける");
        assertEquals(List.of(), unstandableWaypoints(terrain, whenSeen.route()),
                "避けて引いたルートの中間目標は、どれも実地形で立てる場所");

        // 3. スラブしか見えないと、同じ地形で梯子がALLOWまで落ち（実機ログ「奈落・溶岩混じりを
        //    避ける道が見つからないため、そこを通る長距離ルートに切り替えました」）、
        //    立てない場所を指す中間目標が出る。これが症状の発生源——詳細探索はそこへ到達できず、
        //    到達できないぶんを大回りで埋める
        Attempt whenSlab = ladder(slab, start, goal);
        assertNotEquals(CoarseRouter.BridgePolicy.AVOID, whenSlab.reachedWith(),
                "スラブしか見えないと、溶岩を避ける道が地図の上に存在しない");
        assertTrue(whenSlab.route().waypoints().size() > whenSeen.route().waypoints().size(),
                "下の階を這うぶん中間目標が増える（実機: 19区間）");
        assertNotEquals(List.of(), unstandableWaypoints(terrain, whenSlab.route()),
                "実機と同じく、立てない場所（溶岩の海・奈落）を指す中間目標が出る");
    }

    /**
     * <b>症状そのもの——同じ地形・同じ両端で、経路が何倍になるか。</b>実機のHUDは直線294ブロックに
     * 対して残り655ブロックと出ていた。中間目標を順に辿って組み立てたときの総コストで測る。
     */
    @Test
    void followingTheSlabRouteCostsFarMoreThanWalkingTheUpperFloor() throws Exception {
        FakeCells terrain = terrain();
        SearchBounds full = terrain.bounds();
        BlockPos start = new BlockPos(START_COLUMN.getX(),
                standableAtOrBelow(terrain, full, START_COLUMN.getX(), START_COLUMN.getZ(),
                        UPPER_FLOOR_SEARCH_TOP),
                START_COLUMN.getZ());
        BlockPos goal = new BlockPos(GOAL_COLUMN.getX(),
                standableAtOrBelow(terrain, full, GOAL_COLUMN.getX(), GOAL_COLUMN.getZ(),
                        UPPER_FLOOR_SEARCH_TOP),
                GOAL_COLUMN.getZ());
        SearchBounds slabBox = new SearchBounds(full.minX(), SLAB_MIN_Y, full.minZ(),
                full.maxX(), SLAB_MAX_Y, full.maxZ());

        Walk seen = walk(terrain, start,
                ladder(sample(terrain, full, start.getY()), start, goal).route());
        Walk slab = walk(terrain, start,
                ladder(sample(terrain, slabBox, (SLAB_MIN_Y + SLAB_MAX_Y) / 2), start, goal).route());
        double ratio = slab.cost() / seen.cost();
        String measured = "全部見えている=" + Math.round(seen.cost()) + "tick/" + seen.steps() + "ステップ, "
                + "スラブ=" + Math.round(slab.cost()) + "tick/" + slab.steps() + "ステップ ("
                + Math.round(ratio * 100) / 100.0 + "倍)";

        assertTrue(ratio > COST_RATIO_FLOOR, "スラブしか見えないと経路が大きく膨らむ (" + measured + ")");
    }

    /** {@link #walk}の測定値。 */
    private record Walk(double cost, int steps, int reachedLegs) {
    }

    /** 中間目標を順に辿って組み立てる（実機の区間分割・継ぎ足しと同じ形）。 */
    private static Walk walk(FakeCells terrain, BlockPos start, CoarseRouter.Route route)
            throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        BlockPos from = start;
        double cost = 0;
        int steps = 0;
        int reached = 0;
        for (BlockPos waypoint : route.waypoints()) {
            PathResult result = executor.submit(terrain, from, waypoint, LEG_LIMITS, true, LEG_GOAL_RADIUS)
                    .get();
            if (result.steps().isEmpty()) {
                break;
            }
            for (PathStep step : result.steps()) {
                cost += step.cost();
            }
            steps += result.steps().size();
            if (!result.complete()) {
                break;
            }
            reached++;
            from = result.steps().get(result.steps().size() - 1).pos();
        }
        return new Walk(cost, steps, reached);
    }
}
