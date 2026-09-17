package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;

/**
 * 距離と整数のIDの二分ヒープ。同じIDを重ねて積み、取り出した距離が表より大きければ読み捨てる（遅延削除）。
 * decrease-keyの位置表を持たないぶん、数百万ノードの逆Dijkstraで確保が1本の配列に収まる。
 */
final class DistanceHeap {

    private double[] keys = new double[1 << 12];
    private int[] values = new int[1 << 12];
    private int size;

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }

    long bytes() {
        return 12L * keys.length;
    }

    double topKey() {
        return keys[0];
    }

    void push(double key, int value) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (keys[parent] <= key) {
                break;
            }
            keys[i] = keys[parent];
            values[i] = values[parent];
            i = parent;
        }
        keys[i] = key;
        values[i] = value;
    }

    int pop() {
        int top = values[0];
        size--;
        if (size > 0) {
            double key = keys[size];
            int value = values[size];
            int i = 0;
            while (true) {
                int child = 2 * i + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && keys[child + 1] < keys[child]) {
                    child++;
                }
                if (keys[child] >= key) {
                    break;
                }
                keys[i] = keys[child];
                values[i] = values[child];
                i = child;
            }
            keys[i] = key;
            values[i] = value;
        }
        return top;
    }
}
