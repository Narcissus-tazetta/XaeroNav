package net.prason.xaeronav.client;

import java.util.Arrays;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.flight.FlightRoute;

/**
 * design doc §2-5 / Phase 2項目9。Xaero非依存のワールド内描画。
 * Xaero連携（世界地図・ミニマップアダプタ）が崩れてもこれだけは生き残る構成にする。
 *
 * <p>経路は平坦な1px線ではなく、進行方向に直交する正方形断面を押し出した「筒」として描画する
 * （どの角度から見ても太さのある立体として視認できるようにするため）。
 *
 * <p>描画位置・色・ハイライト対象は{@link PathGeometry}が経路ごとに1度だけ計算する。ここでの
 * 毎フレームの仕事は、カメラから遠い区間を落として頂点を積むことだけに限る。頂点計算も
 * {@link Vec3}を作らずスカラー演算で行う（区間ごとに十数個のベクトルを作ると、
 * 長い経路では毎フレーム数万オブジェクトになる）。
 */
public final class PathRenderer {

    /** 筒の断面半幅（ブロック）。以前の1px線より少し太い程度に留める。 */
    private static final double TUBE_RADIUS = 0.03;

    /**
     * 空中経路の筒の太さ。歩行の経路とは間合いが2桁違う——足元の線は数ブロック先だが、
     * 空中経路は50〜200ブロック先まで伸びるので、{@link #TUBE_RADIUS}のままでは画面上で
     * サブピクセルになって消える。
     *
     * <p>そこで<b>カメラからの距離に比例させて</b>、画面上の太さが間合いによらずおおよそ一定に
     * なるようにする。近くでは{@link #FLIGHT_TUBE_MIN_RADIUS}で頭打ちにして、目の前で異様に
     * 細くならないようにする（歩行の線より明らかに太い、というユーザーの要求はここで満たす）。
     */
    private static final double FLIGHT_TUBE_RADIUS_PER_BLOCK = 0.0075;
    private static final double FLIGHT_TUBE_MIN_RADIUS = 0.12;
    private static final double FLIGHT_TUBE_MAX_RADIUS = 0.8;
    private static final float TUBE_ALPHA = 0.9f;

    private static final float HIGHLIGHT_FILL_ALPHA = 0.35f;
    /** ハイライトの箱をブロック表面よりわずかに外側に出し、地形自体のZファイティングで隠れないようにする。 */
    private static final double HIGHLIGHT_EXPAND = 0.006;

    /**
     * 地形に隠れている側の濃さ。掘る場所は必ず壁の中にあるので、ここを描かないと「どこを掘るのか」が
     * 画面に出てこない。一方で手前の地形と同じ濃さで描くと壁を無視した絵になるので、薄く重ねる。
     */
    private static final float OCCLUDED_TUBE_ALPHA = 0.3f;
    private static final float OCCLUDED_HIGHLIGHT_ALPHA = 0.12f;
    /** 次に掘る1区間ぶんだけは、壁越しでもはっきり見えるようにする。 */
    private static final float NEXT_DIG_FILL_ALPHA = 0.5f;
    private static final float NEXT_DIG_OCCLUDED_ALPHA = 0.3f;

    /** 打ち切られた経路の末端の、いちばん先での濃さの割合。0にすると切れ目が見えなくなる。 */
    private static final float FADE_TAIL_MIN_RATIO = 0.15f;

    /** 概算の直線（点線）の1本の長さと間隔（ブロック）。 */
    private static final double DASH_LENGTH = 1.0;
    private static final double DASH_GAP = 1.0;

    /**
     * 概算の直線を出し始める距離（ブロック）。経路の末端が目的地に着いている場合に、
     * 同じ場所へ向かう点線を重ねて描かないための下限。
     */
    private static final double STRAIGHT_MIN_DISTANCE = 3.0;

    private static final float STRAIGHT_ALPHA = 0.8f;
    private static final float STRAIGHT_OCCLUDED_ALPHA = 0.3f;

    private PathGeometry geometry;

    // 筒の断面4頂点。区間ごとに作り直さず使い回す（描画スレッド専用）。
    private final double[] ringX = new double[4];
    private final double[] ringY = new double[4];
    private final double[] ringZ = new double[4];

    // 経路がまだ無いときに点線を引き始めるプレイヤーの足元（描画スレッド専用）。
    private double playerX;
    private double playerY;
    private double playerZ;

    // 点線の経由点を x,y,z の3つ組で並べたもの。遮蔽側と通常側で同じ列を2度なぞるので、
    // 毎フレーム組み直さずに使い回す（描画スレッド専用）。
    private double[] straightPoints = new double[12];

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        PathResult groundResult = PathfindingState.INSTANCE.currentResult();
        FlightRoute flight = PathfindingState.INSTANCE.flightRoute();
        BlockPos goal = PathfindingState.INSTANCE.goal();
        boolean hasGround = groundResult != null && !groundResult.steps().isEmpty();
        boolean hasFlight = !flight.isEmpty();
        // 到着表示の間は方角を示す点線を出さない。到着の判定半径(3)と点線を出し始める距離(3)は
        // 同じなので、目的地が足元より下にあると、着いた瞬間から真下へ向かう点線が残ってしまう
        boolean hasStraight = goal != null && XaeroNavConfig.INSTANCE.straightLineEnabled()
                && !PathfindingState.INSTANCE.arrived();
        if (!hasGround) {
            geometry = null;
        }
        if (!hasGround && !hasFlight && !hasStraight) {
            return;
        }

        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack.Pose pose = poseStack.last();
        // 描画距離の外は地形自体が描かれないので、そこまで伸びた経路を積む意味がない
        double cullRadius = mc.options.getEffectiveRenderDistance() * 16.0;
        double cullRadiusSq = cullRadius * cullRadius;

        BlockPos playerPos = mc.player.blockPosition();
        boolean playerInWater = mc.level.getFluidState(playerPos).is(FluidTags.WATER);
        double playerFeetY = playerPos.getY() + 0.55;
        playerX = mc.player.getX();
        // 点線（ゴールへの直線）の起点だけ2マス下げる。目線の高さから引くと、飛行中など
        // 見下ろす形になる場面で自分の体に埋もれて見えにくいため
        playerY = (playerInWater ? playerFeetY : mc.player.getY() + 0.55) - 2.0;
        playerZ = mc.player.getZ();

        PathGeometry current = null;
        if (hasGround) {
            current = geometry;
            if (current == null || !current.matches(groundResult)) {
                current = PathGeometry.build(groundResult, playerPos);
                geometry = current;
            }
            renderGroundPath(bufferSource, pose, current, groundResult, cameraPos, cullRadiusSq);
        }
        if (hasFlight) {
            renderFlightRoute(bufferSource, pose, flight, cullRadius, cameraPos);
        }
        if (hasStraight) {
            renderStraightLine(bufferSource, pose, current, hasFlight ? flight.tail() : null, goal, cullRadius);
        }

        poseStack.popPose();
    }

    /**
     * 経路が分からない区間を、目的地までの点線の直線で示す。未読み込みチャンクの先や、
     * 目的地のYが立てない高さの場合、実際に辿れる経路はそこで終わる。そのまま線を切ると
     * 「どちらへ向かえばいいのか」まで消えてしまうので、残りは直線で繋ぐ。
     *
     * <p>始点は経路の末端（無ければプレイヤー自身）。壁越しにも薄く出す — この線は地形を
     * 辿るものではなく方角と距離を示すものなので、遮蔽で消えると意味がなくなる。
     */
    private void renderStraightLine(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose,
                                     PathGeometry geometry, Vec3 flightTail, BlockPos goal, double cullRadius) {
        double fromX = playerX;
        double fromY = playerY;
        double fromZ = playerZ;
        if (flightTail != null) {
            // 空中経路が引けている区間の先だけを点線で繋ぐ。末端が目的地に届いていれば
            // 長さが最小距離を下回り、drawStraightDashes側で自然に何も描かれなくなる
            fromX = flightTail.x;
            fromY = flightTail.y;
            fromZ = flightTail.z;
        } else if (geometry != null) {
            int last = geometry.pointX.length - 1;
            fromX = geometry.pointX[last];
            fromY = geometry.pointY[last];
            fromZ = geometry.pointZ[last];
        }

        // 滑空中は点線が長距離ルートの中間目標を辿る（無ければ曲がり点線）
        List<Vec3> dash = PathfindingState.INSTANCE.flightDashWaypoints();
        int points = 0;
        points = pushStraightPoint(points, fromX, fromY, fromZ);
        for (Vec3 point : dash) {
            points = pushStraightPoint(points, point.x, point.y, point.z);
        }
        points = pushStraightPoint(points, goal.getX() + 0.5, goal.getY() + 0.55, goal.getZ() + 0.5);

        // 遮蔽側を最後まで積んでからバッファを閉じ、それから通常側へ移る。BufferSourceは
        // 一度に1つのRenderTypeしかビルドできず、次のgetBufferを呼んだ時点で前のバッファは
        // 閉じられる——2つを持って交互に書くと閉じた側への書き込みで落ちる
        VertexConsumer occludedQuads = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_QUADS);
        drawStraightDashes(occludedQuads, pose, points, cullRadius, STRAIGHT_OCCLUDED_ALPHA);
        bufferSource.endBatch(NavRenderTypes.OCCLUDED_QUADS);

        VertexConsumer quadBuffer = bufferSource.getBuffer(RenderType.debugQuads());
        drawStraightDashes(quadBuffer, pose, points, cullRadius, STRAIGHT_ALPHA);
        bufferSource.endBatch(RenderType.debugQuads());
    }

    /**
     * 空中経路を筒で描く。歩行の経路と違ってステップごとの色分け（危険・掘削・移動の種類）が無く、
     * あるのは折れ線だけなので{@link PathGeometry}は通さない。
     *
     * <p>先頭の点は計算した時点のプレイヤー位置で、届く頃には最大で再計算間隔ぶん古い。今の位置から
     * 引き直さないと、線が自分の少し後ろから生えているように見える。
     */
    private void renderFlightRoute(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose,
                                    FlightRoute route, double cullRadius, Vec3 camera) {
        List<Vec3> points = route.points();
        // 通り過ぎた区間は描かない。空中経路は引き直しの合間に数十ブロック進むので、
        // これが無いと線が自分の後ろへ伸びたままになる（歩行のrenderGroundPathと同じ理由）
        int first = PathfindingState.INSTANCE.flightRouteFrom();
        int count = 0;
        count = pushStraightPoint(count, playerX, playerY, playerZ);
        for (int i = first; i < points.size(); i++) {
            Vec3 point = points.get(i);
            count = pushStraightPoint(count, point.x, point.y, point.z);
        }

        // 遮蔽側を積み切ってからバッファを閉じ、それから通常側へ移る（renderStraightLineと同じ理由）
        VertexConsumer occluded = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_QUADS);
        drawFlightSegments(occluded, pose, count, cullRadius, OCCLUDED_TUBE_ALPHA, camera);
        bufferSource.endBatch(NavRenderTypes.OCCLUDED_QUADS);

        VertexConsumer quads = bufferSource.getBuffer(RenderType.debugQuads());
        drawFlightSegments(quads, pose, count, cullRadius, TUBE_ALPHA, camera);
        bufferSource.endBatch(RenderType.debugQuads());
    }

    /** 画面上の太さを間合いによらず一定に保つための、この区間での筒の半幅。 */
    private static double flightTubeRadius(Vec3 camera, double fromX, double fromY, double fromZ,
                                            double toX, double toY, double toZ) {
        double distance = Math.sqrt(distanceSqToSegment(camera, fromX, fromY, fromZ, toX, toY, toZ));
        return Math.clamp(distance * FLIGHT_TUBE_RADIUS_PER_BLOCK,
                FLIGHT_TUBE_MIN_RADIUS, FLIGHT_TUBE_MAX_RADIUS);
    }

    private void drawFlightSegments(VertexConsumer buffer, PoseStack.Pose pose, int points, double cullRadius,
                                     float alpha, Vec3 camera) {
        for (int i = 0; i + 1 < points; i++) {
            double fromX = straightPoints[i * 3];
            double fromY = straightPoints[i * 3 + 1];
            double fromZ = straightPoints[i * 3 + 2];
            double toX = straightPoints[i * 3 + 3];
            double toY = straightPoints[i * 3 + 4];
            double toZ = straightPoints[i * 3 + 5];
            double dx = toX - fromX;
            double dy = toY - fromY;
            double dz = toZ - fromZ;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1.0e-4) {
                continue;
            }
            // 描画距離の外は地形ごと描かれないので、そこまで積んでも見えない
            double drawn = Math.min(length, cullRadius);
            drawTube(buffer, pose, flightTubeRadius(camera, fromX, fromY, fromZ, toX, toY, toZ),
                    fromX, fromY, fromZ,
                    fromX + dx / length * drawn, fromY + dy / length * drawn, fromZ + dz / length * drawn,
                    PathColors.FLIGHT[0], PathColors.FLIGHT[1], PathColors.FLIGHT[2], alpha);
        }
    }

    private int pushStraightPoint(int count, double x, double y, double z) {
        if ((count + 1) * 3 > straightPoints.length) {
            straightPoints = Arrays.copyOf(straightPoints, straightPoints.length * 2);
        }
        straightPoints[count * 3] = x;
        straightPoints[count * 3 + 1] = y;
        straightPoints[count * 3 + 2] = z;
        return count + 1;
    }

    private void drawStraightDashes(VertexConsumer buffer, PoseStack.Pose pose, int points, double cullRadius,
                                     float alpha) {
        for (int i = 0; i + 1 < points; i++) {
            double fromX = straightPoints[i * 3];
            double fromY = straightPoints[i * 3 + 1];
            double fromZ = straightPoints[i * 3 + 2];
            double dx = straightPoints[i * 3 + 3] - fromX;
            double dy = straightPoints[i * 3 + 4] - fromY;
            double dz = straightPoints[i * 3 + 5] - fromZ;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < STRAIGHT_MIN_DISTANCE) {
                continue;
            }
            dx /= length;
            dy /= length;
            dz /= length;
            // 描画距離の外は地形ごと描かれないので、そこまで点を積んでも見えない
            double drawn = Math.min(length, cullRadius);
            drawDashes(buffer, pose, fromX, fromY, fromZ, dx, dy, dz, drawn, alpha);
        }
    }

    private void drawDashes(VertexConsumer buffer, PoseStack.Pose pose,
                            double fromX, double fromY, double fromZ,
                            double dirX, double dirY, double dirZ, double length, float alpha) {
        for (double start = 0.0; start < length; start += DASH_LENGTH + DASH_GAP) {
            double end = Math.min(start + DASH_LENGTH, length);
            drawTube(buffer, pose, TUBE_RADIUS,
                    fromX + dirX * start, fromY + dirY * start, fromZ + dirZ * start,
                    fromX + dirX * end, fromY + dirY * end, fromZ + dirZ * end,
                    PathColors.STRAIGHT[0], PathColors.STRAIGHT[1], PathColors.STRAIGHT[2], alpha);
        }
    }

    /**
     * 地形に隠れている側を先に薄く描き、その上から通常の深度テスト付きで描く。手前に何も無ければ
     * 2枚が重なって濃く、壁の向こう側では薄い方だけが残る。
     */
    private void renderGroundPath(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose,
                                   PathGeometry geometry, PathResult result, Vec3 camera, double cullRadiusSq) {
        int segments = geometry.segmentCount();
        int highlights = geometry.highlightCount();
        // 通り過ぎた区間は描かない。経路は歩いても引き直さないので、これが無いと自分の後ろへ
        // 延々と線が伸びたままになる。線はあくまで経路の上に載せ、プレイヤーへ引き寄せない
        // （横にずれているときに自分から線が生えるように見えてしまう）
        int matched = PathProgress.INSTANCE.indexFor(result);
        int first = geometry.firstSegmentFrom(matched);

        VertexConsumer occludedQuads = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_QUADS);
        for (int i = first; i < segments; i++) {
            if (!segmentVisible(geometry, i, camera, cullRadiusSq)) {
                continue;
            }
            drawSegment(occludedQuads, pose, geometry, i, OCCLUDED_TUBE_ALPHA, i == first ? matched : -1);
        }
        for (int i = 0; i < highlights; i++) {
            if (!highlightVisible(geometry, i, camera, cullRadiusSq)) {
                continue;
            }
            drawHighlightBox(occludedQuads, pose, geometry, i,
                    isNextDig(geometry, i) ? NEXT_DIG_OCCLUDED_ALPHA : OCCLUDED_HIGHLIGHT_ALPHA);
        }
        bufferSource.endBatch(NavRenderTypes.OCCLUDED_QUADS);

        // 次に掘る場所だけは枠も壁越しに出す。全部の枠を通すと掘り進む先の線が重なって読めなくなる
        if (geometry.nextDigTo > geometry.nextDigFrom) {
            VertexConsumer occludedLines = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_LINES);
            for (int i = geometry.nextDigFrom; i < geometry.nextDigTo; i++) {
                if (highlightVisible(geometry, i, camera, cullRadiusSq)) {
                    drawHighlightOutline(occludedLines, pose, geometry, i);
                }
            }
            bufferSource.endBatch(NavRenderTypes.OCCLUDED_LINES);
        }

        VertexConsumer quadBuffer = bufferSource.getBuffer(RenderType.debugQuads());
        for (int i = first; i < segments; i++) {
            if (!segmentVisible(geometry, i, camera, cullRadiusSq)) {
                continue;
            }
            drawSegment(quadBuffer, pose, geometry, i, TUBE_ALPHA, i == first ? matched : -1);
        }
        int visibleHighlights = 0;
        for (int i = 0; i < highlights; i++) {
            if (!highlightVisible(geometry, i, camera, cullRadiusSq)) {
                continue;
            }
            visibleHighlights++;
            drawHighlightBox(quadBuffer, pose, geometry, i,
                    isNextDig(geometry, i) ? NEXT_DIG_FILL_ALPHA : HIGHLIGHT_FILL_ALPHA);
        }
        bufferSource.endBatch(RenderType.debugQuads());

        if (visibleHighlights > 0) {
            VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.lines());
            for (int i = 0; i < highlights; i++) {
                if (!highlightVisible(geometry, i, camera, cullRadiusSq)) {
                    continue;
                }
                drawHighlightOutline(lineBuffer, pose, geometry, i);
            }
            bufferSource.endBatch(RenderType.lines());
        }
    }

    /**
     * {@code cutStep}が0以上なら、区間をそのステップの位置で切って先だけを描く。まとめられた
     * 長い直線区間は端点までしか点を持たないので、これが無いと区間ごと消えるか丸ごと残るかの
     * 二択になり、線の始まりが数十ブロック先へ飛ぶ。
     */
    private void drawSegment(VertexConsumer buffer, PoseStack.Pose pose, PathGeometry geometry, int index,
                             float alpha, int cutStep) {
        drawTube(buffer, pose, TUBE_RADIUS,
                cutStep >= 0 ? geometry.cutX(cutStep) : geometry.pointX[index],
                cutStep >= 0 ? geometry.cutY(cutStep) : geometry.pointY[index],
                cutStep >= 0 ? geometry.cutZ(cutStep) : geometry.pointZ[index],
                geometry.pointX[index + 1], geometry.pointY[index + 1], geometry.pointZ[index + 1],
                geometry.segmentColor[index * 3], geometry.segmentColor[index * 3 + 1],
                geometry.segmentColor[index * 3 + 2], alpha * fadeRatio(geometry, index));
    }

    /**
     * 打ち切られた経路の末端を先へ行くほど薄くする割合。線が唐突に途切れると、そこが行き止まりなのか
     * 探索が届かなかっただけなのかが見た目で区別できない。
     */
    private static float fadeRatio(PathGeometry geometry, int index) {
        int segments = geometry.segmentCount();
        if (index < geometry.fadeFromSegment) {
            return 1.0f;
        }
        float progress = (float) (index - geometry.fadeFromSegment + 1) / (segments - geometry.fadeFromSegment);
        return 1.0f - progress * (1.0f - FADE_TAIL_MIN_RATIO);
    }

    private void drawHighlightBox(VertexConsumer buffer, PoseStack.Pose pose, PathGeometry geometry, int index,
                                  float alpha) {
        drawBox(buffer, pose, geometry.highlightX[index], geometry.highlightY[index], geometry.highlightZ[index],
                geometry.highlightColor[index * 3], geometry.highlightColor[index * 3 + 1],
                geometry.highlightColor[index * 3 + 2], alpha);
    }

    private void drawHighlightOutline(VertexConsumer buffer, PoseStack.Pose pose, PathGeometry geometry, int index) {
        drawBoxOutline(buffer, pose, geometry.highlightX[index], geometry.highlightY[index], geometry.highlightZ[index],
                geometry.highlightColor[index * 3], geometry.highlightColor[index * 3 + 1],
                geometry.highlightColor[index * 3 + 2]);
    }

    private boolean isNextDig(PathGeometry geometry, int index) {
        return index >= geometry.nextDigFrom && index < geometry.nextDigTo;
    }

    private boolean segmentVisible(PathGeometry geometry, int index, Vec3 camera, double cullRadiusSq) {
        return distanceSqToSegment(camera,
                geometry.pointX[index], geometry.pointY[index], geometry.pointZ[index],
                geometry.pointX[index + 1], geometry.pointY[index + 1], geometry.pointZ[index + 1]) <= cullRadiusSq;
    }

    private boolean highlightVisible(PathGeometry geometry, int index, Vec3 camera, double cullRadiusSq) {
        double dx = geometry.highlightX[index] + 0.5 - camera.x;
        double dy = geometry.highlightY[index] + 0.5 - camera.y;
        double dz = geometry.highlightZ[index] + 0.5 - camera.z;
        if (dx * dx + dy * dy + dz * dz > cullRadiusSq) {
            return false;
        }
        return !geometry.highlightPlacement[index] || placementPending(geometry, index);
    }

    /**
     * 設置予定地がまだ空いているか。置いた瞬間に枠を消すためのもので、経路の引き直しを待たない
     * （経路は数十tickに一度しか作り直されないので、置いた足場に枠が残り続けて見える）。
     */
    private boolean placementPending(PathGeometry geometry, int index) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return true;
        }
        BlockPos pos = new BlockPos(geometry.highlightX[index], geometry.highlightY[index],
                geometry.highlightZ[index]);
        return level.getBlockState(pos).isAir();
    }

    /**
     * カメラと区間の最短距離の2乗。一直線に続く区間はまとめられていて長くなりうるので、
     * 端点だけを見て判定すると、カメラの真横を通り抜ける長い区間を消してしまう。
     */
    private static double distanceSqToSegment(Vec3 camera, double ax, double ay, double az,
                                               double bx, double by, double bz) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double apx = camera.x - ax;
        double apy = camera.y - ay;
        double apz = camera.z - az;
        double lengthSq = abx * abx + aby * aby + abz * abz;
        double t = lengthSq > 0.0 ? (apx * abx + apy * aby + apz * abz) / lengthSq : 0.0;
        t = Math.clamp(t, 0.0, 1.0);
        double dx = apx - abx * t;
        double dy = apy - aby * t;
        double dz = apz - abz * t;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 区間を「筒」として描画する。フラットな線だと真横から見た時に見づらいため、
     * 進行方向に直交する正方形断面を押し出して立体的な形状にする。
     */
    private void drawTube(VertexConsumer buffer, PoseStack.Pose pose, double radius,
                          double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
                          float red, float green, float blue, float alpha) {
        double dirX = toX - fromX;
        double dirY = toY - fromY;
        double dirZ = toZ - fromZ;
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1.0e-4) {
            return;
        }
        dirX /= length;
        dirY /= length;
        dirZ /= length;

        // 進行方向と平行にならない参照ベクトルを選ぶ（外積が潰れるのを避ける）。
        // Z成分は常に0なので、以下の外積ではその項を畳んである。
        boolean steep = Math.abs(dirY) > 0.99;
        double refX = steep ? 1.0 : 0.0;
        double refY = steep ? 0.0 : 1.0;

        double rightX = -dirZ * refY;
        double rightY = dirZ * refX;
        double rightZ = dirX * refY - dirY * refX;
        double rightLength = Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
        rightX = rightX / rightLength * radius;
        rightY = rightY / rightLength * radius;
        rightZ = rightZ / rightLength * radius;

        double upX = rightY * dirZ - rightZ * dirY;
        double upY = rightZ * dirX - rightX * dirZ;
        double upZ = rightX * dirY - rightY * dirX;
        double upLength = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        upX = upX / upLength * radius;
        upY = upY / upLength * radius;
        upZ = upZ / upLength * radius;

        ringX[0] = upX + rightX;
        ringY[0] = upY + rightY;
        ringZ[0] = upZ + rightZ;
        ringX[1] = rightX - upX;
        ringY[1] = rightY - upY;
        ringZ[1] = rightZ - upZ;
        ringX[2] = -ringX[0];
        ringY[2] = -ringY[0];
        ringZ[2] = -ringZ[0];
        ringX[3] = upX - rightX;
        ringY[3] = upY - rightY;
        ringZ[3] = upZ - rightZ;

        for (int i = 0; i < 4; i++) {
            int next = (i + 1) & 3;
            vertex(buffer, pose, fromX + ringX[i], fromY + ringY[i], fromZ + ringZ[i], red, green, blue, alpha);
            vertex(buffer, pose, fromX + ringX[next], fromY + ringY[next], fromZ + ringZ[next], red, green, blue, alpha);
            vertex(buffer, pose, toX + ringX[next], toY + ringY[next], toZ + ringZ[next], red, green, blue, alpha);
            vertex(buffer, pose, toX + ringX[i], toY + ringY[i], toZ + ringZ[i], red, green, blue, alpha);
        }
    }

    private void drawBox(VertexConsumer buffer, PoseStack.Pose pose, int cellX, int cellY, int cellZ,
                         float red, float green, float blue, float alpha) {
        double minX = cellX - HIGHLIGHT_EXPAND;
        double minY = cellY - HIGHLIGHT_EXPAND;
        double minZ = cellZ - HIGHLIGHT_EXPAND;
        double maxX = cellX + 1 + HIGHLIGHT_EXPAND;
        double maxY = cellY + 1 + HIGHLIGHT_EXPAND;
        double maxZ = cellZ + 1 + HIGHLIGHT_EXPAND;

        quad(buffer, pose, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        quad(buffer, pose, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, red, green, blue, alpha);
        quad(buffer, pose, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, red, green, blue, alpha);
        quad(buffer, pose, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, red, green, blue, alpha);
        quad(buffer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        quad(buffer, pose, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ, red, green, blue, alpha);
    }

    private void drawBoxOutline(VertexConsumer buffer, PoseStack.Pose pose, int cellX, int cellY, int cellZ,
                                 float red, float green, float blue) {
        float minX = (float) (cellX - HIGHLIGHT_EXPAND);
        float minY = (float) (cellY - HIGHLIGHT_EXPAND);
        float minZ = (float) (cellZ - HIGHLIGHT_EXPAND);
        float maxX = (float) (cellX + 1 + HIGHLIGHT_EXPAND);
        float maxY = (float) (cellY + 1 + HIGHLIGHT_EXPAND);
        float maxZ = (float) (cellZ + 1 + HIGHLIGHT_EXPAND);

        line(buffer, pose, minX, minY, minZ, maxX, minY, minZ, red, green, blue);
        line(buffer, pose, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue);
        line(buffer, pose, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue);
        line(buffer, pose, minX, minY, maxZ, minX, minY, minZ, red, green, blue);
        line(buffer, pose, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue);
        line(buffer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue);
        line(buffer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue);
        line(buffer, pose, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue);
        line(buffer, pose, minX, minY, minZ, minX, maxY, minZ, red, green, blue);
        line(buffer, pose, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue);
        line(buffer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue);
        line(buffer, pose, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue);
    }

    private void quad(VertexConsumer buffer, PoseStack.Pose pose,
                      double x0, double y0, double z0, double x1, double y1, double z1,
                      double x2, double y2, double z2, double x3, double y3, double z3,
                      float red, float green, float blue, float alpha) {
        vertex(buffer, pose, x0, y0, z0, red, green, blue, alpha);
        vertex(buffer, pose, x1, y1, z1, red, green, blue, alpha);
        vertex(buffer, pose, x2, y2, z2, red, green, blue, alpha);
        vertex(buffer, pose, x3, y3, z3, red, green, blue, alpha);
    }

    private void vertex(VertexConsumer buffer, PoseStack.Pose pose, double x, double y, double z,
                        float red, float green, float blue, float alpha) {
        buffer.addVertex(pose, (float) x, (float) y, (float) z).setColor(red, green, blue, alpha);
    }

    private void line(VertexConsumer buffer, PoseStack.Pose pose,
                      float x0, float y0, float z0, float x1, float y1, float z1,
                      float red, float green, float blue) {
        buffer.addVertex(pose, x0, y0, z0).setColor(red, green, blue, 1.0f).setNormal(pose, 0f, 1f, 0f);
        buffer.addVertex(pose, x1, y1, z1).setColor(red, green, blue, 1.0f).setNormal(pose, 0f, 1f, 0f);
    }
}
