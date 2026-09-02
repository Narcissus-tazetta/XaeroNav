package net.prason.xaeronav.xaero;

import java.util.ArrayList;
import java.util.List;

import net.prason.xaeronav.platform.ModPresence;

/**
 * Xaero連携のmixinが実際に当たったかを、注入先のクラスに付く{@link XaeroHookMarker}で確かめる。
 *
 * <p>このクラス自体は{@code xaero.*}を参照しない（{@link XaeroPresence}と同じ理由）。注入先は
 * クラス名の文字列でだけ指す。
 */
public final class XaeroHooks {

    /** 注入先のクラス1つ＝ユーザーから見た機能1つ。 */
    public enum Hook {
        WORLD_MAP("xaeroworldmap", "xaero.map.gui.GuiMap", "hud.xaeronav.hook_world_map"),
        WAYPOINT_MENU("xaeroworldmap", "xaero.map.mods.gui.WaypointReader", "hud.xaeronav.hook_waypoint_menu"),
        MINIMAP("xaerominimap", "xaero.common.minimap.render.MinimapFBORenderer", "hud.xaeronav.hook_minimap");

        private final String modId;
        private final String className;
        private final String nameKey;

        Hook(String modId, String className, String nameKey) {
            this.modId = modId;
            this.className = className;
            this.nameKey = nameKey;
        }

        public String modId() {
            return modId;
        }

        public String className() {
            return className;
        }

        /** 何が使えなくなったのかをユーザーへ示す文言のキー。 */
        public String nameKey() {
            return nameKey;
        }
    }

    private XaeroHooks() {
    }

    /** 連携先のMODは読み込まれているのに、mixinが当たっていない機能。空なら全て当たっている。 */
    public static List<Hook> missing() {
        List<Hook> missing = new ArrayList<>();
        for (Hook hook : Hook.values()) {
            if (ModPresence.isLoaded(hook.modId()) && !applied(hook.className())) {
                missing.add(hook);
            }
        }
        return List.copyOf(missing);
    }

    /** その連携1つが実際に当たっているか。 */
    public static boolean applied(Hook hook) {
        return applied(hook.className());
    }

    private static boolean applied(String className) {
        try {
            // initialize=false。mixinの適用はクラスの読み込み時点で終わっているので、目印を見るだけなら
            // Xaero側の静的初期化まで走らせる必要は無い
            Class<?> target = Class.forName(className, false, XaeroHooks.class.getClassLoader());
            return XaeroHookMarker.class.isAssignableFrom(target);
        } catch (ClassNotFoundException notFound) {
            // 注入先のクラスごと名前が変わった。ユーザーから見た結果は「当たらなかった」と同じなので、
            // ここで投げ直さずそのまま報告に載せる
            return false;
        }
    }
}
