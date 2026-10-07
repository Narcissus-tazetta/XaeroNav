package net.prason.xaeronav.pathfinding.flight;

/**
 * ハーネスを付けたハッピーガストに乗って飛ぶ。
 *
 * <p>乗り手の入力は視線の向きの単位ベクトル（{@code HappyGhast#getRiddenInput}）で、ジャンプキーが真上へ半分を
 * 足す。{@code travelFlying}は重力なし・毎tick0.91倍の減衰なので、どの向きにも同じ定常速度
 * {@link #cruise}で進み、ジャンプを押していればさらに{@link #lift}だけ上へ進む。出せる速度の集合は
 * 「原点中心の球」と「真上へ{@code lift}ずらした球」の凸包（押す・離すを混ぜれば間も出せる）になる。
 *
 * <p>区間の値段はこの凸集合のゲージ（変位をその速度で何tickで進めるか）にしている。凸で原点を含む集合の
 * ゲージは劣加法的なので、直線の値段がそのままどんな折れ線も下回らない下限になる。
 *
 * <p>前進しながら横移動キーも押すと入力が√2倍になるが、まっすぐ前を向いて飛ぶ操作の速さで数える。
 */
record GhastFlight(double cruise, double lift) implements FlightModel {

    /**
     * 乗っているプレイヤーの足を基準にした体の箱。ガストは4×4×4で、操作する乗り手の足はガストの底から
     * +3.4（乗る位置4.0−プレイヤーの{@code DEFAULT_VEHICLE_ATTACHMENT}0.6）、頭はそこから1.8上。
     * 乗り手は進む向きに1.7前の席に座るが、後ろへずれた分は通ってきた線の上なので足さない。
     */
    static final FlightBody BODY = new FlightBody(2.0, 3.4, 1.8);

    /** {@code travelFlying}の空中での減衰。バニラはfloatの0.91で掛ける。 */
    private static final double AIR_DRAG = 0.91F;

    /** ジャンプキーが入力の上向きに足す量（前進の入力1に対して）。 */
    private static final double JUMP_INPUT = 0.5;

    static GhastFlight of(double flyingSpeed) {
        // 入力は3.9×FLYING_SPEED倍、travelはそれをFLYING_SPEED×5/3倍の加速にする
        double acceleration = 3.9 * flyingSpeed * flyingSpeed * 5.0 / 3.0;
        double cruise = acceleration / (1.0 - AIR_DRAG);
        return new GhastFlight(cruise, cruise * JUMP_INPUT);
    }

    @Override
    public double segmentTicks(double horizontalBlocks, double verticalBlocks) {
        double h = horizontalBlocks;
        double v = verticalBlocks;
        if (v <= 0.0) {
            return Math.sqrt(h * h + v * v) / cruise;
        }
        double level = h / cruise;
        if (v <= lift * level) {
            // 水平に進む間、ジャンプだけで間に合う登り
            return level;
        }
        // 速度(h/t, v/t)が上の球 |(h/t, v/t − lift)| = cruise に乗るtを解く
        double lengthSq = h * h + v * v;
        double inverse = (lift * v + Math.sqrt(lift * lift * v * v + lengthSq * (cruise * cruise - lift * lift)))
                / lengthSq;
        return 1.0 / inverse;
    }

    @Override
    public double lowerBoundTicks(double horizontalBlocks, double verticalLow, double verticalHigh) {
        // segmentTicksは垂直が[0, lift×水平/cruise]で最小、そこから離れるほど増える
        double freeClimb = lift * horizontalBlocks / cruise;
        double vertical = verticalHigh < 0.0 ? verticalHigh
                : verticalLow > freeClimb ? verticalLow
                : Math.max(0.0, Math.min(freeClimb, verticalLow));
        return segmentTicks(horizontalBlocks, vertical);
    }

    @Override
    public double heuristicTicks(double horizontalBlocks, double verticalBlocks) {
        return segmentTicks(horizontalBlocks, verticalBlocks);
    }

    @Override
    public double horizontalTicksPerBlock() {
        return 1.0 / cruise;
    }

    @Override
    public FlightBody body() {
        return BODY;
    }
}
