package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * 軸別ヒューリスティック（design doc §4-2、修正版）。
 * 水平・上昇・下降それぞれについて実コストを下回らない下限値を積算する。
 * 水平距離は斜め移動（同一高度のみ、§4-1）に対応したoctile距離を使う。
 *
 * <p>例外は氷で、氷の上だけは1マスの実コストがこの下限（素の疾走）を下回る
 * （{@code CellData}の速度倍率）。そのぶん氷を含む経路は最適から少し外れうるが、
 * 下限を氷に合わせて下げると氷の無い場所でもヒューリスティックが一律に弱まり、
 * 探索が広がって展開ノード上限に先に当たる（＝経路が手前で切れる）。氷の有無に関わらず
 * 効いてしまう後者の害の方が大きいので、下限は疾走のまま据え置いている。
 */
public final class Heuristic {

    private static final double STRAIGHT = ActionCosts.SPRINT_ONE_BLOCK;
    private static final double DIAGONAL = STRAIGHT * ActionCosts.DIAGONAL_DISTANCE;
    /** octile距離で、斜め移動によるショートカット分を差し引くための係数。 */
    private static final double DIAGONAL_SAVING = DIAGONAL - 2 * STRAIGHT;

    private Heuristic() {
    }

    public static double estimate(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        int dx = Math.abs(toX - fromX);
        int dz = Math.abs(toZ - fromZ);
        int dy = toY - fromY;

        double horizontal = STRAIGHT * (dx + dz) + DIAGONAL_SAVING * Math.min(dx, dz);
        double vertical = dy > 0
                ? dy * ActionCosts.JUMP_ONE_BLOCK
                : -dy * ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK;
        return horizontal + vertical;
    }
}
