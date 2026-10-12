package net.prason.xaeronav.mixin;

/**
 * vanillaのクラスへのmixinに渡す{@code remap}の値。
 *
 * <p>本番が中間マッピング（26.1より前のFabric）・SRG（1.20.4以前のForge）の版は、注入先の名前をrefmapで引く必要がある。
 * 公式マッピングで動く版（NeoForge・1.20.5以降のForge）は引く先が無く、remapのままだとmixinの注釈処理が
 * 「マッピングが見つからない」でビルドを止める。注釈の値は定数でなければならないので、ここに定数として置く。
 */
public final class MixinRemap {
    //? if fabric && <26.1 {
    /*public static final boolean VANILLA = true;
    *///?} else if forge && <1.20.5 {
    /*public static final boolean VANILLA = true;
    *///?} else {
    public static final boolean VANILLA = false;
    //?}

    private MixinRemap() {
    }
}
