package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * 軸別ヒューリスティック（斜め昇降対応版）。
 * 水平・上昇・下降それぞれについて実コストを下回らない下限値を積算する。
 * 水平距離は斜め移動（同一高度のみ）に対応したoctile距離を使う。
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

    private static final double MIN_DIAGONAL_ASCEND = ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK;

    /**
     * 水平移動に相乗りできない純粋な昇り（{@code pureAscends}）1段の下限。
     *
     * <p>「水平変位が要らないのだから梯子（{@link ActionCosts#LADDER_UP_ONE_BLOCK}）が下限」は誤り。
     * {@code Ascend}を水平方向へ<b>往復させれば</b>、正味の水平変位0のまま高さだけ稼げる——
     * 折り返し階段がその形で、実コストは1段あたり{@link ActionCosts#ASCEND_ONE_BLOCK}にしかならない。
     * 梯子(8.511)を下限に置くと、この地形で見積もりが実コストを上回って非許容になる
     * （実例: {@code (0,64,0)→(1,67,0)} は Ascend×3 = 13.90 なのに見積もりは 21.65 になっていた）。
     *
     * <p>{@code Ascend}系は必ず水平1歩を伴うが、その1歩は<b>戻せる</b>のが要点。高さを1段稼ぐ
     * 全ての移動の中で最安なのは{@code Ascend}なので、水平の相乗り先を使い切ったあとも下限は
     * これで変わらない（{@code DiagonalAscend}=6.551、{@code ClimbUp}=8.511、{@code SwimUp}=7.407、
     * {@code Pillar}はさらに設置コストが乗る）。
     */
    private static final double MIN_PURE_ASCEND = ActionCosts.ASCEND_ONE_BLOCK;

    private Heuristic() {
    }

    /** 下限を指定しない版。どの次元・設定でも安全な値を使う。 */
    public static double estimate(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return estimate(fromX, fromY, fromZ, toX, toY, toZ, ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK,
                ActionCosts.SPRINT_ONE_BLOCK);
    }

    /** 水平の下限を指定しない版。徒歩で進む前提の探索はこれで正しい（最速の水平移動が疾走）。 */
    public static double estimate(int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
                                   double minDescentTicksPerBlock) {
        return estimate(fromX, fromY, fromZ, toX, toY, toZ, minDescentTicksPerBlock,
                ActionCosts.SPRINT_ONE_BLOCK);
    }

    /**
     * @param minDescentTicksPerBlock この探索で生成されうる下降移動のうち、1ブロックあたり最も安いもの
     *                               （{@link net.prason.xaeronav.pathfinding.world.CellSource#minDescentTicksPerBlock}）。
     *                               終端速度からの下限(0.2551)は<b>任意の深さの落下が起きうる</b>前提の値で、
     *                               実際に生成される最大の落差が分かっていれば大きく締められる——
     *                               ネザー・落下ダメージ許容offなら3マスが上限で 4.392、17倍の差になる。
     *                               ここが緩いと、登った1マスを取り返す実コスト(9.321)がほぼ無料に見え、
     *                               重み付きA*が上りの枝を系統的に優先して{@code closed}で確定させてしまう
     * @param minHorizontalTicksPerBlock そのノードから先に生成されうる水平移動のうち、1ブロックあたり
     *                               最も安いもの。通常は疾走（{@link ActionCosts#SPRINT_ONE_BLOCK}）だが、
     *                               <b>ボートに乗っているノードだけは{@link ActionCosts#PADDLE_ONE_BLOCK}</b>
     *                               まで下がる。疾走のまま見積もるとボートのノードに対して非許容になり、
     *                               乗り込む1手の大きな一時コストと相まって<b>ボートの枝が一度も展開されない</b>
     *                               ——泳ぎの前線が先にゴールへ達してしまい、総コストで大きく有利でも選ばれない
     */
    public static double estimate(int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
                                   double minDescentTicksPerBlock, double minHorizontalTicksPerBlock) {
        double straight = minHorizontalTicksPerBlock;
        double diagonalStep = straight * ActionCosts.DIAGONAL_DISTANCE;
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
                + (diagonalSteps - diagonalAscends) * diagonalStep
                + cardinalAscends * ActionCosts.ASCEND_ONE_BLOCK
                + (cardinalSteps - cardinalAscends) * straight
                + pureAscends * MIN_PURE_ASCEND;
        double descend = down * minDescentTicksPerBlock;
        return horizontalAndAscend + descend;
    }
}
