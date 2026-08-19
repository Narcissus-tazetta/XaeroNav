package net.prason.xaeronav.pathfinding.astar;

/** design doc §3-4。コスト計算はあくまで事前見積もりなので、提示直前に安全性を再チェックした結果。 */
public enum PathRisk {
    NONE,
    LAVA_ADJACENT,
    WATER_INFLOW,
    VOID_BELOW,
    /** 息継ぎできないまま潜り続ける区間。空気が尽きて溺れる（{@code PathSafetyChecker#drowningRuns}）。 */
    DROWNING,
    /** 着地時に落下ダメージを受ける区間。設定で許可したときだけ経路に現れる。 */
    FALL_DAMAGE,
    /** 着地寸前に水バケツを置かないと落下ダメージを受ける区間。 */
    MLG_REQUIRED,
    /**
     * マグマブロックの上を通る区間。スニークしていれば無傷で渡れる（バニラの
     * {@code isSteppingCarefully}）が、走って踏むと燃える——通行可にしている以上、
     * その条件を伝えないと「案内どおり歩いたら焼かれた」になる。
     */
    SNEAK_OVER_MAGMA
}
