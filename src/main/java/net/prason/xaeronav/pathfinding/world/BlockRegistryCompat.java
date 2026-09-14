package net.prason.xaeronav.pathfinding.world;

import net.minecraft.core.registries.BuiltInRegistries;
//? if forge && <1.21 {
/*import net.minecraftforge.registries.ForgeRegistries;
*///?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * ブロックレジストリの読み書き。1.20.1-forgeだけ{@code BuiltInRegistries.BLOCK}がdeprecated
 * （Forge独自の{@code ForgeRegistries.BLOCKS}への誘導）で、他ノードはdeprecatedではないので
 * 素のまま使う——このローダー分岐を複数箇所へ書き写さないための共通口。
 */
public final class BlockRegistryCompat {

    private BlockRegistryCompat() {
    }

    public static ResourceLocation keyOf(Block block) {
        //? if forge && <1.21 {
        /*return ForgeRegistries.BLOCKS.getKey(block);
        *///?} else {
        return BuiltInRegistries.BLOCK.getKey(block);
        //?}
    }

    /** 未知のIDには{@code null}を返す。 */
    public static Block byId(ResourceLocation id) {
        //? if forge && <1.21 {
        /*return ForgeRegistries.BLOCKS.containsKey(id) ? ForgeRegistries.BLOCKS.getValue(id) : null;
        *///?} else {
        return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : null;
        //?}
    }
}
