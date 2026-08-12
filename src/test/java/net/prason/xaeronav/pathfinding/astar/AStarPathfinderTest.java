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
