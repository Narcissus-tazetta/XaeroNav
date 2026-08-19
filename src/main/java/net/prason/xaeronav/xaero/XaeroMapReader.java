package net.prason.xaeronav.xaero;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;
import net.prason.xaeronav.pathfinding.coarse.CoarseMapBuilder;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGrid;
import net.prason.xaeronav.pathfinding.corridor.SurfaceGridBuilder;
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

    /**
     * このセルを{@link CoarseMap#LAVA_MIXED}（通れるが高コスト）とみなすサンプル比率。
     * これを下回る量の溶岩は、層2・層3が1マス単位で避けられる前提で無視する。
     */
    private static final int LAVA_MIXED_NUMERATOR = 4;

    /**
     * 一度に投げる読み込み要求の上限。要求はXaeroの読み込みスレッドの単一キューに積まれるので、
     * 広い範囲を一気に頼むと地図表示そのものが待たされる。近い側から順に埋まれば
     * 長距離ルートは引けるので、足りなければ次の呼び出しで続きを頼む。
     */
    private static final int MAX_LOAD_REQUESTS = 64;

    /**
     * 1回の読み取りで見る洞窟レイヤーの数の上限。ネザーのように地表レイヤーが空の次元では、
     * 訪れたY帯の数だけレイヤーが増える。全部読むと範囲×レイヤー数だけメインスレッドが止まるので、
     * 参照Yに近い順に絞る。
     */
    private static final int MAX_CAVE_LAYERS = 4;

    /**
     * 1つの洞窟レイヤーが持つスライスの厚さ（ブロック）。Xaeroの{@code CAVE_MODE_DEPTH}既定値で、
     * {@code caveStart}からこのぶん下までしか記録されない。これより近い参照Yの差は
     * ほぼ同じ地形しか見えないので、再挑戦の候補として意味がない。
     */
    private static final int CAVE_MODE_DEPTH = 30;

    private XaeroMapReader() {
    }

    /**
     * この範囲を読むときに見るべきレイヤー。天井のある次元（ネザー）だけが洞窟レイヤーを使い、
     * それ以外は従来どおり地表レイヤー1つ。
     *
     * <p>ネザーでは頭上が必ず岩盤天井で塞がっているため、Xaeroの{@code CaveStartCalculator}が
     * 常に洞窟側に倒れる。データは{@code caveStart >> 4}というY帯ごとのレイヤーに分かれ、
     * 地表レイヤーには何も入らない。
     *
     * <p>判定に{@code hasSkyLight()}を使わないのはジ・エンドのため。エンドもスカイライトを持たないが、
     * 頭上が開けているので{@code CaveStartCalculator}は地表側を返す——つまりエンドのデータは
     * 地表レイヤーに入る。
     *
     * <p>レイヤー番号を{@code caveStart}の計算式から予測しないのは、{@code caveStart}が
     * プレイヤーの頭上の地形次第で決まるため。実際にメモリに載っているものだけを見る。
     */
    private static int[] layersFor(MapProcessor processor, int referenceY) {
        Level level = Minecraft.getInstance().level;
        if (level == null || !level.dimensionType().hasCeiling()) {
            return new int[] {SURFACE_LAYER};
        }
        List<Integer> caveLayers = new ArrayList<>(loadedLayers(processor));
        caveLayers.removeIf(layer -> layer == SURFACE_LAYER);
        if (caveLayers.isEmpty()) {
            return new int[] {SURFACE_LAYER};
        }
        caveLayers.sort(Comparator.comparingInt(layer -> Math.abs(layerCenterY(layer) - referenceY)));
        int count = Math.min(caveLayers.size(), MAX_CAVE_LAYERS);
        int[] layers = new int[count];
        for (int i = 0; i < count; i++) {
            layers[i] = caveLayers.get(i);
        }
        return layers;
    }

    /**
     * レイヤー番号が代表するY。{@code caveLayer == caveStart >> 4}の逆算だが、{@code caveStart}は
     * スライスの<b>上端</b>であって中心ではない（実際に記録されるのは
     * {@code [caveStart - CAVE_MODE_DEPTH, caveStart]}）。{@code caveLayer << 4}をそのまま使うと
     * 実際の中心よりCAVE_MODE_DEPTH/2ぶん高く見積もることになり、参照Yに近いレイヤーの選定が
     * 系統的に上へ偏る。
     */
    private static int layerCenterY(int caveLayer) {
        return caveLayer == SURFACE_LAYER || caveLayer == Integer.MIN_VALUE
                ? 0 : (caveLayer << 4) - CAVE_MODE_DEPTH / 2;
    }

    private static List<Integer> loadedLayers(MapProcessor processor) {
        List<Integer> layers = new ArrayList<>();
        processor.getMapWorld().getCurrentDimension().getLayeredMapRegions()
                .applyToEachLoadedLayer((layer, regions) -> layers.add(layer));
        return layers;
    }

    /**
     * 複数レイヤーを重ねて読むときに、セルごとにどのレイヤーの値を採用するかを決める。
     * 参照Yに最も近い高さが勝つ——ネザーでは同じ(x,z)が複数のY帯で記録されうるため。
     *
     * <p>採用元が隣り合うセルで食い違うと、そこは段差として現れる。粗い地図はもともと崖を
     * 起伏として扱うので破綻はしないが、レイヤーをまたぐ垂直移動を層1/2で表現はできない。
     */
    private static final class LayerMerge {

        private final int minX;
        private final int minZ;
        private final int sizeX;
        private final int sizeZ;
        private final int referenceY;
        private final int[] bestDistance;

        LayerMerge(int minX, int minZ, int sizeX, int sizeZ, int referenceY) {
            this.minX = minX;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.referenceY = referenceY;
            this.bestDistance = new int[sizeX * sizeZ];
            Arrays.fill(this.bestDistance, Integer.MAX_VALUE);
        }

        /** このセルに{@code height}を書くべきなら{@code true}を返し、勝った距離を記録する。 */
        boolean accept(int x, int z, int height) {
            int localX = x - minX;
            int localZ = z - minZ;
            if (localX < 0 || localX >= sizeX || localZ < 0 || localZ >= sizeZ) {
                return false;
            }
            int index = localZ * sizeX + localX;
            int distance = Math.abs(height - referenceY);
            if (distance >= bestDistance[index]) {
                return false;
            }
            bestDistance[index] = distance;
            return true;
        }
    }

    /**
     * 指定範囲の地表を読む。読めたセルが1つも無ければ{@link CoarseMap#knownCells()}が0になる
     * （その範囲が未訪問か、Xaeroがまだリージョンを読み込んでいない）。
     *
     * <p>{@link #layersFor}が選んだレイヤーはそれぞれ独立した床として{@link CoarseMap}へ積む
     * （1つに潰さない）。天井のある次元では、これで初めて上下に重なる複数の通路を層1が
     * 同時に見られる——潰していた頃は参照Yを変えて読み直す再挑戦（梯子）が必要だったが、
     * 1回の読み取りで全レイヤーぶんの床が揃うので不要になった。
     */
    public static CoarseMap readSurface(int minChunkX, int minChunkZ, int chunksX, int chunksZ, int referenceY) {
        CoarseMapBuilder builder = new CoarseMapBuilder(minChunkX, minChunkZ, chunksX, chunksZ);
        MapProcessor processor = processor();
        if (processor == null) {
            return builder.build();
        }
        for (int caveLayer : layersFor(processor, referenceY)) {
            readLayer(processor, caveLayer, minChunkX, minChunkZ, chunksX, chunksZ, builder);
        }
        return builder.build();
    }

    private static void readLayer(MapProcessor processor, int caveLayer,
                                   int minChunkX, int minChunkZ, int chunksX, int chunksZ,
                                   CoarseMapBuilder builder) {
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;

        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                readRegion(processor, caveLayer, regionX, regionZ, builder);
            }
        }
    }

    /**
     * 廊下（層1のwaypoint間の線分±マージン）をブロック解像度で読む。{@link #readSurface}と違い
     * 間引きをしない（256点/チャンク全部）ので、狭い範囲（廊下1本96×96ブロック程度）専用。
     */
    public static SurfaceGrid readSurfaceDetailed(int minBlockX, int minBlockZ, int sizeX, int sizeZ,
                                                   int referenceY) {
        SurfaceGridBuilder builder = new SurfaceGridBuilder(minBlockX, minBlockZ, sizeX, sizeZ);
        MapProcessor processor = processor();
        if (processor == null) {
            return builder.build();
        }

        int minChunkX = minBlockX >> 4;
        int maxChunkX = (minBlockX + sizeX - 1) >> 4;
        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = (minBlockZ + sizeZ - 1) >> 4;
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = maxChunkX >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = maxChunkZ >> CHUNKS_PER_REGION_SHIFT;

        LayerMerge merge = new LayerMerge(minBlockX, minBlockZ, sizeX, sizeZ, referenceY);
        for (int caveLayer : layersFor(processor, referenceY)) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    readRegionDetailed(processor, caveLayer, regionX, regionZ, builder, merge);
                }
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
    public static RegionStats surveyRegions(int minChunkX, int minChunkZ, int chunksX, int chunksZ,
                                             int referenceY) {
        MapProcessor processor = processor();
        if (processor == null) {
            return new RegionStats(0, 0, 0);
        }

        int inRange = 0;
        int loaded = 0;
        int pendingLoad = 0;
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;
        for (int caveLayer : layersFor(processor, referenceY)) {
            MapLayer layer = processor.getMapWorld().getCurrentDimension()
                    .getLayeredMapRegions().getLayer(caveLayer);
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    inRange++;
                    MapRegion region = processor.getLeafMapRegion(caveLayer, regionX, regionZ, false);
                    if (region != null && region.isLoaded()) {
                        loaded++;
                    }
                    if (layer != null && layer.getRegionDetection(regionX, regionZ) != null) {
                        pendingLoad++;
                    }
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
    public static int requestLoad(int minChunkX, int minChunkZ, int chunksX, int chunksZ, int referenceY) {
        MapProcessor processor = processor();
        if (processor == null) {
            return 0;
        }

        int requested = 0;
        int minRegionX = minChunkX >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionX = (minChunkX + chunksX - 1) >> CHUNKS_PER_REGION_SHIFT;
        int minRegionZ = minChunkZ >> CHUNKS_PER_REGION_SHIFT;
        int maxRegionZ = (minChunkZ + chunksZ - 1) >> CHUNKS_PER_REGION_SHIFT;
        // 上限はレイヤー横断で1つ。レイヤーごとに満額使うとXaeroの単一読み込みキューが溢れ、
        // 地図表示そのものが待たされる
        for (int caveLayer : layersFor(processor, referenceY)) {
            if (requested >= MAX_LOAD_REQUESTS) {
                break;
            }
            MapLayer layer = processor.getMapWorld().getCurrentDimension()
                    .getLayeredMapRegions().getLayer(caveLayer);
            if (layer == null) {
                continue;
            }
            for (int regionX = minRegionX; regionX <= maxRegionX && requested < MAX_LOAD_REQUESTS; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ && requested < MAX_LOAD_REQUESTS; regionZ++) {
                    // 検出情報が無い＝未訪問か、すでに読み込み済み。どちらも要求する意味が無い
                    // （要求すると空のリージョンをメモリに作るだけで終わる）
                    if (layer.getRegionDetection(regionX, regionZ) == null) {
                        continue;
                    }
                    // ここだけcreate=true。読み込みを頼むにはリージョンの器そのものが要る
                    MapRegion region = processor.getLeafMapRegion(caveLayer, regionX, regionZ, true);
                    if (region == null || region.isLoaded()) {
                        continue;
                    }
                    processor.getMapSaveLoad().requestLoad(region, "xaeronav");
                    requested++;
                }
            }
        }
        return requested;
    }

    /**
     * 1レイヤーが、ある範囲についてどれだけデータを持っているか。{@code caveLayer}が
     * {@link Integer#MAX_VALUE}なら地表レイヤー。
     */
    public record LayerProbe(int caveLayer, int knownCells, int minHeight, int maxHeight) {

        public boolean isSurface() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    /**
     * メモリに載っている全レイヤーを、選定・マージを通さずそのまま個別に読んで比べる診断用。
     * ネザーで「地表レイヤーが空で洞窟レイヤーに散っている」ことを実データで確かめるためのもの。
     */
    public static List<LayerProbe> probeLayers(int minChunkX, int minChunkZ, int chunksX, int chunksZ) {
        MapProcessor processor = processor();
        if (processor == null) {
            return List.of();
        }
        List<LayerProbe> probes = new ArrayList<>();
        for (int caveLayer : loadedLayers(processor)) {
            CoarseMapBuilder builder = new CoarseMapBuilder(minChunkX, minChunkZ, chunksX, chunksZ);
            // 1レイヤーだけを読むので、1セルに複数の床が積まれることはない（floor 0だけを見ればよい）
            readLayer(processor, caveLayer, minChunkX, minChunkZ, chunksX, chunksZ, builder);
            CoarseMap map = builder.build();

            int minHeight = Integer.MAX_VALUE;
            int maxHeight = Integer.MIN_VALUE;
            for (int chunkX = minChunkX; chunkX < minChunkX + chunksX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ < minChunkZ + chunksZ; chunkZ++) {
                    if (map.floorCount(chunkX, chunkZ) == 0) {
                        continue;
                    }
                    short height = map.heightAtFloor(chunkX, chunkZ, 0);
                    minHeight = Math.min(minHeight, height);
                    maxHeight = Math.max(maxHeight, height);
                }
            }
            probes.add(new LayerProbe(caveLayer, map.knownCells(),
                    minHeight == Integer.MAX_VALUE ? 0 : minHeight,
                    maxHeight == Integer.MIN_VALUE ? 0 : maxHeight));
        }
        probes.sort(Comparator.comparingInt(LayerProbe::caveLayer));
        return probes;
    }

    /** Xaeroの洞窟モード設定。0=無効（地表レイヤーへ）/ 1=Y帯ごとに分割 / 2=単一レイヤー。 */
    public static int caveModeType() {
        MapProcessor processor = processor();
        return processor == null ? -1 : processor.getMapWorld().getCurrentDimension().getCaveModeType();
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

    private static void readRegion(MapProcessor processor, int caveLayer, int regionX, int regionZ,
                                    CoarseMapBuilder builder) {
        // create=falseなので、Xaeroがまだ読み込んでいないリージョンはnullで返る。
        // ここでディスクから読ませないのは、読み込みが非同期で完了を待てないため
        MapRegion region = processor.getLeafMapRegion(caveLayer, regionX, regionZ, false);
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

    /**
     * 1タイル＝1チャンク。タイル自身がチャンク座標を持っているので、外側の添字から復元する必要はない。
     *
     * <p>{@link #readSurface}が複数レイヤーを読むとき、ここは呼ばれるたびに床を1つ
     * {@link CoarseMapBuilder#putFloor}で積む。レイヤーを1つの高さへ潰さない——潰すと
     * 天井のある次元で上下に重なる独立した通路が、片方だけ生き残ったり不当な段差として
     * 繋がって見えたりする。
     */
    private static void readTile(MapTile tile, CoarseMapBuilder builder) {
        int waterSamples = 0;
        int lavaSamples = 0;
        int heightSum = 0;
        int heightSamples = 0;
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        // 溶岩面の高さの平均。samplesが全部溶岩だった稀なケース（下記）だけで使う
        int lavaHeightSum = 0;
        int samples = 0;

        for (int x = 0; x < 16; x += SAMPLE_STEP) {
            for (int z = 0; z < 16; z += SAMPLE_STEP) {
                MapBlock block = tile.getBlock(x, z);
                if (block == null || isEmpty(block)) {
                    continue;
                }
                samples++;
                boolean water = isWater(block);
                boolean lava = !water && isLava(block);
                // 水面の高さを使うのは、粗いルートが見るのが「そこを通れるか」だから。
                // 水底の高さで段差を測ると、深い海が巨大な崖として現れて経路が歪む
                int sampleHeight = water ? block.getTopHeight() : block.getHeight();
                if (water) {
                    waterSamples++;
                } else if (lava) {
                    lavaSamples++;
                    lavaHeightSum += sampleHeight;
                    // 溶岩面は代表高さ（＝waypointのY）から除く。溶岩は立てないので、
                    // その高さを混ぜるとwaypointが溶岩の海の水面に落ち、層2の
                    // resolveStandableが到達できなくなる
                    continue;
                }
                heightSum += sampleHeight;
                heightSamples++;
                minHeight = Math.min(minHeight, sampleHeight);
                maxHeight = Math.max(maxHeight, sampleHeight);
            }
        }

        if (samples == 0) {
            return;
        }
        // 溶岩は水と同じく「過半数」で通行不能とし、それ未満は通れるが高いセルに落とす。
        // 以前はこの1/4の量で通行不能にしていたが、溶岩が地形の一部であるネザーでは
        // 既知セルの58%が壁になり、出発点すら通行不能になっていた
        byte kind;
        if (lavaSamples * 2 >= samples) {
            kind = CoarseMap.LAVA;
        } else if (lavaSamples * LAVA_MIXED_NUMERATOR >= samples) {
            kind = CoarseMap.LAVA_MIXED;
        } else if (waterSamples * 2 >= samples) {
            kind = CoarseMap.WATER;
        } else {
            kind = CoarseMap.LAND;
        }
        // heightSamples==0はサンプル全部が溶岩のときだけ（＝kindは必ずLAVA）。ここは溶岩面の高さで
        // 正しい——LavaPolicy.BRIDGEでこのセルを渡るとき、足場を置くのがまさにその高さになる
        int averageHeight = heightSamples > 0 ? heightSum / heightSamples : lavaHeightSum / lavaSamples;
        int representativeMin = heightSamples > 0 ? minHeight : averageHeight;
        int representativeMax = heightSamples > 0 ? maxHeight : averageHeight;
        builder.putFloor(tile.getChunkX(), tile.getChunkZ(), kind, averageHeight, representativeMin,
                representativeMax);
    }

    private static void readRegionDetailed(MapProcessor processor, int caveLayer, int regionX, int regionZ,
                                            SurfaceGridBuilder builder, LayerMerge merge) {
        // create=falseなので、Xaeroがまだ読み込んでいないリージョンはnullで返る（readRegionと同じ理由）
        MapRegion region = processor.getLeafMapRegion(caveLayer, regionX, regionZ, false);
        if (region == null || !region.isLoaded()) {
            return;
        }
        for (int tileChunkX = 0; tileChunkX < TILE_CHUNKS_PER_REGION; tileChunkX++) {
            for (int tileChunkZ = 0; tileChunkZ < TILE_CHUNKS_PER_REGION; tileChunkZ++) {
                MapTileChunk tileChunk = region.getChunk(tileChunkX, tileChunkZ);
                if (tileChunk == null) {
                    continue;
                }
                readTileChunkDetailed(tileChunk, builder, merge);
            }
        }
    }

    private static void readTileChunkDetailed(MapTileChunk tileChunk, SurfaceGridBuilder builder,
                                               LayerMerge merge) {
        for (int tileX = 0; tileX < TILES_PER_TILE_CHUNK; tileX++) {
            for (int tileZ = 0; tileZ < TILES_PER_TILE_CHUNK; tileZ++) {
                MapTile tile = tileChunk.getTile(tileX, tileZ);
                if (tile == null || !tile.isLoaded()) {
                    continue;
                }
                readTileDetailed(tile, builder, merge);
            }
        }
    }

    /**
     * 1タイル分をブロック解像度（256点）で読む。{@link #readTile}と違い間引かない — 廊下限定の
     * 狭い範囲でしか呼ばないので、ここで数百チャンク分を舐める心配は無い。
     */
    private static void readTileDetailed(MapTile tile, SurfaceGridBuilder builder, LayerMerge merge) {
        int blockX = tile.getChunkX() * 16;
        int blockZ = tile.getChunkZ() * 16;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                MapBlock block = tile.getBlock(x, z);
                if (block == null || isEmpty(block)) {
                    continue;
                }
                boolean lava = isLava(block);
                boolean water = !lava && isWater(block);
                // 採用の判定は通れる高さで行う。水は水面、それ以外は地表そのもの
                int mergeHeight = water ? block.getTopHeight() : block.getHeight();
                if (!merge.accept(blockX + x, blockZ + z, mergeHeight)) {
                    continue;
                }
                if (lava) {
                    builder.put(blockX + x, blockZ + z, CoarseMap.LAVA, block.getHeight());
                } else if (water) {
                    // 水底と水面の両方が読めるので、層1（水面のみ）には無い水深を持たせられる
                    builder.put(blockX + x, blockZ + z, CoarseMap.WATER, block.getHeight(), block.getTopHeight());
                } else {
                    builder.put(blockX + x, blockZ + z, CoarseMap.LAND, block.getHeight());
                }
            }
        }
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

    /**
     * この列にこのレイヤーのデータが無いか。
     *
     * <p>Xaeroは走査範囲に不透明ブロックを1つも見つけられなかった列を、空気かつ高さ
     * {@code worldBottomY}（ネザーなら0）として書く（{@code MapWriter#loadPixel}）。洞窟レイヤーは
     * {@code caveStart}から{@code CAVE_MODE_DEPTH}ブロック下までしか見ないので、これが普通に起きる。
     *
     * <p>そのまま読むと奈落の底に地面があることになり、waypointが{@code y=1}へ落ちる。地表レイヤーは
     * 常に下まで走査して必ず何かに当たるので、空気は「データが無い」の印として使える。
     */
    private static boolean isEmpty(MapBlock block) {
        BlockState state = block.getState();
        return state == null || state.isAir();
    }

    private static boolean isLava(MapBlock block) {
        BlockState state = block.getState();
        return state != null && state.getFluidState().is(FluidTags.LAVA);
    }
}
