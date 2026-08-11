package net.prason.xaeronav.pathfinding.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * {@link CellData}のビット詰め。
 *
 * <p>1セルを{@code long}1個に詰めているので、フィールドの位置がずれると「掘削コストが速度倍率として
 * 読まれる」といった、例外も出さずに経路だけが静かに壊れる事故になる。
 *
 * <p>{@code flagsOf(BlockState)}はMinecraftのレジストリ起動を要求するのでここでは触らない。
 * 検証するのは、そこから先の詰め方・取り出し方だけ。
 */
class CellDataPackingTest {

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 1.0, 2.0, 40.0, 123.5, 1000.0, 65535.0})
    void digTicksSurviveTheRoundTrip(double ticks) {
        long cell = CellData.withDigTicks(CellData.PRESENT, ticks);

        // 値はtick数（数十〜数千）なのでfloatの有効桁で足りる
        assertEquals(ticks, CellData.digTicks(cell), 1.0e-3);
        assertTrue(CellData.present(cell), "掘削コストを詰めてもフラグ側は壊れない");
    }

    @Test
    void infeasibleIsPreservedExactly() {
        // 掘れないセルは正の無限大で表す。floatとの往復で有限値に化けると、
        // 岩盤や掘削禁止ブロックを掘る経路が生まれてしまう
        long cell = CellData.withDigTicks(CellData.PRESENT, ActionCosts.INFEASIBLE);

        assertEquals(Double.POSITIVE_INFINITY, CellData.digTicks(cell));
        assertTrue(Double.isInfinite(CellData.digTicks(cell)));
    }

    @Test
    void flagsAndDigTicksDoNotOverlap() {
        // 全フラグを立てた状態でも掘削コストが読み出せる＝上位32bitと下位32bitが独立している
        long allFlags = CellData.PRESENT | CellData.PASSABLE_EMPTY | CellData.WATER | CellData.LAVA
                | CellData.STANDABLE | CellData.FALLING_BLOCK | CellData.UNRESOLVED_SHAPE
                | CellData.CLIMBABLE | CellData.OPENABLE | CellData.COBWEB | CellData.HAZARD;
        long cell = CellData.withDigTicks(allFlags, 40.0);

        assertEquals(40.0, CellData.digTicks(cell), 1.0e-3);
        assertTrue(CellData.present(cell));
        assertTrue(CellData.passableEmpty(cell));
        assertTrue(CellData.water(cell));
        assertTrue(CellData.lava(cell));
        assertTrue(CellData.standable(cell));
        assertTrue(CellData.fallingBlock(cell));
        assertTrue(CellData.unresolvedShape(cell));
        assertTrue(CellData.climbable(cell));
        assertTrue(CellData.openable(cell));
        assertTrue(CellData.cobweb(cell));
        assertTrue(CellData.hazard(cell));
    }

    @Test
    void absentCellAnswersNoToEveryQuestion() {
        // 探索範囲外・未ロードチャンクは「触れない・立てない・掘れない」＝経路が伸びない、
        // という安全側の扱いになっていなければならない
        long absent = CellData.ABSENT;

        assertFalse(CellData.present(absent));
        assertFalse(CellData.passableEmpty(absent));
        assertFalse(CellData.water(absent));
        assertFalse(CellData.lava(absent));
        assertFalse(CellData.standable(absent));
        assertFalse(CellData.climbable(absent));
        assertFalse(CellData.occupiableWithoutDigging(absent));
        assertFalse(CellData.openable(absent));
        assertFalse(CellData.hazard(absent));
        assertEquals(1.0, CellData.speedFactor(absent), "未設定は等速として読む");
    }

    @Test
    void occupiableCoversAirWaterAndClimbables() {
        assertTrue(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.PASSABLE_EMPTY));
        assertTrue(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.WATER));
        assertTrue(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.CLIMBABLE));
        // 固体は掘らないと体を置けない
        assertFalse(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.STANDABLE));
        // 閉じたドアは「開けて通る」ので占有可ではない（openableとして別に扱う）
        assertFalse(CellData.occupiableWithoutDigging(CellData.PRESENT | CellData.OPENABLE));
    }
}
