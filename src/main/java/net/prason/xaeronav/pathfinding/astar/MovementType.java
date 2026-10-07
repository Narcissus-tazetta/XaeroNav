package net.prason.xaeronav.pathfinding.astar;

/**
 * 同一高度での斜め移動はTRAVERSEとして扱う（{@code AStarPathfinder#addDiagonalTraverse}）。
 * Fall/Pillarは後回し。
 */
public enum MovementType {
    TRAVERSE,
    ASCEND,
    DESCEND,
    /** 水中の移動（水面を泳ぐ・潜る・水底を歩く）。足場が無くても成立する点が他と違う。 */
    SWIM,
    /** ボートで水面を渡る区間。足場も泳ぎも要らないかわりに、出す・乗る・降りる手間が入口に乗る。 */
    BOAT,
    /** トロッコで線路を走る区間。乗る手間が入口に、壊して拾う手間が出口に乗る。 */
    CART,
    /** 梯子・ツタの昇降。水中と同じく足場を要求しない。 */
    CLIMB,
    /** 1マスの隙間を飛び越える区間（{@code AStarPathfinder#addJumpGap}）。他と違い掘削も設置も伴わない。 */
    JUMP,
    /** 落下ダメージを受けて降りる区間（設定で許可した場合のみ）。実際に体力が減る。 */
    FALL_DAMAGE,
    /** 着地寸前に水バケツを置いて落下ダメージを消す区間。タイミング操作が要る。 */
    FALL_MLG,
    /**
     * 馬の仲間・ラクダに乗ったまま進む区間。{@link PathStep#pos}は2×2の足場の角で、乗り物の中心は
     * {@code (x+1, z+1)}にある。
     */
    MOUNT,
    /** {@link #MOUNT}のうち、ジャンプキーを溜めて段へ跳び上がる・隙間を跳び越える手。乗り手の操作が要る。 */
    MOUNT_JUMP,
    /** オウムガイに乗ったまま水中を進む区間。{@link PathStep#pos}はオウムガイのいる水のセルで、乗り手はその上2マス。 */
    MOUNT_SWIM,
    /** 乗り物を降りて置いていく。{@link PathStep#pos}は降りて立つセルで、この先は歩く。 */
    DISMOUNT;

    /** 馬の仲間・ラクダに乗ったまま地上を進む区間か。{@link PathStep#pos}が2×2の足場の角になる。 */
    public boolean ridesOnLand() {
        return this == MOUNT || this == MOUNT_JUMP;
    }

    /** 乗り物に乗ったまま進む区間か。 */
    public boolean rides() {
        return ridesOnLand() || this == MOUNT_SWIM;
    }
}
