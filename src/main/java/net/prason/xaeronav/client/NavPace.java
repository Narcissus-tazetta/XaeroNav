package net.prason.xaeronav.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * プレイヤーが実際にどれくらいの速さで動いているかを測る。
 *
 * <p>A*が積み上げるコストは「最短で動いた場合」の見積もりなので、歩いたり寄り道をしたりすれば
 * そのぶん到着時間はずれる。実測の速さで割り直すことで、表示が実際の動き方に追従する
 * （乗り物や氷の道で速い場合も同じように効く）。
 *
 * <p>止まっている間は測り直さない。0で割ることになるうえ、少し立ち止まっただけで到着時間が
 * 無限に膨らむのは案内として役に立たない。止まっている間は直前の速さを保つ。
 */
public final class NavPace {

    public static final NavPace INSTANCE = new NavPace();

    /**
     * 1tickぶんの寄与の重み。およそ3秒で半分が入れ替わる。プレイヤーの動きは
     * ジャンプ・立ち止まり・向き直りで細かく途切れるので、短くすると表示が落ち着かない。
     */
    private static final double SMOOTHING = 0.012;

    /** これ未満しか動かなかったtickは「止まっている」とみなして測定に混ぜない（ブロック/tick）。 */
    private static final double MOVING_THRESHOLD = 0.01;

    /**
     * 1tickでこれ以上動いたら移動ではない（テレポート・次元移動・チャンク読み込みでの位置補正）。
     * エリトラの最高速でも1tick 2ブロック程度。
     */
    private static final double TELEPORT_THRESHOLD = 8.0;

    /** まだ測れていないときの既定値。スプリント相当。 */
    private static final double DEFAULT_BLOCKS_PER_TICK = 5.612 / 20.0;

    private double blocksPerTick = DEFAULT_BLOCKS_PER_TICK;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean tracking;

    private NavPace() {
    }

    public void onClientTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            tracking = false;
            return;
        }
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        if (!tracking) {
            lastX = x;
            lastY = y;
            lastZ = z;
            tracking = true;
            return;
        }

        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        lastX = x;
        lastY = y;
        lastZ = z;

        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (moved < MOVING_THRESHOLD || moved > TELEPORT_THRESHOLD) {
            return;
        }
        blocksPerTick += (moved - blocksPerTick) * SMOOTHING;
    }

    /** 直近の実測速度（ブロック/tick）。 */
    public double blocksPerTick() {
        return blocksPerTick;
    }
}
