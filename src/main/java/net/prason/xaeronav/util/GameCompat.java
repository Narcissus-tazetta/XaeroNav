package net.prason.xaeronav.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
//? if >=1.17 {
import net.minecraft.world.level.LevelHeightAccessor;
//?}
//? if >=1.21.11 {
/*import net.minecraft.world.attribute.EnvironmentAttributes;
*///?}
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

    // 高さはどれも「下端を含み、上端を含まない」旧来の意味で返す（1.21.2以降のgetMaxY()は上端を含む）
    //? if >=1.17 {
    public static int minBuildHeight(LevelHeightAccessor level) {
        //? if >=1.21.11 {
        /*return level.getMinY();
        *///?} else {
        return level.getMinBuildHeight();
        //?}
    }

    public static int maxBuildHeight(LevelHeightAccessor level) {
        //? if >=1.21.11 {
        /*return level.getMaxY() + 1;
        *///?} else {
        return level.getMaxBuildHeight();
        //?}
    }

    public static int minSection(LevelHeightAccessor level) {
        //? if >=1.21.11 {
        /*return level.getMinSectionY();
        *///?} else {
        return level.getMinSection();
        //?}
    }
    //?} else {
    /*public static int minBuildHeight(Level level) {
        return 0;
    }

    public static int maxBuildHeight(Level level) {
        return level.getMaxBuildHeight();
    }

    public static int minSection(Level level) {
        return 0;
    }
    *///?}

    /** 水を置いても蒸発する次元か（ネザー）。 */
    public static boolean waterEvaporates(Level level) {
        //? if >=1.21.11 {
        /*return level.environmentAttributes().getDimensionValue(EnvironmentAttributes.WATER_EVAPORATES);
        *///?} else {
        return level.dimensionType().ultraWarm();
        //?}
    }

    public static BlockPos containing(Vec3 pos) {
        //? if >=1.17 {
        return BlockPos.containing(pos);
        //?} else {
        /*return new BlockPos(pos);
        *///?}
    }
}
