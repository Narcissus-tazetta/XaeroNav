package net.prason.xaeronav.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;

/**
 * Fabric側の保存先が、NeoForgeの{@code ModConfigSpec}と同じ設定ファイルを作ることを見る。
 *
 * <p>設定の定義（{@link XaeroNavConfig}の37項目）は1箇所にしか無いので、ずれるとしたら
 * 保存先の実装同士。ゴールデン（NeoForge側の実物から採った）と突き合わせる。
 */
class NightConfigStoreTest {

    @Test
    void writesTheSameFileAsModConfigSpec(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("xaeronav-client.toml");
        NightConfigStore store = new NightConfigStore(file);
        new XaeroNavConfig(store.spec());
        store.build();

        CommentedFileConfig written = CommentedFileConfig.builder(file).build();
        written.load();
        for (Map.Entry<String, Golden> entry : golden().entrySet()) {
            List<String> path = List.of(entry.getKey().split("\\."));
            // Objectで受けてから文字列にする。直接String.valueOfへ渡すと、
            // night-configのget()が総称型なのでchar[]のオーバーロードに推論されてClassCastになる
            Object value = written.get(path);
            assertEquals(entry.getValue().defaultValue(), String.valueOf(value),
                    entry.getKey() + " の既定値");
            assertEquals(entry.getValue().comment(), String.valueOf(written.getComment(path)).strip(),
                    entry.getKey() + " のコメント");
        }
    }

    @Test
    void correctsBrokenValuesOnLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("xaeronav-client.toml");
        Files.writeString(file, """
                [pathfinding]
                blockBudgetReserve = 9999
                heuristicWeight = "ではない数"
                diggingEnabled = false
                """, StandardCharsets.UTF_8);

        NightConfigStore store = new NightConfigStore(file);
        XaeroNavConfig config = new XaeroNavConfig(store.spec());
        store.build();

        // レンジ外は丸める・型違いは既定値へ戻す・正しい値はそのまま残す
        assertEquals(512, config.blockBudgetReserve());
        assertEquals(1.5, config.heuristicWeight());
        assertEquals(false, config.diggingEnabled());
    }

    @Test
    void keepsChangesAcrossSaveAndReload(@TempDir Path dir) {
        Path file = dir.resolve("xaeronav-client.toml");
        NightConfigStore store = new NightConfigStore(file);
        XaeroNavConfig config = new XaeroNavConfig(store.spec());
        store.build();

        config.setHudEnabled(false);
        store.save();

        NightConfigStore reopened = new NightConfigStore(file);
        XaeroNavConfig reloaded = new XaeroNavConfig(reopened.spec());
        reopened.build();
        assertTrue(!reloaded.hudEnabled());
    }

    private record Golden(String defaultValue, String comment) {
    }

    /** {@code ConfigSpecGoldenTest}が見ているのと同じ、NeoForge側の実物から採った定義の一覧。 */
    private static Map<String, Golden> golden() throws IOException {
        Map<String, Golden> golden = new LinkedHashMap<>();
        try (InputStream in = NightConfigStoreTest.class.getResourceAsStream("/config-spec.golden")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] columns = line.split(" \\| ", -1);
                String path = columns[0];
                String defaultValue = columns[3].substring("default=".length());
                String comment = columns[5].substring("comment=".length()).replace("\\n", "\n");
                golden.put(path, new Golden(defaultValue, comment));
            }
        }
        return golden;
    }
}
