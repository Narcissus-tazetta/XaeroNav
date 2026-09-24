package net.prason.xaeronav.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
//? if <1.17 {
/*import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
*///?}

/** Text factory shared by Minecraft versions with and without Component static factories. */
public final class TextCompat {
    private TextCompat() {
    }

    public static MutableComponent translatable(String key, Object... args) {
        //? if >=1.17 {
        return Component.translatable(key, args);
        //?} else {
        /*return new TranslatableComponent(key, args);
        *///?}
    }

    public static MutableComponent literal(String value) {
        //? if >=1.17 {
        return Component.literal(value);
        //?} else {
        /*return new TextComponent(value);
        *///?}
    }

    public static MutableComponent empty() {
        return literal("");
    }
}
