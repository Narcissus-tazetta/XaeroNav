package net.prason.xaeronav.diorama;

import net.prason.xaeronav.platform.ModPresence;

/**
 * Dioramaが「MODとして読み込まれているか」。
 *
 * <p>このクラス自体は{@code dev.diorama.*}を参照しない。参照すると、Diorama未導入の環境ではこの判定を
 * 読むだけで{@link NoClassDefFoundError}になる。{@link DioramaWaypoints}を呼ぶ前に必ずここを通すこと。
 */
public final class DioramaPresence {

    public static final String MOD_ID = "diorama";

    private DioramaPresence() {
    }

    public static boolean present() {
        return ModPresence.isLoaded(MOD_ID);
    }
}
