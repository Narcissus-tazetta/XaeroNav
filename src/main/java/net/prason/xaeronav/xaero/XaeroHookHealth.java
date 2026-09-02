package net.prason.xaeronav.xaero;

import net.minecraft.client.Minecraft;
import net.prason.xaeronav.XaeroNav;

/**
 * 「mixinは当たっているのに、地図へ実際には描かれていない」を見つける。
 *
 * <p>{@link XaeroHooks}が見るのは注入先のクラスに目印が付いたかどうかだけで、注入は成功したが
 * Xaero側の描画の作りが変わって何も出なくなった、という壊れ方は素通りする。ユーザーからは
 * どちらも「地図に線が出ない」としか見えない。
 *
 * <p>判定は世界地図の画面が開いている間だけに限る。その間はこちらの注入点（{@code GuiMap#render}）が
 * 毎フレーム必ず通るので、通らないなら壊れていると断定できる。ミニマップ側は
 * ユーザーが表示を切れるため、「描かれない」ことが故障を意味しない。
 */
public final class XaeroHookHealth {

    /** Xaeroの世界地図の画面。クラスを参照するとXaero未導入の環境でこのクラスごと読めなくなる。 */
    private static final String WORLD_MAP_SCREEN = "xaero.map.gui.GuiMap";

    /**
     * 世界地図を開いてからこれだけのtickの間に注入点を1度も通らなければ壊れているとみなす。
     * 画面を開いた最初の数フレームは地形の読み込みで描画が回らないことがあるので、少し待つ。
     */
    private static final int GRACE_TICKS = 40;

    private static int ticksWithMapOpen;
    private static boolean renderBroken;

    private XaeroHookHealth() {
    }

    /** 地図側の注入点から呼ばれる。ここへ到達している＝mixinが実際に動いている。 */
    public static void hookRan() {
        ticksWithMapOpen = 0;
        renderBroken = false;
    }

    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null || !WORLD_MAP_SCREEN.equals(minecraft.screen.getClass().getName())) {
            ticksWithMapOpen = 0;
            return;
        }
        if (renderBroken) {
            return;
        }
        if (++ticksWithMapOpen > GRACE_TICKS) {
            renderBroken = true;
            XaeroNav.LOGGER.warn("XaeroNav: 世界地図のmixinは当たっているが、描画の注入点を一度も通っていない。"
                    + "Xaeroの版が対応範囲の外にある可能性がある");
        }
    }

    /** 世界地図への描き込みが届いていないと判断した状態。 */
    public static boolean worldMapRenderBroken() {
        return renderBroken;
    }
}
