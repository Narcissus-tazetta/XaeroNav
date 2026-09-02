package net.prason.xaeronav.platform;

//? neoforge {
import net.neoforged.fml.ModList;
//?} fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?}

/**
 * 「そのMODが読み込まれているか」をローダーの違いを跨いで答える。
 *
 * <p>クラスの存在（{@code Class.forName}）で代用してはいけない。開発実行のようにjarがクラスパスにだけ
 * 載っている状況では、クラスは見つかるのにそのMOD自身は初期化されておらず、しかもそのクラスは
 * Minecraftのクラスを解決できない別のレイヤーに置かれる。触った瞬間に
 * {@link NoClassDefFoundError}でゲームごと落ちる。
 */
public final class ModPresence {

    private ModPresence() {
    }

    public static boolean isLoaded(String modId) {
        //? neoforge {
        return ModList.get().isLoaded(modId);
        //?} fabric {
        /*return FabricLoader.getInstance().isModLoaded(modId);
        *///?}
    }

    /** 読み込まれているMODのバージョン文字列。未導入なら {@code "unknown"}。 */
    public static String version(String modId) {
        //? neoforge {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
        //?} fabric {
        /*return FabricLoader.getInstance().getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        *///?}
    }
}
