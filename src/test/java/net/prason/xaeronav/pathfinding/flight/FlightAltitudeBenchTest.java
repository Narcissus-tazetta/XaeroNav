package net.prason.xaeronav.pathfinding.flight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.cost.FlightCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/** 開けた平地の上で、本番設定の表示経路の高度を計測する。判定のない診断bench。 */
@Tag("bench")
class FlightAltitudeBenchTest {

    @Test
    void measureOpenTerrainAltitudeProfiles() throws IOException {
        SearchBounds bounds = new SearchBounds(-48, 64, -48, 560, 240, 560);
        FakeCells cells = FakeCells.empty(bounds);
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                cells.set(x, 64, z, FakeCells.STONE);
            }
        }
        FlightTuning tuning = new FlightTuning(6, 12 * FlightCosts.HORIZONTAL_TICKS_PER_BLOCK,
                new SearchLimits(150_000, 2_000, 2.5));
        List<String> summary = new ArrayList<>();
        List<String> profiles = new ArrayList<>();
        summary.add("id,rockets,distance,goal_y,diagonal,termination,nodes,points,min_y,max_y,ascent,descent,reversals,excess_vertical,cost_ticks,straight_ticks");
        profiles.add("id,point,x,y,z");
        int id = 0;
        for (boolean rockets : new boolean[] {false, true}) {
            for (int distance : new int[] {120, 240, 480}) {
                for (int goalY : new int[] {72, 96, 144}) {
                    for (boolean diagonal : new boolean[] {false, true}) {
                        Vec3 start = new Vec3(3, 96, 3);
                        double component = diagonal ? distance / Math.sqrt(2) : distance;
                        Vec3 goal = new Vec3(3 + component, goalY, diagonal ? 3 + component : 3);
                        FlightRoute route = FlightRouter.route(cells, start, goal, FlightModel.elytra(rockets), tuning, () -> false);
                        double ascent = 0;
                        double descent = 0;
                        double cost = 0;
                        int reversals = 0;
                        int previousSign = 0;
                        for (int i = 0; i < route.points().size(); i++) {
                            Vec3 p = route.points().get(i);
                            profiles.add(String.format(Locale.ROOT, "%d,%d,%.6f,%.6f,%.6f", id, i, p.x, p.y, p.z));
                            if (i == 0) {
                                continue;
                            }
                            Vec3 previous = route.points().get(i - 1);
                            double dy = p.y - previous.y;
                            ascent += Math.max(0, dy);
                            descent += Math.max(0, -dy);
                            int sign = dy > 1e-6 ? 1 : dy < -1e-6 ? -1 : 0;
                            if (sign != 0) {
                                if (previousSign != 0 && sign != previousSign) {
                                    reversals++;
                                }
                                previousSign = sign;
                            }
                            cost += FlightCosts.segmentTicks(Math.hypot(p.x - previous.x, p.z - previous.z), dy, rockets);
                        }
                        double minY = route.points().stream().mapToDouble(Vec3::y).min().orElse(Double.NaN);
                        double maxY = route.points().stream().mapToDouble(Vec3::y).max().orElse(Double.NaN);
                        double excess = route.isEmpty() ? Double.NaN
                                : ascent + descent - Math.abs(route.tail().y - start.y);
                        double straight = route.isEmpty() ? Double.NaN : FlightCosts.segmentTicks(
                                Math.hypot(route.tail().x - start.x, route.tail().z - start.z),
                                route.tail().y - start.y, rockets);
                        summary.add(String.format(Locale.ROOT,
                                "%d,%s,%d,%d,%s,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f",
                                id++, rockets, distance, goalY, diagonal, route.termination(), route.expandedNodes(),
                                route.points().size(), minY, maxY, ascent, descent, reversals, excess, cost, straight));
                    }
                }
            }
        }
        Path output = Path.of(System.getProperty("xaeronav.profileOut"));
        Files.createDirectories(output);
        Files.write(output.resolve("flight-altitude-summary.csv"), summary);
        Files.write(output.resolve("flight-altitude-points.csv"), profiles);
    }
}
