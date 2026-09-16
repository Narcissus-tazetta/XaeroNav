package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * <b>「地図に無いセル」は層1にとって最安クラスの通り道になっている。</b>
 *
 * <p>{@link CoarseRouter}の{@code NO_DATA}の値段は<b>固定ではない</b>——既知セルの陸:奈落比から
 * その場で較正される（{@code CoarseRouter#calibratedUnknownMultiplier}）。ただし<b>下限は1.6・
 * 陸=1.0・奈落={@code VOID_BRIDGE_MULTIPLIER}（≒10）</b>で、既知セルが少ない・既知の大半が陸、
 * のどちらかなら較正は1.6付近に留まる。この下限を割ることはない——「分からないことは高くつく
 * 理由にならない」という元の設計意図は崩していない。
 *
 * <p>未知を通行可能にしておくこと自体は意図的な設計（そうしないと未探索の方角へ一切ルートが
 * 出ない）だが、<b>ジ・エンドでは「まだ地図に無い」の実体はほぼ奈落</b>——実機地形3件の実測
 * （{@code EndUnknownVoidRatioBenchTest}）で既知セルの奈落比は35〜53%——なので、既知の奈落比が
 * 高いときに較正が1.6より上がることを{@link #prefersTheKnownDetourWhenTheKnownAreaIsMostlyVoid}が
 * 固定している。
 *
 * <p><b>「部分的な地図のせいで経路が変わる」は実機データで否定済み（2026-08-29）。</b>
 * 実機ジ・エンドの地形で読めている半径を4チャンクまで削っても、経路は完全な地図のときと同一
 * だった——未知が（較正後の値であっても）一様に安いので、部分的な地図でも最短の帯を選ぶ結果が
 * 変わらない。だから実機地形での確認は置かず、<b>倍率の大小関係そのもの</b>だけを固定する。
 */
class UnknownCellRoutingTest {

    /**
     * 未知の帯と奈落の帯を並べて、層1がどちらを通るかで倍率の大小を確かめる。ここが逆転すれば、
     * 部分的な地図でも奈落を避けた経路が出るようになっている。
     *
     * <p>この地形は既知の大半（未知の帯を除く全体）が陸なので、既知の奈落比は低く、較正は
     * 下限の1.6のまま動かない——較正の影響を受けない前提でのテスト。
     */
    @Test
    void unknownCellsAreFarCheaperThanKnownVoid() {
        int radius = 20;
        CoarseMapBuilder builder = new CoarseMapBuilder(-radius, -radius, radius * 2, radius * 2);
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                // x∈[4,8] の帯: z<0 は奈落（床が無いと分かっている）、z>=0 は未知（書かない）
                if (x >= 4 && x <= 8) {
                    if (z < 0) {
                        builder.putFloor(x, z, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                                CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
                    }
                    continue;
                }
                builder.putFloor(x, z, CoarseMap.LAND, 64);
            }
        }
        CoarseMap map = builder.build();

        // 始点・目的地とも z=-8（奈落の帯の正面）。未知の帯(z>=0)へ迂回するかどうか
        BlockPos start = new BlockPos(0 * 16 + 8, 64, -8 * 16 + 8);
        BlockPos goal = new BlockPos(12 * 16 + 8, 64, -8 * 16 + 8);
        CoarseRouter.Route route = CoarseRouter.findRoute(map, start, goal, false,
                CoarseRouter.BridgePolicy.ALLOW);

        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(Integer.MIN_VALUE);
        assertTrue(route.reachedGoal());
        assertTrue(maxZ >= 0,
                "未知の帯へ迂回しなかった＝未知が奈落より安いという前提が崩れている: " + route.waypoints());
    }

    /**
     * <b>既知の大半が奈落なら、未知セルの値段も奈落寄りに較正され、遠回りでも既知の陸を選ぶ。</b>
     *
     * <p>地形: 始点(0,0)→目的地(10,0)の間、x=1〜9・z=0は<b>未知のまま</b>（直進すれば9セルの
     * 未知越え）。一方、z=6を通る「コの字」の迂回路（x=0の列で北上→z=6を東進→x=10の列で南下、
     * 計22セル）は全部<b>既知の陸</b>。これとは別に、経路から離れた場所（x=-20〜-11の帯）へ
     * 既知の奈落を大量に置いて「既知セルの大半が奈落」というサンプルを作る。
     *
     * <p>較正なし（固定1.6倍）なら直進(9×1.6+1≒15.4)が迂回(22)より安いので直進が勝つ——
     * これが{@link #takesTheDirectCrossingWhenTheKnownAreaIsMostlyLand}の対照。既知の奈落比が
     * 高いここでは、較正後の倍率（この地形では8倍超）が直進を22超まで押し上げ、迂回が逆転して勝つ。
     */
    @Test
    void prefersTheKnownDetourWhenTheKnownAreaIsMostlyVoid() {
        CoarseRouter.Route route = detourVersusFogRoute(true);

        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(Integer.MIN_VALUE);
        assertTrue(route.reachedGoal());
        assertTrue(maxZ >= 5 * 16,
                "既知が奈落だらけなのに未知越えを直進した＝較正が効いていない: " + route.waypoints());
    }

    /**
     * <b>対照。</b>迂回路の外側（経路から離れた場所）を陸にすると、既知の奈落比が低いままなので
     * 較正は下限の1.6で止まり、{@link #prefersTheKnownDetourWhenTheKnownAreaIsMostlyVoid}と同じ
     * 迂回路があっても直進（未知越え）の方が安いままになる。これが無いと「そもそも迂回路がある
     * 地形では常に迂回する」地形と区別が付かない。
     */
    @Test
    void takesTheDirectCrossingWhenTheKnownAreaIsMostlyLand() {
        CoarseRouter.Route route = detourVersusFogRoute(false);

        int maxZ = route.waypoints().stream().mapToInt(BlockPos::getZ).max().orElse(Integer.MIN_VALUE);
        assertTrue(route.reachedGoal());
        assertTrue(maxZ < 5 * 16,
                "既知が陸だらけなのに未知越えを避けた＝較正が効きすぎて1.6の下限を割っている: "
                        + route.waypoints());
    }

    /**
     * 始点(0,0)から目的地(10,0)まで、x=1〜9・z=0を未知のまま残し、z=6経由の「コの字」の
     * 既知陸の迂回路を用意する。{@code voidBackground}が{@code true}なら、経路から離れた
     * x=-20〜-11の帯を既知の奈落で埋めて既知セルの奈落比を上げる（{@code false}なら陸で埋める）。
     */
    private static CoarseRouter.Route detourVersusFogRoute(boolean voidBackground) {
        int radius = 20;
        CoarseMapBuilder builder = new CoarseMapBuilder(-radius, -radius, radius * 2, radius * 2);

        // 始点・目的地と、その間の未知の帯(x=1〜9, z=0)には触れない
        builder.putFloor(0, 0, CoarseMap.LAND, 64);
        builder.putFloor(10, 0, CoarseMap.LAND, 64);

        // 迂回路（コの字、全部既知の陸）
        for (int z = 0; z <= 6; z++) {
            builder.putFloor(0, z, CoarseMap.LAND, 64);
            builder.putFloor(10, z, CoarseMap.LAND, 64);
        }
        for (int x = 0; x <= 10; x++) {
            builder.putFloor(x, 6, CoarseMap.LAND, 64);
        }

        // 較正用の背景（経路から離れた帯）
        for (int x = -radius; x <= -11; x++) {
            for (int z = -radius; z < radius; z++) {
                if (voidBackground) {
                    builder.putFloor(x, z, CoarseMap.VOID, CoarseMap.UNKNOWN_HEIGHT,
                            CoarseMap.UNKNOWN_HEIGHT, CoarseMap.UNKNOWN_HEIGHT);
                } else {
                    builder.putFloor(x, z, CoarseMap.LAND, 64);
                }
            }
        }

        CoarseMap map = builder.build();
        BlockPos start = new BlockPos(0 * 16 + 8, 64, 0 * 16 + 8);
        BlockPos goal = new BlockPos(10 * 16 + 8, 64, 0 * 16 + 8);
        return CoarseRouter.findRoute(map, start, goal, false, CoarseRouter.BridgePolicy.ALLOW);
    }
}
