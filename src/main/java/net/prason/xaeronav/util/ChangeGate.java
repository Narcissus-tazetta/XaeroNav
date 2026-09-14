package net.prason.xaeronav.util;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * 「前回と同じ値なら黙る、違えば知らせる」を1つにまとめる。診断ログが同じ理由を毎tick
 * 出し続けないようにする、という同じ形のガードが{@code PathfindingState}に3箇所
 * （合流拒否・繋ぎ目解き直し見送り・立てない目標）並行して書かれていたので共通化する。
 *
 * <p>volatileにしているのは、リセット（成功時）と更新（失敗理由の記録）が別の実行経路から
 * 呼ばれる既存コードの前提（元の{@code lastSeamRepairRefusal}フィールド）を保つため。
 */
public final class ChangeGate<T> {

    private volatile @Nullable T last;
    private volatile long lastAtMillis = Long.MIN_VALUE;

    /** 前回と同じ値なら{@code false}（抑制）。違えば内部を更新して{@code true}を返す。 */
    public boolean changed(T value) {
        if (Objects.equals(last, value)) {
            return false;
        }
        last = value;
        return true;
    }

    /**
     * 値が変わったか、前回の通知から{@code minIntervalMillis}以上経っていれば{@code true}。
     * 同じ状態が続いている間も一定間隔で知らせたい用途向け（{@link #changed(Object)}は
     * 状態が変わらない限り無音のまま）。時刻は{@link MonotonicTime}を渡すこと。
     */
    public boolean changed(T value, long nowMillis, long minIntervalMillis) {
        if (Objects.equals(last, value) && nowMillis - lastAtMillis < minIntervalMillis) {
            return false;
        }
        last = value;
        lastAtMillis = nowMillis;
        return true;
    }

    /** 次の{@link #changed}を必ず通知扱いにする（「もう問題ない」状態に戻ったときに呼ぶ）。 */
    public void reset() {
        last = null;
    }
}
