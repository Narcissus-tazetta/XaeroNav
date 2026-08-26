package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.xaero.XaeroMapReader;

/**
 * 長距離ルートのためにXaeroの地図を読む範囲の決め方。歩行（{@link net.prason.xaeronav.pathfinding.coarse.CoarseMap}）と
 * 飛行（{@link net.prason.xaeronav.pathfinding.flight.CoarseAirMap}）で1セルあたりの状態数だけが違う。
 *
 * <p>両者で別々に組んではいけない。診断コマンドが本番と1チャンクずれた範囲を読んでいて、目的地が
 * 地図の外に落ちるケースだけ報告が食い違っていたことがある。
 */
final class CoarseMapWindow {

    /** 始点・終点それぞれの周りに広げる範囲（チャンク）。 */
    private static final int PADDING_CHUNKS = 32;

    /** 読み取り範囲の上限（チャンク四方）。無制限だと配列確保だけで固まる。 */
    private static final int MAX_SPAN_CHUNKS = 1024;

    /**
     * 探索状態数の上限。{@link net.prason.xaeronav.pathfinding.coarse.CoarseRouter#findRoute}は
     * {@code cells * 階層数}個の{@code double[]}/{@code int[]}/{@code boolean[]}を一括で確保し、
     * しかも溶岩ポリシーの梯子で最大2回呼ばれる——チャンク四方の上限だけでは、層1が3D化して
     * 1セルあたり複数層になったぶんそのまま倍になる。状態数で切ることで、細長い範囲（片軸だけ遠い
     * 目的地）では従来どおりの到達距離を保ったまま、正方形の最悪ケースだけを2D時代と同じ確保量に戻す。
     */
    private static final int MAX_STATES = 1024 * 1024;

    private CoarseMapWindow() {
    }

    /**
     * 2点を含む範囲の地図を読む。確保量が上限を超えるなら{@code null}——呼び出し側は
     * 「長距離ルート無し」として扱うこと。
     *
     * <p><b>メインスレッド専用</b>（{@link XaeroMapReader#readSurface}がXaeroの書き込みスレッドと
     * 同じ構造を触るため）。
     *
     * @param statesPerCell 1セルあたりに確保される状態数（床・高度帯の最大数）
     */
    static CoarseMap read(BlockPos from, BlockPos to, int statesPerCell) {
        int minChunkX = (Math.min(from.getX(), to.getX()) >> 4) - PADDING_CHUNKS;
        int maxChunkX = (Math.max(from.getX(), to.getX()) >> 4) + PADDING_CHUNKS;
        int minChunkZ = (Math.min(from.getZ(), to.getZ()) >> 4) - PADDING_CHUNKS;
        int maxChunkZ = (Math.max(from.getZ(), to.getZ()) >> 4) + PADDING_CHUNKS;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        if (chunksX > MAX_SPAN_CHUNKS || chunksZ > MAX_SPAN_CHUNKS
                || (long) chunksX * chunksZ * statesPerCell > MAX_STATES) {
            return null;
        }
        int referenceY = (from.getY() + to.getY()) / 2;
        return XaeroMapReader.readSurface(minChunkX, minChunkZ, chunksX, chunksZ, referenceY);
    }
}
