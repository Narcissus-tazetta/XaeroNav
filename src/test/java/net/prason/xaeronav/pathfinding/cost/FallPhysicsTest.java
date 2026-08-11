package net.prason.xaeronav.pathfinding.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link FallPhysics}が実装しているバニラの落下式（{@code velocity = (velocity - 0.08) * 0.98}）を
 * 正しく積分できているかの検証。ここが狂うと{@link ActionCosts}経由でジャンプ・落下・
 * ヒューリスティックのコストが軒並みずれるので、他のどのクラスよりコストを払う価値がある。
 */
class FallPhysicsTest {

    @Test
    void zeroDistanceTakesNoTicks() {
        assertEquals(0.0, FallPhysics.ticksToFall(0.0));
        assertEquals(0.0, FallPhysics.ticksToFall(-1.0));
    }

    @Test
    void longerFallsTakeMoreTicks() {
        double previous = 0.0;
        for (double distance = 1.0; distance <= 200.0; distance += 1.0) {
            double ticks = FallPhysics.ticksToFall(distance);
            assertTrue(ticks > previous, "distance=" + distance + "では前回より短くなってはいけない");
            previous = ticks;
        }
    }

    /**
     * 十分長く落ちると、1マスあたりの所要tickは終端速度3.92 blocks/tickの逆数に収束する。
     * ここが外れると、深い縦穴の落下コストが実際のバニラ挙動より甘く/厳しく見積もられる。
     */
    @Test
    void approachesTerminalVelocityOverLongFalls() {
        double perBlockNear = (FallPhysics.ticksToFall(1000.0) - FallPhysics.ticksToFall(999.0));
        assertEquals(1.0 / 3.92, perBlockNear, 0.01);
    }
}
