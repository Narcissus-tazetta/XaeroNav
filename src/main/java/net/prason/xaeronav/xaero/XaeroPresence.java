package net.prason.xaeronav.xaero;

import net.neoforged.fml.ModList;

/**
 * Xaero's World Mapが「MODとして読み込まれているか」。
 *
 * <p>クラスの存在（{@code Class.forName}）で判定してはいけない。開発実行のようにjarがクラスパスにだけ
 * 載っている状況では、クラスは見つかるのにXaero自身は初期化されておらず、しかもXaeroのクラスは
 * Minecraftのクラスを解決できない別のレイヤーに置かれる。その状態で触ると
 * {@code NoClassDefFoundError: net/minecraft/client/Minecraft}でゲームごと落ちる。
 *
 * <p>このクラス自体は{@code xaero.*}を参照しない。参照すると、Xaero未導入の環境ではこの判定を
 * 読むだけで{@link NoClassDefFoundError}になる。{@link XaeroMapReader}を呼ぶ前に必ずここを通すこと。
 */
public final class XaeroPresence {

    private static final String WORLD_MAP_MOD_ID = "xaeroworldmap";
    private static final String MINIMAP_MOD_ID = "xaerominimap";

    private XaeroPresence() {
    }

    public static boolean mapPresent() {
        return ModList.get().isLoaded(WORLD_MAP_MOD_ID);
    }

    /** ミニマップ側。地図データは世界地図が持つので、こちらはウェイポイントを置けるかの判定にだけ使う。 */
    public static boolean minimapPresent() {
        return ModList.get().isLoaded(MINIMAP_MOD_ID);
    }
}
