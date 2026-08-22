package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;

/**
 * Xaeroの世界地図・ミニマップへ経路を描くときの「何をどの色で置くか」。
 *
 * <p>2つの地図は描画先（{@code VertexConsumer}と塗りつぶしヘルパー）と地図座標への変換だけが違い、
 * 経路の取り出し方・描く順序・色はまったく同じになる。ここに1本化しておかないと、経路の種類を
 * 増やしたときに片方だけ追従して「世界地図には出るのにミニマップには出ない」が起きる。
 *
 * <p>切り取り（ミニマップのFBOに載らない遠方を捨てる）は{@link DotSink}側の仕事にする。
 * 何を切るかは地図座標の都合なので、変換を持っている呼び出し側に置くのが筋が通る。
 */
public final class MapPathOverlay {

    /** 1ブロック四方の点を1つ置く。座標はブロック座標で、地図座標への変換は実装側が行う。 */
    @FunctionalInterface
    public interface DotSink {
        void dot(int blockX, int blockZ, float red, float green, float blue);
    }

    private MapPathOverlay() {
    }

    /**
     * その時点で描くべきものを1つに固めたもの。経路はワーカースレッドがいつでも差し替えるので、
     * 「何かあるか」の判定と実際の描画が別々に読むと、あると判断した経路が描く頃には消えている。
     */
    public record Snapshot(PathResult ground, BlockPos goal, BlockPos playerPos,
                            List<BlockPos> coarseWaypoints, List<Vec3> flightRoute, int flightRouteFrom, List<Vec3> flightDash) {

        public boolean isEmpty() {
            return ground == null && goal == null && coarseWaypoints.isEmpty() && flightRoute.isEmpty();
        }
    }

    /**
     * いま描くべきものを1度だけ読み取る。描くものが何も無ければ{@link Snapshot#isEmpty()}がtrueになり、
     * 呼び出し側は{@code VertexConsumer}の取得自体を省ける。
     */
    public static Snapshot snapshot() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new Snapshot(null, null, null, List.of(), List.of(), 0, List.of());
        }
        return PathfindingState.INSTANCE.mapOverlaySnapshot(player.blockPosition());
    }

    public static void draw(Snapshot snapshot, DotSink sink) {
        MapDots dots = snapshot.ground() == null ? null : MapDots.forPath(snapshot.ground());
        if (dots != null) {
            // 通り過ぎた区間は描かない（PathRenderer.renderGroundPathと同じ理由）。継ぎ足しで
            // 伸ばした経路は逸脱・到着まで引き直されないので、これが無いと歩いた跡がそのまま
            // 地図に残り続け、線が際限なく増えていくように見える
            int first = dots.firstDotFrom(PathProgress.INSTANCE.indexFor(snapshot.ground()));
            for (int i = first; i < dots.count; i++) {
                sink.dot(dots.x[i], dots.z[i],
                        dots.color[i * 3], dots.color[i * 3 + 1], dots.color[i * 3 + 2]);
            }
        }

        // 長距離ルートの中間目標を先に結んでおく。目的地までの点線（下）は、この続きから引くことで
        // 「粗いルートに沿った点線」と「目的地への直線」が同時に、しかも食い違う向きに出るのを避ける
        // （中間目標無しに目的地まで一直線で結ぶと、粗いルートが迂回した山や海を平然と突っ切って見える）
        //
        // 始点は中間目標そのものではなく詳細経路の末端（無ければプレイヤー）。中間目標だけを結ぶと、
        // 経路から外れたとき点線が現在地から切り離されて宙に浮き、古いルートが残っているように見える
        List<BlockPos> coarseWaypoints = snapshot.coarseWaypoints();
        BlockPos lastCoarseWaypoint = coarseWaypoints.isEmpty() ? null : coarseWaypoints.get(coarseWaypoints.size() - 1);
        if (!coarseWaypoints.isEmpty()) {
            int previousX;
            int previousZ;
            if (dots != null && dots.count > 0) {
                previousX = dots.x[dots.count - 1];
                previousZ = dots.z[dots.count - 1];
            } else {
                previousX = snapshot.playerPos().getX();
                previousZ = snapshot.playerPos().getZ();
            }
            for (int i = firstAheadWaypoint(coarseWaypoints, previousX, previousZ); i < coarseWaypoints.size(); i++) {
                BlockPos next = coarseWaypoints.get(i);
                StraightDots.forEach(previousX, previousZ, next.getX(), next.getZ(),
                        (x, z) -> sink.dot(x, z,
                                PathColors.COARSE_ROUTE[0], PathColors.COARSE_ROUTE[1], PathColors.COARSE_ROUTE[2]));
                previousX = next.getX();
                previousZ = next.getZ();
            }
        }

        // 空中経路。地図は平面なので高度は表現できないが、「どちらへ回り込むのか」は出る。
        // ワールド内の太線と同じ色にして、点線（方角だけの線）と区別する
        List<Vec3> flightRoute = snapshot.flightRoute();
        Vec3 flightTail = flightRoute.isEmpty() ? null : flightRoute.get(flightRoute.size() - 1);
        if (!flightRoute.isEmpty()) {
            int previousX = snapshot.playerPos().getX();
            int previousZ = snapshot.playerPos().getZ();
            // 通り過ぎた区間は描かない（ワールド内のrenderFlightRouteと同じ添字を使うこと）。
            // 先頭は計算した時点のプレイヤー位置なので捨て、今の位置から引く
            for (int i = Math.min(snapshot.flightRouteFrom(), flightRoute.size() - 1);
                    i < flightRoute.size(); i++) {
                Vec3 next = flightRoute.get(i);
                int nextX = (int) Math.floor(next.x);
                int nextZ = (int) Math.floor(next.z);
                StraightDots.forEach(previousX, previousZ, nextX, nextZ,
                        (x, z) -> sink.dot(x, z,
                                PathColors.FLIGHT[0], PathColors.FLIGHT[1], PathColors.FLIGHT[2]));
                previousX = nextX;
                previousZ = nextZ;
            }
        }

        BlockPos goal = snapshot.goal();
        if (goal != null) {
            // 点線の始点は、粗いルートがあればその終点、無ければ経路の末端。経路も粗いルートも
            // まだ無いならプレイヤー自身から引く。粗いルートの終点が目的地そのものに置き換わっている
            // ときはfrom=toで長さ0になり、StraightDots側が何も描かず自然に消える
            int fromX;
            int fromZ;
            if (flightTail != null) {
                // 空中経路が引けている区間の先だけを点線で繋ぐ
                fromX = (int) Math.floor(flightTail.x);
                fromZ = (int) Math.floor(flightTail.z);
            } else if (lastCoarseWaypoint != null) {
                fromX = lastCoarseWaypoint.getX();
                fromZ = lastCoarseWaypoint.getZ();
            } else if (dots != null && dots.count > 0) {
                fromX = dots.x[dots.count - 1];
                fromZ = dots.z[dots.count - 1];
            } else {
                fromX = snapshot.playerPos().getX();
                fromZ = snapshot.playerPos().getZ();
            }
            // 滑空中は長距離ルートの中間目標を辿る（無ければ曲がり点線、それも無ければ直線）
            for (Vec3 point : snapshot.flightDash()) {
                int nextX = (int) Math.floor(point.x);
                int nextZ = (int) Math.floor(point.z);
                straightDots(sink, fromX, fromZ, nextX, nextZ);
                fromX = nextX;
                fromZ = nextZ;
            }
            straightDots(sink, fromX, fromZ, goal.getX(), goal.getZ());
        }
    }

    /**
     * 始点から結び始める中間目標。ルートが始点へ近づき続けている間は読み飛ばす。
     *
     * <p>通過済みの判定は「詳細経路が何番目の中間目標を向いているか」で行うが、詳細経路が
     * 中間目標を辿っていない間（層2の精緻化中は本来の目的地へ直接向かう）はその番号が進まず、
     * 未通過ぶんの先頭が現在地の遥か後ろに残る。そのまま順に結ぶと、現在地から後ろへ戻る線と
     * ルート本体の線が同じ回廊を並んで走り、<b>黄色い点線が2本出ている</b>ようにしか見えない。
     *
     * <p>逆に、ルートが本当に引き返す形（始点が行き過ぎている）なら2点目は始点から遠ざかるので、
     * ここでは読み飛ばさない。
     */
    private static int firstAheadWaypoint(List<BlockPos> waypoints, int fromX, int fromZ) {
        int first = 0;
        while (first + 1 < waypoints.size()
                && distanceSq(waypoints.get(first + 1), fromX, fromZ)
                        <= distanceSq(waypoints.get(first), fromX, fromZ)) {
            first++;
        }
        return first;
    }

    private static long distanceSq(BlockPos pos, int x, int z) {
        long dx = pos.getX() - (long) x;
        long dz = pos.getZ() - (long) z;
        return dx * dx + dz * dz;
    }

    private static void straightDots(DotSink sink, int fromX, int fromZ, int toX, int toZ) {
        StraightDots.forEach(fromX, fromZ, toX, toZ,
                (x, z) -> sink.dot(x, z,
                        PathColors.STRAIGHT[0], PathColors.STRAIGHT[1], PathColors.STRAIGHT[2]));
    }
}
