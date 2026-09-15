package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 地上優先ナビに入るかの高さ判定（{@code PathfindingState#climbWorthwhile}）。
 *
 * <p>基準にするYが<b>設定の定数ではなくその場の地表</b>になったことを固定する(#45)。
 */
class SurfaceClimbDecisionTest {

    @Test
    void climbsOutOfADeepCaveUnderAMountain() {
        // 山の下の洞窟(y=70)。地表は130なので60ブロック地下だが、設定の既定(60)を基準にすると
        // 「もう地上の高さ」と判定され、中継区間に入らなかった
        assertTrue(PathfindingState.climbWorthwhile(70, 135, 130));
        assertFalse(PathfindingState.climbWorthwhile(70, 135, 60), "既定の定数を基準にすると見逃す");
    }

    @Test
    void staysUndergroundWhenTheGoalIsAlsoUnderground() {
        // 洞窟から洞窟へ。地表へ出てから潜り直す道理が無い
        assertFalse(PathfindingState.climbWorthwhile(70, 90, 130));
        assertFalse(PathfindingState.climbWorthwhile(30, 40, 64));
    }

    @Test
    void ignoresShallowDepthsWhereTheExitIsAlreadyNearby() {
        assertFalse(PathfindingState.climbWorthwhile(61, 70, 64), "地表のすぐ下は中継する価値が無い");
        assertTrue(PathfindingState.climbWorthwhile(59, 70, 64));
    }

    @Test
    void worksBelowSeaLevelWhereTheLocalSurfaceIsLow() {
        // 海底や谷底では地表そのものが既定の60を下回る
        assertTrue(PathfindingState.climbWorthwhile(20, 45, 45));
        assertFalse(PathfindingState.climbWorthwhile(43, 45, 45));
    }
}
