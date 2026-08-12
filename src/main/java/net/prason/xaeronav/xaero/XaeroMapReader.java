package net.prason.xaeronav.xaero;

import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseMapBuilder;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapBlock;
import xaero.map.region.MapLayer;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;
import xaero.map.region.Overlay;

/**
 * Xaeroの世界地図が保存している地形から{@link CoarseMap}を作る。
 *
 * <p>これが読むのは「Xaeroの地図に描かれたことのある範囲」であって、いま読み込まれているチャンクではない。
 * だから読み込み済みチャンクの遥か外側——数千ブロック先の海や山——の地形が分かる。逆に、一度も
 * 訪れていない場所のデータは存在しない。
 *
 * <p><b>スレッド契約:</b> メインスレッドから呼ぶこと。{@code getLeafMapRegion}は生成を伴う場合に
 * メインスレッドであることを自分で検査し、違反すると{@link IllegalAccessError}を投げる。ここでは
 * 生成を要求しない（{@code create=false}）が、Xaeroの書き込みスレッドと同じ構造を触るため、
 * 呼び出し自体をメインスレッドに寄せておく。
 *
 * <p>呼ぶ前に{@link XaeroPresence#mapPresent()}を確認すること。Xaero未導入の環境では
 * このクラスのロード自体が失敗する。
 */
public final class XaeroMapReader {

    /**
     * 地表のレイヤー番号。洞窟レイヤーは「頭上の天井のY」を16で割った値が番号になるのに対し、
     * 地表だけはこの番兵で表される（Xaeroはこの値のときだけ{@code caves/}以下を使わない）。
     */
    private static final int SURFACE_LAYER = Integer.MAX_VALUE;

    /** 1リージョン＝32×32チャンク（512ブロック四方）。 */
    private static final int CHUNKS_PER_REGION_SHIFT = 5;

    /** 1タイル束＝4×4タイル。リージョンは8×8のタイル束を持つ。 */
    private static final int TILE_CHUNKS_PER_REGION = 8;
    private static final int TILES_PER_TILE_CHUNK = 4;

    /**
     * 1チャンク（＝1タイル、16×16ブロック）から何点を見るか。256点すべてを見ると、
     * 数百チャンク四方を集めるだけでメインスレッドが数百ミリ秒止まる。海か陸かの判定に
     * 必要なのは「そのチャンクの大まかな性格」なので、格子状に間引いて足りる。
     */
    private static final int SAMPLE_STEP = 4;
    private static final int SAMPLES_PER_TILE = (16 / SAMPLE_STEP) * (16 / SAMPLE_STEP);

    /** このセルを溶岩とみなすサンプル比率。少しでも溶岩があれば避けたいので低めに取る。 */
    private static final int LAVA_SAMPLE_THRESHOLD = SAMPLES_PER_TILE / 4;

    /**
     * 一度に投げる読み込み要求の上限。要求はXaeroの読み込みスレッドの単一キューに積まれるので、
     * 広い範囲を一気に頼むと地図表示そのものが待たされる。近い側から順に埋まれば
     * 長距離ルートは引けるので、足りなければ次の呼び出しで続きを頼む。
     */
    private static final int MAX_LOAD_REQUESTS = 64;

    private XaeroMapReader() {
    }

    /**
     * 指定範囲の地表を読む。読めたセルが1つも無ければ{@link CoarseMap#knownCells()}が0になる
     * （その範囲が未訪問か、Xaeroがまだリージョンを読み込んでいない）。
     */
    public static CoarseMap readSurface(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        CoarseMapBuilder builder = new CoarseMapBuilder(minChunkX, minChunkZ, chunksX, chunksZ);
        MapProcessor processor = processor();
        if (processor == null) {
            return builder.build();
        }

        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;

        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                readRegion(processor, regionX, regionZ, builder);
            }
        }
        return builder.build();
    }

    /**
     * 範囲内のリージョンの状態。読めたセルが少ないとき、原因は2つに割れる。
     * 訪れてはいるがまだメモリに無い（{@code pendingLoad}）のか、そもそも訪れていない
     * （{@code loaded}にも{@code pendingLoad}にも数えられない）のか。前者なら
     * {@link #requestLoad}で埋まるが、後者には打つ手が無い。
     *
     * <p>{@code pendingLoad}が「ディスクにある数」ではないことに注意。Xaeroはリージョンを
     * 読み込み終えると、その検出情報を捨てる（{@code MapLayer.removeRegionDetection}）。
     * つまりこれは「ディスクにあって、まだ読み込んでいないもの」の数で、読み込みが進むほど減る。
     */
    public record RegionStats(int inRange, int loaded, int pendingLoad) {
    }

    /** {@link #readSurface}と同じ範囲について、リージョンの読み込み状況だけを数える。 */
    public static RegionStats surveyRegions(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        MapProcessor processor = processor();
        if (processor == null) {
            return new RegionStats(0, 0, 0);
        }
        MapLayer layer = processor.getMapWorld().getCurrentDimension()
                .getLayeredMapRegions().getLayer(SURFACE_LAYER);

        int inRange = 0;
        int loaded = 0;
        int pendingLoad = 0;
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                inRange++;
                MapRegion region = processor.getLeafMapRegion(SURFACE_LAYER, regionX, regionZ, false);
                if (region != null && region.isLoaded()) {
                    loaded++;
                }
                if (layer != null && layer.getRegionDetection(regionX, regionZ) != null) {
                    pendingLoad++;
                }
            }
        }
        return new RegionStats(inRange, loaded, pendingLoad);
    }

    /**
     * 範囲内の「ディスクにあるのにまだ読み込まれていない」リージョンに読み込みを要求する。
     * 実際に読み込まれるのは非同期なので、戻り値が0になるまで（あるいは諦めるまで）
     * 呼び出し側が待つ必要がある。要求できた数を返す。
     *
     * <p>Xaeroは地図を表示するために必要になった範囲しかメモリに載せない。起動直後や、
     * 世界地図を一度も開いていない状態では、訪問済みの土地でも1つも読み込まれていない。
     */
    public static int requestLoad(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        MapProcessor processor = processor();
        if (processor == null) {
            return 0;
        }
        MapLayer layer = processor.getMapWorld().getCurrentDimension()
                .getLayeredMapRegions().getLayer(SURFACE_LAYER);
        if (layer == null) {
            return 0;
        }

        int requested = 0;
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;
        for (int regionX = minRegionX; regionX <= maxRegionX && requested < MAX_LOAD_REQUESTS; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ && requested < MAX_LOAD_REQUESTS; regionZ++) {
                // 検出情報が無い＝未訪問か、すでに読み込み済み。どちらも要求する意味が無い
                // （要求すると空のリージョンをメモリに作るだけで終わる）
                if (layer.getRegionDetection(regionX, regionZ) == null) {
                    continue;
                }
                // ここだけcreate=true。読み込みを頼むにはリージョンの器そのものが要る
                MapRegion region = processor.getLeafMapRegion(SURFACE_LAYER, regionX, regionZ, true);
                if (region == null || region.isLoaded()) {
                    continue;
                }
                processor.getMapSaveLoad().requestLoad(region, "xaeronav");
                requested++;
            }
        }
        return requested;
    }

    private static MapProcessor processor() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("XaeroMapReaderはメインスレッドから呼ぶこと");
        }
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable()) {
            return null;
        }
        MapProcessor processor = session.getMapProcessor();
        // 地図のワールドが確定するまでリージョンの座標系そのものが定まらない
        return processor != null && processor.isMapWorldUsable() ? processor : null;
    }

    private static void readRegion(MapProcessor processor, int regionX, int regionZ, CoarseMapBuilder builder) {
        // create=falseなので、Xaeroがまだ読み込んでいないリージョンはnullで返る。
        // ここでディスクから読ませないのは、読み込みが非同期で完了を待てないため
        MapRegion region = processor.getLeafMapRegion(SURFACE_LAYER, regionX, regionZ, false);
        if (region == null || !region.isLoaded()) {
            return;
        }
        for (int tileChunkX = 0; tileChunkX < TILE_CHUNKS_PER_REGION; tileChunkX++) {
            for (int tileChunkZ = 0; tileChunkZ < TILE_CHUNKS_PER_REGION; tileChunkZ++) {
                MapTileChunk tileChunk = region.getChunk(tileChunkX, tileChunkZ);
                if (tileChunk == null) {
                    continue;
                }
                readTileChunk(tileChunk, builder);
            }
        }
    }

    private static void readTileChunk(MapTileChunk tileChunk, CoarseMapBuilder builder) {
        for (int tileX = 0; tileX < TILES_PER_TILE_CHUNK; tileX++) {
            for (int tileZ = 0; tileZ < TILES_PER_TILE_CHUNK; tileZ++) {
                MapTile tile = tileChunk.getTile(tileX, tileZ);
                if (tile == null || !tile.isLoaded()) {
                    continue;
                }
                readTile(tile, builder);
            }
        }
    }

    /** 1タイル＝1チャンク。タイル自身がチャンク座標を持っているので、外側の添字から復元する必要はない。 */
    private static void readTile(MapTile tile, CoarseMapBuilder builder) {
        int waterSamples = 0;
        int lavaSamples = 0;
        int heightSum = 0;
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        int samples = 0;

        for (int x = 0; x < 16; x += SAMPLE_STEP) {
            for (int z = 0; z < 16; z += SAMPLE_STEP) {
                MapBlock block = tile.getBlock(x, z);
                if (block == null) {
                    continue;
                }
                samples++;
                boolean water = isWater(block);
                if (water) {
                    waterSamples++;
                } else if (isLava(block)) {
                    lavaSamples++;
                }
                // 水面の高さを使うのは、粗いルートが見るのが「そこを通れるか」だから。
                // 水底の高さで段差を測ると、深い海が巨大な崖として現れて経路が歪む
                int sampleHeight = water ? block.getTopHeight() : block.getHeight();
                heightSum += sampleHeight;
                minHeight = Math.min(minHeight, sampleHeight);
                maxHeight = Math.max(maxHeight, sampleHeight);
            }
        }

        if (samples == 0) {
            return;
        }
        byte kind;
        if (lavaSamples >= LAVA_SAMPLE_THRESHOLD) {
            kind = CoarseMap.LAVA;
        } else if (waterSamples * 2 >= samples) {
            kind = CoarseMap.WATER;
        } else {
            kind = CoarseMap.LAND;
        }
        builder.put(tile.getChunkX(), tile.getChunkZ(), kind, heightSum / samples, minHeight, maxHeight);
    }

    /**
     * 水は地表のブロックとしてではなくオーバーレイとして記録される。海底の砂が{@code state}に入り、
     * その上に水のオーバーレイが乗る形なので、{@code state}だけを見ると海が砂浜に見える。
     */
    private static boolean isWater(MapBlock block) {
        ArrayList<Overlay> overlays = block.getOverlays();
        if (overlays != null) {
            for (Overlay overlay : overlays) {
                if (overlay.isWater()) {
                    return true;
                }
            }
        }
        BlockState state = block.getState();
        return state != null && state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isLava(MapBlock block) {
        BlockState state = block.getState();
        return state != null && state.getFluidState().is(FluidTags.LAVA);
    }
}
