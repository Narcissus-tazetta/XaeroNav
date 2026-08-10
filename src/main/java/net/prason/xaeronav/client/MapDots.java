package net.prason.xaeronav.client;

import java.util.Arrays;
import java.util.List;

import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;

/**
 * Xaeroの世界地図・ミニマップ用に、経路を平坦な配列へ焼いたもの。
 *
 * <p>地図側は経路を1ブロック四方の矩形の連なりとして描く。毎フレーム{@link PathStep}のリストを
 * 辿って色を判定し直す必要はないので、経路が変わったときにだけ組み直して両方の描画で共有する。
 *
 * <p>地図はXZ平面への投影なので、Yだけが違う連続ステップ（階段・掘り下げ）は同じ矩形になる。
 * 連続する重複を落としても見た目は変わらない。
 */
public final class MapDots {

    private static volatile MapDots cache;

    private final PathResult source;

    public final int[] x;
    public final int[] z;
    /** 点ごとのRGB（点数 × 3）。 */
    public final float[] color;
    public final int count;

    private MapDots(PathResult source, int[] x, int[] z, float[] color, int count) {
        this.source = source;
        this.x = x;
        this.z = z;
        this.color = color;
        this.count = count;
    }

    public static MapDots forPath(PathResult result) {
        MapDots cached = cache;
        if (cached != null && cached.source == result) {
            return cached;
        }
        MapDots built = build(result);
        cache = built;
        return built;
    }

    private static MapDots build(PathResult result) {
        List<PathStep> steps = result.steps();
        int[] x = new int[steps.size()];
        int[] z = new int[steps.size()];
        float[] color = new float[steps.size() * 3];
        int count = 0;

        for (PathStep step : steps) {
            int stepX = step.pos().getX();
            int stepZ = step.pos().getZ();
            if (count > 0 && x[count - 1] == stepX && z[count - 1] == stepZ) {
                continue;
            }
            float[] stepColor = PathColors.forStep(step);
            x[count] = stepX;
            z[count] = stepZ;
            color[count * 3] = stepColor[0];
            color[count * 3 + 1] = stepColor[1];
            color[count * 3 + 2] = stepColor[2];
            count++;
        }

        return new MapDots(result, Arrays.copyOf(x, count), Arrays.copyOf(z, count),
                Arrays.copyOf(color, count * 3), count);
    }
}
