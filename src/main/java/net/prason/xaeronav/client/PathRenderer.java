package net.prason.xaeronav.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.elytra.ElytraPath;

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

    private PathGeometry geometry;

    // 筒の断面4頂点。区間ごとに作り直さず使い回す（描画スレッド専用）。
    private final double[] ringX = new double[4];
    private final double[] ringY = new double[4];
    private final double[] ringZ = new double[4];

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
        ElytraPath elytraPath = ElytraNavState.INSTANCE.currentPath();
        boolean hasGround = groundResult != null && !groundResult.steps().isEmpty();
        boolean hasElytra = elytraPath != null && elytraPath.waypoints().size() >= 2;
        if (!hasGround) {
            geometry = null;
        }
        if (!hasGround && !hasElytra) {
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

        if (hasGround) {
            BlockPos playerPos = mc.player.blockPosition();
            boolean playerInWater = mc.level.getFluidState(playerPos).is(FluidTags.WATER);
            double playerFeetY = playerPos.getY() + 0.55;
            PathGeometry current = geometry;
            if (current == null || !current.matches(groundResult, playerInWater, playerFeetY)) {
                current = PathGeometry.build(mc.level, groundResult, playerPos, playerInWater, playerFeetY);
                geometry = current;
            }
            renderGroundPath(bufferSource, pose, current, cameraPos, cullRadiusSq);
        }
        if (hasElytra) {
            renderElytraPath(bufferSource, pose, elytraPath, cameraPos, cullRadiusSq);
        }

        poseStack.popPose();
    }

    /**
     * 地形に隠れている側を先に薄く描き、その上から通常の深度テスト付きで描く。手前に何も無ければ
     * 2枚が重なって濃く、壁の向こう側では薄い方だけが残る。
     */
    private void renderGroundPath(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose,
                                   PathGeometry geometry, Vec3 camera, double cullRadiusSq) {
        int segments = geometry.segmentCount();
        int highlights = geometry.highlightCount();

        VertexConsumer occludedQuads = bufferSource.getBuffer(NavRenderTypes.OCCLUDED_QUADS);
        for (int i = 0; i < segments; i++) {
            if (!segmentVisible(geometry, i, camera, cullRadiusSq)) {
                continue;
            }
            drawSegment(occludedQuads, pose, geometry, i, OCCLUDED_TUBE_ALPHA);
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
        for (int i = 0; i < segments; i++) {
            if (!segmentVisible(geometry, i, camera, cullRadiusSq)) {
                continue;
            }
            drawSegment(quadBuffer, pose, geometry, i, TUBE_ALPHA);
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

    private void drawSegment(VertexConsumer buffer, PoseStack.Pose pose, PathGeometry geometry, int index,
                             float alpha) {
        drawTube(buffer, pose,
                geometry.pointX[index], geometry.pointY[index], geometry.pointZ[index],
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
        return dx * dx + dy * dy + dz * dz <= cullRadiusSq;
    }

    private void renderElytraPath(MultiBufferSource.BufferSource bufferSource, PoseStack.Pose pose,
                                   ElytraPath elytraPath, Vec3 camera, double cullRadiusSq) {
        VertexConsumer quadBuffer = bufferSource.getBuffer(RenderType.debugQuads());
        List<Vec3> waypoints = elytraPath.waypoints();
        for (int i = 0; i + 1 < waypoints.size(); i++) {
            Vec3 from = waypoints.get(i);
            Vec3 to = waypoints.get(i + 1);
            if (distanceSqToSegment(camera, from.x, from.y, from.z, to.x, to.y, to.z) > cullRadiusSq) {
                continue;
            }
            drawTube(quadBuffer, pose, from.x, from.y, from.z, to.x, to.y, to.z,
                    PathColors.ELYTRA[0], PathColors.ELYTRA[1], PathColors.ELYTRA[2], TUBE_ALPHA);
        }
        bufferSource.endBatch(RenderType.debugQuads());
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
    private void drawTube(VertexConsumer buffer, PoseStack.Pose pose,
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
        rightX = rightX / rightLength * TUBE_RADIUS;
        rightY = rightY / rightLength * TUBE_RADIUS;
        rightZ = rightZ / rightLength * TUBE_RADIUS;

        double upX = rightY * dirZ - rightZ * dirY;
        double upY = rightZ * dirX - rightX * dirZ;
        double upZ = rightX * dirY - rightY * dirX;
        double upLength = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        upX = upX / upLength * TUBE_RADIUS;
        upY = upY / upLength * TUBE_RADIUS;
        upZ = upZ / upLength * TUBE_RADIUS;

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
