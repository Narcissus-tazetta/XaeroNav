package net.prason.xaeronav.pathfinding.cost;

/**
 * Minecraftの落下物理（毎tick velocity = (velocity - 0.08) * 0.98、terminal velocity 3.92 blocks/tick）を
 * シミュレートして、Nブロック落下に要するtick数を求める。
 */
public final class FallPhysics {

    private static final double TERMINAL_VELOCITY = 3.92;

    private FallPhysics() {
    }

    private static double velocityAfterTicks(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -TERMINAL_VELOCITY;
    }

    /**
     * distanceブロック落下するのに要するtick数。最後のtickは着地までの端数を線形補間する
     * （Baritoneのdistance-to-ticks法と同じ考え方）。
     */
    public static double ticksToFall(double distance) {
        if (distance <= 0) {
            return 0.0;
        }
        double remaining = distance;
        int tick = 0;
        while (true) {
            double v = velocityAfterTicks(tick);
            if (remaining <= v) {
                return tick + remaining / v;
            }
            remaining -= v;
            tick++;
        }
    }
}
