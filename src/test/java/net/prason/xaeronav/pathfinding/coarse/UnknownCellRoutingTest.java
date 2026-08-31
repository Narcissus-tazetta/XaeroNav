package net.prason.xaeronav.pathfinding.coarse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * <b>「地図に無いセル」は層1にとって最安クラスの通り道になっている。</b>
 *
 * <p>{@link CoarseRouter}の倍率は{@code NO_DATA}=1.6、陸=1.0、奈落={@code VOID_BRIDGE_MULTIPLIER}
 * （≒10）。つまり<b>未知のセルは「床が無いと分かっているセル」より約6倍安い</b>。未知を通行可能に
 * しておくこと自体は意図的な設計（そうしないと未探索の方角へ一切ルートが出ない）だが、
 * <b>ジ・エンドでは「まだ地図に無い」の実体はほぼ奈落</b>なので、層1が未知の帯をまっすぐ突っ切る
 * 中間目標を並べうる。
 *
 * <p><b>ただし「部分的な地図のせいで経路が変わる」は実機データで否定済み（2026-08-29）。</b>
 * 実機ジ・エンドの地形で読めている半径を4チャンクまで削っても、経路は完全な地図のときと同一
 * だった——未知が一様に安いので、部分的な地図でも最短の帯を選ぶ結果が変わらない。だから実機
 * 地形での確認は置かず、<b>倍率の大小関係そのもの</b>だけを固定する。
 */
class UnknownCellRoutingTest {

    /**
     * 未知の帯と奈落の帯を並べて、層1がどちらを通るかで倍率の大小を確かめる。ここが逆転すれば、
     * 部分的な地図でも奈落を避けた経路が出るようになっている。
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
}
