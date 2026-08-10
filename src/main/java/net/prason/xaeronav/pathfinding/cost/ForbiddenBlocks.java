package net.prason.xaeronav.pathfinding.cost;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * デフォルトで掘削禁止扱いにするブロック（design doc §3-3）。
 * 設定（{@code XaeroNavConfig#additionalForbiddenBlocks}）から{@link #reloadFromConfig}で追加分を反映する。
 */
public final class ForbiddenBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<Block> DEFAULTS = Set.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.SPAWNER, Blocks.CRAFTING_TABLE
    );

    private static final Set<Block> extra = new HashSet<>();

    private ForbiddenBlocks() {
    }

    public static boolean isForbidden(BlockState state) {
        Block block = state.getBlock();
        return DEFAULTS.contains(block) || extra.contains(block) || state.is(BlockTags.LOGS);
    }

    public static void addForbidden(Block block) {
        extra.add(block);
    }

    public static void removeForbidden(Block block) {
        extra.remove(block);
    }

    /** 設定ファイルの{@code additionalForbiddenBlocks}（例: "minecraft:chest"）を反映する。 */
    public static void reloadFromConfig(Collection<? extends String> ids) {
        extra.clear();
        for (String id : ids) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
                LOGGER.warn("XaeroNav config: 未知のブロックIDを無視しました: {}", id);
                continue;
            }
            extra.add(BuiltInRegistries.BLOCK.get(location));
        }
    }
}
