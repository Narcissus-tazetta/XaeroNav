package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathRisk;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * 「いま経路のどこにいるか」の対応づけ。
 *
 * <p>再計算の要否・案内表示・描画の切り詰めが同じ答えを使うための土台なので、ここがずれると
 * 「案内は次の角を出しているのに線は手前から描かれる」といった食い違いが一斉に出る。
 *
 * <p>とくに大事なのが、経路が自分自身の近くを通る地形（洞窟の折り返し階段）で遠くの区間へ
 * 飛び移らないこと。飛び移ると残り距離が突然変わり、案内が別の場所を指す。
 */
class PathProgressTest {

    private static final int Y = 60;

    private static PathResult path(List<BlockPos> positions) {
        List<PathStep> steps = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            steps.add(new PathStep(pos, MovementType.TRAVERSE, 4.0, List.of(), List.of(), PathRisk.NONE, null));
        }
        return new PathResult(steps, PathResult.Termination.REACHED_GOAL, positions.size(), positions.size());
    }

    /** ステップ{@code i}のマス中心に立ったときのプレイヤー座標。 */
    private static Vec3 standingOn(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    @Test
    void followsThePlayerAlongTheRoute() {
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        PathResult result = path(positions);

        // 先頭から順に歩く
        for (int i = 0; i < positions.size(); i++) {
            PathProgress.INSTANCE.update(result, standingOn(positions.get(i)));
            assertEquals(i, PathProgress.INSTANCE.indexFor(result), "ステップ" + i + "に対応づく");
            assertEquals(0.0, PathProgress.INSTANCE.distance(), 1.0e-9);
        }
    }

    @Test
    void aPlayerFarAheadIsFoundByTheFullScan() {
        // 窓（前方32ステップ）の外へ一気に飛んだ場合。テレポートやチャンク読み込み後の位置補正で起きる
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        PathResult result = path(positions);

        PathProgress.INSTANCE.update(result, standingOn(positions.get(80)));

        assertEquals(80, PathProgress.INSTANCE.indexFor(result), "窓の外なら全体を探し直す");
        assertEquals(0.0, PathProgress.INSTANCE.distance(), 1.0e-9);
    }

    @Test
    void doesNotJumpBackToAnEarlierLegThatPassesNearby() {
        // 往路（z=0）と復路（z=2）が2マス隣を並走する経路。復路を歩いているときに
        // 往路へ飛び移ると、残り距離が突然増えて案内が逆を向く
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        for (int i = 30; i >= 1; i--) {
            positions.add(new BlockPos(i, Y, 2));
        }
        PathResult result = path(positions);

        // 往路を歩き切ってから復路へ入る
        for (BlockPos pos : positions.subList(0, 45)) {
            PathProgress.INSTANCE.update(result, standingOn(pos));
        }

        int index = PathProgress.INSTANCE.indexFor(result);
        assertEquals(44, index);
        assertTrue(positions.get(index).getZ() == 2, "復路の側に留まる: " + positions.get(index));
    }

    @Test
    void anUnrelatedResultReportsTheStartOfTheRoute() {
        PathResult tracked = path(List.of(new BlockPos(1, Y, 0), new BlockPos(2, Y, 0)));
        PathResult other = path(List.of(new BlockPos(9, Y, 9), new BlockPos(10, Y, 9)));

        PathProgress.INSTANCE.update(tracked, standingOn(new BlockPos(2, Y, 0)));

        assertEquals(0, PathProgress.INSTANCE.indexFor(other),
                "対応づけていない経路については先頭を返す（描画が途中から始まらないように）");
    }

    @Test
    void measuresTheDistanceWithAndWithoutTheVerticalGap() {
        // 水面を泳いでいて経路が5マス下を通っている場面。縦を数えると既定の逸脱閾値(4)を
        // 超えるが、水の中では上下に自由に動けるので経路からは外れていない
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            positions.add(new BlockPos(i, Y, 0));
        }
        PathResult result = path(positions);

        PathProgress.INSTANCE.update(result, new Vec3(10.5, Y + 5.0, 0.5));

        assertEquals(5.0, PathProgress.INSTANCE.distance(), 1.0e-9);
        assertEquals(0.0, PathProgress.INSTANCE.horizontalDistance(), 1.0e-9);
    }

    @Test
    void anEmptyRouteClearsTheMapping() {
        PathResult result = path(List.of(new BlockPos(1, Y, 0)));
        PathProgress.INSTANCE.update(result, standingOn(new BlockPos(1, Y, 0)));

        PathProgress.INSTANCE.update(null, new Vec3(0, Y, 0));

        assertEquals(Double.MAX_VALUE, PathProgress.INSTANCE.distance(),
                "経路が無い間は「経路から限りなく遠い」＝再計算の対象として扱う");
        assertEquals(Double.MAX_VALUE, PathProgress.INSTANCE.horizontalDistance(),
                "水平で測る側も同じ（水中の逸脱判定がここを読む）");
    }
}
