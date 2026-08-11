package net.prason.xaeronav.client;

/**
 * 経路が分からない区間を目的地まで直線で結ぶ、地図用の点線。
 *
 * <p>未読み込みチャンクの先は探索そのものができないので、地図上でも経路は途中で終わる。
 * どちらへ向かえばいいのかだけは分かるように、残りを直線で繋いで描く。地形を辿った経路とは
 * 別物なので、実線ではなく点線にして区別する。
 */
public final class StraightDots {

    /** 点線の周期（ブロック）。周期の前半だけを描く。 */
    private static final int PERIOD = 4;
    private static final int DASH = 2;

    /**
     * 刻む点の上限。目的地が数千ブロック先でも、地図に載らない範囲まで数える意味はない。
     */
    private static final int MAX_DOTS = 4096;

    @FunctionalInterface
    public interface DotConsumer {
        void accept(int blockX, int blockZ);
    }

    private StraightDots() {
    }

    public static void forEach(int fromX, int fromZ, int toX, int toZ, DotConsumer consumer) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < PERIOD) {
            return;
        }
        int count = (int) Math.min(length, MAX_DOTS);
        double stepX = dx / length;
        double stepZ = dz / length;
        for (int i = 0; i < count; i++) {
            if (i % PERIOD >= DASH) {
                continue;
            }
            consumer.accept((int) Math.floor(fromX + stepX * i), (int) Math.floor(fromZ + stepZ * i));
        }
    }
}
