package net.prason.xaeronav.client;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.world.CellData;

/**
 * 提示中の経路が今のワールドでもまだ成立するかを確認する。
 *
 * <p>プレイヤーが動かなくてもワールドは変わりうる。とはいえ変化を拾うためだけに探索し直すのは
 * 高くつくので、経路上のセルだけを見る。ステップ数に比例した数百回のブロック参照で済み、
 * 探索をやり直すより二桁安い。
 *
 * <p>判定は{@link CellData#flagsOf}を使って探索側と共有する。ここが探索と食い違うと、
 * 探索が通した経路を即座に無効と判断して再計算が止まらなくなる。
 */
final class PathValidator {

    private PathValidator() {
    }

    static boolean stillValid(Level level, PathResult result) {
        return firstFailure(level, result) == null;
    }

    /**
     * 無効化する最初の理由。診断用——{@code stillValid}が{@code false}を返したとき、
     * <b>どのステップの何が</b>不成立になったのかは呼び出し側から見えなかった。実機
     * （ジ・エンドの島渡り、2026-08-28）で、渡り切った直後（2秒後）に経路が「地形が変わった」で
     * 手放される現象があり、原因の切り分けにこれが要る。
     */
    static String firstFailure(Level level, PathResult result) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        List<PathStep> steps = result.steps();
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            BlockPos pos = step.pos();
            if (step.swimming() || step.boating()) {
                // 泳ぐ区間もボートの区間も、足場ではなく水そのものが前提
                if (!CellData.water(CellData.flagsOf(level.getBlockState(pos)))) {
                    return "ステップ%d(%s) 泳ぐ/ボート前提の水が無い pos=%s".formatted(i, step.movement(), pos.toShortString());
                }
            } else if (step.climbing()) {
                // 梯子・ツタの区間も足場ではなく掴めるもの自体が前提
                if (!CellData.climbable(CellData.flagsOf(level.getBlockState(pos)))) {
                    return "ステップ%d(%s) 掴める物が無い pos=%s".formatted(i, step.movement(), pos.toShortString());
                }
            } else if (!step.bridging()) {
                // ブロックを置いて渡る区間は、足元が空いていることが前提なので床を確認しない
                cursor.set(pos.getX(), pos.getY() - 1, pos.getZ());
                if (!CellData.standable(CellData.flagsOf(level.getBlockState(cursor)))) {
                    return "ステップ%d(%s) 足場が無い pos=%s".formatted(i, step.movement(), cursor.immutable().toShortString());
                }
            }
            // 掘り終えた区間を「まだ掘る場所」として提示し続けないよう、掘る前提のセルが
            // 空いていたら経路ごと組み直す（掘れば経路自体も安くなりうる）
            for (BlockPos cell : step.digCells()) {
                if (CellData.occupiableWithoutDigging(CellData.flagsOf(level.getBlockState(cell)))) {
                    return "ステップ%d(%s) 掘る前提のセルが既に空いている cell=%s"
                            .formatted(i, step.movement(), cell.toShortString());
                }
            }
            for (BlockPos cell : step.bodyCells()) {
                if (step.digCells().contains(cell)) {
                    continue;
                }
                long flags = CellData.flagsOf(level.getBlockState(cell));
                // 閉じたドアは通れる前提（開けて通る）なので、塞がっているとは見なさない
                if (!CellData.occupiableWithoutDigging(flags) && !CellData.openable(flags)) {
                    return "ステップ%d(%s, bridging=%s) 身体が通るセルが塞がっている cell=%s state=%s"
                            .formatted(i, step.movement(), step.bridging(), cell.toShortString(),
                                    level.getBlockState(cell));
                }
            }
        }
        return null;
    }
}
