package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.elytra.ElytraPath;

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
    public record Snapshot(PathResult ground, ElytraPath elytra, BlockPos goal, BlockPos playerPos,
                            List<BlockPos> coarseWaypoints) {

        public boolean isEmpty() {
            return ground == null && elytra == null && goal == null && coarseWaypoints.isEmpty();
        }
    }

    /**
     * いま描くべきものを1度だけ読み取る。描くものが何も無ければ{@link Snapshot#isEmpty()}がtrueになり、
     * 呼び出し側は{@code VertexConsumer}の取得自体を省ける。
     */
    public static Snapshot snapshot() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new Snapshot(null, null, null, null, List.of());
        }
        PathResult ground = PathfindingState.INSTANCE.currentResult();
        if (ground != null && ground.steps().isEmpty()) {
            ground = null;
        }
        ElytraPath elytra = ElytraNavState.INSTANCE.currentPath();
        if (elytra != null && elytra.waypoints().isEmpty()) {
            elytra = null;
        }
        BlockPos goal = XaeroNavConfig.INSTANCE.straightLineEnabled()
                ? PathfindingState.INSTANCE.goal()
                : null;
        return new Snapshot(ground, elytra, goal, player.blockPosition(),
                PathfindingState.INSTANCE.coarseRouteWaypoints());
    }

    public static void draw(Snapshot snapshot, DotSink sink) {
        MapDots dots = snapshot.ground() == null ? null : MapDots.forPath(snapshot.ground());
        if (dots != null) {
            for (int i = 0; i < dots.count; i++) {
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
            for (BlockPos next : coarseWaypoints) {
                StraightDots.forEach(previousX, previousZ, next.getX(), next.getZ(),
                        (x, z) -> sink.dot(x, z,
                                PathColors.COARSE_ROUTE[0], PathColors.COARSE_ROUTE[1], PathColors.COARSE_ROUTE[2]));
                previousX = next.getX();
                previousZ = next.getZ();
            }
        }

        BlockPos goal = snapshot.goal();
        if (goal != null) {
            // 点線の始点は、粗いルートがあればその終点、無ければ経路の末端。経路も粗いルートも
            // まだ無いならプレイヤー自身から引く。粗いルートの終点が目的地そのものに置き換わっている
            // ときはfrom=toで長さ0になり、StraightDots側が何も描かず自然に消える
            int fromX;
            int fromZ;
            if (lastCoarseWaypoint != null) {
                fromX = lastCoarseWaypoint.getX();
                fromZ = lastCoarseWaypoint.getZ();
            } else if (dots != null && dots.count > 0) {
                fromX = dots.x[dots.count - 1];
                fromZ = dots.z[dots.count - 1];
            } else {
                fromX = snapshot.playerPos().getX();
                fromZ = snapshot.playerPos().getZ();
            }
            StraightDots.forEach(fromX, fromZ, goal.getX(), goal.getZ(),
                    (x, z) -> sink.dot(x, z,
                            PathColors.STRAIGHT[0], PathColors.STRAIGHT[1], PathColors.STRAIGHT[2]));
        }

        if (snapshot.elytra() != null) {
            for (Vec3 waypoint : snapshot.elytra().waypoints()) {
                sink.dot((int) Math.floor(waypoint.x), (int) Math.floor(waypoint.z),
                        PathColors.ELYTRA[0], PathColors.ELYTRA[1], PathColors.ELYTRA[2]);
            }
        }
    }
}
