package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * 直前に{@link PathValidator}が不成立と判定したセルを、短い間だけ覚えておく。
 * 次の探索はこれを{@code AvoidedCellSource}で避ける。
 *
 * <p><b>これが無いと輪が閉じない。</b>探索側のセル判定と再確認の判定が同じ座標で食い違うと、
 * 引き直した経路がまた同じセルを通り、また即座に無効と判断される。{@code Splice}の再挑戦ゲートも
 * {@code SeamRepair}の1繋ぎ目1回制限も、<b>そのセルを選び直す探索そのもの</b>は止められない
 * ——実機報告(#47)では同じ座標で14秒間その輪が回り続けた。
 *
 * <p>覚えるのは一時的でよい。食い違いの原因が実際の地形変化なら、次に見たときには
 * 探索側も同じ判定になるので避ける必要は無くなる。{@link #TTL_NANOS}は実機ログの輪の周期
 * （2〜5秒）を確実に跨ぐ長さにしてある。
 *
 * <p><b>メインスレッド専用。</b>記録するのは経路の再確認（tick）、読むのは探索を投げる直前の
 * {@code ChunkView}構築時で、どちらもメインスレッドに限られる（{@code ChunkView#capture}が
 * メインスレッド専用なので、読む側は原理的にそこから外れない）。
 */
final class RecentFailures {

    /** 覚えておく長さ。 */
    private static final long TTL_NANOS = 15_000_000_000L;

    /**
     * 同時に覚えるセルの数。溢れたら古い方から捨てる（{@code SeamRepair#QUEUE_LIMIT}と同じ考え方）。
     *
     * <p>際限なく増やさないのは、避けるセルが増えるほど<b>探索から取り上げる選択肢が増える</b>から。
     * 止めたいのは同じ1点へ吸い込まれる輪であって、地形を広く封じることではない。
     */
    private static final int LIMIT = 8;

    /** 不成立だったセル→記録した時刻。挿入順で持ち、溢れたら先頭から捨てる。 */
    private final Map<BlockPos, Long> failures = new LinkedHashMap<>();

    /** このセルを不成立として覚える。既に覚えているなら時刻を更新する。 */
    void note(BlockPos cell) {
        failures.remove(cell);
        failures.put(cell, System.nanoTime());
        Iterator<BlockPos> oldest = failures.keySet().iterator();
        while (failures.size() > LIMIT && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** いま避けるべきセル。期限切れはここで落とす。 */
    List<BlockPos> avoided() {
        long now = System.nanoTime();
        failures.values().removeIf(at -> now - at > TTL_NANOS);
        return new ArrayList<>(failures.keySet());
    }

    /** 目的地の変更で、覚えていたセルを捨てる。 */
    void clear() {
        failures.clear();
    }
}
