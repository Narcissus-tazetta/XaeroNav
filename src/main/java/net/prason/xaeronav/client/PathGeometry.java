package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * ワールド内描画用に経路を焼き固めたもの。経路が変わったときにだけ組み直す。
 *
 * <p>同色かつ一直線に続く区間は1本の区間へまとめる。平坦な地形では数十〜数百の区間が
 * 1本になり、描画する頂点数がそのまま桁で減る。まとめても両端は元のままなので見た目は変わらない。
 */
final class PathGeometry {

    /** 2区間を一直線とみなす外積の大きさの上限。区間長が約1ブロックなので、この値なら実質的に厳密一致。 */
    private static final double COLLINEAR_EPSILON = 1.0e-6;

    /**
     * ゴールに届かなかった経路の末端を、消えていくように描くステップ数。ここだけは直線でも
     * まとめずに区間を分ける（濃さを段階的に落とすため）。
     */
    private static final int FADE_TAIL_STEPS = 8;

    private final PathResult source;

    /** 区間の端点。要素数は「区間数 + 1」。 */
    final double[] pointX;
    final double[] pointY;
    final double[] pointZ;
    /**
     * まとめる前の、ステップごとの描画位置（要素数は「ステップ数 + 1」）。
     * 通り過ぎた区間を落とすとき、まとめられた長い区間を途中で切るために要る。
     */
    private final double[] stepX;
    private final double[] stepY;
    private final double[] stepZ;
    /** 区間ごとのRGB（区間数 × 3）。 */
    final float[] segmentColor;
    /**
     * 区間の終端にあたるステップ番号。通り過ぎた区間を描かないために使う（区間は一直線ごとに
     * まとめられているので、ステップ番号から区間を引くにはこの対応が要る）。
     */
    final int[] segmentEndStep;

    final int[] highlightX;
    final int[] highlightY;
    final int[] highlightZ;
    /** ハイライトごとのRGB（ハイライト数 × 3）。 */
    final float[] highlightColor;
    /**
     * ハイライトが「これから置く場所」か。置いた瞬間に枠を消すため、描画側が毎フレーム
     * そのセルの現況を見る必要があるものだけを区別する（掘る場所は逆で、壊れるまで出し続ける）。
     */
    final boolean[] highlightPlacement;
    /**
     * 「次に掘る場所」にあたるハイライトの添字範囲 {@code [nextDigFrom, nextDigTo)}。経路の先頭側から
     * 最初に掘削を含む区間を指す（経路はプレイヤーの現在地から作り直されるので、これが手前の掘削地点になる）。
     */
    final int nextDigFrom;
    final int nextDigTo;
    /** この区間から先は打ち切られた末端。手前から順に薄くしていく。到達済みの経路では区間数と同じ。 */
    final int fadeFromSegment;

    private PathGeometry(PathResult source, double[] pointX, double[] pointY, double[] pointZ, float[] segmentColor,
                         int[] segmentEndStep, double[] stepX, double[] stepY, double[] stepZ,
                         int[] highlightX, int[] highlightY, int[] highlightZ, float[] highlightColor,
                         boolean[] highlightPlacement, int nextDigFrom, int nextDigTo, int fadeFromSegment) {
        this.source = source;
        this.pointX = pointX;
        this.pointY = pointY;
        this.pointZ = pointZ;
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
        this.segmentColor = segmentColor;
        this.segmentEndStep = segmentEndStep;
        this.highlightX = highlightX;
        this.highlightY = highlightY;
        this.highlightZ = highlightZ;
        this.highlightColor = highlightColor;
        this.highlightPlacement = highlightPlacement;
        this.nextDigFrom = nextDigFrom;
        this.nextDigTo = nextDigTo;
        this.fadeFromSegment = fadeFromSegment;
    }

    int segmentCount() {
        return segmentColor.length / 3;
    }

    int highlightCount() {
        return highlightColor.length / 3;
    }

    /** {@code step}をまだ通り過ぎていない最初の区間。すべて通り過ぎていれば区間数を返す。 */
    int firstSegmentFrom(int step) {
        for (int i = 0; i < segmentEndStep.length; i++) {
            if (segmentEndStep[i] > step) {
                return i;
            }
        }
        return segmentEndStep.length;
    }

    /**
     * {@code step}にいるプレイヤーに対応する、経路上の描き始めの点。
     * {@link #firstSegmentFrom}が返した区間の内側にある（区間は一直線なので端点の間に載る）。
     */
    double cutX(int step) {
        return stepX[step + 1];
    }

    double cutY(int step) {
        return stepY[step + 1];
    }

    double cutZ(int step) {
        return stepZ[step + 1];
    }

    boolean matches(PathResult result) {
        return this.source == result;
    }

    static PathGeometry build(PathResult result, BlockPos start) {
        List<PathStep> steps = result.steps();
        int count = steps.size();

        double[] rawX = new double[count + 1];
        double[] rawY = new double[count + 1];
        double[] rawZ = new double[count + 1];
        float[][] rawColor = new float[count][];

        center(start, rawX, rawY, rawZ, 0);
        for (int i = 0; i < count; i++) {
            PathStep step = steps.get(i);
            center(step.pos(), rawX, rawY, rawZ, i + 1);
            rawColor[i] = PathColors.forStep(step);
        }

        double[] outX = new double[count + 1];
        double[] outY = new double[count + 1];
        double[] outZ = new double[count + 1];
        float[][] outColor = new float[count][];
        int[] outEndStep = new int[count];
        outX[0] = rawX[0];
        outY[0] = rawY[0];
        outZ[0] = rawZ[0];
        int points = 1;
        int segments = 0;
        int tailStartStep = result.complete() ? count : Math.max(0, count - FADE_TAIL_STEPS);
        int fadeFromSegment = Integer.MAX_VALUE;

        for (int i = 1; i <= count; i++) {
            float[] color = rawColor[i - 1];
            boolean inTail = i > tailStartStep;
            if (!inTail && segments > 0 && outColor[segments - 1] == color
                    && continuesStraight(outX[points - 2], outY[points - 2], outZ[points - 2],
                    outX[points - 1], outY[points - 1], outZ[points - 1],
                    rawX[i], rawY[i], rawZ[i])) {
                outX[points - 1] = rawX[i];
                outY[points - 1] = rawY[i];
                outZ[points - 1] = rawZ[i];
                // 点の添字はステップの添字より1つ大きい（先頭の点はプレイヤーの現在地）
                outEndStep[segments - 1] = i - 1;
                continue;
            }
            outX[points] = rawX[i];
            outY[points] = rawY[i];
            outZ[points] = rawZ[i];
            points++;
            outColor[segments] = color;
            outEndStep[segments] = i - 1;
            segments++;
            if (inTail && fadeFromSegment == Integer.MAX_VALUE) {
                fadeFromSegment = segments - 1;
            }
        }

        float[] flatSegmentColor = new float[segments * 3];
        for (int i = 0; i < segments; i++) {
            flatSegmentColor[i * 3] = outColor[i][0];
            flatSegmentColor[i * 3 + 1] = outColor[i][1];
            flatSegmentColor[i * 3 + 2] = outColor[i][2];
        }

        List<BlockPos> highlightCells = new ArrayList<>();
        List<float[]> highlightColors = new ArrayList<>();
        List<Boolean> highlightPlacements = new ArrayList<>();
        int nextDigFrom = 0;
        int nextDigTo = 0;
        for (PathStep step : steps) {
            if (nextDigTo == 0 && step.digging()) {
                nextDigFrom = highlightCells.size();
                nextDigTo = nextDigFrom + step.digCells().size();
            }
            for (BlockPos cell : step.digCells()) {
                highlightCells.add(cell);
                highlightColors.add(PathColors.DIGGING);
                highlightPlacements.add(false);
            }
            if (step.bridging()) {
                highlightCells.add(step.placedBlockPos());
                highlightColors.add(PathColors.BRIDGE);
                highlightPlacements.add(true);
            }
        }

        int highlights = highlightCells.size();
        int[] hx = new int[highlights];
        int[] hy = new int[highlights];
        int[] hz = new int[highlights];
        float[] hColor = new float[highlights * 3];
        boolean[] hPlacement = new boolean[highlights];
        for (int i = 0; i < highlights; i++) {
            BlockPos cell = highlightCells.get(i);
            hx[i] = cell.getX();
            hy[i] = cell.getY();
            hz[i] = cell.getZ();
            float[] color = highlightColors.get(i);
            hColor[i * 3] = color[0];
            hColor[i * 3 + 1] = color[1];
            hColor[i * 3 + 2] = color[2];
            hPlacement[i] = highlightPlacements.get(i);
        }

        return new PathGeometry(result,
                Arrays.copyOf(outX, points), Arrays.copyOf(outY, points), Arrays.copyOf(outZ, points),
                flatSegmentColor, Arrays.copyOf(outEndStep, segments), rawX, rawY, rawZ,
                hx, hy, hz, hColor, hPlacement, nextDigFrom, nextDigTo,
                Math.min(fadeFromSegment, segments));
    }

    /** {@code b}が{@code a}から{@code c}への一直線上にあり、かつ折り返していないか。 */
    private static boolean continuesStraight(double ax, double ay, double az,
                                             double bx, double by, double bz,
                                             double cx, double cy, double cz) {
        double ux = bx - ax;
        double uy = by - ay;
        double uz = bz - az;
        double vx = cx - bx;
        double vy = cy - by;
        double vz = cz - bz;
        double crossX = uy * vz - uz * vy;
        double crossY = uz * vx - ux * vz;
        double crossZ = ux * vy - uy * vx;
        if (crossX * crossX + crossY * crossY + crossZ * crossZ > COLLINEAR_EPSILON) {
            return false;
        }
        return ux * vx + uy * vy + uz * vz > 0;
    }

    /**
     * 経路のセルを線の通過点にする。
     *
     * <p>水中も他と同じく実際のセルのYを使う。以前は水中のセルを「水面のすぐ上」あるいは
     * 「プレイヤーの足元の高さ」へ揃えていたが、どちらも<b>同じ列の高さ違いを1点に潰す</b>ので、
     * XZが同一でYだけ違う{@code SwimUp}/{@code SwimDown}が区間長0になって描画ごと消えていた。
     * 潜降・浮上が線として見えないのはそれが理由。
     */
    private static void center(BlockPos pos, double[] outX, double[] outY, double[] outZ, int index) {
        outX[index] = pos.getX() + 0.5;
        outY[index] = pos.getY() + 0.55;
        outZ[index] = pos.getZ() + 0.5;
    }
}
