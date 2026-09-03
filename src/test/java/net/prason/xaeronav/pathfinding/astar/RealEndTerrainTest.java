package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>実機のワールド保存データそのもの</b>で探索を再現する。
 *
 * <p>ジ・エンドの島渡りが「ルートが全然見つからない」まま4回の推測を外したので、
 * {@code run/saves/test/DIM1/region/r.2.2.mca}をパースして固体ブロックの列を書き出したものを
 * 読み込む（{@code src/test/resources/end_terrain_columns.txt.gz}）。実機ログに出ていた始点・
 * 中間目標をそのまま使うので、<b>実機で失敗している探索と同じ問題</b>を手元で回せる。
 *
 * <p><b>ここでしか測れないものだけを置く。</b>1ケース約8秒かかるので、合成の地形で決まる性質は
 * 置かない——梯子の段の順序は{@code CapStagesTest}、予算と{@code Carryover}の算術は
 * {@code BlockBudgetTest}が、どちらもミリ秒で見ている。残してあるのは<b>規模が大きいときにだけ
 * 現れる穴</b>：{@code PathNode.placedTotal}がノードの同一性に含まれない近似なので、前線が進むほど
 * 設置の枝が理由なく消える。幅4の合成の裂け目では前線が伸びず、構造的に再現できない。
 */
@Tag("slow")
class RealEndTerrainTest {

    /** 実機ログ(08:24)の失敗した探索の始点。 */
    private static final BlockPos START = new BlockPos(1233, 57, 1142);
    /** 同じログの区間1の目標（直行ルート）。 */
    private static final BlockPos DIRECT_GOAL = new BlockPos(1288, 57, 1080);

    /**
     * 予算が原因の穴を踏む値。{@code PathfindingExecutor#capStages}の実測では
     * <b>16〜40だけが60万ノードを焼いて6ステップで終わる</b>（42以上と8以下は到達する）。
     * 帯の端ではなく中央を採るのは、コストモデルが動いたときに帯から外れて<b>静かに空振り</b>に
     * ならないようにするため。
     */
    private static final int BUDGET_INSIDE_THE_BROKEN_BAND = 32;

    /** この地形を渡るのに要る橋の本数。 */
    private static final int BRIDGES_NEEDED = 43;

    private static FakeCells terrain(int maxBridgeRun, int placedBlockBudget) throws IOException {
        // 実機の条件に合わせる。落下ダメージの許容は体力満タンの1/3＝6ポイント
        // （0は「一切落ちない」で、低い島へ降りる経路が全部消える）
        return TerrainFixture.load("/end_terrain_columns.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(maxBridgeRun)
                .placedBlockBudget(placedBlockBudget)
                .maxFallDamagePoints(6));
    }

    private static PathResult search(int cap, int nodes, int placedBlockBudget, Carryover carried)
            throws IOException {
        SearchLimits limits = new SearchLimits(nodes, 30_000, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        try {
            return new net.prason.xaeronav.pathfinding.async.PathfindingExecutor()
                    .submit(terrain(cap, placedBlockBudget), START, DIRECT_GOAL, limits, true, 0, carried)
                    .get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * <b>実機の既定予算(100,000ノード)でこの地形の奈落を渡れること。</b>実測78,143ノードで、
     * <b>余裕は薄い</b>——75,000では届かない。ここが「届かない」側へ落ちると、実機では
     * {@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}の6倍へエスカレーションするまで
     * 案内が出ない（島渡りだけ数秒待たされる、という形で出る）。
     *
     * <p>余裕がこの幅しか無いのは、奈落の上の橋が<b>他の手より安いわけではない</b>ため。
     * 走行を中断する割増（{@code ActionCosts#TERRAIN_EDIT_INTERRUPTION_TICKS}）が柱と浅い橋を
     * 展開の枝から外すぶんだけ余裕が生まれている（実測127,163→78,143ノード）。
     *
     * <p>橋の上限96で解けることもここで見る（別テストに分けると同じ探索をもう一度払う）。
     */
    @Test
    void crossesTheVoidWithinTheDefaultBudget() throws IOException {
        assertFalse(search(96, 75_000, 0, Carryover.NONE).complete(),
                "75,000で届く＝余裕がこの想定より広い。閾値を測り直すこと");

        PathResult normal = search(96, 100_000, 0, Carryover.NONE);
        assertTrue(normal.complete(), "既定の予算で渡れるはず: " + normal.termination());
        assertTrue(longestBridgeRun(normal) > 30,
                "上限30を超える橋が要る地形（だから上限も上げてある）: " + longestBridgeRun(normal));
    }

    /**
     * <b>効いているのは橋の上限ではなく予算だ</b>ということの固定。上限を旧既定の30まで
     * 締めても、緩和の梯子が開いて同じように解ける——上限をいじって直そうとすると空振りする。
     * 実際にこのセッションで一度その回り道をした。
     *
     * <p><b>所要時間の比較はしない。</b>かつては「上限30だと最初の探索が丸ごと無駄になり倍以上
     * 掛かる」を壁時計で固定していたが、{@code PathfindingExecutor#FIRST_PASS_PERCENT}で
     * 最初の探索の取り分を絞ってからは差が消えた（実測 4129ms vs 4048ms）。壁時計の比は
     * マシンの混み具合でも揺れる。
     *
     * <p>既定を96にしてある根拠は所要時間ではなく<b>実測の奈落の幅</b>（保存データで47〜81ブロック）。
     */
    @Test
    void theStrictBridgeCapStillSolvesItThroughTheLooseningLadder() throws IOException {
        assertTrue(search(30, 600_000, 0, Carryover.NONE).complete(),
                "上限30でも緩和の梯子が開いて解けるはず");
    }

    /**
     * <b>持ち物のブロックが足りなくても島渡りは案内する。</b>
     *
     * <p>この地形は橋が{@link #BRIDGES_NEEDED}本要る。それ未満に絞ると、<b>中間の帯だけが
     * 60万ノードを焼いて6ステップで終わっていた</b>——前線が進むほど設置の枝が理由なく消え、
     * 探索が橋以外の道を探し続ける。少ない側で通るのは橋が即座に切られて探索が橋を諦めるからで、
     * <b>「少なくすれば安全」ではない</b>のがこの穴の質の悪いところ。
     *
     * <p>直したのは{@code PathfindingExecutor#capStages}——予算が原因のときは、他の上限より
     * 先に予算を外す段を積む。実機ユーザー報告「エンドの島渡りだけできない」の正体。
     */
    @Test
    void crossesTheIslandsEvenWhenBlocksRunShort() throws IOException {
        PathResult result = search(96, 600_000, BUDGET_INSIDE_THE_BROKEN_BAND, Carryover.NONE);

        assertTrue(result.complete(),
                "予算" + BUDGET_INSIDE_THE_BROKEN_BAND + "で島渡りが出なくなった: "
                        + result.termination() + " steps=" + result.steps().size());
    }

    /**
     * <b>区間をまたいで予算を絞っても島渡りは案内する。</b>
     *
     * <p>手前の区間が使うぶんを引き継ぐようにした以上（{@link Carryover}）、上の穴には
     * <b>予算そのものを絞らなくても入りうる</b>——満額の{@link #BRIDGES_NEEDED}でも、手前が20使って
     * いれば残りは23で帯のど真ん中に落ちる。予算を真っ先に外す段は引き継ぎの有無に関わらず効くこと。
     */
    @Test
    void crossesTheIslandsWhenEarlierSegmentsAlreadySpentTheBudget() throws IOException {
        PathResult result = search(96, 600_000, BRIDGES_NEEDED, new Carryover(0, 20));

        assertTrue(result.complete(),
                "手前の区間が20個使った状態で島渡りが出なくなった: " + result.termination()
                        + " steps=" + result.steps().size());
    }

    private static int longestBridgeRun(PathResult result) {
        int longest = 0;
        int run = 0;
        for (PathStep step : result.steps()) {
            run = step.bridging() ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
