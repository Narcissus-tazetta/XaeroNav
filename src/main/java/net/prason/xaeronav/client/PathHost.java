package net.prason.xaeronav.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * {@link PathfindingState}が持つ、経路の差し替え部品（{@link SeamRepair}・{@link Splice}・{@link Extend}）が
 * 非同期完了時に読み書きする必要のある可変状態。
 */
interface PathHost {
    /** 現在の目的地。 */
    @Nullable BlockPos goal();

    /** 現在表示中の経路。 */
    PathfindingState.DisplayedPath displayed();

    /** 表示中の経路を差し替える。 */
    void setDisplayed(PathfindingState.DisplayedPath path);

    /** 探索中フラグを立て下げする。 */
    void setComputing(boolean computing);
}
