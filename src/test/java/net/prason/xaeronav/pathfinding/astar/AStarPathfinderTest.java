package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
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

    /**
     * 海の中では「水面に顔を出せた」を地上到達として認める。{@code openSkyY}が使う
     * MOTION_BLOCKINGハイトマップは流体を含むので水面の<b>1つ上</b>を指すが、そこは空気で
     * 足場が無く、泳いでいるプレイヤーが立てるノードにならない。そのまま条件にすると、
     * 外洋では地上へ出る中継探索が原理的に成功できなかった。
     */
    @Test
    void surfaceSearchReachesTheWaterLineWhenTheColumnIsSea() {
        // y=62 が海底、y=63〜66 が水、y=67 から上は空（fillWithを使わないので図の外は空気）
        CellSource cells = FakeCells.of(0, 62, 0, """
                ......
                ~~~~~~
                ~~~~~~
                ~~~~~~
                ~~~~~~
                ######""");

        PathResult result = new AStarPathfinder(cells)
                .searchToSurface(new BlockPos(0, 63, 0), 64, NOT_CANCELLED);

        assertTrue(result.complete(), "泳ぎ上がれば水面に出られる");
        assertEquals(66, last(result).pos().getY(),
                "水面のセルで到達とみなす（その1つ上は水の外＝立てない）: " + last(result).pos());
    }

    /**
     * 水没した横穴と、遠回りだが息継ぎできる迂回路。息が続かない潜水は<b>移動そのものを作らない</b>ので、
     * 探索は最初から迂回路だけを見る。
     *
     * <p>y=63が水面（顔が出せる高さ）、y=61〜62が水中の横穴。北(z=1)側は水面まで開けている。
     */
    private static FakeCells floodedTunnel() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 24, 76, 8))
                .fillWith(FakeCells.BEDROCK);
        for (int x = -1; x <= 13; x++) {
            // z=0: 天井(y=64)で塞がれた水没坑道。ここを泳ぐ間ずっと頭が水に浸かる
            for (int y = 61; y <= 63; y++) {
                cells.set(x, y, 0, FakeCells.WATER);
            }
            // z=2: 空の下に水面がある水路。y=63を泳げば顔が出るので息は減らない
            for (int y = 61; y <= 63; y++) {
                cells.set(x, y, 2, FakeCells.WATER);
            }
            cells.set(x, 64, 2, FakeCells.AIR);
        }
        // 両端(x=-1, x=13)だけが2本を繋ぐ。途中のz=1は岩盤の壁なので、坑道の途中で
        // 顔を出しに抜けることはできない
        for (int x : new int[] {-1, 13}) {
            for (int y = 61; y <= 63; y++) {
                cells.set(x, y, 1, FakeCells.WATER);
            }
            cells.set(x, 64, 1, FakeCells.AIR);
        }
        return cells;
    }

    @Test
    void doesNotRouteThroughADiveLongerThanOneBreath() {
        CellSource cells = floodedTunnel().maxSubmergedRunBlocks(4);

        PathResult result = search(cells, new BlockPos(0, 62, 0), new BlockPos(12, 62, 0));

        assertTrue(result.complete(), "顔を出せる水路を回れば到達できる");
        assertTrue(result.steps().stream().anyMatch(step -> step.pos().getZ() != 0),
                "息の続かない水没坑道を突っ切らず、顔を出せる水路へ逸れる: "
                        + result.steps().stream().map(PathStep::pos).toList());
    }

    /**
     * {@link #doesNotRouteThroughADiveLongerThanOneBreath}が空振りしていないことの裏付け。
     * 上限を外せば同じ地形で水没横穴を直進する＝逸れる理由が息であることが確かめられる。
     */
    @Test
    void divesStraightThroughWhenTheBreathLimitIsOff() {
        CellSource cells = floodedTunnel().maxSubmergedRunBlocks(0);

        PathResult result = search(cells, new BlockPos(0, 62, 0), new BlockPos(12, 62, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getZ() == 0),
                "上限が無ければ最短の水没坑道を直進する: "
                        + result.steps().stream().map(PathStep::pos).toList());
    }

    /**
     * 水中を斜めに泳ぐ。足場のある斜め移動（{@code addDiagonalTraverse}）は水中で成立しないので、
     * 泳ぎ専用の斜めが無いとカーディナル2手に分解される。
     *
     * <p>天井を付けて浮上できない形にしてあるのは、斜めに泳げるかどうかだけを見るため。開けた海だと
     * 先に水面へ上がる（{@link #surfacesBeforeCrossingOpenWater}）ので、そちらの挙動が混ざる。
     */
    @Test
    void swimsDiagonallyThroughWater() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 12, 76, 12));
        // 底(y=60)と天井(y=64)に挟まれた水塊。y=62を泳ぐ限り足場は無い
        for (int x = -1; x <= 6; x++) {
            for (int z = -1; z <= 6; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                cells.set(x, 64, z, FakeCells.BEDROCK);
                for (int y = 61; y <= 63; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }

        PathResult result = search(cells, new BlockPos(0, 62, 0), new BlockPos(4, 62, 4));

        assertTrue(result.complete());
        assertEquals(4, result.steps().size(),
                "斜めに泳げば1手で1マスずつXZ両方に進む: " + result.steps().stream().map(PathStep::pos).toList());
        assertTrue(result.steps().stream().allMatch(step -> step.movement() == MovementType.SWIM),
                "水中の斜めも泳ぎとして案内する: " + movements(result));
    }

    /**
     * 岸(x=0)から水面(x=1..width)を渡って対岸(x=width+1)へ。水面は y=62、岸は y=63 で、
     * 水面のほうが1マス低い普通の海岸の形。
     */
    private static FakeCells strait(int width) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, width + 12, 76, 8));
        for (int x = -1; x <= width + 2; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                boolean water = x >= 1 && x <= width;
                cells.set(x, 61, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
                cells.set(x, 62, z, water ? FakeCells.WATER : FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    @Test
    void takesTheBoatAcrossAWideStrait() {
        CellSource cells = strait(40).boatAvailable(true);

        PathResult result = search(cells, new BlockPos(0, 63, 0), new BlockPos(41, 63, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().anyMatch(PathStep::boating),
                "40マスの水面はボートで渡る: " + movements(result));
        assertEquals(1, result.steps().stream().filter(step -> step.movement() == MovementType.BOAT
                        && step.cost() > ActionCosts.BOAT_OVERHEAD_TICKS).count(),
                "出す・乗る手間を払うのは漕ぎ出す1回だけ: " + movements(result));
    }

    /**
     * 同じ形の細い水路ならボートは出さない。乗り降りの手間（{@code BOAT_OVERHEAD_TICKS}）が
     * 泳ぎとの差を上回るため——「小川を渡るのにいちいちボートを出せ」とは言わない。
     */
    @Test
    void swimsAcrossANarrowChannelInsteadOfLaunchingABoat() {
        CellSource cells = strait(3).boatAvailable(true);

        PathResult result = search(cells, new BlockPos(0, 63, 0), new BlockPos(4, 63, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().noneMatch(PathStep::boating),
                "3マスの水路はそのまま泳いで渡る: " + movements(result));
    }

    /**
     * すでに乗っているなら、乗り込む手間をもう一度払わせない。払わせると、残りの水面が短い場面で
     * 「降りて泳いだ方が安い」という案内になる。
     */
    @Test
    void doesNotChargeBoardingAgainWhileAlreadyRiding() {
        CellSource cells = strait(40).boatAvailable(true).ridingBoat(true);

        // 始点は水面（乗っている位置）。目的地は対岸
        PathResult result = search(cells, new BlockPos(1, 62, 0), new BlockPos(41, 63, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> !step.boating()
                        || step.cost() < ActionCosts.BOAT_OVERHEAD_TICKS),
                "乗り込む手間を払う区間が残っている: "
                        + result.steps().stream().filter(PathStep::boating)
                                .map(PathStep::cost).toList());
    }

    @Test
    void doesNotOfferABoatWithoutOneInTheInventory() {
        CellSource cells = strait(40).boatAvailable(false);

        PathResult result = search(cells, new BlockPos(0, 63, 0), new BlockPos(41, 63, 0));

        assertTrue(result.complete(), "ボートが無くても泳いで渡れる");
        assertTrue(result.steps().stream().noneMatch(PathStep::boating),
                "持っていないボートを出せとは言わない: " + movements(result));
    }

    /**
     * 水中の採掘は息をそのぶん使う。1マスに数十tickかかるので、息の残りを<b>マス数</b>で数えると
     * 40tickの採掘が「1マス」にしかならず、水中を掘り進む経路が上限をすり抜けていた。
     */
    @Test
    void countsUnderwaterDiggingAgainstTheBreathLimit() {
        // 水没した石の壁。掘り抜く以外に道が無い（上下は岩盤）
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 12, 76, 8));
        for (int x = -1; x <= 4; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 65, 0, FakeCells.BEDROCK);
            for (int y = 61; y <= 64; y++) {
                cells.set(x, y, 0, x == 2 ? FakeCells.STONE : FakeCells.WATER);
            }
        }

        // 上限8マス＝泳ぎ換算で約44tick。石1マスの採掘(40tick)を水中で5倍払う時点で超える
        PathResult limited = search(cells.maxSubmergedRunBlocks(8), new BlockPos(0, 61, 0),
                new BlockPos(3, 61, 0));

        assertFalse(limited.complete(),
                "息が続かないので水中の壁は掘り抜けない: " + movements(limited));
    }

    /** {@link #countsUnderwaterDiggingAgainstTheBreathLimit}が空振りしていないことの裏付け。 */
    @Test
    void diggingUnderwaterIsStillAllowedWithinTheBreathLimit() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 12, 76, 8));
        for (int x = -1; x <= 4; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 65, 0, FakeCells.BEDROCK);
            for (int y = 61; y <= 64; y++) {
                cells.set(x, y, 0, x == 2 ? FakeCells.STONE : FakeCells.WATER);
            }
        }

        PathResult unlimited = search(cells.maxSubmergedRunBlocks(0), new BlockPos(0, 61, 0),
                new BlockPos(3, 61, 0));

        assertTrue(unlimited.complete(), "上限が無ければ掘り抜ける");
        assertTrue(unlimited.steps().stream().anyMatch(PathStep::digging),
                "掘って抜ける経路になる: " + movements(unlimited));
    }

    /** 水底(y=54)から水面(y=70)まで開けた深い海。x方向に長く、途中に遮るものは無い。 */
    private static FakeCells openSea(int length) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 44, -8, length + 12, 86, 8));
        for (int x = -1; x <= length + 1; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.set(x, 54, z, FakeCells.BEDROCK);
                for (int y = 55; y <= 70; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }
        return cells;
    }

    /**
     * 深い海に出たら、潜ったまま横断せず<b>まず水面へ上がる</b>。息を減らしながら進むのは水平移動なので、
     * 先に解消してから渡る方が安全で、水面ならボートも使える。
     */
    @Test
    void surfacesBeforeCrossingOpenWater() {
        CellSource cells = openSea(60);

        PathResult result = search(cells, new BlockPos(0, 55, 0), new BlockPos(58, 70, 0));

        assertTrue(result.complete());
        int surfacedAt = -1;
        for (int i = 0; i < result.steps().size(); i++) {
            if (result.steps().get(i).pos().getY() == 70) {
                surfacedAt = i;
                break;
            }
        }
        assertTrue(surfacedAt >= 0, "水面に出る: " + result.steps().stream().map(PathStep::pos).toList());
        int horizontalBeforeSurfacing = Math.abs(result.steps().get(surfacedAt).pos().getX());
        assertTrue(horizontalBeforeSurfacing <= 4,
                "横断を始める前に浮上する（水面に出るまでの水平移動）: " + horizontalBeforeSurfacing);
    }

    /**
     * 水面へ出られない場所では形が変わらない。水没した洞窟や天井のある水路では割増が一様に
     * 乗るだけで、潜ったまま進む以外の選択肢がそもそも無い。
     */
    @Test
    void stillSwimsThroughAFloodedTunnelWithNoSurfaceAbove() {
        // y=61〜62 だけが水で、y=63 が岩盤の天井。浮上できない水没坑道
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 24, 76, 8));
        for (int x = -1; x <= 13; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 61, 0, FakeCells.WATER);
            cells.set(x, 62, 0, FakeCells.WATER);
            cells.set(x, 63, 0, FakeCells.BEDROCK);
        }

        PathResult result = search(cells.maxSubmergedRunBlocks(0), new BlockPos(0, 61, 0),
                new BlockPos(12, 61, 0));

        assertTrue(result.complete(), "浮上できなくても水没坑道は通れる");
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() <= 62),
                "天井があるので高さは変わらない: " + result.steps().stream().map(PathStep::pos).toList());
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

    /**
     * ネザーのしだれツタ・ねじれツタは体が通り抜けられるが、バニラでは<b>replaceableではない</b>ので
     * ブロックを置けない（狙っても隣のセルへ飛ぶ）。当たり判定の有無だけで設置可能と判断してはいけない。
     */
    @Test
    void neverPlacesABlockWhereVanillaWouldRefuseIt() {
        FakeCells cells = chasm(2).canPlaceBlocks(true).jumpGapEnabled(false);
        // 隙間の床の高さをしだれツタで埋める。体は通り抜けられるが、そこへ足場は置けない
        cells.set(2, 60, 0, FakeCells.NETHER_VINE);
        cells.set(3, 60, 0, FakeCells.NETHER_VINE);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertTrue(result.steps().stream()
                        .map(PathStep::placedBlockPos)
                        .filter(pos -> pos != null)
                        .noneMatch(pos -> pos.getY() == 60 && pos.getX() >= 2 && pos.getX() < 4),
                "置けないしだれツタの位置へ足場を置いている: " + result.steps());
    }

    /**
     * 梯子・ツタに掴まっている間は{@code onGround()}がfalseで{@code jumpFromGround()}が呼ばれない。
     * 掴まったまま接地していても{@code handleOnClimbable}が水平速度を±0.15に固定する。
     */
    @Test
    void doesNotJumpWhileHangingOnAClimbable() {
        FakeCells cells = chasm(2).jumpGapEnabled(true).canPlaceBlocks(false);
        for (int z = -1; z <= 1; z++) {
            cells.set(1, 61, z, FakeCells.LADDER);
        }

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "梯子に掴まったままでは跳べない: " + movements(result));
    }

    /**
     * 助走が要る。疾走の最高速度は静止から約5tick（≒1マス）かけて乗り、滞空中はほとんど加速
     * できないので、到達距離は踏み切り速度でそのまま決まる。1マス幅の足場からは自分のマスの中しか
     * 助走できない。
     */
    @Test
    void doesNotJumpFromAOneBlockPerchWithNoRunUp() {
        FakeCells cells = chasm(2).jumpGapEnabled(true).canPlaceBlocks(false);
        // 踏み切り(x=1)の手前を塞いで、助走できない1マス幅の足場にする
        for (int z = -1; z <= 1; z++) {
            cells.set(0, 61, z, FakeCells.BEDROCK);
        }

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(movements(result).contains(MovementType.JUMP),
                "助走できない足場から跳ばせてはいけない: " + movements(result));
    }

    /**
     * ソウルサンドの平地の脇に、同じソウルサンドの1マスの尾根を置く。上がっても地面は同じで
     * 何一つ速くならないので、上下動は純粋な損。
     */
    private static FakeCells soulSandFlatWithRidge(char ridgeTop) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 16, 76, 8));
        for (int x = -1; x <= 6; x++) {
            cells.set(x, 60, 0, FakeCells.SOUL_SAND);
            cells.set(x, 60, 1, FakeCells.SOUL_SAND);
            cells.set(x, 61, 1, ridgeTop);
        }
        return cells;
    }

    /**
     * 速度倍率は水平移動にしか掛かっていなかったので、ソウルサンドの上では「1マス登る」(4.633)が
     * 「1マス歩く」(8.909)より安く、鋸歯状に登り降りするのが最安経路になっていた。
     */
    @Test
    void crossesSoulSandFlatInsteadOfHoppingOntoTheRidgeBeside() {
        CellSource cells = soulSandFlatWithRidge(FakeCells.SOUL_SAND);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() == 61),
                "同じソウルサンドなら尾根へ登る意味は無い: " + movements(result));
    }

    /**
     * 「上下動を一律に嫌う」実装にしてはいけない。尾根の上が本当に速い地面なら、登る価値はある。
     */
    @Test
    void stillClimbsOntoARidgeThatIsGenuinelyFaster() {
        CellSource cells = soulSandFlatWithRidge(FakeCells.STONE);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().anyMatch(step -> step.pos().getY() > 61),
                "石の尾根は本当に2.5倍速いので登るべき: " + movements(result));
    }

    /** 障害物の無い平坦な通路。ゴールの扱い（座標一致か領域か）だけを見るための地形。 */
    private static FakeCells flatCorridor() {
        return FakeCells.of(0, 60, 0, """
                ..........
                ..........
                ##########""")
                .extrudeZ(-1, 1);
    }

    /**
     * 中間目標は「通る場所」ではなく「向かう方角」でしかないので、そこへ座標ぴったり寄せるために
     * 遠回りしてはいけない。ゴールを半径付きの領域にすると、触れた時点で終われる。
     */
    @Test
    void aRadiusGoalStopsAsSoonAsTheRegionIsTouched() {
        FakeCells cells = flatCorridor();
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(9, 61, 0);

        PathResult exact = new AStarPathfinder(cells).search(start, goal, NOT_CANCELLED);
        PathResult region = new AStarPathfinder(cells).search(start, goal, NOT_CANCELLED, 4);

        assertTrue(exact.complete() && region.complete());
        assertTrue(region.steps().size() < exact.steps().size(),
                "半径ぶん手前で終われるはず: " + region.steps().size() + " vs " + exact.steps().size());
        assertEquals(5, region.steps().size(), "半径4なら x=5 で領域に触れる");
    }

    /** 半径0（本来の目的地）は従来どおり座標の完全一致。 */
    @Test
    void aZeroRadiusGoalStillRequiresAnExactMatch() {
        PathResult result = new AStarPathfinder(flatCorridor())
                .search(new BlockPos(0, 61, 0), new BlockPos(9, 61, 0), NOT_CANCELLED, 0);

        assertTrue(result.complete());
        assertEquals(new BlockPos(9, 61, 0), last(result).pos());
    }

    /**
     * 中間目標が壁の中のような到達不能な点でも、領域なら近くを通り抜けるだけで済む。
     * 層1はチャンク平均しか見ないので、waypointが到達不能な点に落ちること自体は避けられない。
     */
    @Test
    void aRadiusGoalSucceedsEvenWhenItsCentreIsUnreachable() {
        FakeCells cells = flatCorridor();
        // 目標の座標そのものを岩盤で埋める。座標一致のゴールでは永久に到達しない
        BlockPos unreachable = new BlockPos(5, 61, 0);
        for (int z = -1; z <= 1; z++) {
            cells.set(5, 61, z, FakeCells.BEDROCK);
            cells.set(5, 62, z, FakeCells.BEDROCK);
        }

        PathResult exact = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), unreachable, NOT_CANCELLED, 0);
        PathResult region = new AStarPathfinder(cells)
                .search(new BlockPos(0, 61, 0), unreachable, NOT_CANCELLED, 4);

        assertFalse(exact.complete(), "座標一致では岩盤の中には入れない");
        assertTrue(region.complete(), "領域なら手前で触れて済む");
    }

    /**
     * 幅{@code width}の溶岩の水路。両岸は岩盤で、渡るには溶岩へ足場を置き続けるしかない。
     * {@link #lavaPond}を任意の幅にした版で、橋の連続長の上限を試すために使う。
     */
    private static FakeCells lavaChannel(int width) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 40, -8, width + 12, 80, 8))
                .fillWith(FakeCells.BEDROCK)
                .canPlaceBlocks(true);
        for (int x = -1; x <= width + 1; x++) {
            cells.set(x, 60, 0, x >= 1 && x <= width ? FakeCells.LAVA : FakeCells.BEDROCK);
            cells.set(x, 61, 0, FakeCells.AIR);
            cells.set(x, 62, 0, FakeCells.AIR);
        }
        return cells;
    }

    /**
     * 上限は「コストを重くする」のではなく「移動そのものを作らない」で効かせている。重みで
     * 抑えるとA*は安い辺から展開するので、橋に手を伸ばす前に周囲を展開し尽くして予算を焼く。
     */
    @Test
    void refusesToBridgeBeyondTheConfiguredRun() {
        CellSource cells = lavaChannel(12).maxBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete(), "上限を超える橋しか無いなら渡らない");
        assertTrue(result.steps().stream().filter(PathStep::bridging).count() <= 6,
                "上限を超えて橋を伸ばしてはいけない: " + movements(result));
    }

    @Test
    void stillBridgesWhenTheRunStaysUnderTheCap() {
        CellSource cells = lavaChannel(4).maxBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        assertTrue(result.complete(), "上限内の橋は今までどおり渡れる");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging), "" + movements(result));
    }

    /** 上限0は無制限。設定で切ったときに従来どおりの挙動へ戻ることの確認。 */
    @Test
    void aZeroCapMeansNoLimit() {
        CellSource cells = lavaChannel(12).maxBridgeRunBlocks(0);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertTrue(result.complete(), "上限0なら長さに関わらず渡る");
    }

    /** 上限で移動を捨てたかどうかは、上限を外して探し直す価値があるかの判定に使う。 */
    @Test
    void reportsWhetherTheCapActuallyBlockedAnything() {
        AStarPathfinder blocked = new AStarPathfinder(lavaChannel(12).maxBridgeRunBlocks(6));
        blocked.search(new BlockPos(0, 61, 0), new BlockPos(13, 61, 0), NOT_CANCELLED);
        assertTrue(blocked.bridgeRunCapBlocked());

        AStarPathfinder untouched = new AStarPathfinder(lavaChannel(4).maxBridgeRunBlocks(6));
        untouched.search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);
        assertFalse(untouched.bridgeRunCapBlocked());
    }

    /**
     * 割れ目の底が見えない空洞で、遥か下（20マス）に溶岩がある。足元1マス下は空気なので、
     * 隣接判定（{@code hasAdjacentLava}）だけでは溶岩に気付かない——{@code addBridge}の
     * lavaFarBelow判定が無いと「危険なし」として溶岩橋切り禁止をすり抜けてしまう。
     */
    private static FakeCells voidWithLavaFarBelow(int gapBlocks) {
        FakeCells cells = chasm(gapBlocks);
        for (int x = 2; x < 2 + gapBlocks; x++) {
            cells.set(x, 40, 0, FakeCells.LAVA);
        }
        return cells;
    }

    @Test
    void doesNotBridgeOverAVoidWithLavaFarBelowWhenLavaBridgingDisabled() {
        CellSource cells = voidWithLavaFarBelow(2)
                .jumpGapEnabled(false)
                .canPlaceBlocks(true)
                .lavaBridgingEnabled(false);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertFalse(result.complete(), "遥か下が溶岩でも、溶岩橋を切っている以上渡ってはいけない");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "足元が空気に見えるだけで、遥か下の溶岩を見逃して橋を架けてはいけない: " + result.steps());
    }

    @Test
    void prefersADryDetourOverBridgingAVoidWithLavaFarBelow() {
        // 遥か下が溶岩の割れ目と同じ地形に、z=1側だけ素の地面の迂回路を彫る
        FakeCells cells = voidWithLavaFarBelow(2);
        for (int x = 0; x <= 5; x++) {
            cells.set(x, 60, 1, FakeCells.STONE);
            cells.set(x, 61, 1, FakeCells.AIR);
            cells.set(x, 62, 1, FakeCells.AIR);
        }

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(4, 61, 0));

        assertTrue(result.complete(), "迂回路があるので到達できる");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "遥か下が溶岩と気付かず、迂回できるのに橋を架けた: " + movements(result));
    }

    /**
     * 水面より高い岩盤の断崖に面した縦穴。水面(y=63)からは縁(y=67)へ登れないので、
     * 上に出る手段は「水中から積み上げる」しか無い。壁を水面より高くしてあるのが要点で、
     * 同じ高さだと泳ぎ上がって縁へ{@code Ascend}できてしまい、積むかどうかを問えない。
     */
    private static FakeCells floodedShaft() {
        return FakeCells.of(0, 60, 0, """
                ......
                ......
                .BBB..
                .BBB..
                .BBB..
                ~BBB..
                ~BBB..
                ~BBB..
                BBBBBB""")
                .fillWith(FakeCells.BEDROCK);
    }

    @Test
    void doesNotPillarWhileFloatingInWater() {
        CellSource cells = floodedShaft().canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 67, 0));

        assertFalse(result.complete(), "水中から積み上げられない以上、断崖の上には出られない");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "水中から積み上げる案内はしない: " + result.steps());
    }

    /**
     * {@link #doesNotPillarWhileFloatingInWater}が空振りしていないことの裏付け。同じ地形の
     * 縦穴から水を抜けば、そこは積んで登れる＝登れない理由が水であることが確かめられる。
     */
    @Test
    void pillarsUpTheSameShaftWhenItIsNotFlooded() {
        CellSource cells = floodedShaft()
                .set(0, 61, 0, FakeCells.AIR)
                .set(0, 62, 0, FakeCells.AIR)
                .set(0, 63, 0, FakeCells.AIR)
                .canPlaceBlocks(true);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(1, 67, 0));

        assertTrue(result.complete(), "水が無ければ積んで登れる");
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging),
                "積んで登る区間が出る: " + movements(result));
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

    /**
     * {@link PathResult#termination()}が打ち切り理由を正しく区別すること。展開数上限で切ったときと、
     * openが尽きるまで探索し切って範囲内に道が無かったときとでは、呼び出し側の再挑戦の要否が
     * まったく違う——両方を区別せず「未到達」だけで扱っていた頃は、詰みに対して延々と
     * 無意味な再挑戦を仕掛け続けるバグがあった。
     */
    @Test
    void distinguishesNodeBudgetFromAnExhaustedSearchSpace() {
        // 岩盤の箱に閉じ込められた1マスの空間。四方・天井・床すべて掘れない岩盤なので、
        // 始点を展開しても後継が1つも生成されず、1回展開しただけでopenが尽きる
        SearchBounds sealedBounds = new SearchBounds(-8, 55, -8, 8, 70, 8);
        CellSource sealed = FakeCells.empty(sealedBounds).fillWith(FakeCells.BEDROCK)
                .set(0, 61, 0, FakeCells.AIR)
                .set(0, 62, 0, FakeCells.AIR);
        PathResult exhausted = new AStarPathfinder(sealed)
                .search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);

        assertFalse(exhausted.complete());
        assertEquals(PathResult.Termination.EXHAUSTED, exhausted.termination());
        assertFalse(exhausted.budgetExhausted(), "openが尽きたのは予算切れではなく詰み");

        // 同じ地形でも、展開数の上限を1に絞れば始点を展開する前に上限へ当たる
        CellSource sameTerrain = FakeCells.empty(sealedBounds).fillWith(FakeCells.BEDROCK)
                .set(0, 61, 0, FakeCells.AIR)
                .set(0, 62, 0, FakeCells.AIR);
        SearchLimits tinyBudget = new SearchLimits(0, 2000, 1.5);
        PathResult budgetHit = new AStarPathfinder(sameTerrain, tinyBudget)
                .search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);

        assertFalse(budgetHit.complete());
        assertEquals(PathResult.Termination.NODE_BUDGET, budgetHit.termination());
        assertTrue(budgetHit.budgetExhausted(), "ノード上限に当たったのは予算切れ扱いにする");
    }

    /**
     * {@link CostToGo}を注入する3引数コンストラクタの配線確認（段階4）。{@code null}を渡すと
     * 2引数コンストラクタ（幾何学的な{@link Heuristic}のみ）と完全に同じ結果になる。
     */
    @Test
    void nullCostToGoBehavesExactlyLikeTheTwoArgumentConstructor() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ......
                ######""");

        PathResult withoutCostToGo = new AStarPathfinder(cells).search(new BlockPos(0, 61, 0),
                new BlockPos(5, 61, 0), NOT_CANCELLED);
        PathResult withNullCostToGo = new AStarPathfinder(cells, SearchLimits.DEFAULT, null)
                .search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);

        assertEquals(withoutCostToGo.expandedNodes(), withNullCostToGo.expandedNodes());
        assertEquals(movements(withoutCostToGo), movements(withNullCostToGo));
    }

    /**
     * 注入した{@link CostToGo}が実際に{@code node()}から呼ばれていること（配線の生きた確認）。
     * 呼ばれた回数だけを見るので、値そのものの妥当性には依存しない——ここで確かめたいのは
     * 「注入したインスタンスが探索の経路上に乗っているか」であって、ヒューリスティックとしての
     * 良し悪しは{@link net.prason.xaeronav.pathfinding.coarse.CoarseRouterTest}や
     * {@code PathfindingExecutorCoarseGuidedTest}が別に確認する。
     */
    @Test
    void injectedCostToGoIsActuallyConsultedDuringSearch() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ......
                ......
                ######""");
        int[] callCount = {0};
        CostToGo counting = (x, y, z) -> {
            callCount[0]++;
            return 0.0;
        };

        PathResult result = new AStarPathfinder(cells, SearchLimits.DEFAULT, counting)
                .search(new BlockPos(0, 61, 0), new BlockPos(5, 61, 0), NOT_CANCELLED);

        assertTrue(result.complete());
        assertTrue(callCount[0] > 0, "注入したCostToGoが一度も呼ばれていない＝配線が繋がっていない");
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
