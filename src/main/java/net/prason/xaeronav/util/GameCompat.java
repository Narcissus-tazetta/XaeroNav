package net.prason.xaeronav.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Minecraft 1.16と新しい版とで呼び方だけが違うvanilla API。クライアント専用のものは{@code client.ClientCompat}にある。 */
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

    public static BlockPos containing(Vec3 pos) {
        //? if >=1.17 {
        return BlockPos.containing(pos);
        //?} else {
        /*return new BlockPos(pos);
        *///?}
    }
}
