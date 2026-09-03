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
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;
import net.prason.xaeronav.pathfinding.cost.DigCost;

/**
 * 探索範囲のブロックを読むためのビュー。
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
 * 複数スレッドで共有してはならない）。<b>探索を並列に走らせるときは{@link #forParallelSearch}で
 * ビューを分ける</b>——共有すると壊れ方が例外とは限らず、別のチャンクのブロックを読んだまま
 * 経路が出る。
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
    private final MovementOptions options;
    private final boolean canPlaceBlocks;
    private final int placedBlockBudget;
    private final int maxFallDamagePoints;
    private final int fatalFallBlocks;
    private final boolean canMlgWaterBucket;
    private final boolean boatAvailable;
    private final boolean ridingBoat;
    private final double minDescentTicksPerBlock;

    /**
     * 落差に上限が無い落下がありうるか（水へ落ちる、または水バケツMLG）。落下ダメージの許容量を
     * 変えたときに下降の下限を引き直すのに要る（{@link #minDescentTicksPerBlock(int)}）。
     */
    private final boolean deepFallPossible;
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
                      ItemStack[] hotbar, int[] hotbarEfficiency, MovementOptions options, boolean canPlaceBlocks,
                      int placedBlockBudget, int maxFallDamagePoints, int fatalFallBlocks,
                      boolean canMlgWaterBucket, boolean boatAvailable, boolean ridingBoat,
                      boolean deepFallPossible, double minDescentTicksPerBlock, int minBuildHeight,
                      int maxBuildHeight, int minSection) {
        this.deepFallPossible = deepFallPossible;
        this.chunks = chunks;
        this.totalChunksInBounds = totalChunksInBounds;
        this.bounds = bounds;
        this.hotbar = hotbar;
        this.hotbarEfficiency = hotbarEfficiency;
        this.options = options;
        this.canPlaceBlocks = canPlaceBlocks;
        this.placedBlockBudget = placedBlockBudget;
        this.maxFallDamagePoints = maxFallDamagePoints;
        this.fatalFallBlocks = fatalFallBlocks;
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
    public static ChunkView capture(Level level, Player player, SearchBounds bounds, MovementOptions options) {
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
        for (int slot = 0; slot < hotbar.length; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            hotbar[slot] = stack.copy();
            // NeoForgeが足す ItemStack#getEnchantmentLevel は使わない。この階層はローダーに
            // 依存しない決まりで、他のMODがエンチャント値を動的に書き換える場合まで拾う必要も無い
            hotbarEfficiency[slot] = EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack);
        }
        // 置ける枚数は持ち物<b>全体</b>で数える。ホットバーだけを見ていた頃は、インベントリに
        // 1スタック持っていても橋の案内が出ず、逆にホットバーの1個だけで64マスの橋が出ていた。
        // 採掘の道具（hotbar）をホットバーに限るのとは要求が違う——道具は持ち替えないと使えないが、
        // 足場は置く前にホットバーへ移せる
        int placeableBlocks = countPlaceableBlocks(player);

        int maxFallDamagePoints = options.fallDamageToleranceEnabled()
                ? (int) (player.getHealth() / FALL_DAMAGE_HEALTH_FRACTION) : 0;
        // バニラの落下ダメージは ceil(落下距離 - SAFE_FALL_BLOCKS) 点（0.5ハート単位）で、
        // それが体力以上なら死ぬ。落差は整数マスなので ceil は体力側に掛ければ足りる。
        // 落下ダメージの許容設定とは無関係に求める——あちらは「意図して降りてよい高さ」、
        // こちらは「跳んで外したときに死ぬか」で、跳躍は設定に関わらず生成されるため
        int fatalFallBlocks = ActionCosts.SAFE_FALL_BLOCKS + (int) Math.ceil(player.getHealth());
        // ultraWarmな次元（ネザー）は水を置いても即座に蒸発するので、着地寸前に水バケツを置く
        // MLGは物理的に実行できない。次元を見ずに許可すると、実行不可能な落下を経路に載せてしまう
        boolean canMlgWaterBucket = options.fallDamageToleranceEnabled() && !level.dimensionType().ultraWarm()
                && player.getInventory().contains(stack -> stack.is(Items.WATER_BUCKET));
        boolean boatAvailable = boatAvailable(player);
        boolean ridingBoat = ridingBoat(player);

        // 下降のヒューリスティックの下限は、実際に生成されうる最大の落差で決まる。
        // FALL_TO_WATERは着水先に水があるときだけ生成され、ultraWarmな次元（ネザー）には水が
        // 存在しない（置いても蒸発する——BucketItemがそう書いてある）。水も水バケツMLGも無く、
        // 落下ダメージも許容しないなら、落ちられるのは安全高さまでで打ち止めになる
        boolean deepFallPossible = !level.dimensionType().ultraWarm() || canMlgWaterBucket;
        double minDescentTicksPerBlock = descentBound(deepFallPossible, maxFallDamagePoints);

        // クリエイティブは置いても減らないので予算を掛けない（0＝無制限）。設定でも切れる。
        //
        // 下限が1なのは0が「無制限」を意味するから——予備の設定が手持ちを上回っても、そこで0にすると
        // 制限が丸ごと外れて逆に緩くなる。1個だけ使える状態に倒しておけば、足りない経路は緩和の
        // 梯子（予算を外す段）が受ける。canPlaceBlocksの方に予備を織り込まないのも同じ理由で、
        // 詰むくらいなら予備を使ってよい
        boolean creative = player.getAbilities().instabuild;
        int placedBlockBudget = options.blockBudgetEnabled() && !creative
                ? Math.max(1, placeableBlocks - options.blockBudgetReserve())
                : 0;
        // クリエイティブは持ち物が空でも置ける（インベントリから好きなブロックを取れる）。
        // 手持ちだけを見ていた頃は、ブロックを持たずにジ・エンドへ来ると橋が1本も生成されず、
        // 島渡りの経路が原理的に出なかった——しかも案内には何も出ないので、
        // 「島渡りだけできない」としか見えない
        boolean canPlaceBlocks = options.bridgingEnabled() && (placeableBlocks > 0 || creative);

        int totalChunksInBounds = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        return new ChunkView(chunks, totalChunksInBounds, bounds, hotbar, hotbarEfficiency, options,
                canPlaceBlocks, placedBlockBudget,
                maxFallDamagePoints, fatalFallBlocks, canMlgWaterBucket, boatAvailable, ridingBoat,
                deepFallPossible, minDescentTicksPerBlock, level.getMinBuildHeight(),
                level.getMaxBuildHeight(), level.getMinSection());
    }

    /**
     * 持ち物にある足場に使えるブロックの総数。ホットバーに限らず<b>全スロット</b>を見る——
     * 置く前にホットバーへ移せるので、あるのに数えないと予算が実態より厳しくなる。
     *
     * <p>HUDが不足を知らせるのにも使う。<b>探索時の値を覚えておくのではなく、その場で数え直す</b>
     * ——経路は目的地をキーにキャッシュされるので、途中でブロックを使っても拾っても引き直されない
     * （ボート・ロケットと同じ既知の罠）。
     */
    public static int countPlaceableBlocks(Player player) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isBuildingBlock(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public int loadedChunksInBounds() {
        return chunks.size();
    }

    public int totalChunksInBounds() {
        return totalChunksInBounds;
    }

    /**
     * 同じチャンク参照を使い、キャッシュだけを持ち直した独立のビュー。<b>2つの探索を同時に走らせる
     * ときは、必ず片方にこれを渡すこと</b>（{@code PathfindingExecutor#submitWithDeepFallback}）。
     *
     * <p>共有してよいのは{@code chunks}だけ——{@link #capture}が組み終えた後は読むだけなので、
     * 複数スレッドから引いても壊れない。逆に{@code cells}・{@code states}・チャンクのメモは
     * どれも探索中に書き換わるので、共有すると{@code Long2LongOpenHashMap}が内部で壊れて
     * {@code ArrayIndexOutOfBoundsException}になるか、キーと値がねじれて<b>別のチャンクの
     * ブロックを読む</b>（メモは{@code cachedChunkKey}と{@code cachedChunk}を別々に書くため）。
     *
     * <p>ホットバーを複製するのは、{@link ItemStack}が採掘速度の解決で内部に遅延キャッシュを
     * 持ちうるため。9スロットぶんなので実質ただ。
     */
    public ChunkView forParallelSearch() {
        ItemStack[] copiedHotbar = new ItemStack[hotbar.length];
        for (int slot = 0; slot < hotbar.length; slot++) {
            copiedHotbar[slot] = hotbar[slot].copy();
        }
        return new ChunkView(chunks, totalChunksInBounds, bounds, copiedHotbar, hotbarEfficiency.clone(),
                options, canPlaceBlocks, placedBlockBudget, maxFallDamagePoints, fatalFallBlocks,
                canMlgWaterBucket, boatAvailable, ridingBoat, deepFallPossible, minDescentTicksPerBlock,
                minBuildHeight, maxBuildHeight, minSection);
    }

    /**
     * 同じチャンク参照を使い、掘削だけを禁じたビュー。{@link #capture}をもう一度呼ばずに済ませるための派生。
     *
     * <p>セルの判定結果は掘削の可否で変わるので、キャッシュは共有せず作り直す。チャンク参照とホットバーは
     * 読むだけなので共有してよい。派生元と派生先を別スレッドで同時に使ってはならない（{@link CellSource}の
     * スレッド契約は据え置き）。
     */
    public ChunkView withoutDigging() {
        return new ChunkView(chunks, totalChunksInBounds, bounds, hotbar, hotbarEfficiency,
                options.withoutDigging(), canPlaceBlocks, placedBlockBudget, maxFallDamagePoints, fatalFallBlocks,
                canMlgWaterBucket, boatAvailable, ridingBoat, deepFallPossible, minDescentTicksPerBlock,
                minBuildHeight, maxBuildHeight, minSection);
    }

    /**
     * 落下ダメージの許容量から下降1ブロックあたりの下限を出す。落差に上限が無いなら終端速度の
     * 下限まで緩める以外にない。
     */
    private static double descentBound(boolean deepFallPossible, int maxFallDamagePoints) {
        return deepFallPossible ? ActionCosts.FALL_ASYMPTOTIC_MIN_PER_BLOCK
                : ActionCosts.descentBoundForMaxDrop(ActionCosts.SAFE_FALL_BLOCKS + maxFallDamagePoints);
    }

    @Override
    public double minDescentTicksPerBlock(int maxFallDamagePoints) {
        return descentBound(deepFallPossible, maxFallDamagePoints);
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
    public boolean bridgingAllowedBySettings() {
        return options.bridgingEnabled();
    }

    @Override
    public int placedBlockBudget() {
        return placedBlockBudget;
    }

    @Override
    public boolean lavaBridgingEnabled() {
        return options.lavaBridgingEnabled();
    }

    @Override
    public int maxBridgeRunBlocks() {
        return options.maxBridgeRunBlocks();
    }

    @Override
    public int maxLavaBridgeRunBlocks() {
        return options.maxLavaBridgeRunBlocks();
    }

    @Override
    public int maxVoidBridgeRunBlocks() {
        return options.maxVoidBridgeRunBlocks();
    }

    @Override
    public int maxSubmergedTicks() {
        return options.maxSubmergedTicks();
    }

    @Override
    public boolean jumpGapEnabled() {
        return options.jumpGapEnabled();
    }

    @Override
    public int maxFallDamagePoints() {
        return maxFallDamagePoints;
    }

    @Override
    public int fatalFallBlocks() {
        return fatalFallBlocks;
    }

    @Override
    public boolean avoidRiskyJumps() {
        return options.avoidRiskyJumps();
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
                || !options.diggingEnabled()) {
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
