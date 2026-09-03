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

    /**
     * 経路中で1手にいちばん大きく下がった段数。{@link ActionCosts#SAFE_FALL_BLOCKS}を超えていれば
     * その落下でダメージを受けている——{@code PathStep}は{@code MoveKind}を持たない
     * （{@code MovementType}まで畳まれている）ので、種類ではなく<b>案内が実際に何マス落とすか</b>で見る。
     */
    private static int biggestDrop(BlockPos start, PathResult result) {
        int biggest = 0;
        BlockPos previous = start;
        for (PathStep step : result.steps()) {
            biggest = Math.max(biggest, previous.getY() - step.pos().getY());
            previous = step.pos();
        }
        return biggest;
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
        assertFalse(result.steps().isEmpty(), "届く範囲までは案内する（暫定経路）");
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
        CellSource cells = floodedTunnel().maxSubmergedTicks(22);

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
        CellSource cells = floodedTunnel().maxSubmergedTicks(0);

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

        // 上限45tick＝泳ぎ8マス相当。石1マスの採掘(40tick)を水中で5倍払う時点で超える
        PathResult limited = search(cells.maxSubmergedTicks(45), new BlockPos(0, 61, 0),
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

        PathResult unlimited = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0),
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
        // 水底(55)から水面(70)まで15マス。1手ごとに1マス上がるので、無駄なく上がれば15手で着く
        assertTrue(surfacedAt <= 16, "寄り道せずに浮上する（浮上までの手数）: " + (surfacedAt + 1));
        // その間ずっと目的地の方へ進んでいる＝真上に上がってから横へ、のL字にならない
        int advancedWhileRising = result.steps().get(surfacedAt).pos().getX();
        assertTrue(advancedWhileRising >= 10,
                "目的地へ向かいながら斜めに上がる（浮上までに進んだ水平距離）: " + advancedWhileRising);
    }

    /**
     * 水面へ向かうとき、XとZの両方へ進みながら上がれる。
     *
     * <p>浮上がカーディナル4方向しか無かった頃は「真っ直ぐ進んでから上がる」か「上がってから
     * 斜めに進む」に分解され、水面へ向かう区間だけ経路が直角に折れていた（実機報告
     * 「水面で斜めっていう選択肢が入っていない」）。
     */
    @Test
    void risesDiagonallyTowardsASurfaceGoalOffTheAxis() {
        // 十分に広い水塊。目的地は斜め上（XもZもZ方向も動かす必要がある）
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 30, -8, 40, 86, 40));
        for (int x = -1; x <= 30; x++) {
            for (int z = -1; z <= 30; z++) {
                cells.set(x, 40, z, FakeCells.BEDROCK);
                for (int y = 41; y <= 55; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 41, 0),
                new BlockPos(20, 52, 20));

        assertTrue(result.complete());
        List<PathStep> steps = result.steps();
        boolean roseDiagonally = false;
        for (int i = 1; i < steps.size(); i++) {
            BlockPos previous = steps.get(i - 1).pos();
            BlockPos current = steps.get(i).pos();
            if (current.getY() > previous.getY()
                    && current.getX() != previous.getX() && current.getZ() != previous.getZ()) {
                roseDiagonally = true;
                break;
            }
        }
        assertTrue(roseDiagonally,
                "浮上がカーディナル4方向に縛られ、上がる区間だけ直角に折れている: "
                        + steps.stream().map(PathStep::pos).toList());
    }

    /**
     * 上と同じ「跳ねて割増を回避する」の<b>斜め版</b>。既存の番人は幅1の一本道なので斜めの手が
     * そもそも生成されず、斜め浮上を足したときの跳ねを検出できない。開けた水中で見る。
     */
    @Test
    void doesNotBobDiagonallyToDodgeTheSubmergedPenalty() {
        // 天井と床のある水没した部屋。<b>目的地は真っ直ぐではなく斜め</b>——跳ねが得になるのは
        // 「正直に進んでも斜めの値段を払う」区間だけで、カーディナルに進める区間では
        // 斜め跳ね(√3+√2·P)より素直な水平2手(2·P)の方が元から安く、番人にならない
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 24, 76, 24));
        for (int x = -1; x <= 13; x++) {
            for (int z = -1; z <= 13; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
                for (int y = 61; y <= 64; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
                cells.set(x, 65, z, FakeCells.BEDROCK);
            }
        }

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0),
                new BlockPos(12, 61, 12));

        assertTrue(result.complete());
        List<PathStep> steps = result.steps();
        int climbs = 0;
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i).pos().getY() > steps.get(i - 1).pos().getY()) {
                climbs++;
            }
        }
        assertEquals(0, climbs,
                "水面へ出るためでもないのに浮上している＝割増を跳ねて回避している: "
                        + steps.stream().map(PathStep::pos).toList());
    }

    /**
     * 浮上の割増免除を悪用して上下に跳ねない。斜め浮上だけが割増の対象外なので、値を大きくしすぎると
     * 「斜めに上がって斜めに降りる」を繰り返すのが水平移動より安くなり、水中で延々と波打つ経路になる。
     * {@code SUBMERGED_TRAVEL_PENALTY}の上限はここから決まっている。
     */
    @Test
    void doesNotBobUpAndDownToDodgeTheSubmergedPenalty() {
        // 天井のある水没した一本道。カーディナルにしか進めないので、上下に跳ねる以外の抜け道が無い
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 52, -8, 24, 76, 8));
        for (int x = -1; x <= 13; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            for (int y = 61; y <= 64; y++) {
                cells.set(x, y, 0, FakeCells.WATER);
            }
            cells.set(x, 65, 0, FakeCells.BEDROCK);
        }

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0),
                new BlockPos(12, 61, 0));

        assertTrue(result.complete());
        int climbs = 0;
        List<PathStep> steps = result.steps();
        for (int i = 1; i < steps.size(); i++) {
            if (steps.get(i).pos().getY() > steps.get(i - 1).pos().getY()) {
                climbs++;
            }
        }
        assertTrue(climbs <= 1, "水中で上下に波打っている: "
                + steps.stream().map(step -> step.pos().getY()).toList());
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

        PathResult result = search(cells.maxSubmergedTicks(0), new BlockPos(0, 61, 0),
                new BlockPos(12, 61, 0));

        assertTrue(result.complete(), "浮上できなくても水没坑道は通れる");
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() <= 62),
                "天井があるので高さは変わらない: " + result.steps().stream().map(PathStep::pos).toList());
    }

    /** 水底40、水41〜70、その上は空の深い海。 */
    private static FakeCells deepSea(int length) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 30, -8, length + 12, 86, 8));
        for (int x = -1; x <= length + 1; x++) {
            for (int z = -1; z <= 1; z++) {
                cells.set(x, 40, z, FakeCells.BEDROCK);
                for (int y = 41; y <= 70; y++) {
                    cells.set(x, y, z, FakeCells.WATER);
                }
            }
        }
        return cells;
    }

    /**
     * 遠い水中の目的地へは、息継ぎに水面へ出てから向かう。
     *
     * <p>息の上限を<b>マス数</b>で持っていた頃はここが到達不能だった——水底から水面へ浮上する
     * だけで上限を超えるので、息継ぎに行くことすらできず経路が途中で切れていた。上限がtickに
     * なったことで、浮上・横断・潜降がそれぞれ空気1回分に収まるか正しく測れる。
     */
    @Test
    void surfacesToBreatheOnTheWayToADistantUnderwaterGoal() {
        CellSource cells = deepSea(64).maxSubmergedTicks(250);

        PathResult result = search(cells, new BlockPos(0, 45, 0), new BlockPos(60, 45, 0));

        assertTrue(result.complete(), "息継ぎを挟めば深い水中の目的地にも届く: " + result.termination());
        assertTrue(result.steps().stream().anyMatch(step -> step.pos().getY() == 70),
                "途中で水面まで出る: " + result.steps().stream().mapToInt(step -> step.pos().getY()).max());
    }

    /**
     * 息が続く範囲の水中の目的地へは、わざわざ水面へ寄らずまっすぐ向かう。安全のための
     * 割増（{@code SUBMERGED_TRAVEL_PENALTY}）が、息に余裕のある近距離まで遠回りにしないこと。
     */
    @Test
    void goesStraightToANearbyUnderwaterGoal() {
        CellSource cells = deepSea(16).maxSubmergedTicks(250);

        PathResult result = search(cells, new BlockPos(0, 45, 0), new BlockPos(12, 45, 0));

        assertTrue(result.complete());
        assertTrue(result.steps().stream().allMatch(step -> step.pos().getY() == 45),
                "息が続くなら浮上せず直行する: " + result.steps().stream().map(PathStep::pos).toList());
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

    /**
     * 溶岩の上だけを別の上限で切れる。空洞に架ける橋は外しても落ちるだけだが、溶岩の上では
     * 即死するので、同じ長さでも許してよい範囲が違う。
     */
    @Test
    void refusesToBridgeOverLavaBeyondTheLavaRunEvenWhenTheGeneralCapIsOff() {
        CellSource cells = lavaChannel(12).maxBridgeRunBlocks(0).maxLavaBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete(), "溶岩側の上限を超える橋しか無いなら渡らない");
    }

    /** 溶岩側の上限は溶岩の上でだけ効く。空洞に架ける橋は今までどおりmaxBridgeRunBlocksが見る。 */
    @Test
    void theLavaRunCapLeavesBridgesOverEmptySpaceAlone() {
        CellSource cells = chasm(6).jumpGapEnabled(false).canPlaceBlocks(true)
                .maxBridgeRunBlocks(0).maxLavaBridgeRunBlocks(2);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(8, 61, 0));

        assertTrue(result.complete(), "溶岩の無い割れ目は溶岩側の上限に縛られない: " + movements(result));
    }

    /** 溶岩の上では両方の上限が掛かる。厳しい方が勝つ。 */
    @Test
    void theStricterOfTheTwoCapsWinsOverLava() {
        CellSource cells = lavaChannel(12).maxBridgeRunBlocks(6).maxLavaBridgeRunBlocks(0);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete(), "溶岩側が無制限でも、橋そのものの上限は残る");
    }

    /**
     * 段差の下に低い棚があるだけの地形。落差{@code drop}は落下ダメージ許容が無ければ渡れない。
     * ジ・エンドで「低い島へ降りる」形を、奈落抜きで最小化したもの。
     */
    private static FakeCells ledgeBelow(int drop) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-4, 20, -4, 12, 90, 4))
                .canPlaceBlocks(false);
        for (int x = -2; x <= 1; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
            cells.set(x, 61, 0, FakeCells.AIR);
            cells.set(x, 62, 0, FakeCells.AIR);
        }
        for (int x = 2; x <= 8; x++) {
            cells.set(x, 60 - drop, 0, FakeCells.BEDROCK);
            cells.set(x, 61 - drop, 0, FakeCells.AIR);
            cells.set(x, 62 - drop, 0, FakeCells.AIR);
        }
        return cells.extrudeZ(-1, 1);
    }

    /**
     * 落下ダメージの許容量<b>だけ</b>が足りずに着地を捨てたことを報告する。詰み時に許容量を
     * 段階的に緩める探し直し（{@code PathfindingExecutor}）の発動条件になる。
     *
     * <p>奈落や未ロードで捨てた場合に立ててはいけない——そちらは緩めても着地点が現れない。
     */
    @Test
    void reportsWhenOnlyTheFallDamageAllowanceBlockedALanding() {
        AStarPathfinder blocked = new AStarPathfinder(ledgeBelow(8).maxFallDamagePoints(0));
        PathResult result = blocked.search(new BlockPos(0, 61, 0), new BlockPos(6, 53, 0), NOT_CANCELLED);
        assertFalse(result.complete(), "許容0なら8マスの落下は提示しない");
        assertTrue(blocked.fallDamageCapBlocked(), "床は読めていて落差だけが問題なので、緩める価値がある");

        AStarPathfinder allowed = new AStarPathfinder(ledgeBelow(8).maxFallDamagePoints(8));
        assertTrue(allowed.search(new BlockPos(0, 61, 0), new BlockPos(6, 53, 0), NOT_CANCELLED).complete(),
                "許容を開ければ同じ地形で降りられる");
    }

    /**
     * 底が無い（奈落）場合は、落下ダメージをいくら緩めても着地点が現れない。ここでフラグを立てると
     * 緩和の梯子を最後まで空回りさせることになる。
     */
    @Test
    void doesNotBlameTheFallAllowanceForABottomlessDrop() {
        AStarPathfinder pathfinder =
                new AStarPathfinder(bottomlessGap(6).canPlaceBlocks(false).maxFallDamagePoints(0));
        pathfinder.search(new BlockPos(0, 61, 0), new BlockPos(9, 61, 0), NOT_CANCELLED);
        assertFalse(pathfinder.fallDamageCapBlocked(), "奈落は許容量の問題ではない");
    }

    /**
     * {@link Tolerances}で許容量を上書きすると、{@link CellSource#maxFallDamagePoints()}が0でも
     * その落下が生成される。詰み時の緩和はこの経路で効く。
     */
    @Test
    void tolerancesOverrideTheViewsFallAllowance() {
        FakeCells cells = ledgeBelow(8).maxFallDamagePoints(0);
        AStarPathfinder loosened = new AStarPathfinder(cells, SearchLimits.DEFAULT, null,
                new Tolerances(RunCaps.of(cells), 8, true, cells.placedBlockBudget(), false));

        assertTrue(loosened.search(new BlockPos(0, 61, 0), new BlockPos(6, 53, 0), NOT_CANCELLED).complete(),
                "許容量を上書きしても落下が生成されないなら、緩和の梯子は空回りする");
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
     * 1マスの割れ目に低い天井を張ったもの。<b>渡る高さを y=61（設置先は y=60）に固定する</b>ため。
     * 天井が無いと「柱を1マス積んで1段高い所を渡る」経路が出て、ツタから離れた別のセルへ
     * 足場を置いてしまい、ツタの判定を問えなくなる。
     */
    private static FakeCells vinedChasm() {
        FakeCells cells = chasm(1).jumpGapEnabled(false).canPlaceBlocks(true);
        for (int x = 0; x <= 4; x++) {
            cells.set(x, 63, 0, FakeCells.BEDROCK);
        }
        return cells;
    }

    /**
     * ツタは{@code replaceable}なので「置ける」判定は通るが、狙うと視線がツタに当たり、
     * ブロックはツタのセルへ入ってしまう。案内した位置には置かれない。
     */
    @Test
    void doesNotBridgeIntoVines() {
        // 割れ目を1マスにして設置先を(2,60,0)の1つに絞る。ツタを足元より上に置くと、
        // 橋の代わりにツタを伝って渡る経路（addClimb）が出て、何を測ったのか分からなくなる
        FakeCells cells = vinedChasm();
        cells.set(2, 60, 0, FakeCells.VINE);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(result.complete(), "ツタのセルを足場にして渡ってはいけない");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "ツタのセルを設置先に選んではいけない: " + movements(result));
    }

    /** ツタの隣も同じ。1マス離れていても、置く先を狙う視線はツタを通る。 */
    @Test
    void doesNotBridgeNextToVines() {
        FakeCells cells = vinedChasm();
        cells.set(2, 59, 0, FakeCells.VINE);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertFalse(result.complete(), "ツタに接する場所へ足場を置いて渡ってはいけない");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "ツタに隣接するセルを設置先に選んではいけない: " + movements(result));
    }

    /** ツタが無ければ従来どおり架かる。上の2件が「橋そのものを消した」だけでないことの確認。 */
    @Test
    void stillBridgesTheSameGapWithoutVines() {
        CellSource cells = vinedChasm();

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(3, 61, 0));

        assertTrue(result.complete(), "ツタが無ければ渡れる: " + movements(result));
        assertTrue(result.steps().stream().anyMatch(PathStep::bridging), "" + movements(result));
    }

    /**
     * 底の無い割れ目（ジ・エンドの島間）。{@code fillWith}を呼ばないので書かれていない座標は空気のまま
     * ——探索範囲の下端まで空気が続き、その先は範囲外になる。{@code ChunkView}は範囲外も未ロードも同じ
     * {@code ABSENT}で返すので、区別しなければ「下に何があるか読めない」と誤読される地形そのもの。
     */
    private static FakeCells bottomlessGap(int gapBlocks) {
        // z方向は1列だけ。横へ回り込んで奈落を避ける経路が出ると、橋そのものを問えなくなる
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 28, 0, gapBlocks + 12, 93, 0))
                .canPlaceBlocks(true);
        for (int x = -1; x <= gapBlocks + 2; x++) {
            if (x < 1 || x > gapBlocks) {
                cells.set(x, 60, 0, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /**
     * ジ・エンドの島渡りそのもの。出発の島 → 奈落{@code gapBlocks}マス → {@code dropBlocks}だけ
     * 低い到着の島。橋は水平にしか架けられないので、到着の島へは<b>落ちる</b>しかない。
     */
    private static FakeCells islandsAcrossVoid(int gapBlocks, int dropBlocks) {
        int landingY = 60 - dropBlocks;
        // 下端は着地の島より十分下に取る。ここが着地の島と同じだと、奈落の走査が範囲外で止まって
        // NOTHING_BELOWではなくなり、何を測っているのか分からなくなる
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, landingY - 40, 0, gapBlocks + 12, 93, 0))
                .canPlaceBlocks(true);
        for (int x = -1; x <= 0; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        for (int x = gapBlocks + 1; x <= gapBlocks + 4; x++) {
            cells.set(x, landingY, 0, FakeCells.BEDROCK);
        }
        return cells;
    }

    /**
     * <b>ユーザーが実際に困っていた形の通し検証。</b>「エリトラを持たない人が、奈落を挟んだ
     * 低い島へ徒歩で渡れるか」。
     *
     * <p>下向きの橋はサバイバルでは作れない（虚空側にクリックする面が無い）ので、渡る手順は
     * 「水平に橋を架けて奈落を越え、縁から落ちて着地する」しかない。落下ダメージの許容量が
     * 足りないうちは<b>経路そのものが存在しない</b>——これが実機で hop2 が全条件で失敗していた
     * 構造的な理由で、詰み時に許容量を緩める梯子はここを開けるために入れた。
     */
    @Test
    void walksAcrossVoidAndDropsOntoALowerIsland() {
        int drop = 8;
        FakeCells terrain = islandsAcrossVoid(6, drop);
        BlockPos start = new BlockPos(0, 61, 0);
        BlockPos goal = new BlockPos(8, 61 - drop, 0);

        AStarPathfinder strict = new AStarPathfinder(islandsAcrossVoid(6, drop).maxFallDamagePoints(0));
        PathResult blocked = strict.search(start, goal, NOT_CANCELLED);
        assertFalse(blocked.complete(), "許容0で8マス落ちる経路が出てはいけない: " + movements(blocked));
        assertTrue(strict.fallDamageCapBlocked(), "緩める価値があることを報告しないと梯子が動かない");

        // 詰み時の緩和が渡すのと同じ形で許容量を開ける
        PathResult opened = new AStarPathfinder(terrain, SearchLimits.DEFAULT, null,
                new Tolerances(RunCaps.of(terrain), drop - ActionCosts.SAFE_FALL_BLOCKS, true,
                        terrain.placedBlockBudget(), false))
                .search(start, goal, NOT_CANCELLED);

        assertTrue(opened.complete(), "許容量を開ければ渡れるはず: " + movements(opened));
        assertTrue(opened.steps().stream().anyMatch(PathStep::bridging),
                "奈落は橋で越える: " + movements(opened));
        assertEquals(drop, biggestDrop(start, opened),
                "低い島へは1手で落ちて降りる（痛い落下が経路に乗っている）: " + movements(opened));
    }

    /**
     * 同じ高さの島なら落下ダメージは要らない。<b>層1が回り込みで狙わせる先がこれ</b>——
     * 「遠回りして同じYの島へ」が成立するのは、着いた先が既定の設定のまま渡れるから。
     */
    @Test
    void reachesASameHeightIslandWithoutAnyFallDamage() {
        FakeCells cells = islandsAcrossVoid(6, 0).maxFallDamagePoints(0);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(8, 61, 0));

        assertTrue(result.complete(), "同じ高さの島へは既定の設定で渡れる: " + movements(result));
        assertTrue(biggestDrop(new BlockPos(0, 61, 0), result) <= ActionCosts.SAFE_FALL_BLOCKS,
                "同じ高さなのに痛い落下が混ざっている: " + movements(result));
    }

    /**
     * <b>柱にも連続長の上限を掛ける。</b>{@code addPillar}は{@code bridgeRun}を増やすのに上限を
     * 検査していなかったので、塔が探索範囲の天井まで伸び放題だった。
     *
     * <p>これが実機（the_end、2026-08-27）で効いていた: 島の立てるセルすべてから約150段の塔が
     * 展開対象になり、<b>51万セル</b>を焼いて{@code NODE_BUDGET}で終わっていた。
     * <b>本当の害はノード数ではなく、そのせいで{@code EXHAUSTED}に到達できないこと</b>——
     * 橋の上限を緩める梯子（{@code PathfindingExecutor}）は「範囲内に道が無いと証明できた」ときにしか
     * 走らないので、予算切れで終わる限り<b>一度も発動しない</b>。エンドで橋が上限30に張り付いたまま
     * 渡り切れなかったのはこれ。
     */
    @Test
    void pillarsRespectTheRunCap() {
        // 1マスの足場だけがある空中。塔を伸ばす以外にできることが無いので、上限がそのまま高さになる
        FakeCells cells = FakeCells.empty(new SearchBounds(-4, 20, -4, 4, 200, 4))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(8)
                .set(0, 60, 0, FakeCells.BEDROCK);

        AStarPathfinder pathfinder = new AStarPathfinder(cells);
        PathResult result = pathfinder.search(new BlockPos(0, 61, 0), new BlockPos(0, 190, 0), NOT_CANCELLED);

        assertFalse(result.complete(), "上限8で129マスの塔が建ってはいけない");
        assertTrue(pathfinder.bridgeRunCapBlocked(), "上限で捨てたことを報告しないと緩和の梯子が走らない");
        assertEquals(PathResult.Termination.EXHAUSTED, result.termination(),
                "上限が効いていれば探索は尽きる。予算切れで終わると詰み検知も緩和も動かない: "
                        + result.expandedNodes() + "ノード");
    }

    /**
     * <b>奈落・溶岩の上では、掘らないと通れない場所へ橋を架けない。</b>
     *
     * <p>1手の中に「床を置く」と「身体のセルを掘る」が同居すると、案内は<b>順序を表現できない</b>。
     * 実機（the_end、2026-08-27）でユーザーが踏んだのがこれで、症状は2つに見えていた——
     * 「掘るはずのブロックの横にブロックを置けと言われる」（置く枠が掘る枠の真下に出る）と
     * 「そのまま掘ったら奈落にダイブする」（見えている掘る枠を先に掘ると、足元が奈落の上の空気になる）。
     *
     * <p>正しい順序は「先に床を置く→後で掘る」だが、掘る枠が見えている以上そちらを先にやるのが自然で、
     * 外したときに死ぬ。空中では掘れないので{@link #addJumpGap}や斜め移動が
     * {@code clearWithoutDigging}を要求しているのと同じ規律を、橋にも掛ける。
     *
     * <p>底のある空洞では掛けない。掘って落ちても1マス下の床に着くだけで、結末がまるで違う。
     */
    @Test
    void doesNotBridgeIntoACellThatNeedsDiggingOverVoid() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 20, 0, 12, 90, 0))
                .canPlaceBlocks(true);
        cells.set(-1, 60, 0, FakeCells.BEDROCK).set(0, 60, 0, FakeCells.BEDROCK);
        for (int x = 4; x <= 8; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        // 奈落の上に張り出した岩。跨いで越える回避路は天井で消してあるので、掘り抜く以外に手が無い
        cells.set(2, 61, 0, FakeCells.STONE).set(2, 62, 0, FakeCells.STONE);
        for (int x = -1; x <= 8; x++) {
            cells.set(x, 63, 0, FakeCells.BEDROCK);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(5, 61, 0));

        for (PathStep step : result.steps()) {
            assertTrue(step.placedBlockPos() == null || step.digCells().isEmpty(),
                    "1手で置くと掘るが同居している（順序を表現できないので奈落へ落ちる）: "
                            + step.placedBlockPos() + " / " + step.digCells());
        }
    }

    /**
     * 奈落の上でも橋は架かる。読めるセルだけを辿って底に当たらなかったのは「分からない」ではなく
     * 「本当に底が無い」と分かったということで、渡ってよいかの判断はコストと上限が受け持つ。
     */
    @Test
    void bridgesOverABottomlessGap() {
        CellSource cells = bottomlessGap(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(7, 61, 0));

        assertTrue(result.complete(), "奈落の上にも橋は架けられる: " + movements(result));
        assertEquals(6, result.steps().stream().filter(PathStep::bridging).count(),
                "割れ目のマス数ぶんの足場を置いて渡る: " + movements(result));
    }

    /** 未ロードチャンクで走査が止まった列は「奈落」ではない。下が水かもしれない以上、置いてはいけない。 */
    @Test
    void doesNotBridgeWhenTheColumnBelowIsUnreadable() {
        FakeCells cells = bottomlessGap(6);
        for (int y = 28; y <= 59; y++) {
            cells.set(1, y, 0, FakeCells.ABSENT);
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(7, 61, 0));

        assertFalse(result.complete(), "下が読めない列へは足場を置けない");
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "読めない列を奈落と取り違えて橋を架けた: " + movements(result));
    }

    /**
     * 横に架けた橋の上からは積み始めない。1マス幅の足場の上で跳んで足元に置く動作で、
     * 奈落の上ではまず外す。
     *
     * <p>地形は「出発点の頭上を塞いだ足場 → 奈落 → 4マス高い目的地」。塔を立てられるのは
     * 橋の上だけなので、そこを塞げば届かない。出発点で先に積んでから高い所を渡る抜け道は
     * 天井で潰してある。
     */
    @Test
    void doesNotStartAPillarFromABridge() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 28, 0, 20, 93, 0))
                .canPlaceBlocks(true)
                .set(0, 60, 0, FakeCells.BEDROCK)
                .set(0, 63, 0, FakeCells.BEDROCK)
                .set(6, 64, 0, FakeCells.BEDROCK);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(6, 65, 0));

        assertFalse(result.complete(), "橋の上で塔を立てて登ってはいけない: " + movements(result));
    }

    /**
     * 奈落の上では目標へ近づく向きにしか橋を伸ばさない。これが無いと、岸のあらゆるセルから
     * 全方位へ上限いっぱいの橋が展開対象になり、既定の予算では広い割れ目を渡り切れない
     * （実測: この地形で10万ノードを焼いて予算切れ → 約1.4万ノードで到達）。
     */
    @Test
    void crossesAWideVoidGapWithinTheDefaultBudget() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-40, 28, -40, 120, 93, 40))
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(60).maxLavaBridgeRunBlocks(60).maxVoidBridgeRunBlocks(60);
        for (int z = -4; z <= 4; z++) {
            for (int x = -4; x <= 0; x++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
            for (int x = 61; x <= 66; x++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(63, 61, 0));

        assertTrue(result.complete(),
                "60マスの奈落を既定の予算で渡り切れない（" + result.termination()
                        + "、展開ノード " + result.expandedNodes() + "）");
    }

    /** 奈落の上だけを別の上限で切れる。溶岩側の上限と同じ考え方。 */
    @Test
    void refusesToBridgeOverAVoidBeyondTheVoidRun() {
        CellSource cells = bottomlessGap(12).maxVoidBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete(), "奈落側の上限を超える橋しか無いなら渡らない");
    }

    /** 奈落側の上限は奈落の上でだけ効く。底のある割れ目は今までどおりmaxBridgeRunBlocksが見る。 */
    @Test
    void theVoidRunCapLeavesBridgesOverFlooredGapsAlone() {
        CellSource cells = chasm(6).jumpGapEnabled(false).canPlaceBlocks(true)
                .maxBridgeRunBlocks(0).maxVoidBridgeRunBlocks(2);

        PathResult result = search(cells, new BlockPos(1, 61, 0), new BlockPos(8, 61, 0));

        assertTrue(result.complete(), "底のある割れ目は奈落側の上限に縛られない: " + movements(result));
    }

    /**
     * 幅12の溶岩の水路。手前(x≦5)だけ低い天井が張り出していて、そこでは柱を立てられない。
     *
     * <p>天井が要るのは、上限を柱で迂回する経路を<b>1本に絞る</b>ため。頭上が全面的に開けていると、
     * 「初手で柱を立ててから高い側を渡る」という同コストの経路が別に生まれ、そちらは連続長を
     * 積んだまま到達する——{@code bridgeRun}はノードの同一性に入らないので、同コストなら
     * どちらの連続長が残るかは展開順しだいになり、上限の抜け穴を問うテストにならない。
     */
    private static FakeCells lavaChannelWithLowCeiling(int width) {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 40, -8, width + 12, 90, 8))
                .fillWith(FakeCells.BEDROCK)
                .canPlaceBlocks(true);
        for (int x = -1; x <= width + 1; x++) {
            cells.set(x, 60, 0, x >= 1 && x <= width ? FakeCells.LAVA : FakeCells.BEDROCK);
            // 天井は x≦5 で y=63。渡るのに要る2マス(y=61,62)は空いているが、柱を立てる余地は無い
            int ceiling = x <= 5 ? 62 : 78;
            for (int y = 61; y <= ceiling; y++) {
                cells.set(x, y, 0, FakeCells.AIR);
            }
        }
        return cells;
    }

    /**
     * 柱を立てても橋の連続長は数え直されない。柱は足場を要求しない（自分が直前に置いたブロックの上に
     * 立つ）ので、ここで0に戻していた頃は「上限まで架ける→1マス積む→また上限まで架ける」で
     * 上限を破れた。
     *
     * <p>連続長が数え直されないことより、{@code bridgeRunCapBlocked}が立つことの方が実害が大きい。
     * 柱で迂回できてしまうと上限が原因の詰みとして報告されず、{@code PathfindingExecutor}の
     * 上限緩和（×2→×4→無制限）が一度も走らないまま階段状の経路が確定する。
     */
    @Test
    void pillaringDoesNotResetTheBridgeRun() {
        FakeCells cells = lavaChannelWithLowCeiling(12).maxBridgeRunBlocks(6);
        AStarPathfinder pathfinder = new AStarPathfinder(cells);

        PathResult result = pathfinder.search(new BlockPos(0, 61, 0), new BlockPos(13, 61, 0), NOT_CANCELLED);

        assertFalse(result.complete(), "柱を挟んでも上限を超えて渡ってはいけない: " + movements(result));
        assertTrue(result.steps().stream().filter(PathStep::bridging).count() <= 6,
                "置いた足場の総数が上限を超えている＝柱で数え直されている: " + movements(result));
        assertTrue(pathfinder.bridgeRunCapBlocked(),
                "上限が原因の詰みとして報告されないと、上限を緩めた探し直しが走らない");
    }

    /**
     * 底のある小さな割れ目を渡り切った先が、渡れない奈落で行き止まりになっている地形。
     * 渡り終えた橋と、渡り切れない橋を1つの経路の中で区別できる。
     */
    private static FakeCells crossingThenDeadEnd() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-8, 28, 0, 40, 93, 0))
                .canPlaceBlocks(true)
                .jumpGapEnabled(false)
                .maxVoidBridgeRunBlocks(1);
        for (int x = 0; x <= 1; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        // x=2..3 は底のある割れ目。落ちて登り直すには深すぎるので、渡るなら橋しか無い
        for (int x = 2; x <= 3; x++) {
            cells.set(x, 50, 0, FakeCells.BEDROCK);
        }
        for (int x = 4; x <= 6; x++) {
            cells.set(x, 60, 0, FakeCells.BEDROCK);
        }
        // x>=7 は底の無い奈落。上限1では渡り切れない
        return cells;
    }

    /**
     * 打ち切られた経路から落とすのは<b>末尾の</b>設置区間だけ。渡り終えて向こう岸に立った橋は、
     * その先で経路が途切れていても案内として正しい。切りすぎると、渡れる割れ目の手前で
     * 毎回案内が止まることになる。
     */
    @Test
    void keepsBridgesThatWereAlreadyCrossed() {
        CellSource cells = crossingThenDeadEnd();

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(20, 61, 0));

        assertFalse(result.complete(), "奈落の先へは届かない");
        List<PathStep> steps = result.steps();
        assertTrue(steps.stream().anyMatch(PathStep::bridging),
                "渡り終えた橋まで消してはいけない: " + movements(result));
        assertFalse(steps.get(steps.size() - 1).bridging(),
                "渡り切れない橋の途中で経路を終わらせてはいけない: " + movements(result));
    }

    /**
     * 打ち切られた経路は、自分で置く足場の上では終わらせない。ゴールへ届かなかった経路は
     * 「そこまでは進める」という意味しか持たないが、末尾が橋の途中だと
     * <b>ブロックを消費して渡り切れるかも分からない行き止まりに立たされる</b>ことになる。
     * 渡る手段が橋しか無い場所では、案内できる経路が一本も残らないのが正しい。
     */
    @Test
    void doesNotEndAPartialPathOnBlocksThePlayerHasToPlace() {
        CellSource cells = bottomlessGap(12).maxVoidBridgeRunBlocks(6);

        PathResult result = search(cells, new BlockPos(0, 61, 0), new BlockPos(13, 61, 0));

        assertFalse(result.complete());
        assertTrue(result.steps().stream().noneMatch(PathStep::bridging),
                "渡り切れると証明できていない橋は案内に出さない: " + movements(result));
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
