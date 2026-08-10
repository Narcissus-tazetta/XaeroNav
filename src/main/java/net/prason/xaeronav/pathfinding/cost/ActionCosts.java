package net.prason.xaeronav.pathfinding.cost;

/**
 * 移動コストの基準値（単位: tick）。design doc §3-1/§4-1参照。
 * 数値はBaritone(ActionCosts.java, LGPL)で使われている実測値と同一だが、
 * ここではアイデア・数値のみを参考にし、コード自体は独自実装している。
 */
public final class ActionCosts {

    public static final double WALK_ONE_BLOCK = 20.0 / 4.317;
    public static final double SPRINT_ONE_BLOCK = 20.0 / 5.612;
    public static final double WALK_ONE_IN_WATER = 20.0 / 2.2;

    private static final double WALK_OFF_BLOCK = WALK_ONE_BLOCK * 0.8;
    private static final double CENTER_AFTER_FALL = WALK_ONE_BLOCK - WALK_OFF_BLOCK;

    /**
     * ジャンプで1マス登るのに要するtick数。放物線の対称性から
     * 「1.25マス分落下する時間」と「最後の0.25マス分落下する時間」の差として導出する
     * （踏み切ってから頂点=1.25マスに達するまでの上昇時間 = 対称な下降時間という関係を使う）。
     */
    public static final double JUMP_ONE_BLOCK = FallPhysics.ticksToFall(1.25) - FallPhysics.ticksToFall(0.25);

    public static final double ASCEND_ONE_BLOCK = Math.max(JUMP_ONE_BLOCK, WALK_ONE_BLOCK);

    public static final double DESCEND_ONE_BLOCK = WALK_OFF_BLOCK + Math.max(FallPhysics.ticksToFall(1), CENTER_AFTER_FALL);

    /**
     * 大きく落下する場合、tick/マスはterminal velocity(3.92 blocks/tick)に漸近しこれを下回らない。
     * A*ヒューリスティックの下降成分に使う安全な下限値（design doc §4-2参照）。
     */
    public static final double FALL_ASYMPTOTIC_MIN_PER_BLOCK = 1.0 / 3.92;

    public static final double DIG_OVERHEAD_TICKS = 2.0;

    public static final double INFEASIBLE = Double.POSITIVE_INFINITY;

    private ActionCosts() {
    }
}
