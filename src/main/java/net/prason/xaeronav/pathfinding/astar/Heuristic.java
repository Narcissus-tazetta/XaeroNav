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

    /**
     * 斜め1手に相乗りできる昇り1段の下限。<b>陸と水の安い方</b>を取る——泳ぎの上昇は
     * ジャンプではないので{@link ActionCosts#STEP_TRANSITION_TICKS}が乗らず、
     * {@code DIAGONAL_ASCEND_ONE_BLOCK}より安い。陸の値だけを下限に置くと水中で非許容になる。
     */
    private static final double MIN_DIAGONAL_ASCEND = Math.min(ActionCosts.DIAGONAL_ASCEND_ONE_BLOCK,
            ActionCosts.DIAGONAL_SWIM_ASCEND_ONE_BLOCK);

    /** カーディナル1手に相乗りできる昇り1段の下限。{@link #MIN_DIAGONAL_ASCEND}と同じ理由で水も見る。 */
    private static final double MIN_CARDINAL_ASCEND =
            Math.min(ActionCosts.ASCEND_ONE_BLOCK, ActionCosts.SWIM_ASCEND_ONE_BLOCK);

    /**
     * 水平移動に相乗りできない純粋な昇り（{@code pureAscends}）1段の下限。
     *
     * <p>「水平変位が要らないのだから梯子（{@link ActionCosts#LADDER_UP_ONE_BLOCK}）が下限」は誤り。
     * {@code Ascend}を水平方向へ<b>往復させれば</b>、正味の水平変位0のまま高さだけ稼げる——
     * 折り返し階段がその形で、実コストは1段あたり{@link ActionCosts#ASCEND_ONE_BLOCK}にしかならない。
     * 梯子(8.511)を下限に置くと、この地形で見積もりが実コストを上回って非許容になる
     * （実例: {@code (0,64,0)→(1,67,0)} は Ascend×3 = 13.90 なのに見積もりは 21.65 になっていた）。
     *
     * <p>{@code Ascend}系は必ず水平1歩を伴うが、その1歩は<b>戻せる</b>のが要点。折り返せば
     * 正味の水平変位0のまま高さだけ稼げるので、水平の相乗り先を使い切ったあともこれが下限になる。
     *
     * <p><b>水も見るのが要点。</b>{@link ActionCosts#STEP_TRANSITION_TICKS}が乗る陸の{@code Ascend}
     * (7.633)より、乗らない{@code SwimUp}(7.407)の方が安い。{@code ClimbUp}(8.511)と
     * {@code Pillar}（設置コストが乗る）はどちらより高いので見なくてよい。
     */
    private static final double MIN_PURE_ASCEND =
            Math.min(ActionCosts.ASCEND_ONE_BLOCK, ActionCosts.SWIM_UP_ONE_BLOCK);

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

        // 下降も同じ水平の枠へ相乗りする。`up`と`down`は排他なので枠を奪い合わない。
        // 相乗りできた分は追加コストを0にする。実際には1段ごとに
        // {@code ActionCosts#STEP_TRANSITION_TICKS}が乗るので低く見ているが、下限としては正しい
        // （高く見積もる方だけが非許容になる）。単純加算していた頃は、ネザー相当の下限(4.392)で
        // 斜め下降1手の見積もりが9.432＝実コスト9.321を上回って非許容になっていた。
        int ridableDescends = Math.min(down, diagonalSteps + cardinalSteps);
        int pureDescends = down - ridableDescends;

        double horizontalAndAscend = diagonalAscends * MIN_DIAGONAL_ASCEND
                + (diagonalSteps - diagonalAscends) * diagonalStep
                + cardinalAscends * MIN_CARDINAL_ASCEND
                + (cardinalSteps - cardinalAscends) * straight
                + pureAscends * MIN_PURE_ASCEND;
        double descend = pureDescends * minDescentTicksPerBlock;
        return horizontalAndAscend + descend;
    }
}
