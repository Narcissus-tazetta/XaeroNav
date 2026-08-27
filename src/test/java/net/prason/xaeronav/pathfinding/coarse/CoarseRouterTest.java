package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;

class CoarseRouterTest {

    private static final int RADIUS = 40;

    /** 全面が平坦な陸のマップ。ここへ海や崖を書き込んでいく。 */
    private static CoarseMapBuilder flatLand() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }
        return builder;
    }

    private static BlockPos atChunk(int chunkX, int chunkZ) {
        return new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8);
    }

    @Test
    void routesStraightAcrossOpenLand() {
        CoarseMap map = flatLand().build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        // 平坦な陸を横切るだけなので、Zは出発点の帯から外れない
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getZ() >= -16 && waypoint.getZ() <= 32,
                    "平坦な陸なのに逸れた: " + waypoint);
        }
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    @Test
    void detoursAroundWaterInsteadOfSwimming() {
        CoarseMapBuilder builder = flatLand();
        // 目的地との間を塞ぐ浅い湾。北側(Z<-2)がすぐ開いているので、短い迂回で避けられる
        for (int x = 4; x <= 16; x++) {
            for (int z = -2; z <= RADIUS - 1; z++) {
                builder.replaceCell(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        // 迂回するなら、湾を跨ぐ区間では必ず北へ出ている
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() < -2 * 16),
                "湾を迂回せず突っ切った: " + route.waypoints());
    }

    /**
     * 迂回が長すぎるなら泳いで渡る。うつ伏せ泳ぎは疾走の約1/1.56の速さでしかないので、
     * 「水は避けるもの」を絶対視すると、208ブロック泳げば済む湾を488ブロック歩いて回ることになる。
     */
    @Test
    void swimsAcrossWhenTheDetourIsLongerThanTheCrossing() {
        CoarseMapBuilder builder = flatLand();
        // 北の開口が遠い湾。迂回は往復で112ブロック北へ出る必要がある
        for (int x = 4; x <= 16; x++) {
            for (int z = -6; z <= RADIUS - 1; z++) {
                builder.replaceCell(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertTrue(route.waypoints().stream().allMatch(waypoint -> waypoint.getZ() >= -16),
                "泳いだ方が速い湾を迂回した: " + route.waypoints());
    }

    @Test
    void crossesWaterDirectlyWhenBoatIsAvailable() {
        CoarseMapBuilder builder = flatLand();
        // 迂回できる湾（detoursAroundWaterInsteadOfSwimmingと同じ地形）。ボート無しでは迂回するが、
        // ボートは徒歩より速いので、ボートがあれば迂回せず突っ切る方が安くなるはず
        for (int x = 4; x <= 16; x++) {
            for (int z = -6; z <= RADIUS - 1; z++) {
                builder.replaceCell(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), true,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        assertTrue(route.waypoints().stream().noneMatch(waypoint -> waypoint.getZ() < -6 * 16),
                "ボートがあるのに迂回した: " + route.waypoints());
    }

    @Test
    void swimsWhenDetourIsFarLonger() {
        CoarseMapBuilder builder = flatLand();
        // 端から端まで塞ぐ海峡。迂回路が無いので、遠回りより泳ぐ方が安い
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.WATER, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    @Test
    void neverRoutesThroughLava() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        // 溶岩で完全に分断されているので、目的地へは到達できない
        assertFalse(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getX() < 4 * 16, "溶岩帯に踏み込んだ: " + waypoint);
        }
    }

    /**
     * 溶岩が混じるだけのセルは通れる。ネザーは既知セルの過半数がこれになるので、
     * 通行不能にすると経路がまったく繋がらない。
     */
    @Test
    void crossesMixedLavaWhenItIsTheOnlyWay() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    /** ただし迂回できるなら迂回する——「通れる」と「選ぶ」は別。 */
    @Test
    void detoursAroundMixedLavaWhenCleanGroundExists() {
        CoarseMapBuilder builder = flatLand();
        // 進路上に溶岩混じりの帯を置くが、Z方向に少し逸れれば素の陸で回り込める
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            int chunkX = waypoint.getX() >> 4;
            int chunkZ = waypoint.getZ() >> 4;
            boolean insideMixedLava = chunkX >= 4 && chunkX <= 6 && chunkZ >= -2 && chunkZ <= 2;
            assertFalse(insideMixedLava, "迂回できるのに溶岩混じりを突っ切った: " + waypoint);
        }
    }

    /**
     * {@link CoarseRouter.BridgePolicy#AVOID}は溶岩混じりも通行不能にする。ネザーではこれで
     * 経路が繋がらなくなることが多いが、それは呼び出し側が次の段へ進む合図になる。
     */
    @Test
    void avoidPolicyRefusesMixedLavaEvenWhenItIsTheOnlyWay() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.AVOID);

        assertFalse(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getX() < 4 * 16, "溶岩混じりに踏み込んだ: " + waypoint);
        }
    }

    /** 迂回路があるなら{@code AVOID}でも当然そちらを通って到達する。 */
    @Test
    void avoidPolicyStillReachesGoalByDetouring() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA_MIXED, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.AVOID);

        assertTrue(route.reachedGoal());
        assertEquals(20 * 16 + 8, last(route).getX());
    }

    /** {@code BRIDGE}は、他のどのポリシーでも通れない溶岩の帯を橋で渡る前提で横断する。 */
    @Test
    void bridgePolicyCrossesFullLavaThatBlocksEveryOtherPolicy() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        assertFalse(CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW).reachedGoal());

        CoarseRouter.Route bridged = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(bridged.reachedGoal());
        assertEquals(20 * 16 + 8, last(bridged).getX());
    }

    /** {@code BRIDGE}でも、溶岩を避けられるならそちらを通る——最後の手段であって近道ではない。 */
    @Test
    void bridgePolicyStillPrefersCleanGround() {
        CoarseMapBuilder builder = flatLand();
        for (int x = 4; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                builder.replaceCell(x, z, CoarseMap.LAVA, 62);
            }
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            int chunkX = waypoint.getX() >> 4;
            int chunkZ = waypoint.getZ() >> 4;
            boolean insideLava = chunkX >= 4 && chunkX <= 6 && chunkZ >= -2 && chunkZ <= 2;
            assertFalse(insideLava, "迂回できるのに溶岩を渡った: " + waypoint);
        }
    }

    @Test
    void prefersKnownGroundOverUnmappedShortcut() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        // 地図に無い一帯を、既知の陸の帯が1本だけ横切っている。少し逸れれば乗れる位置に置くのは、
        // 未知のペナルティが「遠回りしてでも避ける」ほど重くはないため（遠い帯なら直進が正しい）
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = 2; z <= 4; z++) {
                builder.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }
        builder.putFloor(0, 0, CoarseMap.LAND, 64);
        builder.putFloor(20, 0, CoarseMap.LAND, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        // 未知を突っ切る直線より、分かっている陸の帯へ寄る
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() >= 2 * 16),
                "既知の陸を使わず未知を突っ切った: " + route.waypoints());
    }

    @Test
    void reportsFailureWhenGoalIsOutsideTheMap() {
        CoarseMap map = flatLand().build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(RADIUS + 10, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertFalse(route.reachedGoal());
        assertTrue(route.isEmpty());
    }

    @Test
    void prefersFlatGroundOverClimbingWhenDistanceIsSimilar() {
        CoarseMapBuilder builder = flatLand();
        // 目的地へ一直線の帯だけが高い尾根。1マス北へ避ければ平坦
        for (int x = 1; x <= 19; x++) {
            builder.replaceCell(x, 0, CoarseMap.LAND, 140);
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        for (BlockPos waypoint : route.waypoints()) {
            assertTrue(waypoint.getY() < 140, "尾根の上を通った: " + waypoint);
        }
    }

    @Test
    void avoidsCliffyCellsEvenWhenAverageHeightMatchesSurroundings() {
        CoarseMapBuilder builder = flatLand();
        // 平均高さは周囲と同じ64だが、セル内の起伏（0〜128）が大きい＝崖のチャンク。
        // 平均だけを見る旧ロジックでは検出できず、1マス北の平坦な迂回路と無差別だった
        for (int x = 1; x <= 19; x++) {
            builder.putFloor(x, 0, CoarseMap.LAND, 64, 0, 128);
        }
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(0, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertTrue(route.waypoints().stream().anyMatch(waypoint -> waypoint.getZ() != 8),
                "起伏の大きいセルを避けず素通りした: " + route.waypoints());
    }

    /**
     * 崖ペナルティに上限が無いと、極端に起伏の激しい1マス（実測ではありえない値だが、境界の
     * 検証として意図的に大きくする）を通るより、壁を大きく迂回する方が常に安くなってしまう。
     * ネザーでは起伏30ブロック程度でも溶岩混じりセルより高くつくので、
     * この上限は「どれだけ起伏があっても、迂回が数セル分ぶんより高くならない」ことを保証する。
     */
    @Test
    void cliffPenaltyCapLetsARuggedShortcutBeatALongDetour() {
        CoarseMapBuilder builder = flatLand();
        // x=0の1列だけを南北に溶岩の壁にし、z=0だけ開ける。開けた1マスは起伏10000という
        // 極端な崖（highMax=10000はshortの範囲内——32767を超えると6引数putのキャストで
        // オーバーフローし、意図と逆に「起伏0」へ丸められてしまうので注意）。
        // 壁を迂回するには斜め移動でz方向に最低6マス分の往復が要り、その分（斜め12マス、
        // 直進より約283tick高い）は崖ペナルティの上限（約77tick）を明確に上回る——
        // 上限が効いていなければ壁を迂回する方が安くなる
        for (int z = -5; z <= 5; z++) {
            if (z == 0) {
                continue;
            }
            builder.putFloor(0, z, CoarseMap.LAVA, 64);
        }
        builder.putFloor(0, 0, CoarseMap.LAND, 64, 0, 10_000);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(-20, 0), atChunk(20, 0), false,
                CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        // 迂回した場合はz=8から一時的に外れるはず。崖の1マスを素通りしたなら終始z=8のまま
        assertTrue(route.waypoints().stream().allMatch(waypoint -> waypoint.getZ() == 8),
                "壁を迂回した＝崖ペナルティの上限が効いていない: " + route.waypoints());
    }

    /**
     * ネザーの3D迷路の核心: 同じセルに上下2本の独立した床があるとき、垂直遷移で繋いで
     * 到達できる。始点・終点のYがそれぞれの床に近いことも{@link CoarseMap#nearestFloor}で
     * 正しく解決される必要がある。
     */
    @Test
    void connectsTwoStackedFloorsInTheSameCellViaAVerticalTransition() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        builder.putFloor(0, 0, CoarseMap.LAND, 40);
        builder.putFloor(0, 0, CoarseMap.LAND, 90);
        CoarseMap map = builder.build();

        BlockPos start = new BlockPos(8, 41, 8);
        BlockPos goal = new BlockPos(8, 91, 8);
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertFalse(route.isEmpty());
        assertEquals(90, last(route).getY(), "登った先の床(90)の高さで終わるはず");
    }

    /**
     * 水平移動は隣接セルの全床にではなく、今の床に最も近い床だけに繋がる。これが無いと、
     * 階層をまたぐ移動が「本当に繋がっているか分からない階層間移動は必ず垂直遷移の
     * 割増コストを払う」というルールを、水平移動のふりをして素通りしてしまう
     * （隣接セルの遠い床へも普通の坂と同じ{@code heightPenalty}だけで渡れてしまい、
     * {@link #connectsTwoStackedFloorsInTheSameCellViaAVerticalTransition}が課している
     * 割増を迂回する抜け道になる）。
     *
     * <p>始点のセルは高さ40の床1つだけ。隣（目的地のセル）には高さ42（近い）と高さ90（遠い）の
     * 2つの床がある。それでも目的地Y=90へは到達できる——最寄りの床(42)を経由して
     * 垂直遷移で登る2段構えの経路になるだけで、90が「繋がっていない床」として消えることはない。
     */
    @Test
    void horizontalStepReachesTheFarFloorOnlyThroughTheNearFloorAndAVerticalTransition() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        builder.putFloor(0, 0, CoarseMap.LAND, 40);
        builder.putFloor(1, 0, CoarseMap.LAND, 42);
        builder.putFloor(1, 0, CoarseMap.LAND, 90);
        CoarseMap map = builder.build();

        BlockPos start = new BlockPos(8, 41, 8);
        BlockPos goal = new BlockPos(24, 91, 8);
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        assertTrue(route.reachedGoal());
        assertEquals(90, last(route).getY());
    }

    /**
     * {@link CoarseRouter#costToGo}——段階4で層3のヒューリスティックへ併用するguide本体。
     * ゴールから逆向きに全状態へのコストを計算し、ブロック座標で引けるラッパーを返す。
     */
    @Test
    void costToGoIsZeroAtTheGoalItself() {
        CoarseMap map = flatLand().build();
        BlockPos goal = atChunk(5, 5);

        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        assertEquals(0.0, guide.estimate(goal.getX(), goal.getY(), goal.getZ()), 1e-9);
    }

    @Test
    void costToGoIncreasesWithDistanceOnFlatLand() {
        CoarseMap map = flatLand().build();
        BlockPos goal = atChunk(0, 0);
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        double near = guide.estimate(atChunk(2, 0).getX(), 64, atChunk(2, 0).getZ());
        double far = guide.estimate(atChunk(10, 0).getX(), 64, atChunk(10, 0).getZ());

        assertTrue(near > 0.0);
        assertTrue(far > near, "遠いセルの方がコストが高くなければならない: near=" + near + " far=" + far);
    }

    /**
     * 探索範囲の外（この地図が知らない座標）を引いても、無限大ではなく0を返す。
     * {@code AStarPathfinder}側は幾何学的なHeuristicとのmaxを取って使うので、0を返せば
     * 「情報が無いので寄与しない」で済む——無限大を返すと、層3の探索範囲がこの地図の
     * 読み取り範囲より広いだけで、範囲外の全ノードのヒューリスティックが汚染される。
     */
    @Test
    void costToGoReturnsZeroOutsideTheMap() {
        CoarseMap map = flatLand().build();
        BlockPos goal = atChunk(0, 0);
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        BlockPos farOutside = atChunk(RADIUS + 100, 0);
        assertEquals(0.0, guide.estimate(farOutside.getX(), 64, farOutside.getZ()));
    }

    /**
     * ゴールから完全に分断されたセル（溶岩の壁の向こう側）も、無限大ではなく0を返す。
     * {@link #costToGoReturnsZeroOutsideTheMap}と同じ安全側の理由——到達不能を無限大で
     * 表現すると、そのセルのヒューリスティックがmax経由で探索全体を壊しかねない。
     */
    @Test
    void costToGoReturnsZeroForCellsUnreachableFromTheGoal() {
        CoarseMapBuilder builder = flatLand();
        for (int z = -RADIUS; z < RADIUS; z++) {
            builder.replaceCell(0, z, CoarseMap.LAVA, 64);
        }
        CoarseMap map = builder.build();
        BlockPos goal = atChunk(20, 0);
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.AVOID);

        BlockPos cutOff = atChunk(-20, 0);
        assertEquals(0.0, guide.estimate(cutOff.getX(), 64, cutOff.getZ()));
    }

    /** 同じセル内の階層をまたぐcost-to-goは、垂直遷移のコスト（割増込み）を反映する。 */
    @Test
    void costToGoAccountsForVerticalTransitionsWithinTheSameCell() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        builder.putFloor(0, 0, CoarseMap.LAND, 40);
        builder.putFloor(0, 0, CoarseMap.LAND, 90);
        CoarseMap map = builder.build();

        BlockPos goal = new BlockPos(8, 91, 8);
        CostToGo guide = CoarseRouter.costToGo(map, goal, false, CoarseRouter.BridgePolicy.ALLOW);

        double atLowerFloor = guide.estimate(8, 41, 8);
        assertTrue(atLowerFloor > 0.0, "50ブロックの階層差はコスト0では済まないはず");
    }

    /**
     * ジ・エンドの群島。目的地の島との間は奈落で、同じ高さの島を経由すれば回り込める。
     * 島は4チャンク角、間は奈落2チャンク（32ブロック）。
     */
    private static CoarseMapBuilder archipelago() {
        CoarseMapBuilder builder = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        // 何も書かなければ床0＝未知。奈落は明示的にVOIDで埋める
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                builder.putFloor(x, z, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                        CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
            }
        }
        return builder;
    }

    private static void island(CoarseMapBuilder builder, int minChunkX, int minChunkZ, int height) {
        for (int x = minChunkX; x < minChunkX + 4; x++) {
            for (int z = minChunkZ; z < minChunkZ + 4; z++) {
                builder.replaceCell(x, z, CoarseMap.LAND, height);
            }
        }
    }

    /**
     * 奈落は「まだ知らない」ではなく「床が無いと分かっている」。未知セル並みに安く通れると、
     * 層1がジ・エンドの島間をまっすぐ突っ切る中間目標を並べ、詳細探索が毎回予算を焼く。
     */
    @Test
    void doesNotRouteThroughVoidWhenAvoiding() {
        CoarseMapBuilder builder = archipelago();
        island(builder, 0, 0, 64);
        island(builder, 10, 0, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(1, 1), atChunk(11, 1), false,
                CoarseRouter.BridgePolicy.AVOID);

        assertFalse(route.reachedGoal(), "奈落を挟んだ島へAVOIDで届いてはいけない");
    }

    /**
     * <b>奈落は{@link CoarseRouter.BridgePolicy#ALLOW}の時点で開く（溶岩より1段早い）。</b>
     *
     * <p>溶岩の橋には設定のスイッチがあるのに対し、奈落の橋には無い（層3は{@code canPlaceBlocks}
     * だけで判断する）。ALLOWで奈落まで通行不能にすると、層3の区間分割がジ・エンドで区間を
     * 1つも作れず、島間を1回の探索で渡ろうとして予算を焼く。
     */
    @Test
    void voidOpensOneStepEarlierThanLava() {
        CoarseMapBuilder voidBuilder = archipelago();
        island(voidBuilder, 0, 0, 64);
        island(voidBuilder, 10, 0, 64);
        assertTrue(CoarseRouter.findRoute(voidBuilder.build(), atChunk(1, 1), atChunk(11, 1), false,
                CoarseRouter.BridgePolicy.ALLOW).reachedGoal(),
                "ALLOWで奈落が通行不能だと、層3の区間分割がジ・エンドで成立しない");

        // 溶岩は据え置き。ALLOWでは「過半数が溶岩」のセルを渡らない
        CoarseMapBuilder lavaBuilder = flatLand();
        for (int x = 4; x <= 8; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                lavaBuilder.replaceCell(x, z, CoarseMap.LAVA, 62);
            }
        }
        assertFalse(CoarseRouter.findRoute(lavaBuilder.build(), atChunk(0, 0), atChunk(12, 0), false,
                CoarseRouter.BridgePolicy.ALLOW).reachedGoal(),
                "溶岩の橋は設定で切れる以上、ALLOWで勝手に渡ってはいけない");
    }

    /** 橋を架ける前提（最後の手段）なら、同じ地形で届く。 */
    @Test
    void bridgesAcrossVoidWhenNothingElseWorks() {
        CoarseMapBuilder builder = archipelago();
        island(builder, 0, 0, 64);
        island(builder, 10, 0, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(1, 1), atChunk(11, 1), false,
                CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal(), "BRIDGEでも届かないなら奈落を渡る手段が無い");
    }

    /**
     * <b>これが「回り込み」の核心。</b>低い島へは（下向きの橋が作れないので）詳細探索が降りられない。
     * 同じ高さの島を経由する道があるなら、奈落を最短で突っ切るより<b>遠回りでもそちらを選ぶ</b>
     * ——奈落の倍率がそれを決めている。
     */
    @Test
    void prefersSteppingStoneIslandsOverTheShortestVoidCrossing() {
        CoarseMapBuilder builder = archipelago();
        island(builder, 0, 0, 64);
        // 目的地の島。まっすぐ向かうと奈落が6チャンク（96ブロック）続く
        island(builder, 12, 0, 64);
        // 飛び石。遠回りになるが、奈落は1チャンクずつしか跨がない
        island(builder, 5, 6, 64);
        island(builder, 10, 5, 64);
        CoarseMap map = builder.build();

        CoarseRouter.Route route = CoarseRouter.findRoute(map, atChunk(1, 1), atChunk(13, 1), false,
                CoarseRouter.BridgePolicy.BRIDGE);

        assertTrue(route.reachedGoal());
        // まっすぐ突っ切っていれば、経路はZ=0の帯から出ない。飛び石を経由していればZが下がる
        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(0);
        assertTrue(maxZ > 32, "奈落を最短で突っ切っている（飛び石を経由していない）: maxZ=" + maxZ);

        // 対照: 同じ地形の奈落を陸にすると、遠回りする理由が消えてまっすぐ進む。
        // これが無いと「そもそも常に遠回りする経路しか出ない」テストと区別が付かない
        CoarseMapBuilder allLand = archipelago();
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                allLand.replaceCell(x, z, CoarseMap.LAND, 64);
            }
        }
        CoarseRouter.Route control = CoarseRouter.findRoute(allLand.build(), atChunk(1, 1), atChunk(13, 1),
                false, CoarseRouter.BridgePolicy.BRIDGE);
        int controlMaxZ = control.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(0);
        assertTrue(controlMaxZ <= 32, "陸なら遠回りする理由が無い: maxZ=" + controlMaxZ);
    }

    /**
     * 奈落と未訪問は別物。データが無いだけのセルは従来どおり通れる——未探索を通行不能にすると
     * 迂回路ごと消えて詰む。
     */
    @Test
    void unvisitedCellsStayPassableUnlikeVoid() {
        CoarseMapBuilder builder = flatLand();
        // 目的地との間を「未訪問」で塞ぐ（床を消すのではなく、そもそも書かない領域を作る）
        CoarseMapBuilder sparse = new CoarseMapBuilder(-RADIUS, -RADIUS, RADIUS * 2, RADIUS * 2);
        for (int x = -RADIUS; x < RADIUS; x++) {
            for (int z = -RADIUS; z < RADIUS; z++) {
                if (x >= 4 && x <= 8) {
                    continue;
                }
                sparse.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }

        CoarseRouter.Route route = CoarseRouter.findRoute(sparse.build(), atChunk(0, 0), atChunk(12, 0), false,
                CoarseRouter.BridgePolicy.AVOID);

        assertTrue(route.reachedGoal(), "未訪問セルを通行不能にすると、探索していない方角へ行けなくなる");
        assertFalse(builder.build().containsChunk(999, 999));
    }

    private static BlockPos last(CoarseRouter.Route route) {
        List<BlockPos> waypoints = route.waypoints();
        return waypoints.get(waypoints.size() - 1);
    }
}
