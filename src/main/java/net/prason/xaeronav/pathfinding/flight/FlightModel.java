package net.prason.xaeronav.pathfinding.flight;

/**
 * 何で飛んでいるか。空中探索の値段と、通れる空間の大きさがこれで決まる。
 *
 * <p>値段の3つの関数は互いに次を満たすこと——A*・粗い層・出口の見積もりがこれを前提にしている:
 * {@code heuristicTicks ≤ lowerBoundTicks ≤ segmentTicks}、かつ{@link #segmentTicks}は区間に対して
 * 劣加法的（折れ線の合計は始点と終点を結ぶ1区間を下回らない）。
 */
public interface FlightModel {

    /** 区間を飛ぶtick数。{@code verticalBlocks}は上が正。 */
    double segmentTicks(double horizontalBlocks, double verticalBlocks);

    /** 水平{@code horizontalBlocks}・垂直{@code verticalLow}〜{@code verticalHigh}のどこかへ着く経路のコストの下限。 */
    double lowerBoundTicks(double horizontalBlocks, double verticalLow, double verticalHigh);

    /** 帯の幅を持つ粗い層の状態から測る、{@link #lowerBoundTicks}以下の見積もり。 */
    double heuristicTicks(double horizontalBlocks, double verticalBlocks);

    /** 水平に1ブロック進むtick数。設定のブロック数をtickへ換算するのに使う。 */
    double horizontalTicksPerBlock();

    /** 経路の点（プレイヤーの足）の周りに空いていなければならない箱。 */
    FlightBody body();

    static FlightModel elytra(boolean rockets) {
        return rockets ? ElytraFlight.ROCKETS : ElytraFlight.GLIDING;
    }

    /** @param flyingSpeed ハッピーガストの{@code FLYING_SPEED} */
    static FlightModel happyGhast(double flyingSpeed) {
        return GhastFlight.of(flyingSpeed);
    }
}
