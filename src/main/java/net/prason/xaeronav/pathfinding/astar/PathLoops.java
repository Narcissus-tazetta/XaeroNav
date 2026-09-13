package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

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
}
