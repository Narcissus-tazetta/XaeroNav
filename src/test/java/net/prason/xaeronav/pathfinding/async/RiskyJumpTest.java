package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 「外したら取り返しがつかない跳躍」を避けつつ、他に道が無いときだけ跳ぶこと。
 *
 * <p>ユーザーの言い分そのものを地形にしてある——<b>C字の島の両端は、跳べば近いが回れば安全</b>
 * なので回る。<b>島と島の間は跳ぶしかない</b>ので跳ぶ。この使い分けは「他に道があるか」であり、
 * 詰み回避の緩和梯子（{@link PathfindingExecutor}）の発動条件そのものなので、そこに載せてある。
 */
class RiskyJumpTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final SearchLimits LIMITS =
            new SearchLimits(300_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static final int FLOOR_Y = 60;
    private static final int FEET_Y = FLOOR_Y + 1;

    /** 跳べば届き、橋なら3マス架かる幅。 */
    private static final int GAP = 3;

    /** C字の腕の長さ。跳べば{@link #GAP}マス、回れば約80マス——近道の誘惑が桁で勝つ形にしておく。 */
    private static final int ARM_LENGTH = 40;

    /** 上の腕の先端。 */
    private static final BlockPos UPPER_TIP = new BlockPos(ARM_LENGTH, FEET_Y, 2);
    /** 下の腕の先端。{@link #UPPER_TIP}とは奈落を挟んで{@link #GAP}マス。 */
    private static final BlockPos LOWER_TIP = new BlockPos(ARM_LENGTH, FEET_Y, 6);

    /**
     * C字の島。開いた口（右端）を跳べば{@link #GAP}マスの近道だが、背（左端）を回れば安全に着ける。
     *
     * <pre>
     *   z=0..2   #########################   ← 上の腕
     *   z=3..5   ###......................   ← 口（奈落）。左3マスだけが背として繋がる
     *   z=6..8   #########################   ← 下の腕
     * </pre>
     */
    private static FakeCells cShapedIsland() {
        SearchBounds bounds = new SearchBounds(-8, 20, -8, ARM_LENGTH + 8, FLOOR_Y + 32, 16);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(false);
        for (int x = 0; x <= ARM_LENGTH; x++) {
            for (int z = 0; z <= 8; z++) {
                boolean arm = z <= 2 || z >= 6;
                boolean spine = x <= 2;
                if (arm || spine) {
                    cells.set(x, FLOOR_Y, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    /**
     * 上の腕の先端から下の腕の先端へ。奈落の上を{@link #GAP}マス跳べば近いが、背を回れば安全に着く。
     * <b>回る方を選ぶこと</b>——跳んで外せば奈落なので、近道の価値では釣り合わない。
     */
    @Test
    void walksAroundTheCShapeInsteadOfJumpingItsMouth() throws Exception {
        FakeCells cells = cShapedIsland();

        PathResult result = new PathfindingExecutor()
                .submit(cells, UPPER_TIP, LOWER_TIP, LIMITS, true, 0).get();

        assertTrue(result.complete(), "背を回れば着けるはず: " + result.termination());
        assertFalse(hasJump(result), "回り道があるなら奈落の上は跳ばない");
    }

    /**
     * 同じC字で避けない設定にすると跳ぶ——<b>上のテストが空振りしていないことの担保</b>。
     * これが無いと、口が跳べない幅だっただけの地形でも上のテストは通ってしまう
     * （実際、最初に書いた地形は口が24マスあり、直す前から一度も跳んでいなかった）。
     */
    @Test
    void jumpsTheSameMouthWhenNotAvoidingRiskyJumps() throws Exception {
        FakeCells cells = cShapedIsland().avoidRiskyJumps(false);

        PathResult result = new PathfindingExecutor()
                .submit(cells, UPPER_TIP, LOWER_TIP, LIMITS, true, 0).get();

        assertTrue(result.complete(), "跳べば着けるはず: " + result.termination());
        assertTrue(hasJump(result), "避けない設定なら近道を跳ぶ");
    }

    /**
     * 島と島の間。回り道が無く、置くブロックも持っていないので、跳ぶ以外に手が無い。
     * 緩和の梯子が開けて跳ぶこと（「絶対に跳ばない」ではなく「他に道が無いときだけ跳ぶ」）。
     */
    @Test
    void jumpsBetweenIslandsWhenThereIsNoOtherWay() throws Exception {
        FakeCells cells = twoPlatforms(GAP, 0);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new PathfindingExecutor().submit(cells, start, goal, LIMITS, true, 0).get();

        assertTrue(result.complete(), "他に道が無いなら跳んで渡るはず: " + result.termination());
        assertTrue(hasJump(result), "跳躍で渡っていること");
    }

    /** 梯子を通さない素の探索では、同じ地形でも跳ばない（＝既定は避ける側）。 */
    @Test
    void theBareSearchRefusesTheSameJump() {
        FakeCells cells = twoPlatforms(GAP, 0);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new AStarPathfinder(cells, LIMITS).search(start, goal, NEVER);

        assertFalse(result.complete(), "既定では奈落の上を跳ばないので届かない: " + result.termination());
        assertFalse(hasJump(result), "跳躍が経路に含まれないこと");
    }

    /**
     * 底はあるが、落ちれば今の体力で死ぬ深さの溝。奈落と同じく避ける——
     * {@code addFall}が「意図して降りる」高さを見るのに対し、こちらは「跳んで外したとき」を見る。
     */
    @Test
    void refusesToJumpOverAPitDeepEnoughToKill() {
        int fatal = ActionCosts.SAFE_FALL_BLOCKS + 20;
        FakeCells cells = twoPlatforms(GAP, fatal + 2).fatalFallBlocks(fatal);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new AStarPathfinder(cells, LIMITS).search(start, goal, NEVER);

        assertFalse(hasJump(result), "落ちたら死ぬ深さの溝は跳ばない");
    }

    /** 同じ溝でも、落ちて助かる深さなら従来どおり跳ぶ。 */
    @Test
    void stillJumpsOverAPitShallowEnoughToSurvive() {
        int fatal = ActionCosts.SAFE_FALL_BLOCKS + 20;
        FakeCells cells = twoPlatforms(GAP, 6).fatalFallBlocks(fatal);
        BlockPos start = new BlockPos(4, FEET_Y, 4);
        BlockPos goal = new BlockPos(8 + GAP, FEET_Y, 4);

        PathResult result = new AStarPathfinder(cells, LIMITS).search(start, goal, NEVER);

        assertTrue(result.complete(), "浅い溝は跳んで渡れるはず: " + result.termination());
        assertTrue(hasJump(result), "跳躍で渡っていること");
    }

    /**
     * 2つの足場を{@code gap}マス離して並べる。
     *
     * @param pitDepth 隙間の底までの深さ。0なら底を作らない＝奈落
     */
    private static FakeCells twoPlatforms(int gap, int pitDepth) {
        int right = 8 + gap;
        SearchBounds bounds = new SearchBounds(-8, FLOOR_Y - pitDepth - 24, -8,
                right + 8, FLOOR_Y + 32, 16);
        FakeCells cells = FakeCells.empty(bounds).canPlaceBlocks(false);
        for (int z = 0; z <= 8; z++) {
            for (int x = 0; x <= 4; x++) {
                cells.set(x, FLOOR_Y, z, FakeCells.BEDROCK);
            }
            for (int x = 5 + gap; x <= right; x++) {
                cells.set(x, FLOOR_Y, z, FakeCells.BEDROCK);
            }
            if (pitDepth > 0) {
                for (int x = 5; x <= 4 + gap; x++) {
                    cells.set(x, FLOOR_Y - pitDepth, z, FakeCells.BEDROCK);
                }
            }
        }
        return cells;
    }

    private static boolean hasJump(PathResult result) {
        for (PathStep step : result.steps()) {
            if (step.movement() == MovementType.JUMP) {
                return true;
            }
        }
        return false;
    }
}
