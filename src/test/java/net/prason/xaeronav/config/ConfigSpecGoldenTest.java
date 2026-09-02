package net.prason.xaeronav.config;

//? neoforge {
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 設定の定義（パス・型・既定値・レンジ・コメント）が意図せず変わっていないことを見る。
 *
 * <p>設定の定義をローダー非依存の記述へ移し替える作業では、37項目を手で書き写すことになる。
 * 既定値やレンジを1つ取り違えても、コンパイルは通り、他のテストも落ちず、ユーザーの手元で
 * 設定が静かに別の値になるだけになる。それを防ぐための突き合わせ。
 */
class ConfigSpecGoldenTest {

    /** 実際の中身。作業ディレクトリ（build/test-run）へ出すので、cleanで一緒に消える。 */
    private static final Path ACTUAL = Path.of("config-spec.actual");

    @Test
    void specMatchesGolden() throws IOException {
        String actual = dump(((ModConfigSpecStore) XaeroNavConfig.store()).modConfigSpec().getSpec());
        Files.writeString(ACTUAL, actual, StandardCharsets.UTF_8);
        assertEquals(golden(), actual, "設定の定義がゴールデンと違う。差分は " + ACTUAL.toAbsolutePath() + " と突き合わせる");
    }

    private static String golden() throws IOException {
        try (InputStream in = ConfigSpecGoldenTest.class.getResourceAsStream("/config-spec.golden")) {
            if (in == null) {
                return "<ゴールデンが無い>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String dump(UnmodifiableConfig spec) {
        List<String> lines = new ArrayList<>();
        collect(spec, "", lines);
        return String.join("\n", lines) + "\n";
    }

    private static void collect(UnmodifiableConfig config, String prefix, List<String> out) {
        for (Map.Entry<String, Object> entry : config.valueMap().entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig nested) {
                collect(nested, path, out);
            } else if (value instanceof ModConfigSpec.ValueSpec valueSpec) {
                out.add(describe(path, valueSpec));
            } else {
                out.add(path + " | 未知のノード: " + value.getClass().getName());
            }
        }
    }

    private static String describe(String path, ModConfigSpec.ValueSpec spec) {
        Object defaultValue = spec.getDefault();
        ModConfigSpec.Range<?> range = spec.getRange();
        return path
                + " | class=" + spec.getClass().getSimpleName()
                + " | type=" + (defaultValue == null ? "null" : defaultValue.getClass().getSimpleName())
                + " | default=" + defaultValue
                + " | range=" + (range == null ? "-" : range)
                + " | comment=" + String.valueOf(spec.getComment()).replace("\n", "\\n");
    }
}
//?}
