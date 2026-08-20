package net.prason.xaeronav.pathfinding.world;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.cost.DigCost;

/**
 * 探索範囲のブロックを読むためのビュー（design doc §4-5）。
 *
 * <p>ブロックデータ自体は<b>コピーしない</b>。{@link #capture}がメインスレッドで集めるのは
 * 「読み込み済みチャンクへの参照」だけで、実際の{@link BlockState}はワーカースレッドが
 * チャンクから直接読む。{@code BlockState}は不変のグローバルシングルトンなので、
 * 参照さえ固定してしまえばワーカースレッドから読んでも安全になる。
 *
 * <p>形状・硬度の問い合わせ（{@code getCollisionShape}/{@code isFaceSturdy}/{@code getDestroySpeed}）は
 * {@code BlockState}側のキャッシュを読むだけでlevelを参照しないため、これもワーカースレッドから呼べる。
 * 唯一の例外が{@code Block#hasDynamicShape()}がtrueのブロックで、これらは形状の解決に実際のlevelを
 * 要求するため、安全側に倒して「進入も設置もできない障害物」として扱う。
 *
 * <p><b>スレッド契約:</b> {@link #capture}はメインスレッドから呼ぶこと。生成後のインスタンスは
 * 単一のワーカースレッドが占有する（直前チャンクとセルのキャッシュを可変フィールドに持つため、
 * 複数スレッドで共有してはならない）。
 */
public final class ChunkView implements CellSource {

    // セルキャッシュの「未計算」を表す番兵。上位32bitは掘削tick数のfloatビット列で、
    // 全ビットが立つ＝NaNになる値は生成されないため、この値と衝突しない。
    private static final long NOT_CACHED = -1L;

    /** 探索1回でセルは数万〜数十万件になる。伸ばしながら作るとその途中で毎回全件の詰め直しが起きる。 */
    private static final int CELL_CACHE_CAPACITY = 1 << 15;

    /**
     * 落下ダメージを許容する場合に受け入れる上限（ダメージ点＝0.5ハート単位）を体力から求める割合。
     * 満タン(20)なら6点＝3ハート＝9マスの落下まで許すことになる。
     */
    private static final float FALL_DAMAGE_HEALTH_FRACTION = 3.0f;

    private final Long2ObjectMap<LevelChunk> chunks;
    private final int totalChunksInBounds;
    private final SearchBounds bounds;
    /** メインスレッドで複製したホットバー。掘削コスト計算をワーカースレッドで行うために必要。 */
    private final ItemStack[] hotbar;
    /** ホットバー各スロットの効率強化レベル。エンチャントの解決にはレジストリが要るのでメインスレッドで取る。 */
    private final int[] hotbarEfficiency;
    private final boolean diggingEnabled;
    private final boolean canPlaceBlocks;
    private final boolean jumpGapEnabled;
    private final boolean lavaBridgingEnabled;
    private final int maxBridgeRunBlocks;
    private final int maxSubmergedRunBlocks;
    private final int maxFallDamagePoints;
    private final boolean canMlgWaterBucket;
    private final boolean boatAvailable;
    private final boolean ridingBoat;
    private final double minDescentTicksPerBlock;
    private final int minBuildHeight;
    private final int maxBuildHeight;
    private final int minSection;

    private final Long2LongOpenHashMap cells = new Long2LongOpenHashMap(CELL_CACHE_CAPACITY, 0.75f);

    /**
     * ブロック状態ごとの判定結果。{@link BlockState}は不変のグローバルシングルトンなので、
     * 同じ状態のセルは座標が違っても結果が同じになる。洞窟1つに実際に現れる状態は数十種類しかないのに、
     * 当たり判定の解決とホットバー全スロットの採掘速度比較はセルごとに走っていた。
     */
    private final Reference2LongOpenHashMap<BlockState> states = new Reference2LongOpenHashMap<>();

    // 経路探索のブロック参照は同一チャンク内に強く局在するので、直前のチャンクを覚えておくだけで
    // 大半のアクセスでハッシュ表引きを省略できる。null（未ロード）もそのまま覚えて再引きを防ぐ。
    private LevelChunk cachedChunk;
    private long cachedChunkKey = ChunkPos.INVALID_CHUNK_POS;

    private ChunkView(Long2ObjectMap<LevelChunk> chunks, int totalChunksInBounds, SearchBounds bounds,
                      ItemStack[] hotbar, int[] hotbarEfficiency, boolean diggingEnabled, boolean canPlaceBlocks,
                      boolean jumpGapEnabled, boolean lavaBridgingEnabled, int maxBridgeRunBlocks,
                      int maxSubmergedRunBlocks, int maxFallDamagePoints,
                      boolean canMlgWaterBucket, boolean boatAvailable, boolean ridingBoat,
                      double minDescentTicksPerBlock, int minBuildHeight,
                      int maxBuildHeight, int minSection) {
        this.chunks = chunks;
        this.totalChunksInBounds = totalChunksInBounds;
        this.bounds = bounds;
        this.hotbar = hotbar;
        this.hotbarEfficiency = hotbarEfficiency;
        this.diggingEnabled = diggingEnabled;
        this.canPlaceBlocks = canPlaceBlocks;
        this.jumpGapEnabled = jumpGapEnabled;
        this.lavaBridgingEnabled = lavaBridgingEnabled;
        this.maxBridgeRunBlocks = maxBridgeRunBlocks;
        this.maxSubmergedRunBlocks = maxSubmergedRunBlocks;
        this.maxFallDamagePoints = maxFallDamagePoints;
        this.canMlgWaterBucket = canMlgWaterBucket;
        this.boatAvailable = boatAvailable;
        this.ridingBoat = ridingBoat;
        this.minDescentTicksPerBlock = minDescentTicksPerBlock;
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = maxBuildHeight;
        this.minSection = minSection;
        this.cells.defaultReturnValue(NOT_CACHED);
        // 実在するブロック状態はPRESENTが必ず立つので、0（＝ABSENT）を未計算の番兵に使える
        this.states.defaultReturnValue(CellData.ABSENT);
    }

    /**
     * ボートで水面を渡る移動を提示してよいか。持ち物にあるか、<b>いま乗っているか</b>のどちらか。
     *
     * <p>乗っている間はボートがアイテムではなくエンティティになるので、持ち物だけを見ると
     * 「岸でボートを出せ」と案内した直後、その通りにした瞬間に前提が消えて経路が組み替わる。
     */
    public static boolean boatAvailable(Player player) {
        return ridingBoat(player)
                || player.getInventory().contains(stack -> stack.getItem() instanceof BoatItem);
    }

    /** いまボートに乗っているか。 */
    public static boolean ridingBoat(Player player) {
        return player.getVehicle() instanceof Boat;
    }

    /** メインスレッド専用。読み込み済みチャンクへの参照とホットバーの複製だけを集める。 */
    public static ChunkView capture(Level level, Player player, SearchBounds bounds, boolean diggingEnabled,
                                     boolean bridgingEnabled, boolean jumpGapEnabled,
                                     boolean lavaBridgingEnabled, int maxBridgeRunBlocks,
                                     int maxSubmergedRunBlocks, boolean fallDamageToleranceEnabled) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;

        Long2ObjectOpenHashMap<LevelChunk> chunks =
                new Long2ObjectOpenHashMap<>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // 未ロードのチャンクはそもそも読めない。ここで拾わないことで、経路は自然に
                // 読み込み済み範囲の縁で打ち切られる（進入不可のセルとして扱われるため）。
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk != null) {
                    chunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
                }
            }
        }

        Holder<Enchantment> efficiency = level.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.EFFICIENCY);
        ItemStack[] hotbar = new ItemStack[Inventory.getSelectionSize()];
        int[] hotbarEfficiency = new int[hotbar.length];
        boolean canPlaceBlocks = false;
        for (int slot = 0; slot < hotbar.length; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            hotbar[slot] = stack.copy();
            hotbarEfficiency[slot] = stack.getEnchantmentLevel(efficiency);
            canPlaceBlocks |= isBuildingBlock(stack);
        }

        int maxFallDamagePoints = fallDamageToleranceEnabled
                ? (int) (player.getHealth() / FALL_DAMAGE_HEALTH_FRACTION) : 0;
        // ultraWarmな次元（ネザー）は水を置いても即座に蒸発するので、着地寸前に水バケツを置く
        // MLGは物理的に実行できない。次元を見ずに許可すると、実行不可能な落下を経路に載せてしまう
        boolean canMlgWaterBucket = fallDamageToleranceEnabled && !level.dimensionType().ultraWarm()
                && player.getInventory().contains(stack -> stack.is(Items.WATER_BUCKET));
        boolean boatAvailable = boatAvailable(player);
        boolean ridingBoat = ridingBoat(player);

        // 下降のヒューリスティックの下限は、実際に生成されうる最大の落差で決まる。
        // FALL_TO_WATERは着水先に水があるときだけ生成され、ultraWarmな次元（ネザー）には水が
        // 存在しない（置いても蒸発する——BucketItemがそう書いてある）。水も水バケツMLGも無く、
        // 落下ダメージも許容しないなら、落ちられるのは安全高さまでで打ち止めになる
        boolean deepFallPossible = !level.dimensionType().ultraWarm() || canMlgWaterBucket;
        int maxDrop = ActionCosts.SAFE_FALL_BLOCKS + maxFallDamagePoints;
        // fallCost(d)/d はdについて単調減少（終端速度に漸近する）ので、生成されうる最大の落差での
        // 値が下限になる。深い落下がありうるなら終端速度の下限まで緩める以外にない。
        // 梯子（LADDER_DOWN_ONE_BLOCK）はこれを上回るが、下限として取り違えないよう明示的に比べる
        double minDescentTicksPerBlock = deepFallPossible
                ? ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK
                : Math.min(ActionCosts.fallCost(maxDrop) / maxDrop, ActionCosts.LADDER_DOWN_ONE_BLOCK);

        int totalChunksInBounds = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        return new ChunkView(chunks, totalChunksInBounds, bounds, hotbar, hotbarEfficiency, diggingEnabled,
                bridgingEnabled && canPlaceBlocks, jumpGapEnabled, lavaBridgingEnabled, maxBridgeRunBlocks,
                maxSubmergedRunBlocks, maxFallDamagePoints, canMlgWaterBucket, boatAvailable, ridingBoat,
                minDescentTicksPerBlock, level.getMinBuildHeight(),
                level.getMaxBuildHeight(), level.getMinSection());
    }

    public int loadedChunksInBounds() {
        return chunks.size();
    }

    public int totalChunksInBounds() {
        return totalChunksInBounds;
    }

    /**
     * 同じチャンク参照を使い、掘削だけを禁じたビュー。{@link #capture}をもう一度呼ばずに済ませるための派生。
     *
     * <p>セルの判定結果は掘削の可否で変わるので、キャッシュは共有せず作り直す。チャンク参照とホットバーは
     * 読むだけなので共有してよい。派生元と派生先を別スレッドで同時に使ってはならない（{@link CellSource}の
     * スレッド契約は据え置き）。
     */
    public ChunkView withoutDigging() {
        return new ChunkView(chunks, totalChunksInBounds, bounds, hotbar, hotbarEfficiency, false, canPlaceBlocks,
                jumpGapEnabled, lavaBridgingEnabled, maxBridgeRunBlocks, maxSubmergedRunBlocks,
                maxFallDamagePoints, canMlgWaterBucket, boatAvailable, ridingBoat,
                minDescentTicksPerBlock, minBuildHeight, maxBuildHeight, minSection);
    }

    /**
     * 空洞を渡る足場として置けるか。上に立てる（{@code standable}）ことに加えて、置いた先が空中でも
     * 留まることを求める — 砂・砂利は置いた瞬間に落ちるので足場にならない。
     */
    private static boolean isBuildingBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        long flags = CellData.flagsOf(blockItem.getBlock().defaultBlockState());
        return CellData.standable(flags) && !CellData.fallingBlock(flags) && !CellData.unresolvedShape(flags);
    }

    @Override
    public boolean canPlaceBlocks() {
        return canPlaceBlocks;
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return lavaBridgingEnabled;
    }

    @Override
    public int maxBridgeRunBlocks() {
        return maxBridgeRunBlocks;
    }

    @Override
    public int maxSubmergedRunBlocks() {
        return maxSubmergedRunBlocks;
    }

    @Override
    public boolean jumpGapEnabled() {
        return jumpGapEnabled;
    }

    @Override
    public int maxFallDamagePoints() {
        return maxFallDamagePoints;
    }

    @Override
    public double minDescentTicksPerBlock() {
        return minDescentTicksPerBlock;
    }

    @Override
    public boolean canMlgWaterBucket() {
        return canMlgWaterBucket;
    }

    @Override
    public boolean boatAvailable() {
        return boatAvailable;
    }

    @Override
    public boolean ridingBoat() {
        return ridingBoat;
    }

    /** 初回アクセス時に計算してキャッシュする。 */
    @Override
    public long cell(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        long cached = cells.get(key);
        if (cached != NOT_CACHED) {
            return cached;
        }
        long computed = computeCell(x, y, z);
        cells.put(key, computed);
        return computed;
    }

    @Override
    public boolean isInBounds(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    /**
     * {@code MOTION_BLOCKING}ハイトマップの1つ上。{@code canSeeSky}（スカイライト15）は
     * ライトエンジンを引くのでワーカースレッドからは触れないが、ハイトマップはチャンクが持つ
     * ビット列を読むだけなので、ブロック状態と同じ条件でここから読める。
     *
     * <p>{@code MOTION_BLOCKING}はクライアントへ配信されるハイトマップなので、
     * 読み込み済みチャンクなら必ず埋まっている（未生成なら{@code getHeight}側が組み直してしまうが、
     * そこへ至るのはサーバー側のチャンクだけ）。
     */
    @Override
    public int openSkyY(int x, int z) {
        LevelChunk chunk = chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return Integer.MAX_VALUE;
        }
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
    }

    @Override
    public SearchBounds bounds() {
        return bounds;
    }

    private long computeCell(int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return CellData.ABSENT;
        }
        BlockState state = blockStateAt(x, y, z);
        if (state == null) {
            return CellData.ABSENT;
        }
        long cached = states.getLong(state);
        if (cached != CellData.ABSENT) {
            return cached;
        }
        long computed = computeState(state);
        states.put(state, computed);
        return computed;
    }

    private long computeState(BlockState state) {
        long flags = CellData.flagsOf(state);
        double digTicks;
        if (CellData.occupiableWithoutDigging(flags)) {
            digTicks = 0.0;
        } else if (CellData.lava(flags) || CellData.hazard(flags) || CellData.unresolvedShape(flags)
                || !diggingEnabled) {
            // 液体は掘削対象ではないので、進入不可を素手のdigTicksとして表現する。
            // 危険セル（炎・パウダースノー・ポータル等）も掘って通す対象にはしない。
            // diggingEnabled=falseの場合も同様に「掘って進入」という選択肢自体を消す。
            digTicks = ActionCosts.INFEASIBLE;
        } else {
            // 落下ブロック連鎖のコストはここでは足さない(AStarPathfinder側が必須セル群の最上部から
            // 一度だけスキャンする。ここで各セル個別に連鎖加算すると隣接する必須セル同士で二重計上になる)。
            digTicks = DigCost.compute(hotbar, hotbarEfficiency, state);
        }
        return CellData.withDigTicks(flags, digTicks);
    }

    private BlockState blockStateAt(int x, int y, int z) {
        if (y < minBuildHeight || y >= maxBuildHeight) {
            return null;
        }
        LevelChunk chunk = chunkAt(x >> 4, z >> 4);
        if (chunk == null) {
            return null;
        }
        return chunk.getSections()[(y >> 4) - minSection].getBlockState(x & 15, y & 15, z & 15);
    }

    private LevelChunk chunkAt(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (key == cachedChunkKey) {
            return cachedChunk;
        }
        LevelChunk chunk = chunks.get(key);
        cachedChunkKey = key;
        cachedChunk = chunk;
        return chunk;
    }
}
