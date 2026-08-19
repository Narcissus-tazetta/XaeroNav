package net.prason.xaeronav.pathfinding.flight;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 線分が通るセルを1つ残らず列挙する（Amanatides–Wooのボクセル走査）。
 *
 * <p>一定間隔で点を打つ方式では、サンプルとサンプルの間にある壁や尾根をまるごと跨いで見落とす。
 * エリトラは秒速30マス級で飛ぶので、見落とした壁は激突と同義になる。判定の粗さがそのまま事故に
 * なる場所なので、ここは間引かずに全セルを見る。
 *
 * <p>セルの大きさは1に固定してある。粗い格子で走査したい呼び出し側は、座標をセル幅で割ってから
 * 渡すこと（{@link AirGrid#clearLine}がそうしている）。座標系の変換を走査側に持ち込むと、
 * 境界までの距離の計算にセル幅が混ざって読みにくくなるだけで、得るものが無い。
 */
public final class VoxelRay {

    @FunctionalInterface
    public interface CellTest {

        /** そのセルを通ってよいか。falseを返した時点で走査は打ち切られる。 */
        boolean passable(int x, int y, int z);
    }

    private VoxelRay() {
    }

    /** 線分が通る全セルが{@code test}を満たすか。 */
    public static boolean traverse(Vec3 from, Vec3 to, CellTest test) {
        int x = Mth.floor(from.x);
        int y = Mth.floor(from.y);
        int z = Mth.floor(from.z);
        int lastX = Mth.floor(to.x);
        int lastY = Mth.floor(to.y);
        int lastZ = Mth.floor(to.z);

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        int stepX = (int) Math.signum(dx);
        int stepY = (int) Math.signum(dy);
        int stepZ = (int) Math.signum(dz);
        // 線分の長さを1としたときの、次のセル境界までの距離とセル1つ分の距離
        double nextX = boundaryFraction(from.x, stepX, dx);
        double nextY = boundaryFraction(from.y, stepY, dy);
        double nextZ = boundaryFraction(from.z, stepZ, dz);
        double spanX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double spanY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double spanZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);

        while (true) {
            if (!test.passable(x, y, z)) {
                return false;
            }
            if (x == lastX && y == lastY && z == lastZ) {
                return true;
            }
            // 最も近い境界を1つだけ跨ぐ。1を超えたらもう線分の外
            if (nextX <= nextY && nextX <= nextZ) {
                if (nextX > 1.0) {
                    return true;
                }
                x += stepX;
                nextX += spanX;
            } else if (nextY <= nextZ) {
                if (nextY > 1.0) {
                    return true;
                }
                y += stepY;
                nextY += spanY;
            } else {
                if (nextZ > 1.0) {
                    return true;
                }
                z += stepZ;
                nextZ += spanZ;
            }
        }
    }

    /** 進行方向にある最初のセル境界までの距離（線分の長さを1とした比率）。 */
    private static double boundaryFraction(double position, int step, double delta) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double offsetInCell = position - Math.floor(position);
        return (step > 0 ? 1.0 - offsetInCell : offsetInCell) / Math.abs(delta);
    }
}
