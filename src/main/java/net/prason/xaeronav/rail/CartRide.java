package net.prason.xaeronav.rail;

/**
 * 人が乗ったトロッコが線路を走る様子を1tickずつ模擬する。旧来のトロッコの挙動（1.21.1の
 * {@code AbstractMinecart#tick}のうちレールの上の部分: {@code moveAlongTrack}・{@code getPos}・
 * {@code moveMinecartOnRail}・{@code applyNaturalSlowdown}）を、位置と速度の2次元のまま写したもの。
 *
 * <p>バニラサーバーで測った平らな直線・パワードの間隔・ブレーキ・曲線のジグザグ・登り・下り・起伏の17本で、
 * 各セルへ入るtickが全セル一致する（{@code CartRideTest}が実測の記録と突き合わせる）。曲線と坂の上では
 * 位置を線路の線へ引き戻すので、線路に沿った1次元の速さに落とすと遅いときに大きく外れる（遅いジグザグで
 * 3割速く、坂の惰性で300tick違った）。
 *
 * <p>写していないもの: ブロックとの当たり判定（線路の終わりで模擬を打ち切るので要らない）、水の中、
 * 乗っている人の押し（{@code pushing}、前進キーの向きを線路の向きとみなす）、検知レールの給電（{@link #DETECTOR_REACH}で近似）。
 * 実験的トロッコ（{@code minecart_improvements}）はまったく別の挙動なので、ここでは扱わない。
 */
public final class CartRide {

    /** {@link Tracks#track}がレールの無いセルに返す値。{@link RailCell}の向きの値が範囲外なので本物と重ならない。 */
    public static final int NONE = -1;

    /** {@code moveMinecartOnRail}: 人が乗っていると移動量が0.75倍。 */
    private static final double RIDDEN_FACTOR = 0.75;
    /** {@code getMaxSpeedWithRail}の既定（Forge/NeoForgeのレールごとの最高速は使わない）。 */
    private static final double MAX_AXIS_SPEED = 0.4;
    /** {@code moveAlongTrack}が水平速度を切る上限。 */
    private static final double MAX_STORED_SPEED = 2.0;
    /** {@code applyNaturalSlowdown}の乗車中の減衰。 */
    private static final double SLOWDOWN = 0.997;
    /** {@code getSlopeAdjustment}の既定。 */
    private static final double SLOPE_ADJUSTMENT = 0.0078125;
    /** {@code moveAlongTrack}末尾: 高さの変化1ブロックあたりの速さの増減。 */
    private static final double HEIGHT_ENERGY = 0.05;
    private static final double POWERED_BOOST = 0.06;
    private static final double BOOST_MIN_SPEED = 0.01;
    private static final double BRAKE_STOP_SPEED = 0.03;
    /** 平らな線路ではバニラの速さは0にならず這い続けるので、実質止まったところで打ち切る。 */
    private static final double CREEP_SPEED = 0.001;
    /** {@code getPos}: トロッコはレールのセルの底より1/16上に乗る。 */
    private static final double RIDE_HEIGHT = 0.0625;
    /** 前進キーで押せるのはトロッコの速さが0.1未満のときだけ（{@code horizontalDistanceSqr() < 0.01}）。 */
    public static final double PUSH_SPEED = 0.1;
    /**
     * 押している間に1tickで足される速さ。{@code moveAlongTrack}は乗っている人の速度の0.1倍を足す。1.21.1の開発クライアントで
     * 乗って前進キーを押し続けて測った（静止から0.1に届くまで約60tick、3.5ブロック先のパワードまで約78tick）。
     */
    private static final double PUSH_ACCELERATION = 0.00175;
    /** 検知レールが給電する隣のパワードレールと、そこから伝わる8本。 */
    private static final int DETECTOR_REACH = 9;

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
     * {@code (x, y, z)}のレールの中央から、出口{@code exit}（0か1、{@link #exitDx}等の順）の向きへ走る。
     *
     * <p>線路を外れる・止まる・押し戻されて向きが逆になる（登り切れない坂）・{@code maxTicks}で終わる。
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
        TrackShape startShape = RailCell.shape(cell);
        double dirX = exitDx(startShape, exit);
        double dirZ = exitDz(startShape, exit);
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        dirX /= length;
        dirZ /= length;
        Cart cart = new Cart(new RecentTracks(tracks), x + 0.5, y, z + 0.5, initialSpeed * dirX, initialSpeed * dirZ,
                dirX, dirZ);
        tracks = cart.tracks;
        int railX = x;
        int railY = y;
        int railZ = z;
        int boosted = 0;
        for (int tick = 1; tick <= maxTicks; tick++) {
            if (!cart.tick(pushing, boosted > 0)) {
                return;
            }
            int bx = floor(cart.x);
            int by = floor(cart.y);
            int bz = floor(cart.z);
            if (tracks.track(bx, by - 1, bz) != NONE) {
                by--;
            }
            if (bx != railX || by != railY || bz != railZ) {
                int next = tracks.track(bx, by, bz);
                if (next == NONE) {
                    return;
                }
                RailKind leaving = RailCell.kind(cell);
                // 検知レールは乗った瞬間に隣を給電する。静的に読むと非通電に見えるパワードが加速器になる
                if (RailCell.kind(next) != RailKind.POWERED) {
                    boosted = 0;
                } else if (leaving == RailKind.DETECTOR) {
                    boosted = DETECTOR_REACH;
                } else {
                    boosted = Math.max(0, boosted - 1);
                }
                cell = next;
                railX = bx;
                railY = by;
                railZ = bz;
                boolean forcedExit = RailCell.kind(cell) == RailKind.ACTIVATOR && RailCell.powered(cell);
                if (!visitor.enter(bx, by, bz, tick, forcedExit) || forcedExit) {
                    return;
                }
            }
            if (cart.reversed || !pushing && cart.speed() < CREEP_SPEED) {
                return;
            }
        }
    }

    /** バニラの{@code AbstractMinecart#EXITS}と同じ並び。坂の低い側の出口は1つ下（{@code dy=-1}）。 */
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
            case ASCENDING_EAST, ASCENDING_SOUTH -> exit == 0 ? -1 : 0;
            case ASCENDING_WEST, ASCENDING_NORTH -> exit == 1 ? -1 : 0;
            default -> 0;
        };
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /**
     * 直近に引いたセルを覚えておく{@link Tracks}。1tickの中で同じ数セル（足元・その下・行き先）を何度も引くので、
     * 覚えないと線路網の表を1tickに8回ほど引く（層1の乗車の辺を組む時間が4倍になった）。
     */
    private static final class RecentTracks implements Tracks {
        private static final int SLOTS = 8;
        private final Tracks source;
        private final long[] keys = new long[SLOTS];
        private final int[] values = new int[SLOTS];
        private final boolean[] filled = new boolean[SLOTS];

        RecentTracks(Tracks source) {
            this.source = source;
        }

        @Override
        public int track(int x, int y, int z) {
            long key = (long) x << 38 ^ (long) (z & 0x3FFFFFF) << 12 ^ y & 0xFFF;
            int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 61);
            if (filled[slot] && keys[slot] == key) {
                return values[slot];
            }
            int value = source.track(x, y, z);
            keys[slot] = key;
            values[slot] = value;
            filled[slot] = true;
            return value;
        }
    }

    /** 1台のトロッコの位置と速度。バニラの変数の流れをそのまま追えるよう、同じ順に計算する。 */
    private static final class Cart {
        final Tracks tracks;
        double x;
        double y;
        double z;
        double vx;
        double vz;
        /** 直前に進んでいた向き。止まりかけで速度の向きが決まらないときと、押し戻しの判定に使う。 */
        double dirX;
        double dirZ;
        boolean reversed;

        Cart(Tracks tracks, double x, double y, double z, double vx, double vz, double dirX, double dirZ) {
            this.tracks = tracks;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vz = vz;
            this.dirX = dirX;
            this.dirZ = dirZ;
        }

        double speed() {
            return Math.sqrt(vx * vx + vz * vz);
        }

        /** {@code AbstractMinecart#tick}のレールの上の部分。レールを外れたら{@code false}。 */
        boolean tick(boolean pushing, boolean boosted) {
            int bx = floor(x);
            int by = floor(y);
            int bz = floor(z);
            if (tracks.track(bx, by - 1, bz) != NONE) {
                by--;
            }
            int cell = tracks.track(bx, by, bz);
            if (cell == NONE) {
                return false;
            }
            moveAlongTrack(bx, by, bz, cell, pushing, boosted);
            double speed = speed();
            if (speed > 0.0) {
                if (vx * dirX + vz * dirZ < 0.0) {
                    reversed = true;
                }
                dirX = vx / speed;
                dirZ = vz / speed;
            }
            return true;
        }

        private void moveAlongTrack(int bx, int by, int bz, int cell, boolean pushing, boolean boosted) {
            double startY = railY(x, y, z);
            TrackShape shape = RailCell.shape(cell);
            RailKind kind = RailCell.kind(cell);
            boolean powered = kind == RailKind.POWERED && (RailCell.powered(cell) || boosted);
            boolean brake = kind == RailKind.POWERED && !powered;
            double newY = by;
            switch (shape) {
                case ASCENDING_EAST -> {
                    vx -= SLOPE_ADJUSTMENT;
                    newY++;
                }
                case ASCENDING_WEST -> {
                    vx += SLOPE_ADJUSTMENT;
                    newY++;
                }
                case ASCENDING_NORTH -> {
                    vz += SLOPE_ADJUSTMENT;
                    newY++;
                }
                case ASCENDING_SOUTH -> {
                    vz -= SLOPE_ADJUSTMENT;
                    newY++;
                }
                default -> { }
            }
            double ax = exitDx(shape, 0);
            double ay = exitDy(shape, 0);
            double az = exitDz(shape, 0);
            double bxx = exitDx(shape, 1);
            double byy = exitDy(shape, 1);
            double bzz = exitDz(shape, 1);
            double railX = bxx - ax;
            double railZ = bzz - az;
            double railLength = Math.sqrt(railX * railX + railZ * railZ);
            double speed = speed();
            // 止まりかけで速度の向きが決まらないときは、直前に進んでいた向きを使う（押す人は前へ押す）
            double along = speed > 1e-9 ? vx * railX + vz * railZ : dirX * railX + dirZ * railZ;
            if (along < 0.0) {
                railX = -railX;
                railZ = -railZ;
            }
            double stored = Math.min(MAX_STORED_SPEED, speed);
            vx = stored * railX / railLength;
            vz = stored * railZ / railLength;
            if (pushing && vx * vx + vz * vz < PUSH_SPEED * PUSH_SPEED) {
                vx += PUSH_ACCELERATION * railX / railLength;
                vz += PUSH_ACCELERATION * railZ / railLength;
                brake = false;
            }
            if (brake) {
                if (speed() < BRAKE_STOP_SPEED) {
                    vx = 0.0;
                    vz = 0.0;
                } else {
                    vx *= 0.5;
                    vz *= 0.5;
                }
            }
            // 線路の線（入口の中点から出口の中点）の上へ位置を引き戻す
            double fromX = bx + 0.5 + ax * 0.5;
            double fromZ = bz + 0.5 + az * 0.5;
            double lineX = bx + 0.5 + bxx * 0.5 - fromX;
            double lineZ = bz + 0.5 + bzz * 0.5 - fromZ;
            double t;
            if (lineX == 0.0) {
                t = z - bz;
            } else if (lineZ == 0.0) {
                t = x - bx;
            } else {
                t = ((x - fromX) * lineX + (z - fromZ) * lineZ) * 2.0;
            }
            x = fromX + lineX * t;
            y = newY;
            z = fromZ + lineZ * t;
            x += Math.max(-MAX_AXIS_SPEED, Math.min(MAX_AXIS_SPEED, RIDDEN_FACTOR * vx));
            z += Math.max(-MAX_AXIS_SPEED, Math.min(MAX_AXIS_SPEED, RIDDEN_FACTOR * vz));
            if (ay != 0.0 && floor(x) - bx == ax && floor(z) - bz == az) {
                y += ay;
            } else if (byy != 0.0 && floor(x) - bx == bxx && floor(z) - bz == bzz) {
                y += byy;
            }
            vx *= SLOWDOWN;
            vz *= SLOWDOWN;
            double endY = railY(x, y, z);
            if (!Double.isNaN(endY) && !Double.isNaN(startY)) {
                double gain = (startY - endY) * HEIGHT_ENERGY;
                double horizontal = speed();
                if (horizontal > 0.0) {
                    vx *= (horizontal + gain) / horizontal;
                    vz *= (horizontal + gain) / horizontal;
                }
                y = endY;
            }
            int nx = floor(x);
            int nz = floor(z);
            if (nx != bx || nz != bz) {
                double horizontal = speed();
                vx = horizontal * (nx - bx);
                vz = horizontal * (nz - bz);
            }
            if (powered) {
                double horizontal = speed();
                if (horizontal > BOOST_MIN_SPEED) {
                    vx += vx / horizontal * POWERED_BOOST;
                    vz += vz / horizontal * POWERED_BOOST;
                }
            }
        }

        /** {@code AbstractMinecart#getPos}の高さ。レールの上でなければNaN。 */
        private double railY(double px, double py, double pz) {
            int i = floor(px);
            int j = floor(py);
            int k = floor(pz);
            if (tracks.track(i, j - 1, k) != NONE) {
                j--;
            }
            int cell = tracks.track(i, j, k);
            if (cell == NONE) {
                return Double.NaN;
            }
            TrackShape shape = RailCell.shape(cell);
            double ax = exitDx(shape, 0);
            double az = exitDz(shape, 0);
            double fromX = i + 0.5 + ax * 0.5;
            double fromY = j + RIDE_HEIGHT + exitDy(shape, 0) * 0.5;
            double fromZ = k + 0.5 + az * 0.5;
            double lineX = i + 0.5 + exitDx(shape, 1) * 0.5 - fromX;
            double lineY = (j + RIDE_HEIGHT + exitDy(shape, 1) * 0.5 - fromY) * 2.0;
            double lineZ = k + 0.5 + exitDz(shape, 1) * 0.5 - fromZ;
            double t;
            if (lineX == 0.0) {
                t = pz - k;
            } else if (lineZ == 0.0) {
                t = px - i;
            } else {
                t = ((px - fromX) * lineX + (pz - fromZ) * lineZ) * 2.0;
            }
            double result = fromY + lineY * t;
            if (lineY < 0.0) {
                result += 1.0;
            } else if (lineY > 0.0) {
                result += 0.5;
            }
            return result;
        }
    }
}
