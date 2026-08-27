package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

class LiveCoarseSamplerTest {

    @Test
    void detectsACliffWithinASingleChunk() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            int floorY = x < 8 ? 64 : 60;
            for (int z = 0; z < 16; z++) {
                cells.set(x, floorY, z, FakeCells.STONE);
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 0));
        assertEquals(60, map.minHeightAtFloor(0, 0, 0));
        assertEquals(64, map.maxHeightAtFloor(0, 0, 0));
    }

    /**
     * 1セルに{@link CoarseMap#MAX_FLOORS}を超える階層があるとき、残すのは参照Yに近い床。
     * 参照Yを見ずに{@code CoarseMapBuilder}へそのまま渡すと、あちらは常に「最も高い床」を
     * 追い出すので、プレイヤーが立っている一番上の回廊がそのまま消える。
     */
    @Test
    void keepsTheFloorsNearestTheReferenceYWhenACellHasTooMany() {
        // 1列あたりの走査はMAX_FLOORSで打ち切られるので、列ごとに違う階層を見せて
        // チャンク全体では5クラスタになるようにする（ネザーでは普通に起きる形）
        SearchBounds bounds = new SearchBounds(0, 0, 0, 15, 127, 15);
        FakeCells cells = FakeCells.empty(bounds);
        // 階層の間隔はXaeroの洞窟レイヤー幅（30）に合わせる。これより詰めると同じ床として
        // まとめられてしまう（FLOOR_CLUSTER_THRESHOLD_BLOCKS）
        for (int x = 0; x < 16; x++) {
            int[] floorYs = x < 8 ? new int[] {28, 58, 88, 118} : new int[] {8};
            for (int floorY : floorYs) {
                for (int z = 0; z < 16; z++) {
                    cells.set(x, floorY, z, FakeCells.STONE);
                }
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds, 118, () -> false);

        assertEquals(CoarseMap.MAX_FLOORS, map.floorCount(0, 0));
        assertEquals(118, map.heightAtFloor(0, 0, CoarseMap.MAX_FLOORS - 1),
                "参照Yの床（プレイヤーが立っている回廊）が残っていない");
        assertEquals(28, map.heightAtFloor(0, 0, 0), "参照Yから最も遠い床が捨てられているはず");
    }

    @Test
    void classifiesAMostlyWaterChunkAsWater() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 60, z, FakeCells.WATER);
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.WATER, map.kindAtFloor(0, 0, 0));
    }

    @Test
    void lavaMixedChunkHeightIgnoresTheLavaSurface() {
        // 溶岩の海の縁: 1/4が溶岩面(Y=31、LAVA_MIXEDの下限)、残りがそれより高い地面(Y=40)。
        // 代表高さが溶岩面へ引っ張られると、waypointが立てない場所に落ちる
        SearchBounds bounds = new SearchBounds(0, 20, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x < 4) {
                    cells.set(x, 31, z, FakeCells.LAVA);
                } else {
                    cells.set(x, 40, z, FakeCells.STONE);
                }
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAVA_MIXED, map.kindAtFloor(0, 0, 0));
        assertEquals(40, map.heightAtFloor(0, 0, 0));
    }

    /**
     * ネザーの形。岩盤天井があるので{@code openSkyY}は天井（ここでは探索範囲の遥か上）を指すが、
     * 地図に載せたいのは範囲内にある足元の地形。走査開始を範囲上端で頭打ちにしないと、
     * 範囲外を読んで全列がABSENTになり、地図が1セルも埋まらない。
     */
    @Test
    void samplesTheGroundInsideBoundsWhenTheCeilingIsAboveThem() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 74, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 42, z, FakeCells.STONE);
            }
        }
        // 探索範囲の外にある岩盤天井。openSkyYはこれを指す
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(1, map.knownCells(), "天井の下にある地形が地図に載らなければならない");
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 0));
        assertEquals(42, map.heightAtFloor(0, 0, 0));
    }

    /** 範囲の上端から遠く下にある溶岩の海も拾う（走査が浅すぎると海そのものが地図に載らない）。 */
    @Test
    void reachesLavaFarBelowTheTopOfBounds() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 74, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 31, z, FakeCells.LAVA);
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAVA, map.kindAtFloor(0, 0, 0), "溶岩の海が地図に載らなければ迂回もできない");
    }

    /**
     * ネザーの3D迷路。探索範囲の上端が岩の中に埋まっている列で、その上端を地面と report しては
     * ならない。全列が同じ高さになって起伏0＝崖ペナルティ0の平坦な最安地形に見えるうえ、
     * 足元の溶岩の海が地図から丸ごと消える。
     */
    @Test
    void doesNotReportTheTopOfBoundsAsGroundWhenItIsInsideRock() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 72, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // 範囲上端(72)から50までを岩で埋める。その下は空洞で、床は溶岩の海
                for (int y = 50; y <= 72; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
                cells.set(x, 31, z, FakeCells.LAVA);
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(CoarseMap.LAVA, map.kindAtFloor(0, 0, 0), "天井側の岩を地面と読むと溶岩の海が地図から消える");
        assertEquals(31, map.heightAtFloor(0, 0, 0));
    }

    /** 上から下まで岩で詰まった列は「不明」。天井の岩を地面と読んではいけない。 */
    @Test
    void columnsFilledWithRockAreLeftUnknown() {
        SearchBounds bounds = new SearchBounds(0, 60, 0, 15, 72, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 60; y <= 72; y++) {
                    cells.set(x, y, z, FakeCells.STONE);
                }
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(0, map.knownCells());
    }

    /**
     * ネザーの3D迷路の核心: 同じXZに上下2本の独立した通路が重なる場合、両方が別々の床として
     * 地図に残らなければならない。潰して1つの高さにすると、垂直に分断された通路が
     * 「安い段差」として繋がって見えたり、片方の通路が丸ごと消えたりする。
     */
    @Test
    void capturesTwoIndependentFloorsStackedInTheSameColumn() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 100, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 90, z, FakeCells.STONE); // 上の階（天井のすぐ下）
                cells.set(x, 40, z, FakeCells.STONE); // 下の階
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(2, map.floorCount(0, 0));
        assertEquals(40, map.heightAtFloor(0, 0, 0), "床は高さ昇順");
        assertEquals(90, map.heightAtFloor(0, 0, 1));
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 0));
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 1));
    }

    /**
     * 片方の階が溶岩の海、もう片方が普通の陸のケース。層1が段階4以降で溶岩の海と
     * 別の階層を正しく区別できるかは、そもそも両方が床として残っているかにかかっている。
     */
    @Test
    void capturesALavaFloorBelowALandFloor() {
        SearchBounds bounds = new SearchBounds(0, 10, 0, 15, 100, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 70, z, FakeCells.STONE);
                cells.set(x, 31, z, FakeCells.LAVA);
            }
        }
        cells.openSkyYOverride(200);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(2, map.floorCount(0, 0));
        assertEquals(CoarseMap.LAVA, map.kindAtFloor(0, 0, 0));
        assertEquals(31, map.heightAtFloor(0, 0, 0));
        assertEquals(CoarseMap.LAND, map.kindAtFloor(0, 0, 1));
        assertEquals(70, map.heightAtFloor(0, 0, 1));
    }

    /** 地上・ジ・エンドと同じ「1列1床」の場合、床数は常に1に収まる（回帰防止）。 */
    @Test
    void aSingleFloorColumnStillProducesExactlyOneFloor() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                cells.set(x, 64, z, FakeCells.STONE);
            }
        }

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(1, map.floorCount(0, 0));
    }

    @Test
    void columnsWithNoDataAreLeftUnknown() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.ABSENT);

        CoarseMap map = LiveCoarseSampler.sample(cells, bounds);

        assertEquals(0, map.knownCells());
        assertEquals(0, map.floorCount(0, 0), "データが無いセルは床を1つも持たない");
    }

    /**
     * <b>床0には3通りの意味がある。</b>空気しか無かった（奈落）・上から下まで固体だった（岩の内部）・
     * 未ロードで読めなかった（分からない）。奈落だけが{@link CoarseMap#VOID}で、他の2つは未知のまま。
     *
     * <p>奈落を未知に倒すと、層1が{@code UNKNOWN_MULTIPLIER}（通行可能・ほぼ最安）でジ・エンドの
     * 島間をまっすぐ突っ切る。逆に岩や未ロードを奈落に倒すと、渡れるはずのない所へ橋を架ける
     * 経路が出る。
     */
    @Test
    void tellsVoidApartFromRockAndFromUnloaded() {
        SearchBounds bounds = new SearchBounds(0, 50, 0, 15, 80, 15);

        CoarseMap air = LiveCoarseSampler.sample(FakeCells.empty(bounds).fillWith(FakeCells.AIR), bounds);
        assertEquals(1, air.floorCount(0, 0), "空気だけの列は「床が無い」と分かっている");
        assertEquals(CoarseMap.VOID, air.kindAtFloor(0, 0, 0));
        assertEquals(CoarseMap.UNKNOWN_HEIGHT, air.heightAtFloor(0, 0, 0),
                "奈落に代表高さは無い。具体値を入れると層2・層3がそこを目指す");

        CoarseMap rock = LiveCoarseSampler.sample(FakeCells.empty(bounds).fillWith(FakeCells.STONE), bounds);
        assertEquals(0, rock.floorCount(0, 0), "岩で詰まった列は奈落ではない");

        CoarseMap unloaded =
                LiveCoarseSampler.sample(FakeCells.empty(bounds).fillWith(FakeCells.ABSENT), bounds);
        assertEquals(0, unloaded.floorCount(0, 0), "未ロードは「床が無い」と言い切れない");
    }
}
