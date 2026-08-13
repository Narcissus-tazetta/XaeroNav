package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * 軸別ヒューリスティック（design doc §4-2、斜め昇降対応版）。
 * 水平・上昇・下降それぞれについて実コストを下回らない下限値を積算する。
 * 水平距離は斜め移動（同一高度のみ、§4-1）に対応したoctile距離を使う。
 *
 * <p>例外は氷で、氷の上だけは1マスの実コストがこの下限（素の疾走）を下回る
 * （{@code CellData}の速度倍率）。そのぶん氷を含む経路は最適から少し外れうるが、
 * 下限を氷に合わせて下げると氷の無い場所でもヒューリスティックが一律に弱まり、
 * 探索が広がって展開ノード上限に先に当たる（＝経路が手前で切れる）。氷の有無に関わらず
 * 効いてしまう後者の害の方が大きいので、下限は疾走のまま据え置いている。
 *
 * <p>上昇分は、斜め移動・カーディナル移動それぞれの1手に「相乗り」できる範囲までは
 * 追加コスト無しで運べる（{@code Ascend}/{@code DiagonalAscend}は水平移動と昇りを1手でこなすため）。
 * 独立加算すると、水平1マス＋上昇1マスの{@code Ascend}1手（実コスト{@code ASCEND_ONE_BLOCK}）を
 * 「水平1マス＋昇り1マス」の2手分として見積もり、実コストを上回る（非許容）。
 * 下降は{@code FALL_ASYMPTOTIC_MIN_PER_BLOCK}が既に十分小さい下限なので、相乗りを考えず単純に加算する。
 */
public final class Heuristic {

    private static final double STRAIGHT = ActionCosts.SPRINT_ONE_BLOCK;
    private static final double DIAGONAL_STEP = STRAIGHT * ActionCosts.DIAGONAL_DISTANCE;
    private static final double MIN_DIAGONAL_ASCEND = ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK;

    private Heuristic() {
    }

    public static double estimate(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        int dx = Math.abs(toX - fromX);
        int dz = Math.abs(toZ - fromZ);
        int dy = toY - fromY;

        int diagonalSteps = Math.min(dx, dz);
        int cardinalSteps = Math.abs(dx - dz);
        int up = Math.max(0, dy);
        int down = Math.max(0, -dy);

        // 上昇を先に斜め移動へ相乗りさせ（節約が大きい）、残りをカーディナル移動へ相乗りさせる。
        // それでも余る分だけが、水平移動を伴わない純粋な昇り（梯子・掘り上がり等）としてコストに乗る。
        int diagonalAscends = Math.min(up, diagonalSteps);
        int cardinalAscends = Math.min(up - diagonalAscends, cardinalSteps);
        int pureAscends = up - diagonalAscends - cardinalAscends;

        double horizontalAndAscend = diagonalAscends * MIN_DIAGONAL_ASCEND
                + (diagonalSteps - diagonalAscends) * DIAGONAL_STEP
                + cardinalAscends * ActionCosts.ASCEND_ONE_BLOCK
                + (cardinalSteps - cardinalAscends) * STRAIGHT
                + pureAscends * ActionCosts.JUMP_ONE_BLOCK;
        double descend = down * ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK;
        return horizontalAndAscend + descend;
    }
}
