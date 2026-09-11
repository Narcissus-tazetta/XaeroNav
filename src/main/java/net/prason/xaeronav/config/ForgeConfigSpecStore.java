package net.prason.xaeronav.config;

//? forge {
/*import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraftforge.common.ForgeConfigSpec;

/^*
 * Forge側の保存先。読み書き・ファイル監視・不正値の補正はすべてFMLが持つ
 * {@code ForgeConfigSpec}に任せる。
 ^/
public final class ForgeConfigSpecStore implements NavConfigStore, NavConfigSpec {

    private final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
    private ForgeConfigSpec built;

    @Override
    public NavConfigSpec spec() {
        return this;
    }

    @Override
    public void build() {
        built = builder.build();
    }

    @Override
    public void save() {
        built.save();
    }

    /^* {@code ModLoadingContext#registerConfig}へ渡すためのもの。 ^/
    public ForgeConfigSpec forgeConfigSpec() {
        return built;
    }

    @Override
    public NavConfigSpec comment(String... lines) {
        builder.comment(lines);
        return this;
    }

    @Override
    public NavConfigSpec push(String section) {
        builder.push(section);
        return this;
    }

    @Override
    public NavConfigSpec pop() {
        builder.pop();
        return this;
    }

    @Override
    public BoolValue define(String path, boolean defaultValue) {
        ForgeConfigSpec.BooleanValue value = builder.define(path, defaultValue);
        return new BoolValue() {
            @Override
            public boolean get() {
                return value.get();
            }

            @Override
            public void set(boolean newValue) {
                value.set(newValue);
            }
        };
    }

    @Override
    public IntValue defineInRange(String path, int defaultValue, int min, int max) {
        ForgeConfigSpec.IntValue value = builder.defineInRange(path, defaultValue, min, max);
        return new IntValue() {
            @Override
            public int get() {
                return value.get();
            }

            @Override
            public void set(int newValue) {
                value.set(newValue);
            }
        };
    }

    @Override
    public DoubleValue defineInRange(String path, double defaultValue, double min, double max) {
        ForgeConfigSpec.DoubleValue value = builder.defineInRange(path, defaultValue, min, max);
        return value::get;
    }

    // Forgeの`defineListAllowEmpty`にはNeoForgeが持つ4引数版（新規要素のSupplierを取る）が無い。
    // GUIの「要素を追加」ボタンが作る初期値をForgeだけ持てないだけで、既定値・保存形式は変わらない
    @Override
    public StringListValue defineStringList(String path, List<String> defaultValue,
            Supplier<String> newElement, Predicate<Object> elementValidator) {
        ForgeConfigSpec.ConfigValue<List<? extends String>> value =
                builder.defineListAllowEmpty(path, defaultValue, elementValidator);
        return value::get;
    }
}
*///?}
