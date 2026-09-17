package net.prason.xaeronav.pathfinding.navgraph;

/**
 * 読める列の範囲。セクションを組んだときに周りがどれだけ読めていたかを数え、後で多く読めるようになったら
 * 組み直すのに使う（{@link NavGraph#missingSections}）。
 *
 * <p>窓（中心からの正方形）の縁で欠けるのに加えて、実機では<b>窓の中でもまだ届いていないチャンク</b>で欠ける。
 * 後者を数えないと、読み込みの途中で組んだセクションが穴の空いたまま残る。
 */
@FunctionalInterface
public interface LoadedArea {

    /** {@code [minX, maxX]×[minZ, maxZ]}のうち読める列の数。 */
    int columns(int minX, int maxX, int minZ, int maxZ);

    /** チャンクが読み込まれているか。 */
    @FunctionalInterface
    interface ChunkLoaded {
        boolean test(int chunkX, int chunkZ);
    }

    /** 中心から水平{@code radius}の正方形が全部読めている。 */
    static LoadedArea square(int centerX, int centerZ, int radius) {
        return (minX, maxX, minZ, maxZ) -> {
            int width = Math.min(maxX, centerX + radius) - Math.max(minX, centerX - radius) + 1;
            int depth = Math.min(maxZ, centerZ + radius) - Math.max(minZ, centerZ - radius) + 1;
            return Math.max(0, width) * Math.max(0, depth);
        };
    }

    /** 中心から水平{@code radius}の正方形のうち、読み込まれたチャンクの列。 */
    static LoadedArea chunks(int centerX, int centerZ, int radius, ChunkLoaded loaded) {
        return (minX, maxX, minZ, maxZ) -> {
            int x0 = Math.max(minX, centerX - radius);
            int x1 = Math.min(maxX, centerX + radius);
            int z0 = Math.max(minZ, centerZ - radius);
            int z1 = Math.min(maxZ, centerZ + radius);
            int total = 0;
            for (int chunkX = x0 >> 4; chunkX <= x1 >> 4; chunkX++) {
                int width = Math.min(x1, chunkX * 16 + 15) - Math.max(x0, chunkX * 16) + 1;
                for (int chunkZ = z0 >> 4; chunkZ <= z1 >> 4; chunkZ++) {
                    if (width > 0 && loaded.test(chunkX, chunkZ)) {
                        total += width * Math.max(0, Math.min(z1, chunkZ * 16 + 15) - Math.max(z0, chunkZ * 16) + 1);
                    }
                }
            }
            return total;
        };
    }
}
