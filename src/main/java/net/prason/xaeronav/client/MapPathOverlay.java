package net.prason.xaeronav.client;

import java.util.List;

import org.joml.Matrix4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.xaero.XaeroHookHealth;

/**
 * Xaeroの世界地図・ミニマップへ経路を描くときの「何をどの色で置くか」。
 *
 * <p>2つの地図は描画先（{@code VertexConsumer}と塗りつぶしヘルパー）と地図座標への変換だけが違い、
 * 経路の取り出し方・描く順序・色はまったく同じになる。ここに1本化しておかないと、経路の種類を
 * 増やしたときに片方だけ追従して「世界地図には出るのにミニマップには出ない」が起きる。
 *
 * <p>切り取り（ミニマップのFBOに載らない遠方を捨てる）は{@link QuadSink}側の仕事にする。
 * 何を切るかは地図座標の都合なので、変換を持っている呼び出し側に置くのが筋が通る。
 */
public final class MapPathOverlay {

    /**
     * 目的地の目印の各部の大きさ（画面上のピクセル）。<b>ブロックではなくピクセルで持つのが要点</b>——
     * ブロックで決めると、地図を縮小したときに目印だけが一緒に縮んで消える。目的地を見失うのは
     * まさに縮小して全体を見ているときなので、それでは用を成さない。
     */
    private static final double PIN_WIDTH_PX = 11.0;
    private static final double PIN_HEIGHT_PX = 20.0;
    private static final double PIN_HOLE_RADIUS_PX = 2.6;
    /** 縁取りの太さ。地形の色が明るくても暗くてもピンが沈まないよう、全体を暗色で縁取る。 */
    private static final double PIN_OUTLINE_PX = 1.0;
    /** これより細い帯は描かない。1ブロックに満たない帯を描くと、輪郭がぼやけるだけで形は出ない。 */
    private static final double PIN_MIN_BAND_PX = 0.35;

    /**
     * 1ブロックあたりの画面ピクセル数の許容範囲。地図の縮尺は{@link #pixelsPerBlock}が行列から
     * 割り出すが、Xaeroが途中でFBOを挟む都合で見積もりがずれることがある。ずれても目印の大きさが
     * 際限なく暴れないよう、ここで頭と底を押さえる。
     */
    private static final double MIN_PIXELS_PER_BLOCK = 0.05;
    private static final double MAX_PIXELS_PER_BLOCK = 16.0;

    /**
     * 地図へ矩形を1つ置く。座標はブロック座標（{@code x2}/{@code z2}は含まない）で、
     * 地図座標への変換と切り取りは実装側が行う。
     *
     * <p>経路は1ブロック四方の点の連なりだが、目的地の目印だけは縮尺によらず画面上で同じ大きさに
     * したい。点で敷き詰めると縮小時に数万個になるので、矩形を基本形にしてある。
     */
    @FunctionalInterface
    public interface QuadSink {
        void rect(int blockX1, int blockZ1, int blockX2, int blockZ2, float red, float green, float blue);

        /** 1ブロック四方の点。経路はこれの連なりとして描く。 */
        default void dot(int blockX, int blockZ, float red, float green, float blue) {
            rect(blockX, blockZ, blockX + 1, blockZ + 1, red, green, blue);
        }
    }

    /**
     * この行列で1ブロックが画面上の何ピクセルになるか。x軸の基底ベクトルの長さがそのまま倍率になる
     * （回転が入っていても長さは変わらないので、回るミニマップでもそのまま使える）。
     */
    public static double pixelsPerBlock(Matrix4f pose) {
        double x = pose.m00();
        double y = pose.m01();
        double z = pose.m02();
        double scale = Math.sqrt(x * x + y * y + z * z);
        return Double.isFinite(scale) ? Math.clamp(scale, MIN_PIXELS_PER_BLOCK, MAX_PIXELS_PER_BLOCK) : 1.0;
    }

    private MapPathOverlay() {
    }

    /**
     * その時点で描くべきものを1つに固めたもの。経路はワーカースレッドがいつでも差し替えるので、
     * 「何かあるか」の判定と実際の描画が別々に読むと、あると判断した経路が描く頃には消えている。
     */
    public record Snapshot(PathResult ground, BlockPos goal, boolean straightLine, boolean goalMarker,
                            BlockPos playerPos, List<BlockPos> coarseWaypoints, List<Vec3> flightRoute,
                            int flightRouteFrom, List<Vec3> flightDash) {

        public boolean isEmpty() {
            return ground == null && goal == null && coarseWaypoints.isEmpty() && flightRoute.isEmpty();
        }
    }

    /**
     * いま描くべきものを1度だけ読み取る。描くものが何も無ければ{@link Snapshot#isEmpty()}がtrueになり、
     * 呼び出し側は{@code VertexConsumer}の取得自体を省ける。
     */
    public static Snapshot snapshot() {
        XaeroHookHealth.hookRan();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new Snapshot(null, null, false, false, null, List.of(), List.of(), 0, List.of());
        }
        return PathfindingState.INSTANCE.mapOverlaySnapshot(player.blockPosition());
    }

    public static void draw(Snapshot snapshot, QuadSink sink, double pixelsPerBlock) {
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
        if (goal != null && snapshot.straightLine()) {
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
                straightDots(sink, fromX, fromZ, nextX, nextZ, PathColors.STRAIGHT);
                fromX = nextX;
                fromZ = nextZ;
            }
            straightDots(sink, fromX, fromZ, goal.getX(), goal.getZ(), PathColors.STRAIGHT);
        }

        // 目印は最後。経路や点線と重なる位置に来るので、後から置いて上に乗せる
        if (goal != null && snapshot.goalMarker()) {
            drawGoalMarker(sink, goal, pixelsPerBlock);
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

    /**
     * 目的地に立てるピン。経路も点線も「そこへ向かう途中」しか描かないので、地図を開いても
     * どこが目的地なのかは線を端まで辿らないと分からない——遠くて経路が途中で切れているときは特に。
     *
     * <p><b>大きさは地図の縮尺に追従させ、画面上では常に同じ大きさに見えるようにする。</b>ピンは
     * 「地面に置いた物」ではなく「地図の上の印」なので、Xaero自身のウェイポイントと同じく、縮小しても
     * 縮まないのが正しい。目的地を見失うのはまさに縮小して全体を見ているときで、そこで一緒に縮んで
     * 消えるようでは用を成さない。
     *
     * <p>形は横1行ずつの帯に分けて描く。帯の数は画面上の高さで決まるので、どれだけ縮小しても
     * 矩形は数十個で済む（ブロック単位で刻むと、縮小したときに数万個になる）。
     *
     * <p><b>先端が目的地のブロックそのもの</b>で、そこから北（地図の上）へ向かって立つ。縁取り・本体・
     * 穴の3層を層ごとにまとめて描くのは、行ごとに重ねると次の行の縁取りが前の行の本体を上書きして、
     * ピンの内側に暗い筋が走るため。
     */
    private static void drawGoalMarker(QuadSink sink, BlockPos goal, double pixelsPerBlock) {
        double radius = PIN_WIDTH_PX / 2.0;
        int top = -(int) Math.ceil(PIN_HEIGHT_PX + PIN_OUTLINE_PX) - 1;
        int bottom = (int) Math.ceil(PIN_OUTLINE_PX);

        for (int row = top; row <= bottom; row++) {
            double body = pinHalfWidth(row, radius, PIN_HEIGHT_PX);
            // ひと回り大きい同じ形と「本体＋縁の太さ」の広い方。前者だけだと、行ごとの丸めの差で
            // 本体がはみ出して縁が途切れる
            double grown = pinHalfWidth(row - PIN_OUTLINE_PX, radius + PIN_OUTLINE_PX,
                    PIN_HEIGHT_PX + 2 * PIN_OUTLINE_PX);
            pinBand(sink, goal, row, body > 0 ? Math.max(grown, body + PIN_OUTLINE_PX) : grown,
                    pixelsPerBlock, PathColors.GOAL_MARKER_OUTLINE);
        }
        for (int row = top; row <= 0; row++) {
            pinBand(sink, goal, row, pinHalfWidth(row, radius, PIN_HEIGHT_PX),
                    pixelsPerBlock, PathColors.GOAL_MARKER);
        }
        double centre = -(PIN_HEIGHT_PX - radius);
        for (int row = top; row <= 0; row++) {
            // 帯の中心の高さで測る。行の上端で測ると穴が半行ぶん上へずれる
            double fromCentre = row + 0.5 - centre;
            if (Math.abs(fromCentre) <= PIN_HOLE_RADIUS_PX) {
                pinBand(sink, goal, row,
                        Math.sqrt(PIN_HOLE_RADIUS_PX * PIN_HOLE_RADIUS_PX - fromCentre * fromCentre),
                        pixelsPerBlock, PathColors.GOAL_MARKER_HOLE);
            }
        }
    }

    /**
     * 先端を0として上へ{@code rowPx}（負が上）の位置でのピンの半幅。頭の円と、円の中心から先端へ
     * すぼまる三角形の和集合として求める。
     */
    private static double pinHalfWidth(double rowPx, double radius, double height) {
        double centre = -(height - radius);
        double half = 0.0;
        double fromCentre = rowPx - centre;
        if (Math.abs(fromCentre) <= radius) {
            half = Math.sqrt(radius * radius - fromCentre * fromCentre);
        }
        if (rowPx >= centre && rowPx <= 0.0) {
            half = Math.max(half, radius * (rowPx / centre));
        }
        return half;
    }

    /** ピンの横1行ぶんの帯。画面上の位置と幅をブロックへ直して置く。 */
    private static void pinBand(QuadSink sink, BlockPos goal, double rowPx, double halfPx,
                                 double pixelsPerBlock, float[] color) {
        if (halfPx < PIN_MIN_BAND_PX) {
            return;
        }
        int z1 = goal.getZ() + scaled(rowPx, pixelsPerBlock);
        int z2 = Math.max(goal.getZ() + scaled(rowPx + 1, pixelsPerBlock), z1 + 1);
        int x1 = goal.getX() + scaled(-halfPx, pixelsPerBlock);
        int x2 = Math.max(goal.getX() + scaled(halfPx, pixelsPerBlock), x1 + 1);
        sink.rect(x1, z1, x2, z2, color[0], color[1], color[2]);
    }

    /** 画面上の{@code pixels}に相当するブロック数（符号付き）。 */
    private static int scaled(double pixels, double pixelsPerBlock) {
        return (int) Math.round(pixels / pixelsPerBlock);
    }

    private static void straightDots(QuadSink sink, int fromX, int fromZ, int toX, int toZ) {
        straightDots(sink, fromX, fromZ, toX, toZ, PathColors.STRAIGHT);
    }

    private static void straightDots(QuadSink sink, int fromX, int fromZ, int toX, int toZ, float[] color) {
        StraightDots.forEach(fromX, fromZ, toX, toZ,
                (x, z) -> sink.dot(x, z, color[0], color[1], color[2]));
    }
}
