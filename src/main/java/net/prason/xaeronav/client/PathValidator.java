package net.prason.xaeronav.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 成立しなくなったステップと、その理由。
     *
     * <p>理由の文字列は診断用。<b>添字</b>の方は迂回の判断に要る——変化が「もう歩き終えた区間」
     * なのか「これから通る区間」なのか、これから通るならどこから先を作り直せば足りるのかは、
     * どのステップで壊れたかが分からないと決められない。
     */
    record Failure(int stepIndex, String reason) {
    }

    /**
     * {@code fromIndex}から先で最初に成立しなくなったステップ。全て成立しているなら{@code null}。
     *
     * <p>手前を見ないのは、経路を部分的に作り直すときに「作り直さない区間」まで無効の理由に
     * 数えてしまうと、迂回できるはずの経路まで全引き直しへ落ちるため。
     */
    static Failure firstFailureFrom(Level level, PathResult result, int fromIndex) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        List<PathStep> steps = result.steps();
        // 掘削は経路全体で積み上げる。<b>手前のステップで掘るセルは、その先のステップにとっても
        // 「通れる前提」</b>——掘るのはプレイヤーがそこへ着いてからなので、いま塞がっているのは
        // 当たり前で、変化ではない。ステップ自身の掘削しか見ていなかった頃は、砂利を掘って登る
        // ような経路が毎回この検査で蹴られ、探索は同じ経路を出し直すので<b>2秒ごとの全引き直しが
        // 永久に続いた</b>（実機ログで110秒・34回）。しかも「失敗」ではないので緩和も
        // エスカレーションも走らない
        Set<BlockPos> plannedDigs = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            plannedDigs.addAll(step.digCells());
            if (i < fromIndex) {
                continue;
            }
            String reason = stepFailure(level, step, i, cursor, plannedDigs);
            if (reason != null) {
                return new Failure(i, reason);
            }
        }
        return null;
    }

    /**
     * このステップ<b>単体</b>が今のワールドで成立しない理由。成立するなら{@code null}。
     *
     * <p>手前のステップの掘削を織り込まないので、合流点を探すときのように「他所から来て
     * いきなりここへ入れるか」を問う用途に使う。経路を順に辿る検査は{@link #firstFailureFrom}。
     */
    static String stepFailure(Level level, PathStep step, int index) {
        return stepFailure(level, step, index, new BlockPos.MutableBlockPos(), Set.copyOf(step.digCells()));
    }

    /**
     * このステップで<b>塞がっていてはいけない</b>身体セル＝手前（自分自身を含む）で掘る予定に
     * なっていないもの。
     */
    static List<BlockPos> unexcavatedBodyCells(PathStep step, Set<BlockPos> plannedDigs) {
        return step.bodyCells().stream().filter(cell -> !plannedDigs.contains(cell)).toList();
    }

    private static String stepFailure(Level level, PathStep step, int i, BlockPos.MutableBlockPos cursor,
                                      Set<BlockPos> plannedDigs) {
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
        for (BlockPos cell : unexcavatedBodyCells(step, plannedDigs)) {
            long flags = CellData.flagsOf(level.getBlockState(cell));
            // 閉じたドアは通れる前提（開けて通る）なので、塞がっているとは見なさない
            if (!CellData.occupiableWithoutDigging(flags) && !CellData.openable(flags)) {
                return "ステップ%d(%s, bridging=%s) 身体が通るセルが塞がっている cell=%s state=%s"
                        .formatted(i, step.movement(), step.bridging(), cell.toShortString(),
                                level.getBlockState(cell));
            }
        }
        return null;
    }
}
