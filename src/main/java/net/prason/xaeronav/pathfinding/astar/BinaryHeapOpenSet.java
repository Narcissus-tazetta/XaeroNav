package net.prason.xaeronav.pathfinding.astar;

import java.util.Arrays;

/**
 * A*のオープンセット用の二分ヒープ。1-indexedの配列で持つ。
 *
 * <p>{@code PriorityQueue}にコスト更新のたび新しいエントリを積む方式（lazy deletion）だと、
 * 同じ座標のエントリが何重にも溜まり、ヒープが実ノード数の数倍まで膨らむ。
 * {@link PathNode#heapPosition}を持たせてdecrease-keyを直接行うことで、
 * ヒープの要素数は常にオープンなノード数と一致し、エントリ用のオブジェクトも生まれない。
 */
final class BinaryHeapOpenSet {

    private static final int INITIAL_CAPACITY = 1024;

    private PathNode[] array = new PathNode[INITIAL_CAPACITY];
    private int size;

    boolean isEmpty() {
        return size == 0;
    }

    void insert(PathNode node) {
        if (size >= array.length - 1) {
            array = Arrays.copyOf(array, array.length << 1);
        }
        size++;
        array[size] = node;
        node.heapPosition = size;
        siftUp(node);
    }

    /** コストが下がったノードを正しい位置へ引き上げる。 */
    void update(PathNode node) {
        siftUp(node);
    }

    PathNode removeLowest() {
        PathNode result = array[1];
        result.heapPosition = -1;

        PathNode last = array[size];
        array[size] = null;
        size--;
        if (size == 0) {
            return result;
        }

        array[1] = last;
        last.heapPosition = 1;
        siftDown(last);
        return result;
    }

    private void siftUp(PathNode node) {
        int index = node.heapPosition;
        double cost = node.combinedCost;
        while (index > 1) {
            int parentIndex = index >>> 1;
            PathNode parent = array[parentIndex];
            if (parent.combinedCost <= cost) {
                break;
            }
            array[parentIndex] = node;
            array[index] = parent;
            parent.heapPosition = index;
            index = parentIndex;
        }
        node.heapPosition = index;
    }

    private void siftDown(PathNode node) {
        int index = node.heapPosition;
        double cost = node.combinedCost;
        while (true) {
            int child = index << 1;
            if (child > size) {
                break;
            }
            PathNode smaller = array[child];
            if (child < size) {
                PathNode right = array[child + 1];
                if (right.combinedCost < smaller.combinedCost) {
                    child++;
                    smaller = right;
                }
            }
            if (cost <= smaller.combinedCost) {
                break;
            }
            array[index] = smaller;
            array[child] = node;
            smaller.heapPosition = index;
            index = child;
        }
        node.heapPosition = index;
    }
}
