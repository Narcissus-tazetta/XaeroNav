package net.prason.xaeronav.pathfinding.cost;

import java.util.function.ToDoubleFunction;

/**
 * エリトラ滑空の物理を、バニラ{@code LivingEntity#travel}の fall-flying 分岐そのままの漸化式で回す。
 *
 * <p>滑空の速度・沈下率・滑空比は「およそ30ブロック/秒」「滑空比10:1」といった数字が知られているが、
 * <b>ここでは一切書き写さない</b>。バニラの式を回して出た値だけを{@link FlightCosts}へ渡す。
 * 揚力・推力・操舵・減衰が互いに掛かり合う式なので、途中の項を1つ落としただけで数％ずれ、
 * それがコストモデル全体の傾き（登りと水平の釣り合い）を静かに歪める。
 *
 * <p>ヨーは常に0として、前進成分{@code horizontal}（+Z方向）と垂直成分{@code vertical}の2次元で回す。
 * ヨーは進行方向を回すだけで速度の大きさに影響しないので、滑空ポーラを求めるのに3次元は要らない。
 */
public final class ElytraPhysics {

    /**
     * 重力。{@code Attributes.GRAVITY}の{@code RangedAttribute}既定値そのもの。
     * バニラの式では{@code d0}として現れる。
     */
    private static final double GRAVITY = 0.08;

    /** 収束を見るtick数。時定数は減衰0.98/0.99から数十tickなので、これで十分に落ち着く。 */
    private static final int STEADY_STATE_TICKS = 4000;

    /** 一撃上昇を追う長さ。頂点は100tick以内に来るので、これを超えて見ても頂点は動かない。 */
    private static final int ZOOM_CLIMB_TICKS = 200;

    private ElytraPhysics() {
    }

    /** 速度（blocks/tick）。垂直は上が正。 */
    public record Velocity(double horizontal, double vertical) {

        static final Velocity ZERO = new Velocity(0.0, 0.0);

        /** 水平に何ブロック進む間に1ブロック沈むか。沈んでいなければ{@link Double#NaN}。 */
        public double glideRatio() {
            return vertical < 0.0 ? horizontal / -vertical : Double.NaN;
        }
    }

    /**
     * 機首上げ1回で稼げる高度と、頂点までのtick数。
     *
     * @param blocks 高度の増分（機首を上げた地点を0とした最高到達点）
     * @param ticks  頂点に達するまでのtick数
     */
    public record ZoomClimb(double blocks, int ticks) {

        /** 1ブロック稼ぐのに要したtick数。 */
        public double ticksPerBlock() {
            return blocks > 0.0 ? ticks / blocks : Double.POSITIVE_INFINITY;
        }
    }

    /**
     * 1tick進める。{@code pitchRadians}は<b>下向きが正</b>（バニラの{@code xRot}と同じ向き）。
     *
     * <p>バニラの式との対応: {@code lookY = vec31.y}、{@code lookZ = vec31.z}、{@code horizontalLook}は
     * ルックの水平成分（{@code d1}）、{@code entrySpeed}はこのtickに入る時点の水平速度（{@code d3}）、
     * {@code lift}は迎え角による揚力係数（{@code d5}）。
     */
    public static Velocity step(Velocity velocity, double pitchRadians, boolean rocket) {
        double horizontal = velocity.horizontal();
        double vertical = velocity.vertical();
        double lookY = -Math.sin(pitchRadians);
        double lookZ = Math.cos(pitchRadians);
        double horizontalLook = Math.abs(lookZ);
        double entrySpeed = Math.abs(horizontal);

        // cos^2(pitch) * min(1, |look|/0.4)。ルックは単位ベクトルなので後者は常に1
        double lift = lookZ * lookZ * Math.min(1.0, 1.0 / 0.4);
        vertical += GRAVITY * (-1.0 + lift * 0.75);

        if (vertical < 0.0 && horizontalLook > 0.0) {
            // 沈下の一部を揚力と推力へ変える。滑空が滑空である理由がここ1箇所に集約されている
            double recovered = vertical * -0.1 * lift;
            horizontal += lookZ * recovered / horizontalLook;
            vertical += recovered;
        }
        if (pitchRadians < 0.0 && horizontalLook > 0.0) {
            // 機首上げ。水平速度を高度へ替える（垂直へは3.2倍で入る）
            double zoom = entrySpeed * -Math.sin(pitchRadians) * 0.04;
            horizontal += -lookZ * zoom / horizontalLook;
            vertical += zoom * 3.2;
        }
        if (horizontalLook > 0.0) {
            // 水平速度の向きをルックへ寄せる（大きさは変えない）
            horizontal += (lookZ / horizontalLook * entrySpeed - horizontal) * 0.1;
        }
        if (rocket) {
            // FireworkRocketEntity#tick: 装着中の滑空者の速度をlook*1.5へ0.5ずつ寄せ、さらにlook*0.1を足す
            horizontal += lookZ * 0.1 + (lookZ * 1.5 - horizontal) * 0.5;
            vertical += lookY * 0.1 + (lookY * 1.5 - vertical) * 0.5;
        }
        return new Velocity(horizontal * 0.99, vertical * 0.98);
    }

    /** 静止からその姿勢を保ち続けたときの定常速度。 */
    public static Velocity steadyState(double pitchDegrees, boolean rocket) {
        double pitch = Math.toRadians(pitchDegrees);
        Velocity velocity = Velocity.ZERO;
        for (int tick = 0; tick < STEADY_STATE_TICKS; tick++) {
            velocity = step(velocity, pitch, rocket);
        }
        return velocity;
    }

    /**
     * ピッチを{@code fromDegrees}から{@code toDegrees}まで{@code stepDegrees}刻みで振り、
     * {@code score}が最大になった姿勢の定常速度を返す。「最速の水平巡航」「最良の滑空比」
     * 「最大の上昇率」はどれも同じ掃引に別の評価関数を当てただけなので1つにまとめてある。
     */
    public static Velocity bestSteadyState(double fromDegrees, double toDegrees, double stepDegrees,
                                            boolean rocket, ToDoubleFunction<Velocity> score) {
        Velocity best = Velocity.ZERO;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (double pitch = fromDegrees; pitch <= toDegrees + 1.0e-9; pitch += stepDegrees) {
            Velocity candidate = steadyState(pitch, rocket);
            double value = score.applyAsDouble(candidate);
            if (Double.isFinite(value) && value > bestScore) {
                bestScore = value;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * {@code entry}の速度から機首を{@code pitchDegrees}へ上げ、頂点まで登らせる。
     *
     * <p>ロケットが無いエリトラは<b>定常状態では登れない</b>（どのピッチでも定常の垂直成分は負）。
     * 高度を得る唯一の手段が、溜めた水平速度を一度きり高度へ替えるこの動きになる。
     */
    public static ZoomClimb zoomClimb(Velocity entry, double pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        Velocity velocity = entry;
        double height = 0.0;
        double peak = 0.0;
        int peakTick = 0;
        for (int tick = 1; tick <= ZOOM_CLIMB_TICKS; tick++) {
            velocity = step(velocity, pitch, false);
            height += velocity.vertical();
            if (height > peak) {
                peak = height;
                peakTick = tick;
            }
        }
        return new ZoomClimb(peak, peakTick);
    }

    /** {@code entry}から最も安く（1ブロックあたりのtickが最小で）高度を稼げる機首上げ。 */
    public static ZoomClimb bestZoomClimb(Velocity entry, double fromDegrees, double toDegrees,
                                           double stepDegrees) {
        ZoomClimb best = new ZoomClimb(0.0, 0);
        for (double pitch = fromDegrees; pitch >= toDegrees - 1.0e-9; pitch -= stepDegrees) {
            ZoomClimb candidate = zoomClimb(entry, pitch);
            if (candidate.ticksPerBlock() < best.ticksPerBlock()) {
                best = candidate;
            }
        }
        return best;
    }
}
