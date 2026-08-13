package net.prason.xaeronav;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 翻訳キーの整合性。
 *
 * <p>文言の追加は「コードに1つ、en_usに1つ、ja_jpに1つ」の3箇所へ同時に書く作業なので、
 * どれかが抜けやすい。抜けても例外は出ず、画面に生のキー文字列（{@code hud.xaeronav.foo}）が
 * 出るだけなので、動かして該当の状況を再現しない限り気付けない。
 *
 * <p>JSONの解析にライブラリは使わない。対象は自分で書いた平坦な文字列辞書だけで、
 * そのためにテスト依存を増やす必要はない。
 */
class LanguageKeyTest {

    private static final Path LANG_DIR = Path.of("src/main/resources/assets/xaeronav/lang");
    private static final Path SOURCE_DIR = Path.of("src/main/java");

    /** {@code "key": "value"} の左辺だけを拾う。 */
    private static final Pattern JSON_KEY = Pattern.compile("\"([^\"]+)\"\\s*:");

    /**
     * ソース中の翻訳キーらしき文字列リテラル。
     *
     * <p>呼び出し方（{@code Component.translatable(...)}・{@code RightClickOption}のコンストラクタ・
     * 三項演算子の枝）で絞り込まず、名前空間で拾う。渡し方は増えるので、そのたびにこの正規表現を
     * 直す羽目になると、テストが通っているのに実際は見ていない状態に静かに戻る。
     */
    private static final Pattern USED_KEY = Pattern.compile(
            "\"((?:gui|hud|commands|key|xaeronav)\\.[a-zA-Z0-9_.]+)\"");

    private static Set<String> keysOf(String fileName) {
        String json = read(LANG_DIR.resolve(fileName));
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = JSON_KEY.matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("読めませんでした: " + path.toAbsolutePath(), e);
        }
    }

    @Test
    void everyLanguageFileHasTheSameKeys() {
        Set<String> english = keysOf("en_us.json");
        Set<String> japanese = keysOf("ja_jp.json");

        assertTrue(!english.isEmpty(), "en_us.json からキーを1つも読めていない（テスト側の問題）");

        Set<String> missingInJapanese = new TreeSet<>(english);
        missingInJapanese.removeAll(japanese);
        Set<String> missingInEnglish = new TreeSet<>(japanese);
        missingInEnglish.removeAll(english);

        assertTrue(missingInJapanese.isEmpty(), "ja_jp.json に無いキー: " + missingInJapanese);
        assertTrue(missingInEnglish.isEmpty(), "en_us.json に無いキー: " + missingInEnglish);
    }

    @Test
    void everyKeyUsedInCodeIsTranslated() {
        Set<String> declared = keysOf("en_us.json");
        Set<String> used = usedKeys();

        assertTrue(!used.isEmpty(), "ソースから翻訳キーを1つも拾えていない（テスト側の問題）");

        Set<String> undeclared = new TreeSet<>(used);
        undeclared.removeAll(declared);
        // GuiMapRightClickMixinがXaero自身のメニュー項目を判別するために比較対象として持つ、
        // Xaeroの翻訳キー（"gui.xaero_..."）。うちのlangに declare するものではない
        // （うちの実際のキーは"gui.xaeronav..."で、間にアンダースコアが入らないため衝突しない）
        undeclared.removeIf(key -> key.startsWith("gui.xaero_"));

        assertTrue(undeclared.isEmpty(),
                "コードで参照しているのに lang ファイルに無いキー: " + undeclared);
    }

    @Test
    void everyTranslatedKeyIsUsedSomewhere() {
        Set<String> declared = keysOf("en_us.json");
        Set<String> used = usedKeys();

        Set<String> unused = new TreeSet<>(declared);
        unused.removeAll(used);
        // 設定画面の項目名・説明はNeoForgeが xaeronav.configuration.* を規約で引くので、
        // ソースには文字列として現れない
        unused.removeIf(key -> key.startsWith("xaeronav.configuration."));
        // キーバインドもNeoForgeがKeyMappingの登録名から引く
        unused.removeIf(key -> key.startsWith("key."));

        assertTrue(unused.isEmpty(), "lang にあるがコードから参照されていないキー: " + unused);
    }

    private static Set<String> usedKeys() {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE_DIR)) {
            List<Path> javaFiles = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            for (Path file : javaFiles) {
                Matcher matcher = USED_KEY.matcher(read(file));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ソースを走査できませんでした", e);
        }
        return keys;
    }
}
