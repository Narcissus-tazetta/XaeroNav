package net.prason.xaeronav.pathfinding.astar;

/** design doc §3-4。コスト計算はあくまで事前見積もりなので、提示直前に安全性を再チェックした結果。 */
public enum PathRisk {
    NONE,
    LAVA_ADJACENT,
    WATER_INFLOW,
    VOID_BELOW,
    /** 息継ぎできないまま潜り続ける区間。空気が尽きて溺れる（{@code PathSafetyChecker#drowningRuns}）。 */
    DROWNING
}
