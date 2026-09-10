package net.prason.xaeronav.pathfinding.coarse;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.xaero.XaeroMapReader;

/**
 * フィクスチャの地形から、<b>Xaeroが保存しているであろう床</b>を{@link VoxelTerrain}へ流し込む。
 *
 * <p>本番の{@code XaeroMapReader#forEachCaveFloor}と同じ形（1本の柱について、洞窟レイヤーごとに
 * 「いちばん上の固体ブロックのY」を1つずつ）で{@link VoxelTerrain#markFloor}を呼ぶ。実データが
 * 持つのは<b>床のYだけ</b>で、その上下が岩か空洞かは分からない——完全な3次元地形から作った
 * 理想のガイドを本番の保証値にしない、という約束を守るための道具。
 *
 * <p>{@link #fill}の{@code keepFraction}は<b>未訪問のチャンク</b>を落とす。Xaeroの地図は
 * 歩いた所しか埋まらないので、ガイドの成否は「目的地の周りが欠けていても壊れないか」で決まる。
 */
public final class XaeroMapModel {

    /**
     * 1本の柱から拾う床の数の上限。Xaeroの洞窟レイヤーは{@code CAVE_MODE_DEPTH}(30)ブロックの
     * スライスに分かれ、レイヤー番号は{@code caveStart >> 4}なので、ネザーの高さ(0..127)では
     * 最大8枚になる。
     *
     * <p><b>8枚そろうのは「その柱を全部の高さ帯で歩いた」ときだけ</b>で、実際の保存はずっと薄い。
     * 実機ログ（2026-09-09）のレイヤー別内訳は{@code L4=3408}で他は全部0——<b>1枚だけ</b>だった。
     * その薄さで測るには{@link #fill(VoxelTerrain, CellSource, int[], double, long)}を使う。
     */
    private static final int MAX_LAYERS = 8;

    /** Xaeroの{@code CAVE_MODE_DEPTH}既定値。1枚のレイヤーはこの厚さのスライスしか持たない。 */
    private static final int CAVE_MODE_DEPTH = 30;

    /** 本番の{@code XaeroMapReader#SAMPLE_STEP}と同じ間引き。 */
    private static final int SAMPLE_STEP = 2;

    private XaeroMapModel() {
    }

    /**
     * 箱のYを<b>渡された範囲そのまま</b>にした箱。ネザーの実際の高さ(0..127)を渡す限りは
     * 本番と同じ形になる（本番は床のある範囲＋余白で、天井の無い空きを含まない）。
     *
     * <p>次元の高さがネザーより高い環境まで含めて本番と同じ形を測るなら
     * {@link #grid(CellSource, BlockPos, BlockPos, LevelHeightAccessor, int[], double, long)}を使う。
     */
    public static SearchBounds guideBox(BlockPos start, BlockPos goal, int minY, int maxY) {
        return new SearchBounds(
                Math.min(start.getX(), goal.getX()) - VoxelTerrain.MARGIN_BLOCKS, minY,
                Math.min(start.getZ(), goal.getZ()) - VoxelTerrain.MARGIN_BLOCKS,
                Math.max(start.getX(), goal.getX()) + VoxelTerrain.MARGIN_BLOCKS, maxY,
                Math.max(start.getZ(), goal.getZ()) + VoxelTerrain.MARGIN_BLOCKS);
    }

    /** 次元の高さだけを持つ{@link LevelHeightAccessor}。本番の{@code boxFor}をそのまま通すため。 */
    public static LevelHeightAccessor height(int minY, int maxY) {
        return LevelHeightAccessor.create(minY, maxY - minY + 1);
    }

    /**
     * <b>本番（{@code NetherVoxelGuide#start}）と同じ2段構え</b>で格子を組む。1回目で床のあるYを
     * 測り、{@link VoxelTerrain#boxFor}に箱を決めさせてから床を流す。
     *
     * <p>次元の高さが歩ける高さより広い環境（＝実機で起きていた形）を測れるのはここだけ。
     *
     * @param caveLayers 洞窟レイヤーの番号。{@code null}なら全高から最大8枚拾う濃いモデル
     */
    public static VoxelTerrain grid(CellSource all, BlockPos start, BlockPos goal,
                                     LevelHeightAccessor level, int[] caveLayers,
                                     double keepFraction, long seed) {
        SearchBounds full = guideBox(start, goal, level.getMinBuildHeight(),
                level.getMaxBuildHeight() - 1);
        int[] range = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        scan(all, full, caveLayers, keepFraction, seed, (x, z, floorTopY, lava) -> {
            range[0] = Math.min(range[0], floorTopY);
            range[1] = Math.max(range[1], floorTopY);
        });
        if (range[0] > range[1]) {
            return null;
        }
        VoxelTerrain terrain = VoxelTerrain.of(
                VoxelTerrain.boxFor(level, start, goal, range[0], range[1]), true);
        if (terrain != null) {
            scan(all, full, caveLayers, keepFraction, seed, terrain::markFloor);
        }
        return terrain;
    }

    /**
     * {@link #guideBox}の地形へ{@link #fill}を流し、本番と同じ{@link VoxelCostToGo}を作る。
     *
     * <p>目的地は<b>寄せ直していない生の座標</b>。本番（{@code NetherVoxelGuide}）も同じで、
     * ガイドを組む時点では目的地のチャンクが読み込まれておらず{@code StanceFinder}を通せない。
     * 数ブロックのずれは{@code VoxelCostToGo}の起点探しが吸収する。
     */
    public static VoxelCostToGo guide(CellSource all, BlockPos start, BlockPos goal, int minY, int maxY,
                                       double keepFraction, long seed) {
        VoxelTerrain terrain = VoxelTerrain.of(guideBox(start, goal, minY, maxY), true);
        fill(terrain, all, keepFraction, seed);
        return VoxelCostToGo.build(terrain, goal, () -> false);
    }

    /** 全チャンクが訪問済みとしたときの床。 */
    public static void fill(VoxelTerrain terrain, CellSource all) {
        fill(terrain, all, 1.0, 0L);
    }

    /**
     * チャンクの{@code keepFraction}だけが訪問済みだったときの床。落としたチャンクからは
     * 床を1つも報告しない（＝Xaeroがそのリージョンをまだ持っていない）。
     */
    public static void fill(VoxelTerrain terrain, CellSource all, double keepFraction, long seed) {
        scan(all, terrain.box(), null, keepFraction, seed, terrain::markFloor);
    }

    /**
     * <b>プレイヤーが特定の高さ帯しか歩いていないときの床。</b>洞窟レイヤー{@code L}が持つのは
     * {@code [L*16 - CAVE_MODE_DEPTH, L*16]}のスライスにある<b>いちばん上の床1枚だけ</b>で、
     * 歩いていない高さ帯のレイヤーはタイルごと存在しない。
     *
     * <p>{@link #fill(VoxelTerrain, CellSource, double, long)}が全高から最大8枚拾うのに対し、
     * こちらは実機の保存と同じ薄さになる。<b>ここで測らないと実機の破綻が回帰で捕まらない</b>
     * ——8枚拾うモデルでは、ガイドの膨らみが実機の5.84倍に対し2.57倍にしかならず、
     * 探索が詰まる条件そのものが再現しない。
     */
    public static void fill(VoxelTerrain terrain, CellSource all, int[] caveLayers,
                             double keepFraction, long seed) {
        scan(all, terrain.box(), caveLayers, keepFraction, seed, terrain::markFloor);
    }

    /**
     * {@code box}のXZ範囲を柱ごとに走査して床を報告する。{@code caveLayers}が{@code null}なら
     * 全高から最大{@link #MAX_LAYERS}枚、そうでなければレイヤーごとにスライス1枚ずつ。
     */
    private static void scan(CellSource all, SearchBounds box, int[] caveLayers,
                              double keepFraction, long seed, XaeroMapReader.FloorVisitor visitor) {
        SearchBounds world = all.bounds();
        Random random = new Random(seed);
        Map<Long, Boolean> visited = new HashMap<>();
        for (int x = Math.max(box.minX(), world.minX()); x <= Math.min(box.maxX(), world.maxX());
                x += SAMPLE_STEP) {
            for (int z = Math.max(box.minZ(), world.minZ()); z <= Math.min(box.maxZ(), world.maxZ());
                    z += SAMPLE_STEP) {
                long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
                if (!visited.computeIfAbsent(chunk, key -> random.nextDouble() < keepFraction)) {
                    continue;
                }
                if (caveLayers == null) {
                    scanColumn(visitor, all, box, world, x, z);
                    continue;
                }
                for (int layer : caveLayers) {
                    scanSlice(visitor, all, box, world, x, z, layer);
                }
            }
        }
    }

    /** 1枚のレイヤーが持つスライスを上から辿り、最初に見つけた床だけを報告する。 */
    private static void scanSlice(XaeroMapReader.FloorVisitor visitor, CellSource all,
                                   SearchBounds box, SearchBounds world, int x, int z, int caveLayer) {
        boolean airSeen = false;
        int top = Math.min(caveLayer * 16, Math.min(world.maxY(), box.maxY()));
        int bottom = Math.max(caveLayer * 16 - CAVE_MODE_DEPTH, Math.max(world.minY(), box.minY()));
        for (int y = top; y >= bottom; y--) {
            long cell = all.cell(x, y, z);
            if (!CellData.present(cell)) {
                return;
            }
            if (CellData.passableEmpty(cell)) {
                airSeen = true;
                continue;
            }
            if (airSeen) {
                visitor.floor(x, z, y, CellData.lava(cell));
                return;
            }
        }
    }

    /**
     * 1本の柱を上から辿り、空気の下にある固体の面を床として報告する。
     * {@code LiveCoarseSampler#sampleColumnFloors}と同じ規則。
     */
    private static void scanColumn(XaeroMapReader.FloorVisitor visitor, CellSource all,
                                    SearchBounds box, SearchBounds world, int x, int z) {
        boolean airSeen = false;
        int found = 0;
        int top = Math.min(world.maxY(), box.maxY());
        int bottom = Math.max(world.minY(), box.minY());
        for (int y = top; y >= bottom && found < MAX_LAYERS; y--) {
            long cell = all.cell(x, y, z);
            if (!CellData.present(cell)) {
                return;
            }
            if (CellData.passableEmpty(cell)) {
                airSeen = true;
                continue;
            }
            if (airSeen) {
                visitor.floor(x, z, y, CellData.lava(cell));
                found++;
                airSeen = false;
            }
        }
    }
}
