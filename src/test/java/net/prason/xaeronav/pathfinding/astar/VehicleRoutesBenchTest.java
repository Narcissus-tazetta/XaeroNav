package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseRouter;
import net.prason.xaeronav.pathfinding.coarse.LiveCoarseSampler;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.MountState;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * 実地形で、徒歩・馬・車（幅3/5/7）の探索時間と、途切れずに着く割合を比べる（判定なし）。
 *
 * <p>車は徒歩のガイド（層1の徒歩の値段）を速さの比で縮めて使う。幅の広い車ほど徒歩の近道（狭い隙間）へ
 * 吸い寄せられて予算を使い切るおそれがあるので、ガイドあり・なしの両方を測る。
 * 「着く」は降りて歩く経路も含む（車で行ける所まで行って降りるのが仕様）。
 */
@Tag("bench")
class VehicleRoutesBenchTest {

    private static final BooleanSupplier NEVER = () -> false;
    private static final int PRODUCTION_NODE_BUDGET = 100_000;
    private static final double PRODUCTION_WEIGHT = 1.2;
    private static final long SEED = 20261127L;
    private static final int ROUTES = 12;

    private record Rider(String name, MountState mount) {
    }

    @Test
    void compareRiders() throws IOException {
        List<Rider> riders = List.of(
                new Rider("walk", MountState.NONE),
                new Rider("horse", new MountState(MountState.Kind.HORSE, 0.225, 0.7)),
                new Rider("car3", MountState.vehicle(3, 4)),
                new Rider("car5", MountState.vehicle(5, 4)),
                new Rider("car7", MountState.vehicle(7, 5)));
        String[] terrains = {"/overworld_terrain_columns.txt.gz", "/overworld_savanna.txt.gz",
                "/overworld_forest.txt.gz", "/overworld_mountains.txt.gz", "/overworld_jungle.txt.gz"};
        List<String> report = new ArrayList<>();
        for (String terrain : terrains) {
            FakeCells base = TerrainFixture.load(terrain, bounds -> FakeCells.empty(bounds).canPlaceBlocks(true)
                    .maxBridgeRunBlocks(96).maxFallDamagePoints(6));
            List<BlockPos[]> routes = TerrainFixture.randomRoutes(base, base.bounds(), SEED, ROUTES, 40, 110);
            for (Rider rider : riders) {
                for (boolean guided : new boolean[] {true, false}) {
                    if (!guided && rider.mount() == MountState.NONE) {
                        continue;
                    }
                    base.mount(rider.mount());
                    int complete = 0;
                    int drivenAll = 0;
                    long totalNanos = 0;
                    long worstNanos = 0;
                    long totalExpanded = 0;
                    double rideShare = 0.0;
                    for (BlockPos[] route : routes) {
                        CostToGo guide = null;
                        if (guided) {
                            CoarseMap map = LiveCoarseSampler.sample(base, base.bounds(), route[0].getY(), NEVER);
                            guide = CoarseRouter.costToGo(map, route[1], false, CoarseRouter.BridgePolicy.BRIDGE);
                        }
                        long t0 = System.nanoTime();
                        PathResult result = new AStarPathfinder(base,
                                new SearchLimits(PRODUCTION_NODE_BUDGET, 120_000, PRODUCTION_WEIGHT), guide)
                                .search(route[0], route[1], NEVER);
                        long nanos = System.nanoTime() - t0;
                        totalNanos += nanos;
                        worstNanos = Math.max(worstNanos, nanos);
                        totalExpanded += result.expandedNodes();
                        if (result.complete()) {
                            complete++;
                            long rides = result.steps().stream().filter(step -> step.movement().rides()).count();
                            rideShare += result.steps().isEmpty() ? 0 : (double) rides / result.steps().size();
                            if (rides == result.steps().size()) {
                                drivenAll++;
                            }
                        }
                    }
                    report.add(String.format(Locale.ROOT,
                            "%-30s %-6s guide=%-5s complete=%2d/%d rideAll=%2d rideShare=%.2f mean=%.0fms worst=%.0fms expanded=%d",
                            terrain, rider.name(), guided, complete, routes.size(), drivenAll,
                            complete == 0 ? 0 : rideShare / complete, totalNanos / 1e6 / routes.size(), worstNanos / 1e6,
                            totalExpanded / routes.size()));
                }
            }
        }
        report.forEach(System.out::println);
    }
}
