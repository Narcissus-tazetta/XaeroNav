package net.prason.xaeronav.client;

import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * ワールド内描画・Xaeroマップ描画の両方で使う経路の色分けルール。design doc §2-5参照。
 *
 * <p>色は共有の定数配列として返す。ワールド内描画・世界地図・ミニマップの3経路がそれぞれ
 * 毎フレーム全ステップぶん{@link #forStep}を呼ぶため、ここで配列を作ると1フレームあたり
 * 数千個のゴミになる。返された配列は書き換えないこと。
 */
public final class PathColors {

    public static final float[] ELYTRA = {0.9f, 0.95f, 1.0f};
    /** 経路が分からない区間を繋ぐ直線。実際に辿れる経路と取り違えないよう、彩度を落とした色にする。 */
    public static final float[] STRAIGHT = {0.85f, 0.85f, 0.9f};
    public static final float[] BRIDGE = {0.4f, 0.9f, 0.9f};
    public static final float[] LAVA_ADJACENT = {1.0f, 0.1f, 0.1f};
    public static final float[] VOID_BELOW = {0.8f, 0.1f, 0.8f};
    public static final float[] WATER_INFLOW = {0.1f, 0.7f, 1.0f};
    /** 息継ぎできない潜水区間。同じ水色でも{@link #SWIM}とは明確に違う暗さにする。 */
    public static final float[] DROWNING = {0.1f, 0.15f, 0.55f};
    public static final float[] DIGGING = {1.0f, 0.55f, 0.1f};
    public static final float[] SWIM = {0.1f, 0.4f, 1.0f};
    public static final float[] JUMP = {0.95f, 0.6f, 0.9f};
    public static final float[] CLIMB = {0.7f, 0.5f, 1.0f};
    public static final float[] ASCEND = {1.0f, 0.9f, 0.2f};
    public static final float[] DESCEND = {0.3f, 0.6f, 1.0f};
    public static final float[] WALK = {0.2f, 0.9f, 0.5f};

    private PathColors() {
    }

    public static float[] forStep(PathStep step) {
        if (step.risk() == PathRisk.LAVA_ADJACENT) {
            return LAVA_ADJACENT;
        }
        if (step.risk() == PathRisk.VOID_BELOW) {
            return VOID_BELOW;
        }
        if (step.risk() == PathRisk.WATER_INFLOW) {
            return WATER_INFLOW;
        }
        if (step.risk() == PathRisk.DROWNING) {
            return DROWNING;
        }
        if (step.bridging()) {
            return BRIDGE;
        }
        if (step.digging()) {
            return DIGGING;
        }
        if (step.movement() == MovementType.SWIM) {
            return SWIM;
        }
        if (step.movement() == MovementType.JUMP) {
            return JUMP;
        }
        if (step.movement() == MovementType.CLIMB) {
            return CLIMB;
        }
        if (step.movement() == MovementType.ASCEND) {
            return ASCEND;
        }
        if (step.movement() == MovementType.DESCEND) {
            return DESCEND;
        }
        return WALK;
    }
}
