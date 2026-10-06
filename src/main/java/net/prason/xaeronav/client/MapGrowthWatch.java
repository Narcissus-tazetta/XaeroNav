package net.prason.xaeronav.client;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.util.BlockDistance;
import net.prason.xaeronav.util.ChangeGate;
import net.prason.xaeronav.util.MonotonicTime;
import org.jspecify.annotations.Nullable;

/**
 * <b>読み込みを要求しているのに地図が増えないことを知らせる。</b>
 *
 * <p>{@code XaeroMapReader#requestLoad}は効いているのに、読み込まれたリージョンの中身が空
 * ——という状態が実機で起きた（2026-09-18: 同じ場所で3回続けて地図を読むと「30 awaiting → 0 awaiting」まで
 * 進むのに、既知セルは3213/4225のまま1つも増えなかった）。Xaeroがそのリージョンのキャッシュを
 * {@code .outdated}へ退避しているとこうなる。<b>黙っていると、薄い地図のまま経路を決め続けていることに
 * 誰も気づけない</b>。
 *
 * <p>メインスレッド専用。
 */
final class MapGrowthWatch {

    /** ほぼ同じ場所から読み直したとみなす距離（ブロック）。これを超えたら別の範囲として数え直す。 */
    private static final double SAME_PLACE_BLOCKS = 32.0;

    /** 「増えない」と判断するまでの読み直しの回数。読み直しは約3秒間隔なので、5回で約15秒。 */
    private static final int ATTEMPTS = 5;

    /** 警告を繰り返す間隔。同じ状態が続く間ずっと出しても意味が無い。 */
    private static final long LOG_INTERVAL_MILLIS = 60_000L;

    private @Nullable BlockPos lastReadFrom;
    private int lastKnownCells = -1;
    private int readsWithoutGain;
    private final ChangeGate<Boolean> logGate = new ChangeGate<>();

    void note(BlockPos start, CoarseMapWindow.Window window, CoarseMap map) {
        boolean samePlace = lastReadFrom != null
                && BlockDistance.horizontal(start, lastReadFrom) <= SAME_PLACE_BLOCKS;
        if (!samePlace || map.knownCells() > lastKnownCells) {
            readsWithoutGain = 0;
        } else if (window.pendingRegions() > 0) {
            readsWithoutGain++;
        }
        lastReadFrom = start;
        lastKnownCells = map.knownCells();
        if (readsWithoutGain < ATTEMPTS) {
            return;
        }
        if (!logGate.changed(true, MonotonicTime.millis(), LOG_INTERVAL_MILLIS)) {
            return;
        }
        XaeroNav.LOGGER.warn("XaeroNav: map is not growing despite load requests ({} reads in a row, known cells={}/{},"
                        + " pending regions={}) — Xaero may be failing to read its cache for this area"
                        + " (opening the world map over this area may fix it)",
                readsWithoutGain, map.knownCells(), map.totalCells(), window.pendingRegions());
    }

    void reset() {
        readsWithoutGain = 0;
        lastReadFrom = null;
        lastKnownCells = -1;
    }
}
