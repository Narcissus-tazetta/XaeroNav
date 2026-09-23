package net.prason.xaeronav.client;

import net.minecraft.client.Options;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Small API differences between Minecraft 1.16 and the newer supported releases. */
public final class GameCompat {
    private GameCompat() {
    }

    public static Inventory inventory(Player player) {
        //? if >=1.17 {
        return player.getInventory();
        //?} else {
        /*return player.inventory;
        *///?}
    }

    public static Abilities abilities(Player player) {
        //? if >=1.17 {
        return player.getAbilities();
        //?} else {
        /*return player.abilities;
        *///?}
    }

    public static int minBuildHeight(Level level) {
        //? if >=1.17 {
        return level.getMinBuildHeight();
        //?} else {
        /*return 0;
        *///?}
    }

    public static int minSection(Level level) {
        //? if >=1.17 {
        return level.getMinSection();
        //?} else {
        /*return 0;
        *///?}
    }

    public static int renderDistance(Options options) {
        //? if >=1.17 {
        return options.getEffectiveRenderDistance();
        //?} else {
        /*return options.renderDistance;
        *///?}
    }
}
