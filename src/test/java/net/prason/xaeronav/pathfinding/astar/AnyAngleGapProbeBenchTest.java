package net.prason.xaeronav.pathfinding.astar;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;

/**
 * 8方向の格子の最適経路を、同じ高さの平地の疾走区間だけ直線に引き直したら何割縮むか（any-angleとの差）。
 * 模型の最適と比べるbenchには原理的に映らない損を測る。判定なし。
 *
 * <p>あわせて、最適経路の値段のうち段差の手間（{@link ActionCosts#STEP_TRANSITION_TICKS}）が占める割合も出す。
 * ダッシュジャンプで進む人には段差の手間がほぼ掛からないので、この割合が模型と跳ぶ人のずれの上限になる。
 */
@Tag("bench")
class AnyAngleGapProbeBenchTest {

    private static final double HALF_WIDTH = 0.3;
    private static final double SAMPLE_BLOCKS = 0.05;

    private record Terrain(String name, String resource, boolean ceiling) {
    }

    private static boolean plainSprint(PathStep step, BlockPos from) {
        if (step.movement() != MovementType.TRAVERSE || step.digging() || step.bridging()
                || step.pos().getY() != from.getY()) {
            return false;
        }
        double straight = ActionCosts.SPRINT_ONE_BLOCK;
        double diagonal = straight * ActionCosts.DIAGONAL_DISTANCE;
        return Math.abs(step.cost() - straight) < 1e-6 || Math.abs(step.cost() - diagonal) < 1e-6;
    }

    /** 幅0.6の体がaの中心からbの中心まで、高さを変えずに床の上を真っ直ぐ歩けるか。 */
    private static boolean clearLine(FakeCells cells, BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5;
        double az = a.getZ() + 0.5;
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        int samples = (int) Math.ceil(Math.hypot(dx, dz) / SAMPLE_BLOCKS);
        int y = a.getY();
        for (int s = 0; s <= samples; s++) {
            double t = (double) s / samples;
            double px = ax + dx * t;
            double pz = az + dz * t;
            for (int cx = (int) Math.floor(px - HALF_WIDTH); cx <= (int) Math.floor(px + HALF_WIDTH); cx++) {
                for (int cz = (int) Math.floor(pz - HALF_WIDTH); cz <= (int) Math.floor(pz + HALF_WIDTH); cz++) {
                    long floor = cells.cell(cx, y - 1, cz);
                    long feet = cells.cell(cx, y, cz);
                    long head = cells.cell(cx, y + 1, cz);
                    if (!CellData.standable(floor) || CellData.hazard(floor)
                            || !CellData.passableEmpty(feet) || CellData.water(feet) || CellData.hazard(feet)
                            || !CellData.passableEmpty(head) || CellData.hazard(head)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 平地の疾走区間を前から貪欲に直線へ引き直した経路の値段。 */
    private static double pulled(FakeCells cells, BlockPos start, List<PathStep> steps) {
        BlockPos[] points = new BlockPos[steps.size() + 1];
        points[0] = start;
        for (int i = 0; i < steps.size(); i++) {
            points[i + 1] = steps.get(i).pos();
        }
        double total = 0;
        int i = 0;
        while (i < steps.size()) {
            int j = i;
            while (j < steps.size() && plainSprint(steps.get(j), points[j])
                    && points[j + 1].getY() == points[i].getY() && clearLine(cells, points[i], points[j + 1])) {
                j++;
            }
            if (j == i) {
                total += steps.get(i).cost();
                i++;
            } else {
                total += Math.hypot(points[j].getX() - points[i].getX(), points[j].getZ() - points[i].getZ())
                        * ActionCosts.SPRINT_ONE_BLOCK;
                i = j;
            }
        }
        return total;
    }

    /**
     * ダッシュジャンプで進む人の所要時間。跳び続けていれば段差の上下に手間は掛からず、登りでも疾走を保てるので、
     * 素の1段の昇降（掘る・置く・減速床の無い手）だけを疾走の水平移動と跳ぶ時間の大きい方へ置き換える。
     */
    private static double jumpingTicks(List<PathStep> steps) {
        double total = 0;
        for (PathStep step : steps) {
            double cost = step.cost();
            boolean plain = !step.digging() && !step.bridging();
            if (plain && step.movement() == MovementType.ASCEND && near(cost, ActionCosts.ASCEND_ONE_BLOCK)) {
                cost = Math.max(ActionCosts.JUMP_ONE_BLOCK, ActionCosts.SPRINT_ONE_BLOCK);
            } else if (plain && step.movement() == MovementType.ASCEND
                    && near(cost, ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK)) {
                cost = Math.max(ActionCosts.JUMP_ONE_BLOCK, ActionCosts.SPRINT_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE);
            } else if (plain && step.movement() == MovementType.DESCEND && near(cost, ActionCosts.DESCEND_ONE_BLOCK)) {
                cost = ActionCosts.SPRINT_ONE_BLOCK;
            } else if (plain && step.movement() == MovementType.DESCEND
                    && near(cost, ActionCosts.DIAGONAL_DESCEND_ONE_BLOCK)) {
                cost = ActionCosts.SPRINT_ONE_BLOCK * ActionCosts.DIAGONAL_DISTANCE;
            }
            total += cost;
        }
        return total;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    /** 正味の高低差を引いた上り＋下り（{@code PathOptimalityTest}と同じ）。 */
    private static int wobble(BlockPos start, List<PathStep> steps) {
        int up = 0;
        int down = 0;
        BlockPos previous = start;
        for (PathStep step : steps) {
            int dy = step.pos().getY() - previous.getY();
            up += Math.max(0, dy);
            down += Math.max(0, -dy);
            previous = step.pos();
        }
        return up + down - Math.abs(up - down);
    }

    @Test
    void gap() throws IOException {
        List<Terrain> terrains = List.of(
                new Terrain("地上/平原丘陵", "/overworld_terrain_columns.txt.gz", false),
                new Terrain("地上/山岳", "/overworld_mountains.txt.gz", false),
                new Terrain("地上/サバンナ", "/overworld_savanna.txt.gz", false),
                new Terrain("地上/海岸", "/overworld_coast.txt.gz", false),
                new Terrain("地上/森", "/overworld_forest.txt.gz", false),
                new Terrain("地上/ジャングル", "/overworld_jungle.txt.gz", false),
                new Terrain("地上/沼地", "/overworld_swamp.txt.gz", false),
                new Terrain("ネザー/荒地", "/nether_terrain_columns.txt.gz", true),
                new Terrain("ネザー/玄武岩", "/nether_basalt_deltas.txt.gz", true),
                new Terrain("ネザー/ソウル", "/nether_soul_sand_valley.txt.gz", true),
                new Terrain("エンド", "/end_terrain_columns.txt.gz", false));
        int routesPer = Integer.getInteger("xaeronav.routes", 20);
        for (Terrain terrain : terrains) {
            FakeCells cells = TerrainFixture.load(terrain.resource(), bounds -> {
                FakeCells c = FakeCells.empty(bounds).canPlaceBlocks(true).maxBridgeRunBlocks(96).maxFallDamagePoints(0);
                return terrain.ceiling() ? c.openSkyYOverride(bounds.maxY()) : c;
            });
            SearchBounds bounds = cells.bounds();
            double sumRatio = 0;
            double worst = 1;
            double sumGrid = 0;
            double sumPulled = 0;
            double sumStep = 0;
            double sumJumping = 0;
            int sumWobble = 0;
            int n = 0;
            for (BlockPos[] route : TerrainFixture.randomRoutes(cells, bounds, 20260904L, routesPer, 40, 90)) {
                PathResult best = new AStarPathfinder(cells, new SearchLimits(3_000_000, 120_000, 1.0), null)
                        .search(route[0], route[1], () -> false);
                if (!best.complete()) {
                    continue;
                }
                double grid = best.steps().stream().mapToDouble(PathStep::cost).sum();
                double any = pulled(cells, route[0], best.steps());
                double ratio = grid / any;
                sumRatio += ratio;
                worst = Math.max(worst, ratio);
                sumGrid += grid;
                sumPulled += any;
                sumStep += best.steps().stream().filter(step -> step.movement() == MovementType.ASCEND
                        || step.movement() == MovementType.DESCEND).count() * ActionCosts.STEP_TRANSITION_TICKS;
                sumJumping += jumpingTicks(best.steps());
                sumWobble += wobble(route[0], best.steps());
                n++;
            }
            System.out.printf(Locale.ROOT, "%-10s %2d本 格子/直線化 平均%.4f 最悪%.4f 合計%.4f 段差の手間%.1f%% 跳ぶ人の所要%.0f 無駄な上下%d%n",
                    terrain.name(), n, sumRatio / n, worst, sumGrid / sumPulled, 100 * sumStep / sumGrid, sumJumping, sumWobble);
        }
    }
}
