package net.prason.xaeronav.pathfinding.cost;

import net.prason.xaeronav.pathfinding.world.MountState;

/**
 * 乗り物の跳躍で届く高さと距離。{@code LivingEntity#travel}の非流体分岐と同じ順（動いてから重力・減衰）で回す。
 *
 * <p>馬の仲間は{@code AbstractHorse#executeRidersJump}で縦に{@code 跳躍力×溜め}、前へ{@code 0.4×溜め}を足す。
 * ラクダはジャンプキーが突進で（{@code Camel#executeRidersJump}）、縦に{@code 1.4285×溜め×跳躍力}、
 * 前へ{@code 22.2222×溜め×速さ}を足す。空中の加速は{@code 速さ×0.1}（{@code LivingEntity#getFlyingSpeed}の
 * 乗り手が操る分岐）、地上の定常の歩みは{@code 速さ×0.98/(1-0.546)}。
 */
public final class MountPhysics {

    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double AIR_DRAG = 0.91;
    /** 草・石など普通の床の{@code friction 0.6 × 0.91}。 */
    private static final double GROUND_DRAG = 0.546;
    /** 前進キーの入力（{@code LivingEntity#aiStep}が{@code zza}に0.98を掛ける）。 */
    private static final double FORWARD_INPUT = 0.98;
    private static final double HORSE_FORWARD_IMPULSE = 0.4;
    private static final double CAMEL_VERTICAL_MOMENTUM = 1.4285;
    private static final double CAMEL_HORIZONTAL_MOMENTUM = 22.2222;
    /**
     * 選べる溜め。{@code AbstractHorse#onPlayerJump}は押した長さで0.4〜0.8を連続に、90%以上なら1.0にする。
     * 0.8と1.0の間は選べないので刻みに入れない。
     */
    private static final double[] CHARGES = {0.4, 0.5, 0.6, 0.7, 0.8, 1.0};
    private static final int MAX_AIR_TICKS = 200;

    private final double[] apex = new double[CHARGES.length];
    private final double[] reach = new double[CHARGES.length];

    public MountPhysics(MountState mount) {
        boolean camel = mount.kind() == MountState.Kind.CAMEL;
        double speed = mount.movementSpeed();
        double groundStep = speed * FORWARD_INPUT / (1.0 - GROUND_DRAG);
        double airAccel = speed * 0.1 * FORWARD_INPUT;
        for (int i = 0; i < CHARGES.length; i++) {
            double charge = CHARGES[i];
            double vy = camel ? CAMEL_VERTICAL_MOMENTUM * charge * mount.jumpStrength() : mount.jumpStrength() * charge;
            // 踏み切りの直前の速度は、地上の定常の歩みから最後の減衰を戻した値
            double vx = groundStep * GROUND_DRAG
                    + (camel ? CAMEL_HORIZONTAL_MOMENTUM * charge * speed : HORSE_FORWARD_IMPULSE * charge);
            double x = 0.0;
            double y = 0.0;
            double top = 0.0;
            for (int tick = 0; tick < MAX_AIR_TICKS; tick++) {
                vx += airAccel;
                x += vx;
                y += vy;
                top = Math.max(top, y);
                if (y <= 0.0) {
                    break;
                }
                vx *= AIR_DRAG;
                vy = (vy - GRAVITY) * VERTICAL_DRAG;
            }
            apex[i] = top;
            reach[i] = x;
        }
    }

    /** 選べる溜めの数。番号は弱い順。 */
    public int charges() {
        return CHARGES.length;
    }

    /** 溜め{@code charge}番で跳んだときの頂点（踏み切りの高さから）。 */
    public double apex(int charge) {
        return apex[charge];
    }

    /** 溜め{@code charge}番で跳び、踏み切りと同じ高さへ戻るまでに進む水平距離。 */
    public double reach(int charge) {
        return reach[charge];
    }

    /** 跳んで乗れる段の高さ（ブロック）。溜め切った頂点が段の上に届くこと。 */
    public int maxRise() {
        return (int) Math.floor(apex[CHARGES.length - 1]);
    }
}
