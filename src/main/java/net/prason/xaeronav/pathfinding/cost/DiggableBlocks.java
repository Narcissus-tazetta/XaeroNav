package net.prason.xaeronav.pathfinding.cost;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 掘って通ってよいブロックの定義（design doc §3-3）。
 *
 * <p><b>「掘ってはいけないもの」ではなく「掘ってよいもの」を数える。</b>知らないブロック——modが
 * 足した機械・別の追加mod・このバージョンにまだ無いバニラブロック——が既定で掘ってよい側に落ちると、
 * 案内はプレイヤーの持ち物や建築物を壊す指示になる。逆に落ちたときの損は「そこを避けて遠回りする」
 * だけで、経路が消えてもXaeroの地図が読める範囲では迂回路が見つかる。非対称なので許可制を採る。
 *
 * <p>許可するのは<b>自然生成の地形</b>だけ。加工されたブロック（丸石・石レンガ・板材・ネザーレンガ・
 * 深層岩レンガ…）は誰かが置いたものなので、要塞でも古代都市でも自分の家でも掘らせない。この線引きは
 * 副産物として、虫食い石（シルバーフィッシュ）・怪しい砂利（考古学）・スポナーのような「壊すと
 * 事故になる自然物」も自動的に外す——どれも素の石や砂とは別のブロックだから。
 *
 * <p>設定（{@code XaeroNavConfig#additionalDiggableBlocks} /
 * {@code additionalForbiddenBlocks}）から{@link #reloadFromConfig}で両側の追加分を反映する。
 */
public final class DiggableBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 自然地形を指すバニラのタグ。個別のブロック名を並べるより、modが追加した石・土がそのまま
     * 乗ってくるぶん堅牢になる（modの世界生成用ブロックは、洞窟が生成されるように
     * {@code #*_carver_replaceables}へ入れるのが通例）。
     */
    private static final List<TagKey<Block>> TERRAIN_TAGS = List.of(
            // 洞窟の掘削が置き換えてよいブロック＝そのまま「掘って通ってよい地形」。石・土・砂・
            // テラコッタ・鉄/銅鉱石・砂利・砂岩・方解石・雪・氷塊、ネザー側はナイリウムとソウルサンド類
            BlockTags.OVERWORLD_CARVER_REPLACEABLES,
            BlockTags.NETHER_CARVER_REPLACEABLES,
            // 上の2つが拾わない粘土・鍾乳石・エンドストーン・滑らかな玄武岩を足す
            BlockTags.SCULK_REPLACEABLE,
            BlockTags.LEAVES,
            BlockTags.WART_BLOCKS,
            BlockTags.SNOW,
            BlockTags.ICE,
            BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
            BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES
    );

    /** タグに入っていない自然地形。 */
    private static final Set<Block> TERRAIN_BLOCKS = Set.of(
            Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS, Blocks.GILDED_BLACKSTONE,
            Blocks.GLOWSTONE, Blocks.SHROOMLIGHT, Blocks.MAGMA_BLOCK,
            Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POINTED_DRIPSTONE, Blocks.AMETHYST_BLOCK,
            Blocks.SCULK, Blocks.SCULK_VEIN,
            // カサと幹の非対称は地上の木と同じ——葉に当たるカサは掘れて、原木（#logs）は壁のまま
            Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
            Blocks.MELON, Blocks.PUMPKIN,
            // 竹林・ツツジ・コーラスプラントは当たり判定を持つので、掘れないと林がそのまま壁になる
            Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
            Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER,
            Blocks.MANGROVE_ROOTS, Blocks.DIRT_PATH, Blocks.FARMLAND
    );

    // 掘削コスト計算はワーカースレッドから走るため、更新は必ず新しいSetへの差し替えで行う
    // （その場で変更するとイテレーション中の探索スレッドと競合する）。
    private static volatile Set<Block> allowed = Set.of();
    private static volatile Set<Block> forbidden = Set.of();

    private DiggableBlocks() {
    }

    public static boolean isDiggable(BlockState state) {
        Block block = state.getBlock();
        if (forbidden.contains(block)) {
            return false;
        }
        if (allowed.contains(block)) {
            return true;
        }
        // 中身を持つブロックは、壊せばその中身が失われる。チェスト・かまど・スポナーを個別に並べる
        // 代わりにここで一括で外すことで、modが足した機械もまとめて対象外になる。タグ側にも
        // 混ざりうる（#sandは怪しい砂を含む）ので、タグ判定より先に置く
        if (state.hasBlockEntity()) {
            return false;
        }
        if (TERRAIN_BLOCKS.contains(block)) {
            return true;
        }
        for (TagKey<Block> tag : TERRAIN_TAGS) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /** 設定ファイルの2つのブロックIDリスト（例: "minecraft:cobblestone"）を反映する。 */
    public static synchronized void reloadFromConfig(Collection<? extends String> diggableIds,
                                                      Collection<? extends String> forbiddenIds) {
        allowed = resolve(diggableIds);
        forbidden = resolve(forbiddenIds);
    }

    private static Set<Block> resolve(Collection<? extends String> ids) {
        Set<Block> blocks = new HashSet<>();
        for (String id : ids) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
                LOGGER.warn("XaeroNav config: 未知のブロックIDを無視しました: {}", id);
                continue;
            }
            blocks.add(BuiltInRegistries.BLOCK.get(location));
        }
        return Set.copyOf(blocks);
    }
}
