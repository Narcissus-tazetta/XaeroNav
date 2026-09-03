package net.prason.xaeronav.pathfinding.world;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link CellSource}を包んで「このインスタンスを触ったスレッド」を記録する見張り。
 *
 * <p>{@code CellSource}は単一のワーカースレッドが占有する約束（{@code ChunkView}のスレッド契約）で、
 * 破っても症状は非決定的にしか出ない——実機では{@code ArrayIndexOutOfBoundsException}が数分に1回、
 * 出ないときは別のチャンクのブロックを読んだまま経路が出ていた。<b>壊れ方を待ち受けるのではなく、
 * 契約違反そのものを見る</b>ことで、インターリーブに依存しない判定にする。
 *
 * <p>実装が動的プロキシなのは、{@code CellSource}にメソッドが増えても見張りが素通りしないようにするため。
 */
public final class OwnerTrackingCells {

    private final CellSource view;
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final AtomicReference<Thread> intruder = new AtomicReference<>();

    public OwnerTrackingCells(CellSource delegate) {
        this.view = (CellSource) Proxy.newProxyInstance(
                CellSource.class.getClassLoader(),
                new Class<?>[] {CellSource.class},
                (proxy, method, args) -> {
                    Thread current = Thread.currentThread();
                    if (!owner.compareAndSet(null, current) && owner.get() != current) {
                        intruder.compareAndSet(null, current);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    public CellSource view() {
        return view;
    }

    /** このビューを最初に触ったスレッド。一度も触られていなければ{@code null}。 */
    public Thread owner() {
        return owner.get();
    }

    /** 占有者以外で触ったスレッド。契約が守られていれば{@code null}。 */
    public Thread intruder() {
        return intruder.get();
    }
}
