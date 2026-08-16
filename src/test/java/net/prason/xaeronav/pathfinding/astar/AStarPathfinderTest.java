package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * 経路探索コアの振る舞い。地形は{@link FakeCells}で文字として書く。
 *
 * <p>ここで押さえるのは「どの移動が生成され、どの移動が生成されないか」。コスト定数の細かい値ではなく、
 * 地形に対して人間が期待する経路が返るかを見る。案内として破綻するのは経路の形が違うときで、
 * 数tickのコスト差ではない。
 */
class AStarPathfinderTest {

    private static final BooleanSupplierNever NOT_CANCELLED = new BooleanSupplierNever();

    private static PathResult search(CellSource cells, BlockPos start, BlockPos goal) {
        return new AStarPathfinder(cells).search(start, goal, NOT_CANCELLED);
    }

    private static List<MovementType> movements(PathResult result) {
        return result.steps().stream().map(PathStep::movement).toList();
    }

    @Test
    void walksStraightAcrossFlatGround() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ......
                ######""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete(), "平地の直線は必ず到達できる");
        assertEquals(5, result.steps().size());
        assertEquals(List.of(MovementType.TRAVERSE, MovementType.TRAVERSE, MovementType.TRAVERSE,
                MovementType.TRAVERSE, MovementType.TRAVERSE), movements(result));
        assertTrue(result.steps().stream().noneMatch(PathStep::digging), "掘る必要はない");
        assertEquals(new BlockPos(5, 61, 0), last(result).pos());
    }

    @Test
    void climbsAndDescendsAOneBlockStep() {
        // x=2,3 に1マスの段差がある
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ..##..
                ######""");

        PathResult up = search(cells, new BlockPos(0, 61, 0), new BlockPos(3, 62, 0));
        assertTrue(up.complete());
        assertTrue(movements(up).contains(MovementType.ASCEND), "段差は登って越える: " + movements(up));
        assertTrue(up.steps().stream().noneMatch(PathStep::digging), "登れる段差を掘ってはいけない");

        PathResult down = search(cells, new BlockPos(3, 62, 0), new BlockPos(0, 61, 0));
        assertTrue(down.complete());
        assertTrue(movements(down).contains(MovementType.DESCEND), "降りる側も段差として扱う: " + movements(down));
    }

    @Test
    void climbsDiagonallyUpAStaircase() {
        // (0,61,0)→(1,62,1)→(2,63,2)→(3,64,3) と、XZ両方に1段ずつ上がる階段状の床だけを敷く。
        // カーディナルの床（例: (1,60,0)）は一切置かないので、カーディナル分解では登れない
        CellSource cells = diagonalStaircase();

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(3, 64, 3));

        assertTrue(result.complete());
        // カーディナル分解なら1段につき2手（登り+直進）＝6手かかる。斜めなら1段1手＝3手で済む
        assertEquals(3, result.steps().size(),
                "斜め昇りで1段1手のはず: " + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(List.of(MovementType.ASCEND, MovementType.ASCEND, MovementType.ASCEND), movements(result));
        assertEquals(new BlockPos(3, 64, 3), last(result).pos());
    }

    @Test
    void descendsDiagonally() {
        // 上のテストと同じ階段を逆向きに降りる
        CellSource cells = diagonalStaircase();

        PathResult result = search(cells, new BlockPos(3, 64, 3), new BlockPos(0, 61, 0));

        assertTrue(result.complete());
        assertEquals(3, result.steps().size(),
                "斜め降りで1段1手のはず: " + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(List.of(MovementType.DESCEND, MovementType.DESCEND, MovementType.DESCEND), movements(result));
        assertEquals(new BlockPos(0, 61, 0), last(result).pos());
    }

    @Test
    void doesNotCutThroughABlockedCorner() {
        // 斜め昇りの角の一方(1,62,0)を石で塞ぐ。到着地点の床(1,61,1)自体は空いているので、
        // 斜めでは行けないがカーディナル2手（z方向へ直進してから登る）では行ける
        CellSource cells = diagonalAscendWithCardinalDetour().set(1, 62, 0, FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 62, 1));

        assertTrue(result.complete(), "角が塞がっていても迂回すれば届く");
        assertEquals(2, result.steps().size(), "斜めが塞がっているのでカーディナル2手に迂回する: "
                + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(new BlockPos(0, 61, 1), result.steps().get(0).pos());
        assertEquals(new BlockPos(1, 62, 1), last(result).pos());
    }

    @Test
    void doesNotJumpDiagonallyUnderALowCeiling() {
        // 踏み切り地点の頭上(0,63,0)を石で塞ぐ。カーディナル2手側の頭上は別の座標なので影響を受けない
        CellSource cells = diagonalAscendWithCardinalDetour().set(0, 63, 0, FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 62, 1));

        assertTrue(result.complete(), "頭上が塞がっていても迂回すれば届く");
        assertEquals(2, result.steps().size(), "頭上が塞がって跳べないのでカーディナル2手に迂回する: "
                + result.steps().stream().map(PathStep::pos).toList());
        assertEquals(new BlockPos(0, 61, 1), result.steps().get(0).pos());
        assertEquals(new BlockPos(1, 62, 1), last(result).pos());
    }

    /** (0,61,0)から(3,64,3)まで、XZ両方に1段ずつ上がる床だけを敷いた階段。カーディナルの床は無い。 */
    private static CellSource diagonalStaircase() {
        SearchBounds bounds = new SearchBounds(-2, 55, -2, 8, 75, 8);
        return FakeCells.empty(bounds)
                .set(0, 60, 0, FakeCells.STONE)
                .set(1, 61, 1, FakeCells.STONE)
                .set(2, 62, 2, FakeCells.STONE)
                .set(3, 63, 3, FakeCells.STONE);
    }

    /**
     * (0,61,0)→(1,62,1)の斜め昇り1段と、それを迂回できるカーディナル経路
     * （(0,61,0)→(0,61,1)→(1,62,1)、z方向へ直進してから登る）の両方が成立する床だけを敷いた地形。
     */
    private static FakeCells diagonalAscendWithCardinalDetour() {
        SearchBounds bounds = new SearchBounds(-2, 55, -2, 8, 75, 8);
        return FakeCells.empty(bounds)
                .set(0, 60, 0, FakeCells.STONE)
                .set(0, 60, 1, FakeCells.STONE)
                .set(1, 61, 1, FakeCells.STONE);
    }

    @Test
    void digsThroughAWallWhenThereIsNoWayAround() {
        // 天井が岩盤なので登って越えられない。背丈2マスの壁を掘り抜くしかない
        CellSource cells = FakeCells.of(0, 60, 0, """
                BBBBBB
                ..##..
                ..##..
                ######""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete());
        List<BlockPos> dug = result.steps().stream().flatMap(step -> step.digCells().stream()).toList();
        // 足元だけでなく頭の高さも掘る対象に挙がる。到着地点1マスだけを見ていると、
        // 頭がつかえて実際には通れない経路を「掘れば通れる」として出してしまう
        assertTrue(dug.contains(new BlockPos(2, 61, 0)), "壁の足元を掘る: " + dug);
        assertTrue(dug.contains(new BlockPos(2, 62, 0)), "壁の頭の高さも掘る: " + dug);
        assertTrue(dug.contains(new BlockPos(3, 61, 0)), "壁の足元を掘る: " + dug);
        assertTrue(dug.contains(new BlockPos(3, 62, 0)), "壁の頭の高さも掘る: " + dug);
    }

    @Test
    void doesNotDigThroughUndiggableBlocks() {
        // 掘れない壁。diggingEnabled=false のときChunkViewが全固体をこの状態にするのと等価
        CellSource cells = FakeCells.of(0, 60, 0, """
                BBBBBB
                ..BB..
                ..BB..
                ######""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertFalse(result.complete(), "掘れない壁の向こうへは到達できない");
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getX() < 2),
                "壁を越えたステップがあってはいけない: " + result.steps().stream().map(PathStep::pos).toList());
    }

    @Test
    void returnsNoRouteRatherThanAUselesslyShortOne() {
        // 動ける範囲が MIN_DIST_PATH(5ブロック) に満たない密室
        CellSource cells = FakeCells.of(0, 60, 0, """
                BBBB
                ..BB
                BBBB""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(20, 61, 0));

        assertFalse(result.complete());
        assertTrue(result.steps().isEmpty(),
                "数マスしか進めない経路は提示しない（案内として役に立たないため）: " + result.steps().size());
    }

    @Test
    void offersAPartialRouteWhenTheGoalIsOutOfReach() {
        // 長い廊下の先が塞がっている。ゴールへは届かないが、進める分は案内する価値がある
        CellSource cells = FakeCells.of(0, 60, 0, """
                BBBBBBBBBBBBBBBBBB
                ................B#
                ................B#
                ##################""");

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(40, 61, 0));

        assertFalse(result.complete(), "ゴールには届いていない");
        assertFalse(result.steps().isEmpty(), "届く範囲までは案内する（design doc §4-4の暫定経路）");
        assertTrue(last(result).pos().getX() >= 5,
                "始点から MIN_DIST_PATH 以上進んだ地点を返す: " + last(result).pos());
    }

    @Test
    void swimsAcrossWaterWithoutAFloor() {
        // 水面が続く区間。足場が無いのでTraverseは生成されず、Swimでしか渡れない
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                .~~~~.
                #~~~~#""")
                .fillWith(FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete());
        assertTrue(movements(result).contains(MovementType.SWIM), "水の区間は泳ぎとして出す: " + movements(result));
    }

    @Test
    void climbsALadderInsteadOfDigging() {
        // 縦穴に梯子（x=3のy=61〜63）。掘るより梯子の方が安いので、梯子を使う経路が出るべき
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...H##
                ###H##
                ###H##
                ######""");

        PathResult result = search(cells, new BlockPos(3, 61, 0), new BlockPos(1, 63, 0));

        assertTrue(result.complete());
        assertTrue(movements(result).contains(MovementType.CLIMB), "梯子を登る移動が出る: " + movements(result));
        assertTrue(result.steps().stream().noneMatch(PathStep::digging),
                "梯子があるなら掘らない: " + result.steps().stream().flatMap(s -> s.digCells().stream()).toList());
    }

    @Test
    void surfaceSearchStopsAtTheFirstCellAtOrAboveSurfaceLevel() {
        // 東へ向かって階段状に上がる地形。y=64 が地上
        CellSource cells = FakeCells.of(0, 60, 0, """
                .....
                ....#
                ...##
                ..###
                .####
                #####""");

        PathResult result = new AStarPathfinder(cells)
                .searchToSurface(new BlockPos(2, 61, 0), 64, NOT_CANCELLED);

        assertTrue(result.complete(), "地上へ出る道がある");
        assertTrue(last(result).pos().getY() >= 64,
                "surfaceY 以上で止まる: " + last(result).pos());
        assertTrue(result.steps().stream().filter(step -> step.pos().getY() >= 64).count() == 1,
                "surfaceY に達したら即座に打ち切る（そこから先は本来の目的地への経路が引き直される）");
    }

    @Test
    void surfaceSearchWalksOutFromUnderARoofInsteadOfStoppingAtHeight() {
        // y=65〜66 の坑道。西側(x=0,1)は岩の天井の下、東側(x=2,3)は空が開けている
        CellSource cells = FakeCells.of(0, 64, 0, """
                ....
                ##..
                ....
                ....
                ####""");

        PathResult result = new AStarPathfinder(cells)
                .searchToSurface(new BlockPos(0, 65, 0), 64, NOT_CANCELLED);

        assertTrue(result.complete(), "開口部まで歩けば地上に出られる");
        assertTrue(last(result).pos().getX() >= 2,
                "天井の下は高さが足りていても地上ではない。空が開けた列まで進む: " + last(result).pos());
        assertTrue(result.steps().stream().noneMatch(PathStep::digging),
                "既存の坑道を歩いて出られるなら掘らない: " + movements(result));
    }

    @Test
    void samePathIsReturnedForTheSameTerrain() {
        // 展開ノード数で打ち切るのは、同じ入力なら同じ経路を返させるため。
        // 時間で打ち切ると、そのときのマシン負荷で線が変わって案内が落ち着かない
        CellSource cells = FakeCells.of(0, 60, 0, """
                ..........
                ....##....
                ##########""");
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(9, 61, 0);

        List<BlockPos> first = search(cells, start, goal).steps().stream().map(PathStep::pos).toList();
        List<BlockPos> second = search(cells, start, goal).steps().stream().map(PathStep::pos).toList();

        assertEquals(first, second);
        assertNotEquals(0, first.size());
    }

    /**
     * 幅{@code gapBlocks}マスの割れ目。両岸は岩盤で、掘って降りることも回り込むこともできない。
     * 断面の外（z≠0）も岩盤で埋めて、跳ぶ以外の道を残さない。
     */
    private static FakeCells chasm(int gapBlocks) {
        FakeCells cells = FakeCells.of(0, 60, 0, "B".repeat(gapBlocks + 4))
                .fillWith(FakeCells.BEDROCK);
        for (int x = 0; x < gapBlocks + 4; x++) {
            for (int y = 61; y <= 63; y++) {
                cells.set(x, y, 0, FakeCells.AIR);
            }
        }
        // 割れ目は x=2 から gapBlocks マス。床を抜き、落ちても足場が無いよう深く空ける
        for (int x = 2; x < 2 + gapBlocks; x++) {
            for (int y = 40; y <= 60; y++) {
                cells.set(x, y, 0, FakeCells.AIR);
            }
        }
        return cells;
    }

    @Test
    void jumpsGapsUpToThreeBlocksWide() {
        for (int gap = 1; gap <= 3; gap++) {
            CellSource cells = chasm(gap);

            PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(2 + gap, 61, 0));

            assertTrue(result.complete(), gap + "マスの割れ目は跳んで渡れるはず");
            assertTrue(movements(result).contains(MovementType.JUMP),
                    gap + "マスの割れ目を跳ばずに渡った: " + movements(result));
        }
    }

    @Test
    void doesNotJumpOffSoulSand() {
        // 1マスの割れ目。踏み切り地点(x=1)だけをソウルサンドにする
        CellSource cells = chasm(1).set(1, 60, 0, FakeCells.SOUL_SAND);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "減速したまま踏み切ると届かない。跳べと言ってはいけない: " + movements(result));
    }

    @Test
    void doesNotJumpOverLava() {
        // 1マスの割れ目の底を溶岩で埋める。跳べる幅ではあるが、外せば死ぬ
        FakeCells cells = chasm(1);
        cells.set(2, 60, 0, FakeCells.LAVA);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "溶岩の上は跳ばない: " + movements(result));
    }

    @Test
    void stillJumpsWhenThereIsAFloorAboveTheLava() {
        // 溶岩はあるが、その上に床がある割れ目。落ちても溶岩には触れないので跳んでよい
        FakeCells cells = chasm(1);
        cells.set(2, 55, 0, FakeCells.LAVA).set(2, 56, 0, FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertTrue(result.complete());
        assertTrue(movements(result).contains(MovementType.JUMP),
                "溶岩との間に床があるなら跳べる: " + movements(result));
    }

    @Test
    void doesNotJumpGapsBeyondSprintJumpRange() {
        // 4マスの割れ目は疾走ジャンプの到達限界を超える
        CellSource cells = chasm(4);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(6, 61, 0));

        assertFalse(result.complete(), "届かない距離を跳べと言ってはいけない");
        assertFalse(movements(result).contains(MovementType.JUMP), "跳躍は生成されない: " + movements(result));
    }

    @Test
    void bridgesInsteadOfJumpingWhenJumpingIsDisabled() {
        CellSource cells = chasm(2).jumpGapEnabled(false).canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertTrue(result.complete(), "跳べなくてもブロックを置けば渡れる");
        assertFalse(movements(result).contains(MovementType.JUMP),
                "跳躍を切っているのに跳んだ: " + movements(result));
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging),
                "跳ぶ代わりに足場を置いて渡る: " + movements(result));
    }

    @Test
    void doesNotCrossAtAllWhenJumpingAndBridgingAreBothDisabled() {
        CellSource cells = chasm(2).jumpGapEnabled(false).canPlaceBlocks(false);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(result.complete(), "跳ぶことも置くこともできない割れ目は渡れない");
    }

    @Test
    void landsOnTheNearestBankRatherThanJumpingFarther() {
        // 2マスの割れ目の対岸(x=4)の先に、さらに割れ目(x=5)がある。手前の岸に降りるべき
        FakeCells cells = chasm(2);
        for (int y = 40; y <= 60; y++) {
            cells.set(5, y, 0, FakeCells.AIR);
        }
        cells.set(6, 61, 0, FakeCells.AIR).set(6, 62, 0, FakeCells.AIR).set(6, 63, 0, FakeCells.AIR);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertTrue(result.complete());
        assertEquals(new BlockPos(4, 61, 0), last(result).pos(), "手前の岸に降りる");
    }

    /**
     * 掘って登ることも迂回することもできない断崖。Pillarでしか上がれない。
     * 断面の外（z≠0）を岩盤で埋めるのは、そこが空気のままだと橋を架けて回り込めてしまうため。
     */
    private static FakeCells bedrockCliff() {
        return FakeCells.of(0, 60, 0, """
                ......
                ......
                .BBB..
                .BBB..
                .BBB..
                BBBBBB""")
                .fillWith(FakeCells.BEDROCK);
    }

    @Test
    void pillarsUpACliffThatCannotBeDugOrWalkedAround() {
        CellSource cells = bedrockCliff().canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 64, 0));

        assertTrue(result.complete(), "ブロックを積めば断崖の上に出られる");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging),
                "登るためにブロックを置く区間が出る: " + movements(result));
        assertTrue(result.steps().stream().filter(PathStep::bridging)
                        .allMatch(step -> step.movement() == MovementType.ASCEND),
                "積んで登る区間は上昇として案内する: " + movements(result));
    }

    @Test
    void doesNotPillarWithoutBlocksInTheHotbar() {
        CellSource cells = bedrockCliff().canPlaceBlocks(false);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 64, 0));

        assertFalse(result.complete(), "置くブロックが無ければ断崖は越えられない");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "持っていないブロックを置けとは言わない: " + result.steps());
    }

    @Test
    void doesNotPillarThroughAnUnbreakableCeiling() {
        // 断崖と同じ地形だが、積み上がる列(x=0)の頭上が岩盤で塞がっている
        CellSource cells = bedrockCliff().set(0, 63, 0, FakeCells.BEDROCK).canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 64, 0));

        assertFalse(result.complete(), "掘れない天井の下では積み上がれない");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging), "積む区間は出ない: " + result.steps());
    }

    /**
     * 岩盤に囲まれた溶岩の水路。足元(y=60)の4マスが溶岩なので、歩くには広すぎ跳ぶには遠すぎる。
     * 周囲を岩盤で埋めてあるので、迂回も掘削も空中への足場設置もできない——渡る唯一の手が
     * 溶岩そのものに足場を置くことになる。
     */
    private static FakeCells lavaPond() {
        return FakeCells.of(0, 60, 0, """
                ......
                ......
                #LLLL#""")
                .fillWith(FakeCells.BEDROCK)
                .canPlaceBlocks(true);
    }

    @Test
    void bridgesOverLavaWhenThereIsNoOtherWay() {
        CellSource cells = lavaPond();

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete(), "詰むくらいなら溶岩に足場を置いて渡る");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging),
                "溶岩の上は設置で渡る: " + movements(result));
    }

    @Test
    void doesNotBridgeOverLavaWhenDisabled() {
        CellSource cells = lavaPond().lavaBridgingEnabled(false);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertFalse(result.complete(), "切っている以上、溶岩は渡れないままでよい");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "溶岩に足場を置く案内はしない: " + result.steps());
    }

    /** 溶岩の橋は最後の手段。乾いた迂回路があるなら、多少遠回りでもそちらを通る。 */
    @Test
    void prefersADryDetourOverBridgingLava() {
        // 溶岩の水路と同じ地形に、z=1側だけ素の地面の迂回路を彫る
        FakeCells cells = lavaPond();
        for (int x = 0; x <= 5; x++) {
            cells.set(x, 60, 1, FakeCells.STONE);
            cells.set(x, 61, 1, FakeCells.AIR);
            cells.set(x, 62, 1, FakeCells.AIR);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete(), "迂回路があるので到達できる");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "迂回できるのに溶岩へ足場を置いた: " + movements(result));
    }

    @Test
    void doesNotPillarWhileFloatingInWater() {
        // 水中に浮いている状態。踏み切って真下にブロックを置くことはできない
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ......
                .~~~..
                .~~~..
                BBBBBB""")
                .fillWith(FakeCells.BEDROCK)
                .canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(1, 64, 0));

        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "水中から積み上げる案内はしない: " + result.steps());
    }

    /**
     * 高さ{@code drop}マスの一枚岩の崖。降りる手段は落下しかない——岩盤なので掘り下げられず、
     * 断面の外は岩盤で埋めるので迂回もできない。始点は崖の上(x=0)、終点は崖下(x=1)。
     */
    private static FakeCells sheerDrop(int drop) {
        StringBuilder diagram = new StringBuilder("......\n......\n");
        diagram.append("B.....\n".repeat(drop));
        diagram.append("BBBBBB");
        return FakeCells.of(0, 60, 0, diagram.toString()).fillWith(FakeCells.BEDROCK);
    }

    private static BlockPos dropTop(int drop) {
        return new BlockPos(0, 61 + drop, 0);
    }

    private static final BlockPos DROP_BOTTOM = new BlockPos(1, 61, 0);

    @Test
    void fallsFreelyUpToTheSafeHeight() {
        CellSource cells = sheerDrop(3);

        PathResult result = search(cells, dropTop(3), DROP_BOTTOM);

        assertTrue(result.complete(), "安全な高さの落下は設定に関係なく降りられる");
        assertEquals(List.of(MovementType.DESCEND), movements(result));
    }

    @Test
    void doesNotFallBeyondTheSafeHeightByDefault() {
        CellSource cells = sheerDrop(5);

        PathResult result = search(cells, dropTop(5), DROP_BOTTOM);

        assertFalse(result.complete(), "既定では痛い落下を提示しない");
    }

    @Test
    void fallsWithDamageWhenTolerated() {
        // 5マスの落下はダメージ2点。これを許容範囲に収める
        CellSource cells = sheerDrop(5).maxFallDamagePoints(2);

        PathResult result = search(cells, dropTop(5), DROP_BOTTOM);

        assertTrue(result.complete(), "許容範囲のダメージなら飛び降りて降りられる");
        assertEquals(List.of(MovementType.FALL_DAMAGE), movements(result));
    }

    @Test
    void doesNotFallWhenTheDamageExceedsTheTolerance() {
        CellSource cells = sheerDrop(5).maxFallDamagePoints(1);

        PathResult result = search(cells, dropTop(5), DROP_BOTTOM);

        assertFalse(result.complete(), "許容量を超えるダメージの落下は提示しない");
    }

    @Test
    void usesTheWaterBucketForDropsBeyondTheDamageTolerance() {
        // 12マスの落下はダメージ9点。体力満タン(許容6点)でも耐えられないが、MLGなら無傷で降りられる
        CellSource cells = sheerDrop(12).maxFallDamagePoints(6).canMlgWaterBucket(true);

        PathResult result = search(cells, dropTop(12), DROP_BOTTOM);

        assertTrue(result.complete(), "水バケツがあれば高さに関係なく降りられる");
        assertEquals(List.of(MovementType.FALL_MLG), movements(result));
    }

    @Test
    void takesTheCheapDamageRatherThanTheWaterBucket() {
        CellSource cells = sheerDrop(5).maxFallDamagePoints(6).canMlgWaterBucket(true);

        PathResult result = search(cells, dropTop(5), DROP_BOTTOM);

        assertTrue(result.complete());
        assertEquals(List.of(MovementType.FALL_DAMAGE), movements(result),
                "軽いダメージで済む落下に、わざわざ水バケツの手間はかけない");
    }

    private static PathStep last(PathResult result) {
        return result.steps().get(result.steps().size() - 1);
    }

    /** キャンセルされない{@code BooleanSupplier}。ラムダより意図が読める。 */
    private static final class BooleanSupplierNever implements java.util.function.BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return false;
        }
    }
}
