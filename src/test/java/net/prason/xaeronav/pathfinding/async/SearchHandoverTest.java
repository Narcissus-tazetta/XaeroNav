package net.prason.xaeronav.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * ライブナビの非同期の受け渡しを、{@code PathfindingState}と同じ部品（1本の{@link PathfindingExecutor}・
 * 共有の世代・{@link GenerationGate}）と同じ手順で時系列に並べて確かめる。
 *
 * <p>{@code PathfindingState}自体はMinecraftのクライアントが無いと動かないので、状態遷移のうち
 * 「世代を進める・探索を捨てる・投げ直す」の手順だけをここで再現する。手順は
 * {@code PathfindingState#setGoal}（{@code clear}）・{@code clear}・離陸と着地の分岐に揃えてある。
 *
 * <p>古い探索は{@link #slow}で、放っておけば数十秒走る。届くかどうかではなく<b>止まるか</b>を見る。
 */
class SearchHandoverTest {

    private static final long AWAIT_SECONDS = 10;

    /** 遅い探索が自然に終わるまでには、これより十分長くかかる。 */
    private static final SearchLimits LONG = new SearchLimits(10_000_000, 120_000,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static final SearchLimits SHORT = new SearchLimits(100_000, 5_000,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private static final BlockPos START = new BlockPos(5, 61, 5);

    /** 床から隙間6マスで切り離した台の上。跳べず、橋も架けられないので届かない。 */
    private static final BlockPos UNREACHABLE = new BlockPos(215, 61, 100);

    private static final BlockPos NEAR = new BlockPos(20, 61, 5);

    private final AtomicLong generation = new AtomicLong();
    private final GenerationGate gate = new GenerationGate(generation, Runnable::run);
    private final List<String> delivered = new CopyOnWriteArrayList<>();

    /** 対照。消さなければ読み続けること——これが崩れると下のテストは何も確かめていない。 */
    @Test
    void theSlowSearchKeepsRunningUnlessCleared() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow running = slow();
        request(executor, running.view(), UNREACHABLE, "対照");
        running.awaitReads();

        long before = running.reads().get();
        Thread.sleep(2_000);
        assertTrue(running.reads().get() > before, "遅い探索がすぐ終わってしまう＝止まったかを見分けられない");
        executor.cancelAll();
    }

    /** 探索中に目的地を変えた。古い探索の結果は届かず、古い探索は新しい探索を待たせない。 */
    @Test
    void changingTheGoalMidSearchDeliversOnlyTheNewRoute() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow old = slow();
        CompletableFuture<PathResult> stale = request(executor, old.view(), UNREACHABLE, "古い目的地");

        CompletableFuture<PathResult> fresh = request(executor, field(), NEAR, "新しい目的地");

        PathResult result = fresh.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.complete(), "新しい目的地へ届かない: " + result.termination());
        assertTrue(stale.isDone(), "古い探索が新しい探索の後も残っている");
        assertEquals(List.of("新しい目的地"), delivered);
    }

    /** 案内を消した（ログアウト・次元移動・到着）。走っている探索がビューを読むのをやめる。 */
    @Test
    void clearingStopsTheRunningSearchFromReadingTheWorld() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow running = slow();
        CompletableFuture<PathResult> stale = request(executor, running.view(), UNREACHABLE, "消す前");
        running.awaitReads();

        clear(executor);

        assertTrue(running.settles(), "消した後も探索がチャンクを読み続けている");
        assertTrue(stale.isDone());
        assertTrue(delivered.isEmpty(), "消した後に経路が復活した: " + delivered);
    }

    /** 深い予算を並列に走らせている探索も、消せば両方のスレッドで止まる。 */
    @Test
    void clearingAlsoStopsTheParallelDeepSearch() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow normal = slow();
        Slow deep = slow();
        long myGeneration = generation.incrementAndGet();
        CompletableFuture<PathResult> stale = executor.submitWithDeepFallback(normal.view(), deep.view(),
                START, UNREACHABLE, LONG, LONG, false, 0);
        gate.whenStillCurrent(stale, myGeneration, (result, error) -> delivered.add("消す前"));
        normal.awaitReads();
        deep.awaitReads();

        clear(executor);

        assertTrue(normal.settles(), "通常予算の探索が止まらない");
        assertTrue(deep.settles(), "深い予算の探索が止まらない");
        assertTrue(delivered.isEmpty(), "消した後に経路が復活した: " + delivered);
    }

    /**
     * 歩いている間の探索中に離陸し、着地して引き直した。離陸前の探索は離陸の時点で止まり、
     * 着地後の結果だけが届く。
     */
    @Test
    void takingOffAndLandingDeliversOnlyTheRouteFromAfterLanding() throws Exception {
        PathfindingExecutor executor = new PathfindingExecutor();
        Slow beforeTakeoff = slow();
        request(executor, beforeTakeoff.view(), UNREACHABLE, "離陸前");
        beforeTakeoff.awaitReads();

        generation.incrementAndGet();
        executor.cancelAll();
        assertTrue(beforeTakeoff.settles(), "離陸しても歩きの探索が走り続けている");

        CompletableFuture<PathResult> afterLanding = request(executor, field(), NEAR, "着地後");

        assertTrue(afterLanding.get(AWAIT_SECONDS, TimeUnit.SECONDS).complete());
        assertEquals(List.of("着地後"), delivered);
    }

    /** {@code PathfindingState#recalculate}と同じ手順: 世代を進めてから投げ、その世代で受け取る。 */
    private CompletableFuture<PathResult> request(PathfindingExecutor executor, CellSource view, BlockPos goal,
                                                  String label) {
        long myGeneration = generation.incrementAndGet();
        CompletableFuture<PathResult> future = executor.submit(view, START, goal, LONG, false);
        gate.whenStillCurrent(future, myGeneration, (result, error) -> {
            if (error == null) {
                delivered.add(label);
            }
        });
        return future;
    }

    /** {@code PathfindingState#clear}と同じ手順。 */
    private void clear(PathfindingExecutor executor) {
        generation.incrementAndGet();
        executor.cancelAll();
    }

    /** 200×200の床と、隙間6マスを空けた台。 */
    private static FakeCells field() {
        FakeCells cells = FakeCells.empty(new SearchBounds(-4, 20, -4, 230, 90, 204));
        for (int x = 0; x < 200; x++) {
            for (int z = 0; z < 200; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        for (int x = 206; x < 226; x++) {
            for (int z = 90; z < 110; z++) {
                cells.set(x, 60, z, FakeCells.BEDROCK);
            }
        }
        return cells;
    }

    /** セルを1つ読むたびに少し待つビュー。床を舐め尽くすまでに数十秒かかる。 */
    private static Slow slow() {
        FakeCells cells = field();
        AtomicLong reads = new AtomicLong();
        CellSource view = (CellSource) Proxy.newProxyInstance(CellSource.class.getClassLoader(),
                new Class<?>[] {CellSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("cell")) {
                        reads.incrementAndGet();
                        LockSupport.parkNanos(50_000);
                    }
                    try {
                        return method.invoke(cells, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new Slow(view, reads);
    }

    private record Slow(CellSource view, AtomicLong reads) {

        void awaitReads() throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            while (reads.get() < 1_000) {
                assertTrue(System.nanoTime() < until, "探索が始まらない");
                Thread.sleep(10);
            }
        }

        /** 読み取りが止まったか。止まらなければ{@link #AWAIT_SECONDS}で諦める。 */
        boolean settles() throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
            long last = reads.get();
            while (System.nanoTime() < until) {
                Thread.sleep(300);
                long now = reads.get();
                if (now == last) {
                    return true;
                }
                last = now;
            }
            return false;
        }
    }
}
