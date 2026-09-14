package net.prason.xaeronav.pathfinding.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 「世代を追い越されていたら結果を静かに捨てる」を1箇所にまとめたもの。ライブナビ
 * （{@code PathfindingState}）の5箇所の非同期完了処理が同じ形の世代チェックを個別に書いていたので
 * 共通化する（TEST-01）。{@link DiagnosticJobRunner}と考え方は同じだが、あちらは独自の
 * executor・世代カウンタを持つのに対し、こちらは呼び出し側が既に持つ{@link AtomicLong}と
 * executorへ相乗りする形。
 *
 * <p>メインスレッドへの結果の戻し方は構築時に{@code onMainThread}として受け取る
 * （呼び出し側は{@code Minecraft.getInstance()::execute}を渡す想定）。このクラス自体は
 * Minecraft非依存に保ってあるので、世代管理・キャンセル伝播の単体テストに実際のクライアントを要らない。
 */
public final class GenerationGate {

    private final AtomicLong generation;
    private final Consumer<Runnable> onMainThread;

    public GenerationGate(AtomicLong generation, Consumer<Runnable> onMainThread) {
        this.generation = generation;
        this.onMainThread = onMainThread;
    }

    /**
     * {@code future}が完了した時点でまだ{@code myGeneration}が最新世代なら、{@code onMainThread}
     * 経由で{@code action}を呼ぶ。世代を追い越されていれば{@code action}は一切呼ばれない
     * （結果を静かに捨てる）。
     */
    public <T> void whenStillCurrent(CompletableFuture<T> future, long myGeneration,
                                      BiConsumer<T, Throwable> action) {
        future.whenComplete((result, error) -> onMainThread.accept(() -> {
            if (generation.get() != myGeneration) {
                return;
            }
            action.accept(result, error);
        }));
    }
}
