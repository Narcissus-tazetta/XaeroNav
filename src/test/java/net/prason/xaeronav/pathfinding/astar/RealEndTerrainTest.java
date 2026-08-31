package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>実機のワールド保存データそのもの</b>で探索を再現する。
 *
 * <p>ジ・エンドの島渡りが「ルートが全然見つからない」まま4回の推測を外したので、
 * {@code run/saves/test/DIM1/region/r.2.2.mca}をパースして固体ブロックの列を書き出したものを
 * 読み込む（{@code src/test/resources/end_terrain_columns.txt}）。実機ログに出ていた始点・
 * 中間目標をそのまま使うので、<b>実機で失敗している探索と同じ問題</b>を手元で回せる。
 *
 * <p>エンドストーンは掘れるので{@link FakeCells#STONE}で表す。ここで測りたいのは
 * 「この地形でその予算・上限なら解けるのか」であって、ブロックの種類の再現度ではない。
 */
class RealEndTerrainTest {

    /** 実機ログ(08:24)の失敗した探索の始点。 */
    private static final BlockPos START = new BlockPos(1233, 57, 1142);
    /** 同じログの区間1の目標（直行ルート）。 */
    private static final BlockPos DIRECT_GOAL = new BlockPos(1288, 57, 1080);
    /** 成功した回が経由した東の飛び石。 */
    private static final BlockPos STEPPING_STONE = new BlockPos(1288, 63, 1144);

    private static FakeCells terrain(int maxBridgeRun) throws IOException {
        return terrain(maxBridgeRun, 0);
    }

    /** {@code placedBlockBudget}は0で無制限（持ち物のブロック数を見ない従来の挙動）。 */
    private static FakeCells terrain(int maxBridgeRun, int placedBlockBudget) throws IOException {
        try (InputStream in = RealEndTerrainTest.class.getResourceAsStream("/end_terrain_columns.txt.gz")) {
            assertNotNull(in, "地形データが見つからない");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] header = reader.readLine().trim().split(" ");
            SearchBounds bounds = new SearchBounds(
                    Integer.parseInt(header[0]), Integer.parseInt(header[1]), Integer.parseInt(header[2]),
                    Integer.parseInt(header[3]), Integer.parseInt(header[4]), Integer.parseInt(header[5]));
            // 実機の条件に合わせる。落下ダメージの許容は体力満タンの1/3＝6ポイント
            // （FakeCellsの既定0は「一切落ちない」で、低い島へ降りる経路が全部消える）
            FakeCells cells = FakeCells.empty(bounds)
                    .canPlaceBlocks(true)
                    .maxBridgeRunBlocks(maxBridgeRun)
                    .placedBlockBudget(placedBlockBudget)
                    .maxFallDamagePoints(6);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(" ");
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[1]);
                for (int i = 2; i < parts.length; i++) {
                    int comma = parts[i].indexOf(',');
                    int from = Integer.parseInt(parts[i].substring(0, comma));
                    int to = Integer.parseInt(parts[i].substring(comma + 1));
                    for (int y = from; y <= to; y++) {
                        cells.set(x, y, z, FakeCells.STONE);
                    }
                }
            }
            return cells;
        }
    }

    /**
     * <b>これが直っていることの本体。</b>実機の既定予算(100,000ノード)ではこの地形の奈落を
     * 渡る経路は出ず、6倍(600,000)なら出る——{@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}が
     * その6倍で、通常の予算で解けなかった場所にだけ掛かる。
     */
    @Test
    void crossesTheVoidOnlyWithTheDeepBudget() throws IOException {
        PathResult normal = search(START, DIRECT_GOAL, 96, 100_000, 30_000);
        assertFalse(normal.complete(),
                "既定の予算では渡れない（この前提が崩れたら深い予算は不要になっている）: " + normal.termination());

        PathResult deep = search(START, DIRECT_GOAL, 96, 100_000 * 6, 30_000);
        assertTrue(deep.complete(), "6倍の予算なら渡れるはず: " + deep.termination());
        assertTrue(longestBridgeRun(deep) > 30,
                "上限30を超える橋が要る地形（だから上限も上げてある）: " + longestBridgeRun(deep));
    }

    /**
     * <b>効いているのは橋の上限ではなく予算だ</b>ということの固定。
     *
     * <p>上限を30(旧既定)にしても96にしても無制限にしても、必要な展開ノード数は51〜53万で
     * ほとんど変わらない（上限で詰んだら緩和の梯子が開けるため）。上限をいじって直そうとすると
     * ここで空振りする——実際にこのセッションで一度その回り道をした。
     *
     * <p><b>所要時間の比較はしない。</b>かつては「上限30だと最初の探索が丸ごと無駄になり倍以上
     * 掛かる」を壁時計で固定していたが、{@code PathfindingExecutor#FIRST_PASS_PERCENT}で
     * 最初の探索の取り分を絞ってからは差が消えた（実測 4129ms vs 4048ms）——届かない探索が
     * 早く諦めるようになったので、上限がきついこと自体の損が小さい。壁時計の比はマシンの
     * 混み具合でも揺れるので、ここでは「どちらでも解ける」だけを見る。
     *
     * <p>既定を96にしてある根拠は所要時間ではなく<b>実測の奈落の幅</b>（保存データで47〜81ブロック）。
     */
    @Test
    void bothBridgeCapsFindThePathThroughTheLooseningLadder() throws IOException {
        assertTrue(search(START, DIRECT_GOAL, 30, 600_000, 30_000).complete(),
                "上限30でも緩和の梯子が開いて解けるはず");
        assertTrue(search(START, DIRECT_GOAL, 96, 600_000, 30_000).complete(),
                "上限96で解けるはず");
    }

    private static PathResult search(BlockPos start, BlockPos goal, int cap, int nodes, long millis)
            throws IOException {
        return search(start, goal, cap, nodes, millis, 0);
    }

    private static PathResult search(BlockPos start, BlockPos goal, int cap, int nodes, long millis,
                                      int placedBlockBudget) throws IOException {
        return search(start, goal, cap, nodes, millis, placedBlockBudget, Carryover.NONE);
    }

    private static PathResult search(BlockPos start, BlockPos goal, int cap, int nodes, long millis,
                                      int placedBlockBudget, Carryover carried) throws IOException {
        FakeCells cells = terrain(cap, placedBlockBudget);
        SearchLimits limits = new SearchLimits(nodes, millis, AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);
        try {
            return new net.prason.xaeronav.pathfinding.async.PathfindingExecutor()
                    .submit(cells, start, goal, limits, true, 0, carried).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * <b>持ち物のブロックが足りなくても島渡りは案内する。</b>
     *
     * <p>この地形は橋が43本要る。予算をそれ未満に絞ると、<b>中間の帯（16〜40）だけが
     * 60万ノードを焼いて6ステップで終わっていた</b>——{@code PathNode.placedTotal}はノードの
     * 同一性に含まれない近似なので、前線が進むほど設置の枝が理由なく消え、探索が橋以外の道を
     * 探し続ける。少ない側（8以下）で通るのは橋が即座に切られて探索が橋を諦めるからで、
     * <b>「少なくすれば安全」ではない</b>のがこの穴の質の悪いところ。
     *
     * <p>直したのは{@code PathfindingExecutor#capStages}——予算が原因のときは、他の上限より
     * 先に予算を外す段を積む。実機ユーザー報告「エンドの島渡りだけできない」の正体。
     */
    @Test
    void crossesTheIslandsEvenWhenBlocksRunShort() throws IOException {
        for (int budget : new int[] {43, 40, 32, 16, 8}) {
            PathResult result = search(START, DIRECT_GOAL, 96, 600_000, 30_000, budget);
            assertTrue(result.complete(),
                    "予算" + budget + "で島渡りが出なくなった: " + result.termination()
                            + " steps=" + result.steps().size());
        }
    }

    /**
     * <b>区間をまたいで予算を絞っても島渡りは案内する。</b>
     *
     * <p>手前の区間が使うぶんを引き継ぐようにした以上（{@link Carryover}）、上の
     * 「中間の帯だけが壊れる」穴には<b>予算そのものを絞らなくても入りうる</b>——予算43でも、
     * 手前が20使っていれば残りは23で帯のど真ん中に落ちる。緩和の梯子が予算を真っ先に外すのは
     * 引き継ぎの有無に関わらず効かなければならない。
     */
    @Test
    void crossesTheIslandsWhenEarlierSegmentsAlreadySpentTheBudget() throws IOException {
        // 20は残り23＝壊れる帯のど真ん中、43は残り0＝最初の1本から設置が全部消える状態
        for (int carried : new int[] {20, 43}) {
            PathResult result = search(START, DIRECT_GOAL, 96, 600_000, 30_000, 43,
                    new Carryover(0, carried));
            assertTrue(result.complete(),
                    "手前の区間が" + carried + "個使った状態で島渡りが出なくなった: " + result.termination()
                            + " steps=" + result.steps().size());
        }
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
