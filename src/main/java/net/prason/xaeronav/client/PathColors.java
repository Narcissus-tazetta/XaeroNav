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
final class PathColors {

    /** 経路が分からない区間を繋ぐ直線。実際に辿れる経路と取り違えないよう、彩度を落とした色にする。 */
    static final float[] STRAIGHT = {0.85f, 0.85f, 0.9f};
    /** 長距離ルートの粗い中間目標列。目的地までの直線（{@link #STRAIGHT}）と見分けが付くよう別の色調にする。 */
    static final float[] COARSE_ROUTE = {0.95f, 0.75f, 0.2f};
    static final float[] BRIDGE = {0.4f, 0.9f, 0.9f};
    static final float[] LAVA_ADJACENT = {1.0f, 0.1f, 0.1f};
    static final float[] VOID_BELOW = {0.8f, 0.1f, 0.8f};
    static final float[] WATER_INFLOW = {0.1f, 0.7f, 1.0f};
    /** 息継ぎできない潜水区間。同じ水色でも{@link #SWIM}とは明確に違う暗さにする。 */
    static final float[] DROWNING = {0.1f, 0.15f, 0.55f};
    /** 体力が減る降下。危険色の中では警告寄り（{@link #LAVA_ADJACENT}の赤ほど強くない）。 */
    static final float[] FALL_DAMAGE = {1.0f, 0.35f, 0.0f};
    /** 着地寸前の水バケツが要る降下。{@link #FALL_DAMAGE}と同系だが、水を使うことが分かる色にする。 */
    static final float[] MLG_REQUIRED = {0.0f, 0.85f, 0.8f};
    /** スニークで渡るマグマブロック。溶岩そのもの（{@link #LAVA_ADJACENT}）ほど強くない警告色。 */
    static final float[] SNEAK_OVER_MAGMA = {1.0f, 0.5f, 0.25f};
    static final float[] DIGGING = {1.0f, 0.55f, 0.1f};
    static final float[] SWIM = {0.1f, 0.4f, 1.0f};
    static final float[] JUMP = {0.95f, 0.6f, 0.9f};
    static final float[] CLIMB = {0.7f, 0.5f, 1.0f};
    static final float[] ASCEND = {1.0f, 0.9f, 0.2f};
    static final float[] DESCEND = {0.3f, 0.6f, 1.0f};
    static final float[] WALK = {0.2f, 0.9f, 0.5f};

    private PathColors() {
    }

    /**
     * 危険 → 作業（設置・掘削）→ 移動の種類、の順に見る。網羅switchにしてあるので、
     * {@link PathRisk}や{@link MovementType}に値が増えたときはここがコンパイルエラーになる
     * （if連鎖だった頃は、追加した種類が黙って{@link #WALK}色として描かれていた）。
     */
    static float[] forStep(PathStep step) {
        float[] risk = switch (step.risk()) {
            case LAVA_ADJACENT -> LAVA_ADJACENT;
            case VOID_BELOW -> VOID_BELOW;
            case WATER_INFLOW -> WATER_INFLOW;
            case DROWNING -> DROWNING;
            case FALL_DAMAGE -> FALL_DAMAGE;
            case MLG_REQUIRED -> MLG_REQUIRED;
            case SNEAK_OVER_MAGMA -> SNEAK_OVER_MAGMA;
            case NONE -> null;
        };
        if (risk != null) {
            return risk;
        }
        if (step.bridging()) {
            return BRIDGE;
        }
        if (step.digging()) {
            return DIGGING;
        }
        return switch (step.movement()) {
            case SWIM -> SWIM;
            case JUMP -> JUMP;
            case CLIMB -> CLIMB;
            case ASCEND -> ASCEND;
            case DESCEND -> DESCEND;
            // riskのswitchで必ず先に拾われるので、ここへは落ちてこない
            case FALL_DAMAGE -> FALL_DAMAGE;
            case FALL_MLG -> MLG_REQUIRED;
            case TRAVERSE -> WALK;
        };
    }
}
