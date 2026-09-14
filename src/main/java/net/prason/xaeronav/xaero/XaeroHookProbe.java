package net.prason.xaeronav.xaero;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * runtime CIで、Xaero向けmixinの注入点が「付いた」だけでなく実際に呼ばれたことを数える。
 *
 * <p>通常起動ではsystem propertyが無いので、各注入点で行うのはboolean判定1回だけ。CI driver側から
 * countを増やすAPIは持たず、変換後のXaero対象メソッドを通ったときだけ記録される。
 */
public final class XaeroHookProbe {

    public static final String PROPERTY = "xaeronav-ci.runtimeHookProbe";

    public enum Point {
        WORLD_MAP_RENDER,
        MINIMAP_RENDER,
        WORLD_MAP_KEY,
        WORLD_MAP_MENU,
        WAYPOINT_MENU
    }

    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final AtomicIntegerArray COUNTS = new AtomicIntegerArray(Point.values().length);

    private XaeroHookProbe() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void record(Point point) {
        if (ENABLED) {
            COUNTS.incrementAndGet(point.ordinal());
        }
    }

    public static boolean ran(Point point) {
        return COUNTS.get(point.ordinal()) > 0;
    }

    public static List<Point> missing() {
        List<Point> missing = new ArrayList<>();
        for (Point point : Point.values()) {
            if (!ran(point)) {
                missing.add(point);
            }
        }
        return List.copyOf(missing);
    }
}
