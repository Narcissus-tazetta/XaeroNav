package net.prason.xaeronav.config;

//? neoforge {
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge側の保存先。読み書き・ファイル監視・不正値の補正はすべてFMLが持つ
 * {@code ModConfigSpec}に任せる。
 */
public final class ModConfigSpecStore implements NavConfigStore, NavConfigSpec {

    private final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
    private ModConfigSpec built;

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

    /** {@code ModContainer#registerConfig}へ渡すためのもの。 */
    public ModConfigSpec modConfigSpec() {
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
        ModConfigSpec.BooleanValue value = builder.define(path, defaultValue);
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
        ModConfigSpec.IntValue value = builder.defineInRange(path, defaultValue, min, max);
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
        ModConfigSpec.DoubleValue value = builder.defineInRange(path, defaultValue, min, max);
        return value::get;
    }

    @Override
    public StringListValue defineStringList(String path, List<String> defaultValue,
            Supplier<String> newElement, Predicate<Object> elementValidator) {
        ModConfigSpec.ConfigValue<List<? extends String>> value =
                builder.defineListAllowEmpty(path, defaultValue, newElement, elementValidator);
        return value::get;
    }
}
//?}
