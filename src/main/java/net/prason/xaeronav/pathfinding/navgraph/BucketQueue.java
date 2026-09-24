package net.prason.xaeronav.pathfinding.navgraph;

import java.util.Arrays;

/**
 * 距離を一定幅のバケットに切った優先度付きキュー（Dial法）。バケットの中は積んだ逆順に出す。
 *
 * <p>幅を辺の値段の最小値以下にすれば、あるバケットから緩和した先は必ず後ろのバケットへ積まれるので、
 * バケットを前から空にしていくだけでDijkstraの確定順になる。二分ヒープより1回の出し入れが軽い。
 */
final class BucketQueue {

    /** バケットごとの先頭の要素。空なら-1。 */
    private int[] head = new int[0];
    private int[] value = new int[1 << 12];
    private int[] next = new int[1 << 12];
    private int size;
    /** {@link #head}のうち使っている範囲。 */
    private int buckets;

    void clear(int bucketCount) {
        if (head.length < bucketCount) {
            head = new int[bucketCount + bucketCount / 4];
        }
        Arrays.fill(head, 0, Math.max(buckets, bucketCount), -1);
        buckets = bucketCount;
        size = 0;
    }

    void push(int bucket, int item) {
        if (bucket >= buckets) {
            if (bucket >= head.length) {
                head = Arrays.copyOf(head, Math.max(bucket + 1, head.length * 2));
            }
            Arrays.fill(head, buckets, bucket + 1, -1);
            buckets = bucket + 1;
        }
        if (size == value.length) {
            value = Arrays.copyOf(value, size * 2);
            next = Arrays.copyOf(next, size * 2);
        }
        value[size] = item;
        next[size] = head[bucket];
        head[bucket] = size++;
    }

    /** @return バケットが空なら-1 */
    int pop(int bucket) {
        int entry = head[bucket];
        if (entry < 0) {
            return -1;
        }
        head[bucket] = next[entry];
        return value[entry];
    }

    /** @return {@code from}以降で最初の空でないバケット。無ければ-1 */
    int nextNonEmpty(int from) {
        for (int b = from; b < buckets; b++) {
            if (head[b] >= 0) {
                return b;
            }
        }
        return -1;
    }

    long bytes() {
        return 4L * head.length + 8L * value.length;
    }
}
