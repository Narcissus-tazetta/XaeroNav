package net.prason.xaeronav.pathfinding.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.prason.xaeronav.pathfinding.cost.ElytraPhysics.Velocity;

/**
 * バニラの漸化式から出た滑空ポーラを固定する。
 *
 * <p>ここが動いたらコストモデルの傾き（登りと水平の釣り合い）が変わったということなので、
 * 経路の見た目も必ず変わる。値そのものを覚えておくのが目的で、変えるなとは言っていない。
 */
class FlightCostsTest {

    private static final double TOLERANCE = 1.0e-3;

    @Test
    void bestGlideMatchesTheVanillaRecurrence() {
        Velocity glide = ElytraPhysics.steadyState(0.0, false);

        // 30.2ブロック/秒・滑空比10.1。どちらもコミュニティで知られた値と一致する
        assertEquals(1.5102, glide.horizontal(), TOLERANCE);
        assertEquals(-0.1495, glide.vertical(), TOLERANCE);
        assertEquals(10.10, glide.glideRatio(), 0.01);
    }

    @Test
    void divingIsFasterHorizontallyButBurnsAltitude() {
        Velocity fastest = ElytraPhysics.bestSteadyState(0.0, 80.0, 0.5, false, Velocity::horizontal);

        assertTrue(fastest.horizontal() > 3.3, "最速の水平巡航が出ていない: " + fastest);
        assertTrue(fastest.glideRatio() < ElytraPhysics.steadyState(0.0, false).glideRatio(),
                "最速で飛ぶ姿勢の滑空比が最良滑空を上回っている: " + fastest);
    }

    @Test
    void glidingCannotClimbInSteadyState() {
        // ロケット無しでは、どの姿勢でも定常状態の垂直成分は負。「水平飛行はすでに登り」の根拠
        for (double pitch = -90.0; pitch <= 80.0; pitch += 5.0) {
            Velocity steady = ElytraPhysics.steadyState(pitch, false);
            assertTrue(steady.vertical() < 0.0,
                    "ピッチ" + pitch + "で高度を保てることになっている: " + steady);
        }
    }

    @Test
    void terminalDiveMatchesVanillaFallPhysics() {
        // 真下を向いた滑空は揚力が0になるので、ただの落下と同じ終端速度3.92に落ち着く
        assertEquals(-3.92, ElytraPhysics.steadyState(90.0, false).vertical(), TOLERANCE);
    }

    @Test
    void rocketsMakeClimbingMuchCheaper() {
        assertTrue(FlightCosts.ROCKET_ASCENT_TICKS_PER_BLOCK * 3.0 < FlightCosts.GLIDING_ASCENT_TICKS_PER_BLOCK,
                "ロケットの有無で上昇コストが3倍も違わない: ロケット有"
                        + FlightCosts.ROCKET_ASCENT_TICKS_PER_BLOCK + " / 無"
                        + FlightCosts.GLIDING_ASCENT_TICKS_PER_BLOCK);
    }

    @Test
    void derivedConstantsAreStable() {
        assertEquals(0.6622, FlightCosts.HORIZONTAL_TICKS_PER_BLOCK, TOLERANCE);
        assertEquals(10.10, FlightCosts.GLIDE_RATIO, 0.01);
        assertEquals(0.2551, FlightCosts.DESCENT_TICKS_PER_BLOCK, TOLERANCE);
        assertEquals(0.6373, FlightCosts.ROCKET_ASCENT_TICKS_PER_BLOCK, TOLERANCE);
        assertEquals(2.2365, FlightCosts.GLIDING_ASCENT_TICKS_PER_BLOCK, 0.01);
    }

    @Test
    void glidingDownTheNaturalSlopeIsFreeButLevelFlightIsNot() {
        double distance = 100.0;
        double naturalDrop = -distance / FlightCosts.GLIDE_RATIO;

        double gliding = FlightCosts.segmentTicks(distance, naturalDrop, false);
        double level = FlightCosts.segmentTicks(distance, 0.0, false);

        assertTrue(level > gliding * 1.2,
                "水平飛行が自然な滑空とほぼ同じ値段になっている: 水平" + level + " / 滑空" + gliding);
    }

    @Test
    void divingSteeperThanTheGlideSlopeIsNotRewarded() {
        double distance = 100.0;
        double natural = FlightCosts.segmentTicks(distance, -distance / FlightCosts.GLIDE_RATIO, false);
        double steep = FlightCosts.segmentTicks(distance, -50.0, false);

        assertTrue(steep > natural, "高度を捨てる急降下が最良滑空より安くなっている");
    }

    @Test
    void heuristicNeverExceedsTheSegmentCost() {
        // A*の許容性。滑空の割引を区間コスト側にだけ置いてあることの確認
        for (double horizontal = 0.0; horizontal <= 200.0; horizontal += 7.0) {
            for (double vertical = -100.0; vertical <= 100.0; vertical += 7.0) {
                for (boolean rockets : new boolean[] {false, true}) {
                    double estimate = FlightCosts.heuristicTicks(horizontal, vertical, rockets);
                    double actual = FlightCosts.segmentTicks(horizontal, vertical, rockets);
                    assertTrue(estimate <= actual + 1.0e-9,
                            "見積もりが区間コストを上回った: 水平" + horizontal + " 垂直" + vertical
                                    + " ロケット" + rockets + " → 見積" + estimate + " / 実" + actual);
                }
            }
        }
    }
}
