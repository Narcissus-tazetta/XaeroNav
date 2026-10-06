package net.prason.xaeronav.rail;

/**
 * 人が乗ったトロッコが線路を走る様子を1tickずつ模擬する。旧来のトロッコの挙動
 * （{@code AbstractMinecart#moveAlongTrack}・{@code moveMinecartOnRail}・{@code applyNaturalSlowdown}）を、
 * 線路に沿った1次元の速さに落としたもの。
 *
 * <p>平らな直線では実機（1.16.5・1.21.1・1.21.10のバニラサーバー）と全セル0tick差で一致する。
 * 曲線のジグザグは軸ごとに0.4/tickで切られるので、曲線のセルを長さ√2/2・上限0.4√2として持つ
 * （実測の斜め8√2m/sと一致）。坂は下の定数をバニラから写しただけで、実測とは照合していない。
 *
 * <p>実験的トロッコ（{@code minecart_improvements}）はまったく別の挙動なので、ここでは扱わない。
 */
public final class CartRide {

    /** {@link Tracks#track}がレールの無いセルに返す値。{@link RailCell}の向きの値が範囲外なので本物と重ならない。 */
    public static final int NONE = -1;

    /** {@code AbstractMinecart#moveMinecartOnRail}: 人が乗っていると移動量が0.75倍。 */
    private static final double RIDDEN_FACTOR = 0.75;
    /** {@code getMaxSpeedWithRail}の既定（Forge/NeoForgeのレールごとの最高速は使わない）。 */
    private static final double MAX_AXIS_SPEED = 0.4;
    /** {@code moveAlongTrack}が水平速度を切る上限。 */
    private static final double MAX_STORED_SPEED = 2.0;
    /** {@code applyNaturalSlowdown}の乗車中の減衰。 */
    private static final double SLOWDOWN = 0.997;
    /** {@code getSlopeAdjustment}の既定。坂の上では毎tick下りの向きへ足される。 */
    private static final double SLOPE_ADJUSTMENT = 0.0078125;
    /** {@code moveAlongTrack}末尾: 高さの変化1ブロックあたりの速さの増減。 */
    private static final double HEIGHT_ENERGY = 0.05;
    private static final double POWERED_BOOST = 0.06;
    private static final double BOOST_MIN_SPEED = 0.01;
    private static final double BRAKE_STOP_SPEED = 0.03;
    private static final double CREEP_SPEED = 0.001;
    /**
     * 前進キーで押せるのはトロッコの速さが0.1未満のときだけ（{@code horizontalDistanceSqr() < 0.01}）。
     * 押しの立ち上がりは測っていないので、押している間は0.1を保つものとして扱う（推測）。
     */
    public static final double PUSH_SPEED = 0.1;
    /** 検知レールが給電する隣のパワードレールと、そこから伝わる8本。 */
    private static final int DETECTOR_REACH = 9;
    private static final double CURVE_LENGTH = Math.sqrt(0.5);
    private static final double CURVE_CAP = MAX_AXIS_SPEED * Math.sqrt(2.0);

    private CartRide() {
    }

    /** 座標のレール（{@link RailCell}の形、チャンク内の位置は見ない）か{@link #NONE}。 */
    @FunctionalInterface
    public interface Tracks {
        int track(int x, int y, int z);
    }

    @FunctionalInterface
    public interface Visitor {
        /**
         * トロッコが新しいセルに入った。{@code forcedExit}なら乗っている人はここで降ろされ、走りは終わる。
         *
         * @return {@code false}で模擬を打ち切る
         */
        boolean enter(int x, int y, int z, int tick, boolean forcedExit);
    }

    /**
     * {@code (x, y, z)}のレールから、出口{@code exit}（0か1、{@link #exitDx}等の順）の向きへ走る。
     *
     * @param pushing 速さが{@link #PUSH_SPEED}を下回ったら前進キーで押し続ける。押している間はブレーキも効かない
     *                （{@code moveAlongTrack}が押した回の{@code flag1}を落とす）
     */
    public static void ride(Tracks tracks, int x, int y, int z, int exit, double initialSpeed, boolean pushing,
                            int maxTicks, Visitor visitor) {
        int cell = tracks.track(x, y, z);
        if (cell == NONE) {
            return;
        }
        double v = initialSpeed;
        double along = 0.0;
        int boosted = 0;
        for (int tick = 0; tick < maxTicks; tick++) {
            TrackShape shape = RailCell.shape(cell);
            RailKind kind = RailCell.kind(cell);
            boolean curve = isCurve(shape);
            int slope = slopeSign(shape, exit);
            double length = curve ? CURVE_LENGTH : 1.0;

            v = Math.min(MAX_STORED_SPEED, v);
            v -= slope * SLOPE_ADJUSTMENT;
            boolean pushed = false;
            if (pushing && v < PUSH_SPEED) {
                v = PUSH_SPEED;
                pushed = true;
            }
            boolean powered = kind == RailKind.POWERED && (RailCell.powered(cell) || boosted > 0);
            if (kind == RailKind.POWERED && !powered && !pushed) {
                v = v < BRAKE_STOP_SPEED ? 0.0 : v * 0.5;
            }
            double moved = Math.min(RIDDEN_FACTOR * v, curve ? CURVE_CAP : MAX_AXIS_SPEED);
            v *= SLOWDOWN;
            if (slope != 0) {
                v -= slope * HEIGHT_ENERGY * Math.min(moved, length - along);
            }
            if (powered && v > BOOST_MIN_SPEED) {
                v += POWERED_BOOST;
            }
            // 平らな線路ではバニラの速さは0にならず這い続けるので、実質止まったところで打ち切る
            if (v < CREEP_SPEED) {
                return;
            }
            along += moved;
            while (along >= length) {
                along -= length;
                int nextX = x + exitDx(shape, exit);
                int nextZ = z + exitDz(shape, exit);
                int nextY = findNext(tracks, nextX, y + exitDy(shape, exit), nextZ, exitDy(shape, exit) == 0);
                if (nextY == Integer.MIN_VALUE) {
                    return;
                }
                int next = tracks.track(nextX, nextY, nextZ);
                int entry = exitToward(RailCell.shape(next), x - nextX, z - nextZ);
                if (entry < 0) {
                    // 繋がっていないレールへ乗り移ると脱線するか、別の向きへ曲がる。どちらも案内できない
                    return;
                }
                RailKind leaving = RailCell.kind(cell);
                x = nextX;
                y = nextY;
                z = nextZ;
                cell = next;
                exit = 1 - entry;
                // 検知レールは乗った瞬間に隣を給電する。静的に読むと非通電に見えるパワードが加速器になる
                if (RailCell.kind(cell) != RailKind.POWERED) {
                    boosted = 0;
                } else if (leaving == RailKind.DETECTOR) {
                    boosted = DETECTOR_REACH;
                } else {
                    boosted = Math.max(0, boosted - 1);
                }
                boolean forcedExit = RailCell.kind(cell) == RailKind.ACTIVATOR && RailCell.powered(cell);
                if (!visitor.enter(x, y, z, tick + 1, forcedExit) || forcedExit) {
                    return;
                }
                length = isCurve(RailCell.shape(cell)) ? CURVE_LENGTH : 1.0;
            }
        }
    }

    /** 同じ高さ、下りなら1つ下にもあるレールの高さ。無ければ{@link Integer#MIN_VALUE}。 */
    private static int findNext(Tracks tracks, int x, int y, int z, boolean mayDescend) {
        if (tracks.track(x, y, z) != NONE) {
            return y;
        }
        if (mayDescend && tracks.track(x, y - 1, z) != NONE) {
            return y - 1;
        }
        return Integer.MIN_VALUE;
    }

    /** {@code shape}の出口のうち水平に{@code (dx, dz)}を向くもの。無ければ-1。 */
    static int exitToward(TrackShape shape, int dx, int dz) {
        for (int exit = 0; exit < 2; exit++) {
            if (exitDx(shape, exit) == dx && exitDz(shape, exit) == dz) {
                return exit;
            }
        }
        return -1;
    }

    static boolean isCurve(TrackShape shape) {
        return shape.ordinal() >= TrackShape.SOUTH_EAST.ordinal();
    }

    /** 出口{@code exit}へ向かうとき登りなら1、下りなら-1、平らなら0。 */
    static int slopeSign(TrackShape shape, int exit) {
        return switch (shape) {
            case ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH, ASCENDING_SOUTH ->
                    exitDy(shape, exit) == 1 ? 1 : -1;
            default -> 0;
        };
    }

    /** バニラの{@code AbstractMinecart#EXITS}と同じ並び。坂は高い側の隣のレールが1つ上にある。 */
    public static int exitDx(TrackShape shape, int exit) {
        return switch (shape) {
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> exit == 0 ? -1 : 1;
            case SOUTH_EAST, NORTH_EAST -> exit == 0 ? 0 : 1;
            case SOUTH_WEST, NORTH_WEST -> exit == 0 ? 0 : -1;
            default -> 0;
        };
    }

    public static int exitDz(TrackShape shape, int exit) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> exit == 0 ? -1 : 1;
            case SOUTH_EAST, SOUTH_WEST -> exit == 0 ? 1 : 0;
            case NORTH_WEST, NORTH_EAST -> exit == 0 ? -1 : 0;
            default -> 0;
        };
    }

    static int exitDy(TrackShape shape, int exit) {
        return switch (shape) {
            case ASCENDING_EAST, ASCENDING_SOUTH -> exit;
            case ASCENDING_WEST, ASCENDING_NORTH -> 1 - exit;
            default -> 0;
        };
    }
}
