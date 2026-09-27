package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * <b>経路が同じ位置を2度通っている区間を畳む。</b>
 *
 * <p>1回の{@link AStarPathfinder}では起きない（同じセルを二度閉じない）。生まれるのは
 * <b>継ぎ足しと合流の繋ぎ目</b>で、後ろの区間は前の区間がどこを通ったかを知らないまま解かれるため。
 * ユーザー報告「同じブロックに2つのルートが重なる」がこれ。
 *
 * <p><b>掘削・設置を含む区間は畳まない。</b>置いたブロックの上を後の手が歩いていることがあり、
 * 消すと足場ごと消える。掘った跡も同じで、後の手はその穴を通る前提で繋がっている。
 *
 * <p>始点そのものへ戻る折り返しは畳まない（始点はステップではないので、この列に現れない）。
 */
public final class PathLoops {

    /**
     * @param steps    畳んだ後の列
     * @param newIndex 畳む前の添字に対応する畳んだ後の添字。消えたステップは<b>その位置に残った方</b>の
     *                 添字を指す（区間の境目を張り直すのに使う）
     */
    public record Folded(List<PathStep> steps, int[] newIndex) {

        public Folded {
            steps = List.copyOf(steps);
            newIndex = newIndex.clone();
        }

        @Override
        public int[] newIndex() {
            return newIndex.clone();
        }

        public boolean changed() {
            return steps.size() != newIndex.length;
        }
    }

    private PathLoops() {
    }

    public static Folded fold(List<PathStep> steps) {
        List<PathStep> out = new ArrayList<>(steps.size());
        int[] newIndex = new int[steps.size()];
        Map<BlockPos, Integer> seen = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            Integer previous = seen.get(step.pos());
            if (previous != null && foldable(out, previous) && !edits(step)) {
                for (int drop = out.size() - 1; drop > previous; drop--) {
                    seen.remove(out.get(drop).pos());
                    out.remove(drop);
                }
                // 消えた区間を指していた添字は、畳んだ先＝残った方のステップへ寄せる
                for (int old = 0; old < i; old++) {
                    newIndex[old] = Math.min(newIndex[old], previous);
                }
                newIndex[i] = previous;
                continue;
            }
            out.add(step);
            seen.put(step.pos(), out.size() - 1);
            newIndex[i] = out.size() - 1;
        }
        return new Folded(out, newIndex);
    }

    /** {@code from}より後ろに掘削・設置が1つも無いか。 */
    private static boolean foldable(List<PathStep> out, int from) {
        for (int i = from + 1; i < out.size(); i++) {
            if (edits(out.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean edits(PathStep step) {
        return step.digging() || step.bridging();
    }

    /**
     * 継ぎ足した区間が、既存の経路の近くへ戻ってきた輪。{@code route[entry]}から{@code tail[rejoin]}までが輪で、
     * 両端を結び直せば輪ごと要らなくなる。
     *
     * @param gap 経路に沿った輪のステップ数
     */
    public record Return(int entry, int rejoin, int gap) {
    }

    /**
     * 継ぎ足しの区間{@code tail}が、経路{@code route}の{@code fromIndex}以降の近くへ戻ってくる所のうち、経路に沿って最も遠回りな組。
     *
     * <p>窓の縁の先の行き止まりで出口が切り替わると、継ぎ足しは末端から引き返して戻る。戻る線は来た線と数ブロック〜十数ブロック
     * 離れて並ぶ（実機のエンド・ネザーのV字）ので、同じセルへ戻る輪（{@link #fold}）だけでは拾えない。離れているほど、両端の間を
     * 普通に歩いた長さも長いので、輪とみなすには離れている分だけ長い遠回りを要求する。
     *
     * @param nearBlocks 近いとみなす水平の距離（各軸）
     * @param nearY      近いとみなす高さの差
     * @param minGap     輪とみなす、経路に沿った最小のステップ数
     * @param gapPerBlock 両端が1ブロック離れるごとに要求する、経路に沿ったステップ数
     */
    public static @Nullable Return widestReturn(List<PathStep> route, List<PathStep> tail, int fromIndex,
                                                int nearBlocks, int nearY, int minGap, int gapPerBlock) {
        Return best = null;
        for (int j = 0; j < tail.size(); j++) {
            BlockPos at = tail.get(j).pos();
            for (int k = Math.max(0, fromIndex); k < route.size(); k++) {
                int gap = route.size() - k + j;
                if (gap < minGap || best != null && gap <= best.gap()) {
                    break;
                }
                BlockPos p = route.get(k).pos();
                int apart = Math.max(Math.abs(p.getX() - at.getX()), Math.abs(p.getZ() - at.getZ()));
                if (apart <= nearBlocks && Math.abs(p.getY() - at.getY()) <= nearY
                        && gap >= Math.max(minGap, gapPerBlock * apart)) {
                    best = new Return(k, j, gap);
                    break;
                }
            }
        }
        return best;
    }

    /**
     * {@code steps[from..to]}を取り除いたとき、{@code to}より後ろの手が足場や通り道を失うか。
     *
     * <p>取り除く区間で置いたブロックの上に立つ・そのブロックに当てて次を置く手や、そこで掘った穴を通る手があれば失う。
     * 無ければ、区間に掘削・設置があっても結び直してよい。
     */
    public static boolean laterStepsDependOn(List<PathStep> steps, int from, int to) {
        Set<BlockPos> placed = new HashSet<>();
        Set<BlockPos> dug = new HashSet<>();
        for (int i = from; i <= to; i++) {
            PathStep step = steps.get(i);
            if (step.placedBlockPos() != null) {
                placed.add(step.placedBlockPos());
            }
            dug.addAll(step.digCells());
        }
        if (placed.isEmpty() && dug.isEmpty()) {
            return false;
        }
        for (int i = to + 1; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            if (placed.contains(step.pos().below())) {
                return true;
            }
            BlockPos place = step.placedBlockPos();
            if (place != null) {
                for (Direction side : Direction.values()) {
                    if (placed.contains(place.relative(side))) {
                        return true;
                    }
                }
            }
            for (BlockPos cell : step.bodyCells()) {
                if (dug.contains(cell)) {
                    return true;
                }
            }
            if (dug.contains(step.pos()) || dug.contains(step.pos().above())) {
                return true;
            }
        }
        return false;
    }
}
