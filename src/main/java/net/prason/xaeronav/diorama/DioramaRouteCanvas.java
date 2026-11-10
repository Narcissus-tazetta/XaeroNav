package net.prason.xaeronav.diorama;

//? if diorama {
import java.util.Arrays;

import dev.diorama.MapScreen;
import dev.diorama.Projection;
import dev.diorama.render.Circle;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.waypoint.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.prason.xaeronav.client.ClientCompat;
import net.prason.xaeronav.client.MapPathOverlay;

/**
 * Dioramaの地図（世界地図・ミニマップ）へ経路を描く。
 *
 * <p>Dioramaは世界を斜めから見下ろす投影で描くので、同じXZでも高さで画面上の位置が変わる。
 * 点ごとの高さを{@link MapPathOverlay.QuadSink#height}で受け取り、Dioramaの{@code Projection}に通して
 * 画面座標へ直す。地図の向き（東西南北）も{@code Projection}が持っている。
 *
 * <p>描画そのものはDioramaの{@code MapCanvas}に任せる。Minecraft 1.21.6でGUIの描き方が変わっており、
 * その差を{@code MapCanvas}が吸収しているため。
 */
public final class DioramaRouteCanvas {

    private static final int ALPHA = 0xE6000000;

    /**
     * 点の画面上の最小の大きさ（GUIピクセル）。地図を縮小すると1ブロックは1ピクセルを割り、
     * そのまま描くと線が消える。
     */
    private static final float MIN_DOT_PX = 1.5f;

    /** 目的地の目印（ウェイポイントで示せなかったときだけ出す）の半分の大きさ。 */
    private static final float MARKER_HALF_PX = 3.5f;
    private static final float MARKER_OUTLINE_PX = 1.0f;
    private static final int MARKER_COLOR = 0xFFFFFFFF;
    private static final int MARKER_OUTLINE_COLOR = 0xFF101010;

    private DioramaRouteCanvas() {
    }

    /**
     * 世界地図へ描く。別の次元の地図を開いているときは描かない——経路の座標はいまいる次元のもので、
     * 別の次元の地図に重ねても意味が無い。
     */
    public static void drawOnWorldMap(GuiGraphics graphics, MapViewport.View view) {
        if (ClientCompat.screen(Minecraft.getInstance()) instanceof MapScreen screen
                && !screen.dimension().equals(Waypoints.dimension())) {
            return;
        }
        draw(graphics, view);
    }

    public static void draw(GuiGraphics graphics, MapViewport.View view) {
        MapPathOverlay.Snapshot snapshot = MapPathOverlay.read();
        if (snapshot.isEmpty()) {
            return;
        }
        Quads quads = new Quads(view);
        MapPathOverlay.drawRoute(snapshot, quads);
        BlockPos goal = snapshot.goal();
        if (goal != null && snapshot.goalMarker()) {
            quads.marker(goal);
        }
        if (quads.count > 0) {
            // 1.21.6以降のMapCanvasは渡した配列をそのフレームの描画まで持ち続けるので、使い回さない
            MapCanvas.of(graphics).solids(Arrays.copyOf(quads.xyz, quads.count * 12),
                    Arrays.copyOf(quads.colors, quads.count), quads.count);
        }
    }

    private static final class Quads implements MapPathOverlay.QuadSink {
        private final MapViewport.View view;
        private final Circle circle;
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;
        private float[] xyz = new float[12 * 256];
        private int[] colors = new int[256];
        private int count;
        private int height;

        Quads(MapViewport.View view) {
            this.view = view;
            this.circle = view.round() ? Circle.in(view) : null;
            this.left = view.x();
            this.top = view.y();
            this.right = view.x() + view.width();
            this.bottom = view.y() + view.height();
        }

        @Override
        public void height(int blockY) {
            this.height = blockY;
        }

        @Override
        public void rect(int blockX1, int blockZ1, int blockX2, int blockZ2, float red, float green, float blue) {
            // 地図の向きで矩形の角は入れ替わるが、回転は90度刻みなので画面上でも軸に沿った矩形のまま
            float ax = view.screenX(Projection.u(blockX1, blockZ1));
            float bx = view.screenX(Projection.u(blockX2, blockZ2));
            float ay = view.screenY(Projection.v(blockX1, height, blockZ1));
            float by = view.screenY(Projection.v(blockX2, height, blockZ2));
            float x1 = Math.min(ax, bx);
            float x2 = Math.max(ax, bx);
            float y1 = Math.min(ay, by);
            float y2 = Math.max(ay, by);
            if (x2 - x1 < MIN_DOT_PX) {
                float centre = (x1 + x2) / 2;
                x1 = centre - MIN_DOT_PX / 2;
                x2 = centre + MIN_DOT_PX / 2;
            }
            if (y2 - y1 < MIN_DOT_PX) {
                float centre = (y1 + y2) / 2;
                y1 = centre - MIN_DOT_PX / 2;
                y2 = centre + MIN_DOT_PX / 2;
            }
            add(x1, y1, x2, y2, ALPHA | channel(red) << 16 | channel(green) << 8 | channel(blue));
        }

        /** 目的地の目印。画面上で常に同じ大きさの、縁取り付きの四角。 */
        void marker(BlockPos goal) {
            float x = view.screenX(Projection.u(goal.getX() + 0.5, goal.getZ() + 0.5));
            float y = view.screenY(Projection.v(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5));
            float outer = MARKER_HALF_PX + MARKER_OUTLINE_PX;
            add(x - outer, y - outer, x + outer, y + outer, MARKER_OUTLINE_COLOR);
            add(x - MARKER_HALF_PX, y - MARKER_HALF_PX, x + MARKER_HALF_PX, y + MARKER_HALF_PX, MARKER_COLOR);
        }

        private void add(float x1, float y1, float x2, float y2, int argb) {
            if (circle != null) {
                // 点は小さいので、丸いミニマップでは中心が円の外なら丸ごと落とせば足りる
                if (!circle.contains((x1 + x2) / 2, (y1 + y2) / 2)) {
                    return;
                }
            } else {
                x1 = Math.max(x1, left);
                y1 = Math.max(y1, top);
                x2 = Math.min(x2, right);
                y2 = Math.min(y2, bottom);
                if (x1 >= x2 || y1 >= y2) {
                    return;
                }
            }
            if (count == colors.length) {
                colors = Arrays.copyOf(colors, count * 2);
                xyz = Arrays.copyOf(xyz, count * 24);
            }
            // 角の順はMapCanvasの決まり（左上・左下・右下・右上）。逆回りは描かれない
            int base = count * 12;
            xyz[base] = x1;
            xyz[base + 1] = y1;
            xyz[base + 3] = x1;
            xyz[base + 4] = y2;
            xyz[base + 6] = x2;
            xyz[base + 7] = y2;
            xyz[base + 9] = x2;
            xyz[base + 10] = y1;
            colors[count++] = argb;
        }

        private static int channel(float value) {
            return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
        }
    }
}
//?}
