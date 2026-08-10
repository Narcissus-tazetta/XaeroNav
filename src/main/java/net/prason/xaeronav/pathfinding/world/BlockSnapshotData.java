package net.prason.xaeronav.pathfinding.world;

/**
 * ワールドスナップショット中の1ブロック分の事前計算済みデータ。
 * ワーカースレッドはこのdouble/booleanだけを読み、Level/BlockStateには一切触れない。
 */
public record BlockSnapshotData(
        boolean passableEmpty,
        boolean water,
        boolean lava,
        boolean standable,
        boolean fallingBlock,
        double digTicks
) {
    public boolean isOccupiableWithoutDigging() {
        return passableEmpty || water;
    }
}
