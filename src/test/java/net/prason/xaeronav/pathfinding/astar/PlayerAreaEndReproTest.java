package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.async.PathfindingExecutor;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.TerrainFixture;
import org.junit.jupiter.api.Tag;
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
@Tag("slow")
class PlayerAreaEndReproTest {

    /** 実機ログの現在地。24339列の島（x700-877 z1118-1350）の北の突端。 */
    private static final BlockPos PLAYER = new BlockPos(769, 51, 1151);

    /** 実機の深い探索（{@code PathfindingState#DEEP_SEARCH_BUDGET_FACTOR}＝通常の6倍）。 */
    private static final SearchLimits DEEP = new SearchLimits(600_000, 15_000, 1.5);

    private static FakeCells terrain() throws IOException {
        // 実機の設定（run/config/xaeronav-client.toml）に合わせる。
        // プレイヤーはクリエイティブなので置ける・持ち物の予算は無制限
        return TerrainFixture.load("/end_player_area.txt.gz", bounds -> FakeCells.empty(bounds)
                .canPlaceBlocks(true)
                .maxBridgeRunBlocks(96)
                .maxVoidBridgeRunBlocks(96)
                .maxLavaBridgeRunBlocks(30)
                .maxFallDamagePoints(0)
                .avoidRiskyJumps(true));
    }

    private static String describe(PathResult r) {
        long bridges = r.steps().stream().filter(PathStep::bridging).count();
        return String.format("%s steps=%d 橋=%d 節点=%d",
                r.complete() ? "到達" : r.termination(), r.steps().size(), bridges, r.expandedNodes());
    }

    /** ユーザーが報告した島。ここへの経路は下の3本すべてが対象にする。 */
    private static final BlockPos NEAREST_ISLAND = new BlockPos(839, 0, 1081);

    private static BlockPos onGround(FakeCells terrain, BlockPos p) {
        return TerrainFixture.onGround(terrain, terrain.bounds(), p);
    }

    /**
     * 近隣の島へ、実機の深い探索と同じ条件で渡れること。
     *
     * <p>{@link #NEAREST_ISLAND}はここに含めない——下の実機相当の時間枠のテストが<b>より短い枠で
     * 同じ探索</b>を回すので、こちらに置くと同じ経路を2回払うだけになる。
     */
    @Test
    void crossesToTheNeighbouringIslands() throws Exception {
        FakeCells terrain = terrain();
        System.out.printf("%n=== 島渡り（始点=%s・実機の深い探索と同条件）===%n", PLAYER);
        // 東130 / 北東163ブロック。どちらも橋の上限96より長い奈落を挟む
        for (int[] t : new int[][] {{899, 1151}, {912, 1072}}) {
            BlockPos goal = onGround(terrain, new BlockPos(t[0], 0, t[1]));
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
     *
     * <p>{@link #NEAREST_ISLAND}へ橋を架けて渡っていることもここで見る（上の島渡りより枠が
     * 厳しいので、こちらが通れば深い枠でも通る）。
     */
    @Test
    void crossesWithinTheEffectiveRealDeviceTimeBudget() throws Exception {
        FakeCells terrain = terrain();
        BlockPos goal = onGround(terrain, NEAREST_ISLAND);
        SearchLimits tight = new SearchLimits(600_000, 4_800, 1.5);

        long began = System.currentTimeMillis();
        PathResult r = new PathfindingExecutor().submit(terrain, PLAYER, goal, tight, true, 0).get();
        System.out.printf("%n=== 実機相当の時間枠(4.8秒) ===%n  %s (%dms)%n",
                describe(r), System.currentTimeMillis() - began);
        assertTrue(r.complete(), "実機相当の時間では届かない: " + describe(r));
        assertTrue(r.steps().stream().anyMatch(PathStep::bridging),
                "橋を架けずに渡っている＝地形が対照になっていない: " + describe(r));
    }

    /**
     * <b>対照。</b>重みを上げなければ同じ予算で届かない——これが崩れると、
     * {@code retryGreedier}が無くても解ける地形を検証していることになり、テストが空振りする。
     */
    @Test
    void theSameSearchFailsWithoutRaisingTheWeight() throws Exception {
        FakeCells terrain = terrain();
        BlockPos goal = onGround(terrain, NEAREST_ISLAND);

        PathResult bare = new PathfindingExecutor().submitRaw(terrain, PLAYER, goal, DEEP).get();

        System.out.printf("%n=== 対照（重み1.5のまま・再挑戦なし）===%n  %s%n", describe(bare));
        assertTrue(!bare.complete(),
                "重み1.5のままでも届いてしまう＝この地形では retryGreedier の効果を確かめられない: "
                        + describe(bare));
    }
}
