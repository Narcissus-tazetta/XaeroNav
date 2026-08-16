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
            Blocks.SPAWNER, Blocks.CRAFTING_TABLE,
            // レッドストーンでしか開かない＝手で通り抜けられないが、壊して通る経路を出すものでもない
            Blocks.IRON_DOOR, Blocks.IRON_TRAPDOOR
    );

    // 掘削コスト計算はワーカースレッドから走るため、更新は必ず新しいSetへの差し替えで行う
    // （その場で変更するとイテレーション中の探索スレッドと競合する）。
    private static volatile Set<Block> extra = Set.of();

    private ForbiddenBlocks() {
    }

    public static boolean isForbidden(BlockState state) {
        Block block = state.getBlock();
        // BlockTags.LOGSは地上の原木だけでなくネザーのcrimson/warped stem（きのこの幹）も含む。
        // 「木を切らせない」意図は地上専用で、ネザーの3D迷路ではこの幹が壁一枚を占めることが
        // 珍しくないため、そこだけ掘削禁止から除外する
        return DEFAULTS.contains(block) || extra.contains(block)
                || (state.is(BlockTags.LOGS) && !state.is(BlockTags.CRIMSON_STEMS) && !state.is(BlockTags.WARPED_STEMS));
    }

    /** 設定ファイルの{@code additionalForbiddenBlocks}（例: "minecraft:chest"）を反映する。 */
    public static synchronized void reloadFromConfig(Collection<? extends String> ids) {
        Set<Block> updated = new HashSet<>();
        for (String id : ids) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
                LOGGER.warn("XaeroNav config: 未知のブロックIDを無視しました: {}", id);
                continue;
            }
            updated.add(BuiltInRegistries.BLOCK.get(location));
        }
        extra = Set.copyOf(updated);
    }
}
