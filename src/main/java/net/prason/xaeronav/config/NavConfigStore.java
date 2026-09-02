package net.prason.xaeronav.config;

/**
 * 設定の保存先。
 *
 * <p>NeoForgeでは{@code ModConfigSpec}（＝FMLが読み書きとファイル監視まで面倒を見る）、
 * Fabricでは自前でnight-configのTOMLを読み書きする。どちらもファイルの場所と書式は同じ
 * （{@code config/xaeronav-client.toml}）。
 */
public interface NavConfigStore {

    NavConfigSpec spec();

    /** 全項目の宣言が済んだ後に1度だけ呼ぶ。ここで初めてファイルの読み込みと既定値の補完が起きる。 */
    void build();

    /** 変更をディスクへ書き出す。 */
    void save();
}
