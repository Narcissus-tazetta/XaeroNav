package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * <b>ユーザーが「エンドの島渡りだけできない」と報告した地点そのもの</b>を保存データから取り込んで、
 * 実機と同じ探索を回す（{@code run/saves/test/DIM1/region/r.1.2.mca}、x700-1000 z1050-1350）。
 *
 * <p><b>症状</b>: 長距離ルートは出ている（実機ログ「奈落・溶岩混じりを避ける道が見つからないため…」）
 * のに、層3の経路が<b>1〜4ステップ・橋0本</b>しか出ず「終端に到着」を0.05秒ごとに繰り返していた。
 *
 * <p><b>原因</b>: プレイヤーは24339列の巨大な島の突端におり、最寄りの他の島は
 * 北東99・東130ブロック。<b>島の上ではヒューリスティックがほぼ一定になる</b>——どこにいても
 * ゴールは奈落の向こうで、残りの見積もりは「縁までの距離＋橋の値段」だから差が付きにくい。
 * 重み1.5の探索はそこで幅優先に近くなり、橋に手を伸ばす前に島を舐め尽くして予算が尽きる。
 * 橋1マスは徒歩10マス相当なので、100マスの奈落に届くには徒歩1000マス分の陸地を先に展開し終える
 * 必要がある。
 *
 * <p><b>測定</b>（北東99ブロックの島 839,57,1081 へ、予算60万ノード）:
 *
 * <pre>
 * 重み1.5 ガイドあり → 未到達      重み2.5 ガイドあり → 到達（19.8万）
 * 重み2.0 ガイドあり → 未到達      重み3.0 ガイドあり → 到達（10.7万）
 * 重み1.5〜3.0 ガイド<b>なし</b> → すべて未到達
 * </pre>
 *
 * <p><b>cost-to-goガイドが無いとどの重みでも解けない。</b>島の縁へ導いているのはガイドの方で、
 * 重みはそれを信じる度合いを上げているだけ。直したのは
 * {@code PathfindingExecutor#retryGreedier}（予算を焼き切って届かなければ重みを上げて再挑戦）。
 *
 * <p>ここが落ちたら症状が再発している。
 */
class PlayerAreaEndReproTest {

    /** 実機ログの現在地。24339列の島（x700-877 z1118-1350）の北の突端。 */
    private static final BlockPos PLAYER = new BlockPos(769, 51, 1151);

    /** 実機の深い探索（{@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}＝通常の6倍）。 */
    private static final SearchLimits DEEP = new SearchLimits(600_000, 15_000, 1.5);

    private static FakeCells terrain() throws IOException {
        try (InputStream in = PlayerAreaEndReproTest.class.getResourceAsStream("/end_player_area.txt.gz")) {
            assertNotNull(in, "地形データが見つからない");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String[] h = reader.readLine().trim().split(" ");
            SearchBounds bounds = new SearchBounds(
                    Integer.parseInt(h[0]), Integer.parseInt(h[1]), Integer.parseInt(h[2]),
                    Integer.parseInt(h[3]), Integer.parseInt(h[4]), Integer.parseInt(h[5]));
            // 実機の設定（run/config/xaeronav-client.toml）に合わせる。
            // プレイヤーはクリエイティブなので置ける・持ち物の予算は無制限
            FakeCells cells = FakeCells.empty(bounds)
                    .canPlaceBlocks(true)
                    .maxBridgeRunBlocks(96)
                    .maxVoidBridgeRunBlocks(96)
                    .maxLavaBridgeRunBlocks(30)
                    .maxFallDamagePoints(0)
                    .avoidRiskyJumps(true);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split(" ");
                int x = Integer.parseInt(p[0]);
                int z = Integer.parseInt(p[1]);
                for (int i = 2; i < p.length; i++) {
                    int comma = p[i].indexOf(',');
                    int from = Integer.parseInt(p[i].substring(0, comma));
                    int to = Integer.parseInt(p[i].substring(comma + 1));
                    for (int y = from; y <= to; y++) {
                        cells.set(x, y, z, FakeCells.STONE);
                    }
                }
            }
            return cells;
        }
    }

    private static int standableY(CellSource cells, SearchBounds b, int x, int z) {
        for (int y = b.maxY() - 1; y > b.minY(); y--) {
            if (CellData.standable(cells.cell(x, y - 1, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y, z))
                    && CellData.occupiableWithoutDigging(cells.cell(x, y + 1, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static String describe(PathResult r) {
        long bridges = r.steps().stream().filter(PathStep::bridging).count();
        return String.format("%s steps=%d 橋=%d 節点=%d",
                r.complete() ? "到達" : r.termination(), r.steps().size(), bridges, r.expandedNodes());
    }

    /** 近隣3つの島へ、実機の深い探索と同じ条件で渡れること。 */
    @Test
    void crossesToTheNeighbouringIslands() throws Exception {
        FakeCells reference = terrain();
        SearchBounds b = reference.bounds();
        System.out.printf("%n=== 島渡り（始点=%s・実機の深い探索と同条件）===%n", PLAYER);
        // 北東99 / 東130 / 北東163ブロック。どれも橋の上限96より長い奈落を挟む
        for (int[] t : new int[][] {{839, 1081}, {899, 1151}, {912, 1072}}) {
            int y = standableY(reference, b, t[0], t[1]);
            assertTrue(y != Integer.MIN_VALUE, t[0] + "," + t[1] + " に立てる場所が無い＝地形データが違う");
            BlockPos goal = new BlockPos(t[0], y, t[1]);
            double dist = Math.hypot(goal.getX() - PLAYER.getX(), goal.getZ() - PLAYER.getZ());
            PathResult r = new PathfindingExecutor().submit(terrain(), PLAYER, goal, DEEP, true, 0).get();
            System.out.printf("  %-12s 距離%-5.0f %s%n", goal.getX() + "," + goal.getZ(), dist, describe(r));
            assertTrue(r.complete(), goal + " へ届かない: " + describe(r));
            assertTrue(r.steps().stream().anyMatch(PathStep::bridging),
                    goal + " へ橋を架けずに渡っている＝地形が対照になっていない");
        }
    }

    /**
     * <b>実機の速度を織り込んでも届くこと。</b>実機は測定環境より2〜3倍遅い
     * （[[xaeronav-realdevice-debug-loop]]の実測）ので、深い探索の枠12秒は実効5秒程度に相当する。
     * ここが落ちるなら、オフラインで解けても実機では時間切れになる。
     */
    @Test
    void crossesWithinTheEffectiveRealDeviceTimeBudget() throws Exception {
        FakeCells reference = terrain();
        SearchBounds b = reference.bounds();
        int y = standableY(reference, b, 839, 1081);
        BlockPos goal = new BlockPos(839, y, 1081);
        SearchLimits tight = new SearchLimits(600_000, 4_800, 1.5);

        long began = System.currentTimeMillis();
        PathResult r = new PathfindingExecutor().submit(terrain(), PLAYER, goal, tight, true, 0).get();
        System.out.printf("%n=== 実機相当の時間枠(4.8秒) ===%n  %s (%dms)%n",
                describe(r), System.currentTimeMillis() - began);
        assertTrue(r.complete(), "実機相当の時間では届かない: " + describe(r));
    }

    /**
     * <b>対照。</b>重みを上げなければ同じ予算で届かない——これが崩れると、
     * {@code retryGreedier}が無くても解ける地形を検証していることになり、テストが空振りする。
     */
    @Test
    void theSameSearchFailsWithoutRaisingTheWeight() throws Exception {
        FakeCells reference = terrain();
        SearchBounds b = reference.bounds();
        int y = standableY(reference, b, 839, 1081);
        BlockPos goal = new BlockPos(839, y, 1081);

        PathResult bare = new PathfindingExecutor().submitRaw(terrain(), PLAYER, goal, DEEP).get();

        System.out.printf("%n=== 対照（重み1.5のまま・再挑戦なし）===%n  %s%n", describe(bare));
        assertTrue(!bare.complete(),
                "重み1.5のままでも届いてしまう＝この地形では retryGreedier の効果を確かめられない: "
                        + describe(bare));
    }
}
