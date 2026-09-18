package net.prason.xaeronav.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * 「目的地へいちばん近づいた所から、そのあと遠ざかった」ことの検出。ネザーの実機で
 * <b>約300ブロックの往復</b>が出たのを、次に起きたときログから追えるようにするためのもの。
 *
 * <p>経路の形では判定できない。区間ごとには常に正しい経路が引かれていて、悪いのは
 * 「東の回廊へ入り、行き止まりで西へ引き返す」という<b>計画をまたいだ動き</b>だから
 * （実機2026-09-18: 目的地まで189→229→242ブロックと離れていった）。
 *
 * <p>溶岩の海や奈落を大きく迂回する経路は、目的地から遠ざかりながら正しく進んでいる。
 * だから{@link #RETREAT_BLOCKS}まではふつうの迂回として黙っている。
 *
 * <p>判定だけを持ち、ログは{@code PathfindingState}が出す（{@link StuckTracker}と同じ分け方）。
 */
final class RetreatWatcher {

    /**
     * 最接近からこれだけ遠ざかったら記録する（ブロック）。
     *
     * <p>実機の往復は約300ブロックで、正しい迂回（溶岩の海の縁を回る）は実測で最大
     * 60ブロック台だった（模型のネザー3本で最悪の後退が63/61/24）。その間に置いてある。
     */
    private static final double RETREAT_BLOCKS = 80.0;

    /** 1回の後退で何度も書かないための間隔（ブロック）。 */
    private static final double REPORT_STEP_BLOCKS = 32.0;

    private double closest = Double.MAX_VALUE;
    private @Nullable BlockPos closestAt;
    /** 最後に記録したときの距離。まだ記録していなければ0。 */
    private double reportedDistance;

    /**
     * 記録に値する後退。{@code closest}は最接近したときの水平距離、{@code distance}は今の水平距離。
     */
    record Retreat(BlockPos at, double distance, BlockPos closestAt, double closest) {

        double retreated() {
            return distance - closest;
        }
    }

    void reset() {
        closest = Double.MAX_VALUE;
        closestAt = null;
        reportedDistance = 0;
    }

    /**
     * 今の位置を見せる。記録に値する後退が起きていればそれを返す。
     *
     * <p>近づいたときは最接近を更新して記録の間隔もリセットする——そこから先は別の後退として数える。
     */
    @Nullable Retreat observe(BlockPos at, BlockPos goal) {
        double distance = horizontal(at, goal);
        if (distance < closest) {
            closest = distance;
            closestAt = at;
            reportedDistance = 0;
            return null;
        }
        if (closestAt == null || distance - closest < RETREAT_BLOCKS) {
            return null;
        }
        if (reportedDistance > 0 && distance - reportedDistance < REPORT_STEP_BLOCKS) {
            return null;
        }
        reportedDistance = distance;
        return new Retreat(at, distance, closestAt, closest);
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
