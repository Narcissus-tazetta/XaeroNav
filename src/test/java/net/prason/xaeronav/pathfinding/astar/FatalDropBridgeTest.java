package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>「謎にわたらせる」——外したら死ぬ高さの谷に、迂回できるのに橋を架ける。</b>
 *
 * <p>ユーザー報告（2026-08-29、実機のスクショ）:「下にブロックあるからいいとか思ってそう」。
 * まさにそのとおりだった——{@code addBridge}は{@code obstacleY}が実在の床を指してさえいれば、
 * その床が<b>何マス下か</b>を一切見ずに普通の橋として扱っていた:
 *
 * <table>
 *   <tr><th>橋の下</th><th>掘削禁止</th><th>連続長の上限</th><th>追加コスト</th></tr>
 *   <tr><td>奈落</td><td>要求</td><td>{@code maxVoidBridgeRun}</td><td>{@code VOID_BRIDGE_PENALTY}</td></tr>
 *   <tr><td>溶岩</td><td>要求</td><td>{@code maxLavaBridgeRun}</td><td>{@code LAVA_BRIDGE_PENALTY}</td></tr>
 *   <tr><td><b>床が43マス下（即死）</b></td><td><b>無し</b></td><td>通常のみ</td><td><b>0</b></td></tr>
 * </table>
 *
 * <p>落ちれば死ぬという結末は奈落と同じなのに、値段だけが「底のある1マスの窪み」と同じだった。
 * その23で跳躍（{@code addJumpGap}）には{@code fatalMiss}で致死落差を入れたが、橋には入れ忘れていた。
 */
class FatalDropBridgeTest {

    private static final BooleanSupplier NEVER = () -> false;

    private static final int GROUND_Y = 63;
    private static final int STAND_Y = 64;
    /** 谷の底。{@code STAND_Y}から43マス下＝既定の致死落差(23)を大きく超える。 */
    private static final int CHASM_FLOOR_Y = 20;

    private static final int CHASM_MIN_X = 30;
    private static final int CHASM_MAX_X = 36;
    /** 谷はここまでしか伸びていない。これより南（Zが大きい側）へ回れば歩いて渡れる。 */
    private static final int CHASM_MAX_Z = 25;

    /**
     * 平らな台地を、幅7ブロック・深さ43ブロックの谷が途中まで裂いている。
     * 谷の南端（{@code CHASM_MAX_Z}）を回り込めば、橋を1本も架けずに向こう側へ行ける。
     */
    private static FakeCells terrain() {
        SearchBounds bounds = new SearchBounds(-16, 0, -16, 96, 110, 80);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(6);
        for (int x = -16; x <= 96; x++) {
            for (int z = -16; z <= 80; z++) {
                boolean inChasm = x >= CHASM_MIN_X && x <= CHASM_MAX_X && z <= CHASM_MAX_Z;
                cells.set(x, inChasm ? CHASM_FLOOR_Y : GROUND_Y, z, FakeCells.STONE);
            }
        }
        return cells;
    }

    private static int bridgeSteps(PathResult result) {
        int n = 0;
        for (PathStep step : result.steps()) {
            if (step.bridging()) {
                n++;
            }
        }
        return n;
    }

    private static PathResult solve(FakeCells cells, BlockPos start, BlockPos goal) {
        SearchLimits limits = new SearchLimits(500_000, 20_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        return new AStarPathfinder(cells, limits).search(start, goal, NEVER);
    }

    /**
     * <b>本体。</b>谷の南端を回れば歩いて行けるのだから、外したら死ぬ谷へ橋を架けてはいけない。
     */
    @Test
    void walksAroundAChasmDeepEnoughToKillInsteadOfBridgingIt() {
        FakeCells cells = terrain();
        BlockPos start = new BlockPos(10, STAND_Y, 0);
        BlockPos goal = new BlockPos(60, STAND_Y, 0);

        PathResult result = solve(cells, start, goal);
        int maxZ = result.steps().stream().mapToInt(s -> s.pos().getZ()).max().orElse(0);

        System.out.printf("致死落差の谷: complete=%s steps=%d 橋=%d maxZ=%d%n",
                result.complete(), result.steps().size(), bridgeSteps(result), maxZ);

        assertTrue(result.complete(), "南へ回れば歩いて行けるので必ず到達する: " + result.termination());
        assertEquals(0, bridgeSteps(result),
                "外したら死ぬ谷に橋を架けた（迂回できるのに）。橋=" + bridgeSteps(result));
        assertTrue(maxZ > CHASM_MAX_Z, "谷の南端を回り込んでいない: maxZ=" + maxZ);
    }

    /**
     * <b>対照。</b>同じ地形で谷を浅く（落ちても死なない深さに）すると、迂回する理由が消えて
     * 橋で渡る。これが無いと「そもそも常に迂回する」だけのテストと区別が付かない。
     */
    @Test
    void stillBridgesAShallowChasmWhereFallingIsSurvivable() {
        SearchBounds bounds = new SearchBounds(-16, 0, -16, 96, 110, 80);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(6);
        // 底は2マス下だけ。落ちても死なないので、遠回りするより架けた方が安い
        for (int x = -16; x <= 96; x++) {
            for (int z = -16; z <= 80; z++) {
                boolean inChasm = x >= CHASM_MIN_X && x <= CHASM_MAX_X && z <= CHASM_MAX_Z;
                cells.set(x, inChasm ? GROUND_Y - 2 : GROUND_Y, z, FakeCells.STONE);
            }
        }
        BlockPos start = new BlockPos(10, STAND_Y, 0);
        BlockPos goal = new BlockPos(60, STAND_Y, 0);

        PathResult result = solve(cells, start, goal);
        int maxZ = result.steps().stream().mapToInt(s -> s.pos().getZ()).max().orElse(0);
        System.out.printf("浅い窪み:     complete=%s steps=%d 橋=%d maxZ=%d%n",
                result.complete(), result.steps().size(), bridgeSteps(result), maxZ);

        assertTrue(result.complete());
        assertTrue(maxZ <= CHASM_MAX_Z,
                "浅い窪みなら回り込む理由が無い（この対照が崩れたら本体のテストは空振りしている）: maxZ=" + maxZ);
    }

    /**
     * 迂回路が無ければ、致死落差の谷でも架けて渡る。<b>禁止ではなく高くしただけ</b>であることの固定
     * ——詰みを増やす修正になっていないか。
     */
    @Test
    void stillBridgesAFatalChasmWhenThereIsNoWayAround() {
        SearchBounds bounds = new SearchBounds(-16, 0, -16, 96, 110, 80);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).canPlaceBlocks(true)
                .maxFallDamagePoints(6);
        // 端から端まで裂けた谷。回り込む道が無い
        for (int x = -16; x <= 96; x++) {
            for (int z = -16; z <= 80; z++) {
                boolean inChasm = x >= CHASM_MIN_X && x <= CHASM_MAX_X;
                cells.set(x, inChasm ? CHASM_FLOOR_Y : GROUND_Y, z, FakeCells.STONE);
            }
        }
        BlockPos start = new BlockPos(10, STAND_Y, 0);
        BlockPos goal = new BlockPos(60, STAND_Y, 0);

        PathResult result = solve(cells, start, goal);
        System.out.printf("迂回不能:     complete=%s steps=%d 橋=%d%n",
                result.complete(), result.steps().size(), bridgeSteps(result));

        assertTrue(result.complete(), "迂回できないなら架けて渡るしかない: " + result.termination());
        assertTrue(bridgeSteps(result) > 0, "橋を架けずにどうやって渡ったのか");

        // 架けるしかない場合でも、外せば死ぬことは色で伝える（PathSafetyCheckerがaddBridgeと
        // 同じ判定を使う「対」になっているかの固定）
        PathResult annotated = PathSafetyChecker.annotate(cells, result);
        boolean warned = annotated.steps().stream()
                .anyMatch(step -> step.bridging() && step.risk() == PathRisk.VOID_BELOW);
        assertTrue(warned, "致死落差の上の橋に警告が付いていない（安全な橋と同じ色で描かれる）");
    }
}
