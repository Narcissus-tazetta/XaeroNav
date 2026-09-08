package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * <b>ネザーの長距離を、実機と同じ組み立てで歩き通して測る。</b>
 */
@Tag("slow")
class NetherLiveWalkTest {

    private static final int WINDOW_RADIUS = 160;

    private static List<BlockPos[]> routes() {
        return List.of(
                new BlockPos[] {new BlockPos(-447, 74, 525), new BlockPos(-259, 65, 379)},
                new BlockPos[] {new BlockPos(-505, 71, 836), new BlockPos(-538, 67, 496)},
                new BlockPos[] {new BlockPos(-317, 44, 567), new BlockPos(-523, 66, 465)},
                new BlockPos[] {new BlockPos(-474, 69, 629), new BlockPos(-271, 73, 482)});
    }

    private static FakeCells terrain() throws IOException {
        return TerrainFixture.load("/nether_wide.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true).maxFallDamagePoints(6)
                .maxBridgeRunBlocks(96).maxVoidBridgeRunBlocks(96));
    }

    private static String ratio(ProgressiveWalk.Trace trace, double best) {
        if (trace.steps().isEmpty()) {
            return "未到達: " + trace.stopped();
        }
        double cost = ProgressiveWalk.cost(trace.steps());
        return String.format(Locale.ROOT, "%6.0f(%.3f倍) 繋ぎ目%d 解き直し%d/%d 描き変わり%d(足元%d)",
                cost, cost / best, trace.joints().size(), trace.repairsTaken(),
                trace.repairAttempts(), trace.redraws(), trace.nearRedraws());
    }

    @Test
    void walksTheNetherTheWayTheGameDoes() throws Exception {
        FakeCells cells = terrain();
        List<String> report = new ArrayList<>();
        for (BlockPos[] route : routes()) {
            long began = System.currentTimeMillis();
            double best = ProgressiveWalk.fullVisibilityBest(cells, route[0], route[1]);
            ProgressiveWalk.Trace extend = ProgressiveWalk.trace(cells, route[0], route[1],
                    WINDOW_RADIUS, ProgressiveWalk.Mode.EXTEND, ProgressiveWalk.Aim.GOAL);
            ProgressiveWalk.Trace repair = ProgressiveWalk.trace(cells, route[0], route[1],
                    WINDOW_RADIUS, ProgressiveWalk.Mode.REPAIR, ProgressiveWalk.Aim.GOAL);
            report.add(String.format(Locale.ROOT,
                    "%s→%s 基準%6.0f%n  継ぎ足しのみ %s%n  繋ぎ目も直す %s%n  (%.0f秒)",
                    route[0].toShortString(), route[1].toShortString(), best,
                    ratio(extend, best), ratio(repair, best),
                    (System.currentTimeMillis() - began) / 1000.0));
            System.out.println(report.get(report.size() - 1));
        }
        System.out.println(String.join("\n", report));
        assertTrue(!report.isEmpty());
    }
}
