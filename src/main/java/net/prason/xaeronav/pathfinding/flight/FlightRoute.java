package net.prason.xaeronav.pathfinding.flight;

import java.util.List;

import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.astar.PathResult;

/**
 * 空中経路。始点を含む折れ線。
 *
 * <p>歩行の{@code PathResult}とは別の型にしてある。{@code PathStep}は掘削セル・身体セル・
 * 遊泳・登坂・橋といった<b>足場のある移動</b>の概念でできていて、空中経路にはその1つも無い。
 * 無理に共有すると{@code PathValidator}（床が残っているかを見る）や{@code PathGeometry}
 * （危険と作業で色を決める）がどれも飛行では意味を成さなくなる。
 *
 * @param points      折れ線の頂点。先頭は<b>計算した時点</b>のプレイヤー位置なので、描画側は
 *                    そこを捨てて今の位置から引き直すこと
 * @param termination 探索が終わった理由。歩行側と同じ区別（予算切れと「範囲内に道が無い」）が
 *                    そのまま要るので enum を共有する
 * @param expandedNodes 展開したセル数。診断コマンド用
 * @param cellBlocks  この経路を解いた格子の一辺（ブロック）。<b>設定値ではなく実際に使われた値</b>——
 *                    エスカレーションで細かい格子に落ちていれば、線の周りの余白もその分狭い
 */
public record FlightRoute(List<Vec3> points, PathResult.Termination termination, int expandedNodes,
                           int cellBlocks) {

    public static final FlightRoute NONE =
            new FlightRoute(List.of(), PathResult.Termination.EXHAUSTED, 0, 0);

    public boolean isEmpty() {
        return points.size() < 2;
    }

    /** 狙った先まで届いたか。届いていなければ末端の先は点線が引き受ける。 */
    public boolean complete() {
        return termination == PathResult.Termination.REACHED_GOAL;
    }

    /**
     * 探索資源（ノード数・時間）を使い切って打ち切ったか。歩行の{@code PathResult#budgetExhausted}と
     * 同じ判定で、細かい格子への解き直しが無駄になるのはこのとき。
     */
    public boolean budgetExhausted() {
        return termination == PathResult.Termination.NODE_BUDGET
                || termination == PathResult.Termination.TIME_LIMIT;
    }

    /** 折れ線の末端。空なら{@code null}。 */
    public Vec3 tail() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }
}
