package net.prason.xaeronav.pathfinding.astar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * {@link BinaryHeapOpenSet}の正しさの検証。A*探索1回ごとに数万回のinsert/update/removeLowestを
 * さばく心臓部で、ここが壊れると症状は「探索結果がたまに変な経路になる」としてしか表に出ない
 * （壊れたヒープでも大抵は動いているように見える）。decrease-key経路（{@link #update}）は
 * 通常の優先度付きキューには無い分岐なので、特に手厚く見る。
 */
class BinaryHeapOpenSetTest {

    private static PathNode node(int index, double combinedCost) {
        // x, y, z, estimatedCostToGoalは今回のテストでは使わないので識別用のindexだけ意味を持つ
        PathNode node = new PathNode(index, 0, 0, 0.0);
        node.combinedCost = combinedCost;
        return node;
    }

    @Test
    void removalOrderIsNonDecreasingUnderRandomInsertions() {
        Random random = new Random(20260811L);
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        int count = 2000;
        for (int i = 0; i < count; i++) {
            heap.insert(node(i, random.nextDouble() * 1000.0));
        }

        double previous = Double.NEGATIVE_INFINITY;
        int removed = 0;
        while (!heap.isEmpty()) {
            PathNode next = heap.removeLowest();
            assertTrue(next.combinedCost >= previous,
                    "順序が逆転: " + previous + " の次に " + next.combinedCost + " が出た");
            previous = next.combinedCost;
            removed++;
        }
        assertEquals(count, removed);
    }

    @Test
    void decreaseKeyMovesNodeAheadOfCheaperExistingEntries() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        PathNode cheap = node(0, 10.0);
        PathNode mid = node(1, 20.0);
        PathNode expensive = node(2, 30.0);
        heap.insert(cheap);
        heap.insert(mid);
        heap.insert(expensive);

        // 元は cheap(10) < mid(20) < expensive(30) の順で出るはずだが、expensiveのコストを
        // 5まで下げてからupdate()する。これがsiftUpを正しく起動しないと、ヒープが古い位置に
        // expensiveを残したまま矛盾した状態になる
        expensive.combinedCost = 5.0;
        heap.update(expensive);

        assertEquals(5.0, heap.removeLowest().combinedCost);
        assertEquals(10.0, heap.removeLowest().combinedCost);
        assertEquals(20.0, heap.removeLowest().combinedCost);
        assertTrue(heap.isEmpty());
    }

    @Test
    void isOpenReflectsMembership() {
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        PathNode a = node(0, 1.0);
        assertFalse(a.isOpen());

        heap.insert(a);
        assertTrue(a.isOpen());

        PathNode removed = heap.removeLowest();
        assertEquals(a, removed);
        assertFalse(a.isOpen());
    }

    @Test
    void handlesGrowthPastInitialCapacity() {
        // BinaryHeapOpenSetの初期配列サイズ(1024)を超えて配列の伸長(copyOf)が起きても
        // ヒープ順序が壊れないことを確認する
        BinaryHeapOpenSet heap = new BinaryHeapOpenSet();
        int count = 5000;
        for (int i = 0; i < count; i++) {
            heap.insert(node(i, count - i));
        }
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double cost = heap.removeLowest().combinedCost;
            assertTrue(cost >= previous);
            previous = cost;
        }
    }
}
