package net.prason.xaeronav.pathfinding.async;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code /xaeronav debug}診断コマンド専用の非同期実行基盤。
 *
 * <p>単一のバックグラウンドスレッドで動く。{@link #begin()}を呼ぶたびに世代を進めて待機中の
 * 前世代のjobを捨て、実行中のjobへは世代不一致を協調cancelの合図として渡す——同時に何本も
 * 診断を走らせず、常に最新の1本だけが結果を出す。1回のコマンド呼び出しが複数段の探索を
 * 順番に行う場合は、{@link #begin()}は最初の1回だけ呼び、以降の段はすべて同じ世代番号を使うこと
 * （途中で{@link #begin()}を呼び直すと自分自身を追い越してキャンセル扱いになる）。
 *
 * <p>ライブナビの{@link PathfindingExecutor}とは完全に別インスタンス・別スレッドで使うこと。
 * 共有すると、診断コマンドを打っただけで進行中の本番探索がキャンセルされてしまう。
 *
 * <p>メインスレッドへの結果の戻し方は構築時に{@code onMainThread}として受け取る
 * （呼び出し側は{@code Minecraft.getInstance()::execute}を渡す想定）。このクラス自体は
 * Minecraft非依存に保ってあるので、世代管理・キャンセルの単体テストに実際のクライアントを要らない。
 */
public final class DiagnosticJobRunner {

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "xaeronav-diagnostic");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong generation = new AtomicLong();
    private final Consumer<Runnable> onMainThread;

    public DiagnosticJobRunner(Consumer<Runnable> onMainThread) {
        this.onMainThread = onMainThread;
    }

    /**
     * 新しい診断チェーンを始める。世代を進め、まだ実行が始まっていない前世代のjobを待機queueから捨てる。
     *
     * @return 以降の{@link #submit}すべてに渡す世代番号
     */
    public long begin() {
        long mine = generation.incrementAndGet();
        executor.getQueue().clear();
        return mine;
    }

    /**
     * {@code work}をバックグラウンドで実行する。{@code work}へ渡す{@link BooleanSupplier}は、
     * 呼び出し後に世代が追い越されていれば{@code true}を返す——{@code AStarPathfinder#search}等の
     * 協調cancel引数へそのまま渡せる。完了時、まだ最新世代なら{@code onMainThread}経由で
     * {@code onComplete}を呼ぶ（結果が正常なら{@code error}は{@code null}、例外なら{@code result}は
     * {@code null}）。世代を追い越されていれば{@code onComplete}は一切呼ばれない（結果を静かに捨てる）。
     *
     * <p>{@code work}が投げた{@link Exception}はここで捕まえて{@code onComplete}の第2引数へ渡す。
     * {@link Error}は対処できない致命的な状態なので捕まえず、そのままスレッドの
     * 未捕捉例外ハンドラへ伝播させる（{@link ThreadPoolExecutor}は死んだworkerを自動的に補充する）。
     */
    public <T> void submit(long generationToken, Function<BooleanSupplier, T> work,
                            BiConsumer<T, Throwable> onComplete) {
        BooleanSupplier cancelled = () -> generation.get() != generationToken;
        executor.execute(() -> {
            T result;
            try {
                result = work.apply(cancelled);
            } catch (Exception exception) {
                complete(generationToken, null, exception, onComplete);
                return;
            }
            complete(generationToken, result, null, onComplete);
        });
    }

    private <T> void complete(long generationToken, T result, Throwable error, BiConsumer<T, Throwable> onComplete) {
        if (generation.get() != generationToken) {
            return;
        }
        onMainThread.accept(() -> {
            if (generation.get() != generationToken) {
                return;
            }
            onComplete.accept(result, error);
        });
    }
}
