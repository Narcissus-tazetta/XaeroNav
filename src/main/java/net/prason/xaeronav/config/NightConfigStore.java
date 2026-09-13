package net.prason.xaeronav.config;

import net.prason.xaeronav.util.MathSupport;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * NeoForgeの{@code ModConfigSpec}が持っていない場所（Fabric）での保存先。
 *
 * <p>読み書きにはnight-configを使う——NeoForgeの{@code ModConfigSpec}が内部で使っているのと
 * 同じライブラリなので、生成されるTOMLはローダーが違っても同じ形になる（コメントの
 * {@code Default:} / {@code Range:} 行まで含めて揃えてある）。
 *
 * <p>ローダー固有のクラスには触れない。設定ファイルの場所だけ呼び出し側から受け取る
 * ——そうしておくと、Fabricノードでしかコンパイルされないコードにならず、正典ノードの
 * 単体テストで読み書きの挙動をそのまま確かめられる。
 */
public final class NightConfigStore implements NavConfigStore, NavConfigSpec {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final CommentedFileConfig file;
    private final Path path;
    private final List<Definition> definitions = new ArrayList<>();
    private final Deque<String> section = new ArrayDeque<>();

    private String pendingComment;

    public NightConfigStore(Path path) {
        this.path = path;
        this.file = CommentedFileConfig.builder(path)
                .sync()
                .preserveInsertionOrder()
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .build();
    }

    @Override
    public NavConfigSpec spec() {
        return this;
    }

    @Override
    public void build() {
        try {
            file.load();
        } catch (RuntimeException parseError) {
            recoverBrokenFile(parseError);
        }
        for (Definition definition : definitions) {
            file.set(definition.path(), definition.correct(file.get(definition.path())));
            file.setComment(definition.path(), definition.comment());
        }
        file.save();
    }

    /** 構文が壊れた元ファイルを残したまま、既定値で再生成できる空の設定へ戻す。 */
    private void recoverBrokenFile(RuntimeException parseError) {
        Path broken = path.resolveSibling(path.getFileName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.move(path, broken, StandardCopyOption.REPLACE_EXISTING);
            file.clear();
            LOGGER.warn("XaeroNav: 壊れた設定ファイルを {} へ退避し、既定値で再生成します", broken, parseError);
        } catch (java.io.IOException moveError) {
            moveError.addSuppressed(parseError);
            throw new IllegalStateException("壊れた設定ファイルを退避できませんでした: " + path, moveError);
        }
    }

    @Override
    public void save() {
        file.save();
    }

    @Override
    public NavConfigSpec comment(String... lines) {
        pendingComment = String.join("\n", lines);
        return this;
    }

    @Override
    public NavConfigSpec push(String name) {
        section.addLast(name);
        List<String> path = List.copyOf(section);
        if (!file.contains(path)) {
            file.set(path, file.createSubConfig());
        }
        file.setComment(path, takeComment());
        return this;
    }

    @Override
    public NavConfigSpec pop() {
        section.removeLast();
        return this;
    }

    @Override
    public BoolValue define(String name, boolean defaultValue) {
        List<String> path = define(name, defaultValue, takeComment(),
                value -> value instanceof Boolean ? value : defaultValue);
        return new BoolValue() {
            @Override
            public boolean get() {
                return file.get(path);
            }

            @Override
            public void set(boolean value) {
                file.set(path, value);
            }
        };
    }

    @Override
    public IntValue defineInRange(String name, int defaultValue, int min, int max) {
        List<String> path = define(name, defaultValue, rangeComment(takeComment(), defaultValue, range(min, max)),
                value -> value instanceof Number number ? MathSupport.clamp(number.longValue(), min, max) : defaultValue);
        return new IntValue() {
            @Override
            public int get() {
                return ((Number) file.get(path)).intValue();
            }

            @Override
            public void set(int value) {
                file.set(path, value);
            }
        };
    }

    @Override
    public DoubleValue defineInRange(String name, double defaultValue, double min, double max) {
        List<String> path = define(name, defaultValue, rangeComment(takeComment(), defaultValue, min + " ~ " + max),
                value -> value instanceof Number number ? MathSupport.clamp(number.doubleValue(), min, max) : defaultValue);
        return () -> ((Number) file.get(path)).doubleValue();
    }

    @Override
    public StringListValue defineStringList(String name, List<String> defaultValue,
            Supplier<String> newElement, Predicate<Object> elementValidator) {
        List<String> path = define(name, defaultValue, takeComment(), value -> {
            if (!(value instanceof List<?> list)) {
                return defaultValue;
            }
            // 1つでも壊れた要素があればリストごと既定値へ戻す（ModConfigSpecと同じ扱い）。
            // 壊れた要素だけ落とすと、直したつもりの設定が黙って一部無視されることになる
            return list.stream().allMatch(elementValidator) ? list : defaultValue;
        });
        return () -> file.<List<String>>get(path);
    }

    private List<String> define(String name, Object defaultValue, String comment, Function<Object, Object> corrector) {
        section.addLast(name);
        List<String> path = List.copyOf(section);
        section.removeLast();
        definitions.add(new Definition(path, defaultValue, comment, corrector));
        return path;
    }

    private String takeComment() {
        String comment = pendingComment;
        pendingComment = null;
        return comment;
    }

    /** ModConfigSpecが既定値とレンジをコメント末尾へ足すのに合わせる。 */
    private static String rangeComment(String comment, Object defaultValue, String range) {
        return comment + "\n Default: " + defaultValue + "\n Range: " + range;
    }

    private static String range(int min, int max) {
        if (max == Integer.MAX_VALUE) {
            return "> " + min;
        }
        if (min == Integer.MIN_VALUE) {
            return "< " + max;
        }
        return min + " ~ " + max;
    }

    private record Definition(List<String> path, Object defaultValue, String comment,
            Function<Object, Object> corrector) {

        Object correct(Object value) {
            return value == null ? defaultValue : corrector.apply(value);
        }
    }
}
