package net.prason.xaeronav.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.client.OptionInstance;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;

/**
 * 設定画面に登録されるトグルの一覧が壊れていないことを見る。GUIの実描画・レイアウトは対象外——
 * 「登録される項目とその値」の境界だけを確認する。
 *
 * <p>{@code XaeroNavConfig.INSTANCE}は静的初期化子でspecを作るだけで実際のload（ファイル読み込み）を
 * 待たないため、値を読むgetterを呼ぶと{@code IllegalStateException}になる。{@link NightConfigStoreTest}と
 * 同じ手順（{@link NightConfigStore}を経由してbuildまで済ませる）でload済みインスタンスを作る。
 */
class XaeroNavConfigScreenOptionsTest {

    @Test
    void everyOptionIsRegisteredWithoutThrowing(@TempDir Path dir) throws IOException {
        NightConfigStore store = new NightConfigStore(dir.resolve("xaeronav-client.toml"));
        XaeroNavConfig cfg = new XaeroNavConfig(store.spec());
        store.build();

        List<OptionInstance<?>> collected = new ArrayList<>();
        assertDoesNotThrow(() -> XaeroNavConfigScreen.addAllOptions(cfg, collected::add));
        // XaeroNavConfigScreen.addAllOptions内のaddBig.accept呼び出し数と一致させること。
        // 項目を増減したときにここが検知する。
        assertEquals(13, collected.size());
    }
}
