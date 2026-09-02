package net.prason.xaeronav.config;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 設定項目の宣言先。{@link XaeroNavConfig}はこの1面だけに対して37項目を書き、
 * 実際の保存先（NeoForgeの{@code ModConfigSpec} / Fabricのnight-config）は
 * {@link NavConfigStore}の実装が受け持つ。
 *
 * <p>メソッドの形はNeoForgeの{@code ModConfigSpec.Builder}に意図的に揃えてある。
 * 揃えないと37項目を書き写す際に既定値やレンジがずれても気付けない
 * （その突き合わせは{@code ConfigSpecGoldenTest}が見る）。
 */
public interface NavConfigSpec {

    /** 直後に宣言する項目（または{@link #push}するセクション）へ付けるコメント。 */
    NavConfigSpec comment(String... lines);

    NavConfigSpec push(String section);

    NavConfigSpec pop();

    BoolValue define(String path, boolean defaultValue);

    IntValue defineInRange(String path, int defaultValue, int min, int max);

    DoubleValue defineInRange(String path, double defaultValue, double min, double max);

    StringListValue defineStringList(String path, List<String> defaultValue,
            Supplier<String> newElement, Predicate<Object> elementValidator);

    interface BoolValue {
        boolean get();

        void set(boolean value);
    }

    interface IntValue {
        int get();

        void set(int value);
    }

    interface DoubleValue {
        double get();
    }

    interface StringListValue {
        List<? extends String> get();
    }
}
