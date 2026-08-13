package net.prason.xaeronav.pathfinding.corridor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 区間ごとに層2で解決した点列を、精緻なwaypoint列へ組み立てる。Minecraft/Xaeroのどちらにも
 * 依存しない純粋なロジック（{@link CorridorLegSolver}がXaero地図の読み取りを担い、こちらは
 * その結果を後処理するだけ）。
 */
public final class CorridorWaypoints {

    private CorridorWaypoints() {
    }

    /**
     * 各区間の点列を順に連結する。区間ごとの点列は、層2A*が解けた区間なら
     * {@code PathResult.steps()}の位置（未到達でも辿り着けた分をそのまま使う——既存の
     * 「暫定経路」と同じ思想）、地表データが無い区間なら生のwaypoint1点だけ、を呼び出し側が渡す。
     */
    public static List<BlockPos> stitch(List<List<BlockPos>> legPoints) {
        List<BlockPos> waypoints = new ArrayList<>();
        for (List<BlockPos> leg : legPoints) {
            waypoints.addAll(leg);
        }
        return waypoints;
    }

    /**
     * 直前に採用した点からユークリッド距離で{@code minSpacingBlocks}未満の点を間引く。層2は
     * ブロック単位の細かい点列を返すため、間引かないとHUDの「長距離ルート N/M」やwaypoint数が
     * 層1の頃と比べて桁違いに増え、案内として読みにくくなる。
     *
     * <p>最後の点（区間の終点＝次のwaypointへの到着点）は間引かれても必ず残す——waypointの
     * 到着位置がずれると、それに続く区間の始点との対応が崩れる。
     */
    public static List<BlockPos> downsample(List<BlockPos> points, int minSpacingBlocks) {
        if (points.isEmpty()) {
            return points;
        }
        List<BlockPos> kept = new ArrayList<>();
        BlockPos last = points.get(0);
        kept.add(last);
        double minSpacingSq = (double) minSpacingBlocks * minSpacingBlocks;
        for (int i = 1; i < points.size() - 1; i++) {
            BlockPos candidate = points.get(i);
            if (distanceSq(last, candidate) >= minSpacingSq) {
                kept.add(candidate);
                last = candidate;
            }
        }
        BlockPos finalPoint = points.get(points.size() - 1);
        if (points.size() > 1 && !finalPoint.equals(last)) {
            kept.add(finalPoint);
        }
        return kept;
    }

    private static double distanceSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
