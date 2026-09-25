package net.prason.xaeronav.client;

import java.util.Arrays;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.flight.VoxelRay;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.util.MathSupport;

/**
 * ワールド内描画用に経路を焼き固めたもの。経路が変わったときにだけ組み直す。
 *
 * <p>同色かつ一直線に続く区間は1本の区間へまとめる。平坦な地形では数十〜数百の区間が
 * 1本になり、描画する頂点数がそのまま桁で減る。まとめても両端は元のままなので見た目は変わらない。
 *
 * <p>水中とボートの区間だけは、一直線でなくても<b>通せる限り</b>まとめる（{@link #fluidShortcut}）。
 * 陸と違って1手ごとの位置に意味が無く、格子の目に沿った階段がそのままジグザグに見えるため。
 */
final class PathGeometry {

    /** 水面の区間の線を水面よりわずかに浮かせ、水面のテクスチャとのZファイティングを避ける。 */
    private static final double WATER_SURFACE_OFFSET = 0.05;

    /**
     * 泳いで渡る区間の線を、水面からどれだけ沈めて描くか（ブロック）。
     *
     * <p><b>うつ伏せ泳ぎの目線は水面そのもの</b>（{@code Pose.SWIMMING}はeyeHeight 0.4で、
     * 体は目が水面に来る高さで浮く）。そこへ線を水面に置くと、渡り切るまで画面の中央＝水平線の
     * 上に棒が載り続ける。逃がす方向が下なのは、上へ逃がすと今度は見上げたときに同じことに
     * なるのと、水中から見上げる場面で水面の描画に紛れるため。
     *
     * <p>値は「近くでは視界の外、遠くではまだ読める」で決まる。1.25なら2マス先で視線の32度下
     * （既定FOV70の縦の画角＝上下35度のすぐ外）、10マス先で7度下に来る。
     *
     * <p><b>ボートは沈めない。</b>あちらは目線が水面より1マス以上上にあるので、水面の線は
     * 元から視界を塞がない。
     */
    private static final double SWIM_LINE_DEPTH = 1.25;

    /** 2区間を一直線とみなす外積の大きさの上限。区間長が約1ブロックなので、この値なら実質的に厳密一致。 */
    private static final double COLLINEAR_EPSILON = 1.0e-6;

    /**
     * ゴールに届かなかった経路の末端を、消えていくように描くステップ数。ここだけは直線でも
     * まとめずに区間を分ける（濃さを段階的に落とすため）。
     */
    private static final int FADE_TAIL_STEPS = 8;

    /**
     * 水中・ボートの区間を1本の直線へ畳んでよい最大の長さ（ブロック）。
     *
     * <p>畳める長さの上限が要るのは、判定が「区間の始点から候補までの弦を毎回走査し直す」形
     * ——伸ばすたびに全長を見るのでO(長さ^2)——だから。長い直線が数本に分かれるだけで
     * 見た目はほとんど変わらない（{@code FlightSmoother#LOOKAHEAD_POINTS}と同じ考え方）。
     */
    private static final int MAX_FLUID_SHORTCUT_BLOCKS = 32;

    /** 区間の端点。要素数は「区間数 + 1」。 */
    final double[] pointX;
    final double[] pointY;
    final double[] pointZ;
    /** 区間ごとのRGB（区間数 × 3）。 */
    final float[] segmentColor;
    /**
     * 区間の終端にあたるステップ番号。通り過ぎた区間を描かないために使う（区間は一直線ごとに
     * まとめられているので、ステップ番号から区間を引くにはこの対応が要る）。
     */
    final int[] segmentEndStep;
    /**
     * 両端とも{@link #SWIM_LINE_DEPTH}ぶん沈めて描いた区間か。描画側が自分の周りを抜くために使う
     * （{@code PathRenderer#SWIM_NEAR_CLIP_BLOCKS}）。
     */
    final boolean[] segmentSunk;
    /**
     * 両端のセルがどちらも水の区間か。水の外から見ると、この区間は水の描画に深度で隠れる
     * （{@code PathRenderer#THROUGH_WATER_ALPHA}）。
     */
    final boolean[] segmentInWater;
    /**
     * 危険区間か（{@link PathColors.Kind#DANGER}）。色だけに頼らない識別のため、描画側が
     * 破線で強調する（A11Y-01）。区間内のステップは同じ色＝同じ分類にまとめられているので、
     * 区間ごとに1つ持てば足りる。
     */
    final boolean[] segmentDashed;

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
     * ハイライトごとの元の{@link PathStep}の添字（昇順）。通り過ぎたハイライトを描かないために要る。
     *
     * <p>線の方は{@link #segmentEndStep}で切り詰めているのに、ハイライトには対応する情報が無く
     * <b>枠だけが経路の引き直しまで残っていた</b>。設置予定地は「実際に置かれた」ときにしか枠が
     * 消えない（{@code PathRenderer#placementPending}）ので、置かずに脇を通り過ぎた設置予定地は
     * セルが{@code replaceable}のまま＝背後に青い枠が残り続ける。
     */
    final int[] highlightStep;
    /**
     * 設置予定地が「足元に置いて真上へ上る」手のものか。置く先は直前の手で立つセルそのものなので、そこへ着く前に
     * 置かれると立つ場所が埋まり、手前からは2段上れず計画が成り立たなくなる（実機2026-09-26: 前方に見えた枠へ
     * 手前から置き、引き直しで横にまた置く枠が出た）。描画側はこの枠を踏み切り点に着くまで出さない。
     */
    final boolean[] highlightPillar;
    /** この区間から先は打ち切られた末端。手前から順に薄くしていく。到達済みの経路では区間数と同じ。 */
    final int fadeFromSegment;

    private PathGeometry(double[] pointX, double[] pointY, double[] pointZ, float[] segmentColor,
                         int[] segmentEndStep, boolean[] segmentSunk, boolean[] segmentInWater,
                         boolean[] segmentDashed,
                         int[] highlightX, int[] highlightY, int[] highlightZ, float[] highlightColor,
                         boolean[] highlightPlacement, int[] highlightStep, boolean[] highlightPillar,
                         int fadeFromSegment) {
        this.pointX = pointX;
        this.pointY = pointY;
        this.pointZ = pointZ;
        this.segmentColor = segmentColor;
        this.segmentEndStep = segmentEndStep;
        this.segmentSunk = segmentSunk;
        this.segmentInWater = segmentInWater;
        this.segmentDashed = segmentDashed;
        this.highlightX = highlightX;
        this.highlightY = highlightY;
        this.highlightZ = highlightZ;
        this.highlightColor = highlightColor;
        this.highlightPlacement = highlightPlacement;
        this.highlightStep = highlightStep;
        this.highlightPillar = highlightPillar;
        this.fadeFromSegment = fadeFromSegment;
    }

    /** ハイライトの添字範囲 {@code [from, to)}。{@link #from} と {@link #to} が等しければ空。 */
    record Range(int from, int to) {
        boolean contains(int index) {
            return index >= from && index < to;
        }

        boolean isEmpty() {
            return from >= to;
        }
    }

    /**
     * {@code fromStep}以降で最初に掘るステップの、掘削セルのハイライト範囲。1手で複数セルを掘ることがあるので範囲で返す。
     *
     * <p>「次に掘る場所」は<b>いま居るステップから先</b>で探す。経路の先頭から決め打ちにすると、
     * 掘る場所を通り過ぎた後もそこを濃く塗り続ける。
     */
    Range nextDig(int fromStep) {
        for (int i = 0; i < highlightStep.length; i++) {
            if (highlightStep[i] < fromStep || highlightPlacement[i]) {
                continue;
            }
            int to = i + 1;
            while (to < highlightStep.length && highlightStep[to] == highlightStep[i] && !highlightPlacement[to]) {
                to++;
            }
            return new Range(i, to);
        }
        return new Range(0, 0);
    }

    int segmentCount() {
        return segmentColor.length / 3;
    }

    int highlightCount() {
        return highlightColor.length / 3;
    }

    /**
     * {@code step}をまだ通り過ぎていない最初の区間。すべて通り過ぎていれば区間数を返す。
     *
     * <p>境界は{@code >=}(以上)。{@code step}は「プレイヤーに最も近いステップ」であって
     * 「到達済みのステップ」ではない――経路計算直後は自分の足元が最初のステップに最も近く、
     * 1歩も動いていなくても{@code step}は0になる。{@code >}(より大きい)にすると、その最初の
     * ステップで終わる区間まるごとが「通り過ぎた」扱いになり、真下から線が生えなくなる。
     */
    int firstSegmentFrom(int step) {
        for (int i = 0; i < segmentEndStep.length; i++) {
            if (segmentEndStep[i] >= step) {
                return i;
            }
        }
        return segmentEndStep.length;
    }

    /**
     * プレイヤーの現在地に対応する、区間{@code segment}の描き始めの点を{@code out}へ書く。
     *
     * <p><b>プレイヤーの連続座標そのもの</b>をその区間の弦へ射影する。以前は「最も近いステップの
     * 位置」を代わりに射影していたが、その最寄りステップ探索は経路の始点(プレイヤー自身がまだ
     * 立っている場所)を候補に含まない――そのため経路計算直後で1歩も動いていなくても最初の
     * ステップが常に最寄りとなり、真下からではなくその1歩先から線が生え始めていた。
     */
    void cutPoint(int segment, double playerX, double playerY, double playerZ, double[] out) {
        projectOntoSegment(playerX, playerY, playerZ,
                pointX[segment], pointY[segment], pointZ[segment],
                pointX[segment + 1], pointY[segment + 1], pointZ[segment + 1], out);
    }

    /** 点{@code p}を線分{@code a}-{@code b}へ射影した点（線分の外へは出さない）。 */
    static void projectOntoSegment(double px, double py, double pz,
                                   double ax, double ay, double az,
                                   double bx, double by, double bz, double[] out) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        double t = lengthSq < 1.0e-12 ? 0.0
                : MathSupport.clamp(((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / lengthSq, 0.0, 1.0);
        out[0] = ax + dx * t;
        out[1] = ay + dy * t;
        out[2] = az + dz * t;
    }

    static PathGeometry build(Level level, PathResult result, BlockPos start) {
        List<PathStep> steps = result.steps();
        int count = steps.size();

        double[] rawX = new double[count + 1];
        double[] rawY = new double[count + 1];
        double[] rawZ = new double[count + 1];
        float[][] rawColor = new float[count][];
        // 描画位置とは別に、元のブロック座標も持っておく。水面の区間は線を水面の上へ持ち上げる
        // ので、描画位置をそのまま通行判定に使うと1つ上の空気のセルを見てしまう
        BlockPos[] rawBlock = new BlockPos[count + 1];

        // 沈めて描いた点。区間ごとの印（segmentSunk）を後から組み立てるために持つ
        boolean[] rawSunk = new boolean[count + 1];
        boolean[] rawInWater = new boolean[count + 1];
        // 危険区間か（区間ごとの印segmentDashedを後から組み立てるために持つ、A11Y-01）
        boolean[] rawDangerous = new boolean[count];

        rawBlock[0] = start;
        // 始点は、そこから出ていく1手と同じ扱いにする。別扱いにすると先頭の1区間だけ段差になる
        rawSunk[0] = center(level, start, steps.isEmpty() ? null : steps.get(0), rawX, rawY, rawZ, 0);
        rawInWater[0] = level.getFluidState(start).is(FluidTags.WATER);
        for (int i = 0; i < count; i++) {
            PathStep step = steps.get(i);
            rawBlock[i + 1] = step.pos();
            rawSunk[i + 1] = center(level, step.pos(), step, rawX, rawY, rawZ, i + 1);
            rawInWater[i + 1] = level.getFluidState(step.pos()).is(FluidTags.WATER);
            rawColor[i] = PathColors.forStep(step);
            rawDangerous[i] = PathColors.kindFor(step) == PathColors.Kind.DANGER;
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

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // いま伸ばしている区間の始点にあたる raw の添字。水中の近道判定はこの点からの弦を見る
        int segmentStart = 0;
        for (int i = 1; i <= count; i++) {
            float[] color = rawColor[i - 1];
            boolean inTail = i > tailStartStep;
            if (!inTail && segments > 0 && outColor[segments - 1] == color
                    && (continuesStraight(outX[points - 2], outY[points - 2], outZ[points - 2],
                    outX[points - 1], outY[points - 1], outZ[points - 1],
                    rawX[i], rawY[i], rawZ[i])
                    || fluidShortcut(level, cursor, color, rawBlock[segmentStart], rawBlock[i]))) {
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
            segmentStart = i - 1;
            if (inTail && fadeFromSegment == Integer.MAX_VALUE) {
                fadeFromSegment = segments - 1;
            }
        }

        float[] flatSegmentColor = new float[segments * 3];
        boolean[] flatSegmentSunk = new boolean[segments];
        boolean[] flatSegmentInWater = new boolean[segments];
        boolean[] flatSegmentDashed = new boolean[segments];
        int startRaw = 0;
        for (int i = 0; i < segments; i++) {
            flatSegmentColor[i * 3] = outColor[i][0];
            flatSegmentColor[i * 3 + 1] = outColor[i][1];
            flatSegmentColor[i * 3 + 2] = outColor[i][2];
            int endRaw = outEndStep[i] + 1;
            flatSegmentSunk[i] = rawSunk[startRaw] && rawSunk[endRaw];
            flatSegmentInWater[i] = rawInWater[startRaw] && rawInWater[endRaw];
            // 区間内は同じ色＝同じ分類にまとめられているので、末尾ステップの判定で区間全体を代表できる
            flatSegmentDashed[i] = rawDangerous[outEndStep[i]];
            startRaw = endRaw;
        }

        // 上限は事前に数えられる（1手につきdigCells()の数＋bridging分1個）ので、ArrayList<Boolean>/
        // <Integer>のboxingを経由せずプリミティブ配列へ直接書き込む
        int highlightCapacity = 0;
        for (int i = 0; i < count; i++) {
            PathStep step = steps.get(i);
            highlightCapacity += step.digCells().size();
            if (step.bridging()) {
                highlightCapacity++;
            }
        }

        int[] hx = new int[highlightCapacity];
        int[] hy = new int[highlightCapacity];
        int[] hz = new int[highlightCapacity];
        float[] hColor = new float[highlightCapacity * 3];
        boolean[] hPlacement = new boolean[highlightCapacity];
        int[] hStep = new int[highlightCapacity];
        boolean[] hPillar = new boolean[highlightCapacity];
        int highlights = 0;
        for (int i = 0; i < count; i++) {
            PathStep step = steps.get(i);
            for (BlockPos cell : step.digCells()) {
                hx[highlights] = cell.getX();
                hy[highlights] = cell.getY();
                hz[highlights] = cell.getZ();
                hColor[highlights * 3] = PathColors.DIGGING[0];
                hColor[highlights * 3 + 1] = PathColors.DIGGING[1];
                hColor[highlights * 3 + 2] = PathColors.DIGGING[2];
                hPlacement[highlights] = false;
                hStep[highlights] = i;
                highlights++;
            }
            if (step.bridging()) {
                BlockPos cell = step.placedBlockPos();
                hx[highlights] = cell.getX();
                hy[highlights] = cell.getY();
                hz[highlights] = cell.getZ();
                hColor[highlights * 3] = PathColors.BRIDGE[0];
                hColor[highlights * 3 + 1] = PathColors.BRIDGE[1];
                hColor[highlights * 3 + 2] = PathColors.BRIDGE[2];
                hPlacement[highlights] = true;
                hStep[highlights] = i;
                hPillar[highlights] = placesUnderPrevious(steps, i, start);
                highlights++;
            }
        }

        return new PathGeometry(
                Arrays.copyOf(outX, points), Arrays.copyOf(outY, points), Arrays.copyOf(outZ, points),
                flatSegmentColor, Arrays.copyOf(outEndStep, segments), flatSegmentSunk, flatSegmentInWater,
                flatSegmentDashed,
                hx, hy, hz, hColor, hPlacement, hStep, hPillar, Math.min(fadeFromSegment, segments));
    }

    /**
     * {@code index}のステップが、直前の手で立つセル（先頭なら経路の始点）へ置いて上る手か。橋の置く先は着地点の下で、
     * 直前の位置とは重ならない。
     */
    static boolean placesUnderPrevious(List<PathStep> steps, int index, BlockPos start) {
        BlockPos placed = steps.get(index).placedBlockPos();
        BlockPos previous = index == 0 ? start : steps.get(index - 1).pos();
        return placed != null && placed.equals(previous);
    }

    /**
     * 水中・ボートの区間を、格子の目に沿った折れ線ではなく<b>通せる限りの直線</b>にしてよいか。
     *
     * <p>陸の経路では1手ごとの位置に意味がある（このブロックの上に立つ、という指示そのもの）。
     * 水の中とボートの上には足場が無く、A*が返す階段状の並びは<b>探索格子の都合でしかない</b>——
     * 地形が無いぶんその階段がそのまま線に出るので、開けた海では意味の無いジグザグに見える。
     * 追うべきなのは向きだけ、という点で滑空中の線と同じ性質なので、扱いも揃える
     * （{@code FlightSmoother}のstring pullと同じ考え方で、判定も同じ{@link VoxelRay}を使う）。
     *
     * <p>近道が通る全セルが水であること、かつその1つ上が掘らずに通れることを求める。前者だけだと
     * 岬や浅瀬を突っ切る線になり、後者を見ないと天井の低い水路で体がつかえる線になる。
     */
    private static boolean fluidShortcut(Level level, BlockPos.MutableBlockPos cursor, float[] color,
                                         BlockPos from, BlockPos to) {
        if (color != PathColors.SWIM && color != PathColors.BOAT && color != PathColors.DROWNING) {
            return false;
        }
        if (from.distSqr(to) > (double) MAX_FLUID_SHORTCUT_BLOCKS * MAX_FLUID_SHORTCUT_BLOCKS) {
            return false;
        }
        // 判定はブロック座標のセル中心どうしで行う。描画位置は水面の区間だけ持ち上げてあるので、
        // そちらを渡すと1つ上の空気のセルを走査してしまう
        Vec3 a = new Vec3(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5);
        Vec3 b = new Vec3(to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5);
        return VoxelRay.traverse(a, b, (x, y, z) -> {
            cursor.set(x, y, z);
            if (!CellData.water(CellData.flagsOf(level.getBlockState(cursor)))) {
                return false;
            }
            cursor.set(x, y + 1, z);
            return CellData.occupiableWithoutDigging(CellData.flagsOf(level.getBlockState(cursor)));
        });
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
     * 経路のセルを線の通過点にする。沈めて描いたなら{@code true}。
     *
     * <p><b>水面のセルだけ</b>は線をセル中心から動かす。水面のセルはブロックの高さで言えば
     * 水の中なので、セル中心（+0.55）に描くと水面の描画に沈み、ボートに乗っていると自分の体の
     * 真下になって見えない。水の上を進む区間（ボート・水面を泳ぐ）はこれが常態になる。
     *
     * <p>動かす向きは<b>足場の有無</b>で分かれる。足が着いていれば目線は水面より上にあるので
     * 水面の上へ持ち上げる（ボート・浅瀬を歩く区間）。足場が無い＝泳いでいる区間は目線が水面
     * そのものなので、逆に{@link #SWIM_LINE_DEPTH}ぶん沈める。<b>沈める側は水面と水中の
     * 段差も同時に消える</b>——持ち上げていた頃は、経路が1マス潜るだけで線が1.5マス落ちていた
     * （水面セルが+1.05、その1つ下が+0.55）。
     *
     * <p>水面のセル以外でセルのYをそのまま使うのは変えていない。以前は<b>水中のセルを列ごと
     * 水面へ揃えて</b>いて、XZが同一でYだけ違う{@code SwimUp}/{@code SwimDown}が1点に潰れ、
     * 潜降・浮上が区間長0になって描画ごと消えていた。
     */
    private static boolean center(Level level, BlockPos pos, PathStep step,
                                  double[] outX, double[] outY, double[] outZ, int index) {
        outX[index] = pos.getX() + 0.5;
        outZ[index] = pos.getZ() + 0.5;
        if (!isWaterSurface(level, pos)) {
            outY[index] = pos.getY() + 0.55;
            return false;
        }
        if (sinkable(level, pos, step)) {
            outY[index] = pos.getY() + 1.0 - SWIM_LINE_DEPTH;
            return true;
        }
        outY[index] = pos.getY() + 1.0 + WATER_SURFACE_OFFSET;
        return false;
    }

    /**
     * この水面のセルを、泳いでいる区間として沈めて描いてよいか。
     *
     * <p>ボートを除くのは目線の高さが違うから（{@link #SWIM_LINE_DEPTH}）。足場を見るのは、
     * 浅瀬を<b>歩いて</b>渡る区間も水面のセルを通るため——そこで沈めると、線が自分の歩いている
     * 地面の下へ潜る。{@code MoveKind.SWIM}は足が着いていても付くので、種類ではなく足場で見る。
     */
    private static boolean sinkable(Level level, BlockPos pos, PathStep step) {
        return step != null && !step.boating()
                && !CellData.standable(CellData.flagsOf(level.getBlockState(pos.below())));
    }

    /** 水のセルで、かつ真上が水でない＝そこが水面。 */
    private static boolean isWaterSurface(Level level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER)
                && !level.getFluidState(pos.above()).is(FluidTags.WATER);
    }
}
