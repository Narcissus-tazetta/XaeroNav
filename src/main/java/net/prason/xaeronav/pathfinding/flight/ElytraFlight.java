package net.prason.xaeronav.pathfinding.flight;

import net.prason.xaeronav.pathfinding.cost.FlightCosts;

/** エリトラの滑空。ロケットの所持で登りの値段が切り替わる（{@link FlightCosts}参照）。 */
enum ElytraFlight implements FlightModel {
    GLIDING(false),
    ROCKETS(true);

    private final boolean rockets;

    ElytraFlight(boolean rockets) {
        this.rockets = rockets;
    }

    @Override
    public double segmentTicks(double horizontalBlocks, double verticalBlocks) {
        return FlightCosts.segmentTicks(horizontalBlocks, verticalBlocks, rockets);
    }

    @Override
    public double lowerBoundTicks(double horizontalBlocks, double verticalLow, double verticalHigh) {
        return FlightCosts.lowerBoundTicks(horizontalBlocks, verticalLow, verticalHigh, rockets);
    }

    @Override
    public double heuristicTicks(double horizontalBlocks, double verticalBlocks) {
        return FlightCosts.heuristicTicks(horizontalBlocks, verticalBlocks, rockets);
    }

    @Override
    public double horizontalTicksPerBlock() {
        return FlightCosts.HORIZONTAL_TICKS_PER_BLOCK;
    }

    @Override
    public FlightBody body() {
        return FlightBody.NONE;
    }
}
