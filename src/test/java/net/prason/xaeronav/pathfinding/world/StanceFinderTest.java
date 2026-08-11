package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * 探索の始点・終点の寄せ直し。
 *
 * <p>A*が作る移動はすべて「足場のあるセル・水・梯子」で終わるので、そうでない座標を渡すと
 * 経路が1本も伸びない。始点（落下中のプレイヤー）も終点（地図のクリック座標）も探索の外から
 * 降ってくる値で、そのままでは成立しないことが珍しくない。
 */
class StanceFinderTest {

    @Test
    void startFallingThroughTheAirDropsToTheLandingSpot() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                ...
                ###""");

        // 空中に浮いた始点。このあと着地する場所から先の経路が出てほしい
        BlockPos resolved = StanceFinder.resolveStart(cells, new BlockPos(1, 63, 0));

        assertEquals(new BlockPos(1, 61, 0), resolved, "床の上まで下ろす");
    }

    @Test
    void startAlreadyStandingIsLeftAlone() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                ###""");

        assertEquals(new BlockPos(1, 61, 0), StanceFinder.resolveStart(cells, new BlockPos(1, 61, 0)));
    }

    @Test
    void startEmbeddedInTheFloorIsLiftedOneBlock() {
        // 半ブロックの中・地面にめり込んだ位置。真下に下ろすのではなく1マス上を見る
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                ###
                ###""");

        assertEquals(new BlockPos(1, 62, 0), StanceFinder.resolveStart(cells, new BlockPos(1, 61, 0)));
    }

    @Test
    void goalBuriedInDiggableGroundIsKeptAsIs() {
        // 地中の目的地。掘れば辿り着けるので寄せない — そこまでの坑道を出すのが正しい
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ###
                ###
                ###""");

        assertEquals(new BlockPos(1, 61, 0), StanceFinder.resolveGoal(cells, new BlockPos(1, 61, 0)),
                "掘って到達できる座標は動かさない");
    }

    @Test
    void goalFloatingInTheAirIsPulledDownToTheGround() {
        // 地図のクリックやウェイポイントは空中を指すことがある。到達不能として扱うと、
        // 目の前まで来ているのに「経路なし」になってしまう
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                ...
                ###""");

        assertEquals(new BlockPos(1, 61, 0), StanceFinder.resolveGoal(cells, new BlockPos(1, 63, 0)),
                "足場のある高さまで寄せる");
    }

    @Test
    void goalInsideUndiggableRockIsPulledToTheNearestReachableCell() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                ...
                BBB
                BBB""");

        // (1,61) は岩盤の中で、掘っても辿り着けない。上の空間へ寄せる
        assertEquals(new BlockPos(1, 62, 0), StanceFinder.resolveGoal(cells, new BlockPos(1, 61, 0)));
    }

    @Test
    void waterCountsAsAStanceEvenWithoutAFloor() {
        CellSource cells = FakeCells.of(0, 60, 0, """
                ...
                .~.
                .~.
                ###""");

        // 水中は足場が無くても立てる（泳ぐ）扱い。ここを外すと海の上の目的地が全部寄ってしまう
        assertEquals(new BlockPos(1, 61, 0), StanceFinder.resolveGoal(cells, new BlockPos(1, 61, 0)));
    }
}
