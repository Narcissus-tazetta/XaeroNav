package net.prason.xaeronav.pathfinding.coarse;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * 経路全体を覆う<b>3次元</b>の粗い地形。{@link VoxelCostToGo}のためだけに存在する。
 *
 * <p>層1（{@link CoarseMap}）と役割は同じ「遠くの地形をざっくり知る」だが、あちらは1セルが
 * チャンク1本＝2.5次元で、天井のある次元では<b>何の情報も与えない</b>。実測（ユーザーの停止ルート
 * {@code (-328,64,696)→(-259,64,379)}）では、層1のcost-to-goを掛けた探索は始点から1歩も動けず、
 * ガイド無しで全世界が見えていても300万ノードで未到達だった。同じルートを、ここが作る3次元の
 * 格子から作ったガイドで解くと到達する。ネザーの縦に積まれたトンネルは、柱ごとに床を数枚持つ
 * 表現では表せない。
 *
 * <p><b>正確である必要はない。</b>床の位置だけを知り、それ以外を一律「空洞」と見なすモデルでも
 * 到達した。Xaeroの洞窟レイヤーが提供できるのはまさに「柱ごと・スライスごとの床Y」なので、
 * この粒度で足りることが設計の前提になっている。
 *
 * <p>セルの種別は3つで、<b>既定は{@link #OPEN}＝「床は知らない」</b>。{@link #OPEN}を壁にすると、
 * 地図の無い領域にある迂回路ごと消えて、ガイドが「行けない」としか言わなくなる。値段の付け方は
 * {@link VoxelCostToGo}側で、実費のまま使う（縮める・比を落とすは測って外してある）。
 *
 * <p><b>この層の急所は{@link #boxFor}のY</b>。次元の全高で取ると、次元が中身より高い環境で
 * 岩盤天井より上の空きが格子の半分を占め、ガイドが「天井の上を橋で走る」道を描く。
 *
 * <p>データ源は<b>Xaeroの洞窟レイヤーだけ</b>。読み込み済みチャンクの実データを重ねても
 * <b>実測では経路が1手も変わらなかった</b>（ユーザーの停止ルートで4.3万セルを壁に塗っても
 * 結果が同一）。そのために探索用の{@code CellSource}を数百万回読む価値は無い。
 *
 * <p>生成後は{@link #markFloor}で埋め、{@link VoxelCostToGo#build}へ渡したら以後変更しないこと
 * （ガイドはこの配列を読み続ける）。
 */
public final class VoxelTerrain {

    // 値の大小がそのまま上書きの優先順位。1つのセルには複数の柱・複数のレイヤーが入るので、
    // 書いた順で結果が変わってはいけない
    /** 立てる床が無い（空洞・未知）。渡るには橋を架ける。 */
    public static final byte OPEN = 0;

    /** 溶岩の面。立てないうえ、橋を架けられる長さも空中より短い。 */
    public static final byte LAVA = 1;

    /** 立てる床がある。走って通れる。 */
    public static final byte STANDABLE = 2;

    /** 格子の一辺の既定（ブロック）。実測でこの粒度なら経路全体を覆っても1秒台で組める。 */
    public static final int DEFAULT_CELL_BLOCKS = 4;

    /**
     * 格子の総数の上限。超えるぶんはセルを粗くして吸収する（{@link #cellBlocksFor}）。
     *
     * <p>目的地が遠いほど箱が広がるので、ここが無いと確保量も構築時間も距離の2乗で伸びる。
     * 実測では50万セル（543ブロック四方・ネザー全高）で約1.2秒・約5MB。
     */
    private static final int MAX_CELLS = 500_000;

    /** 粗くしていく順。ここを使い切ってもなお超えるなら、その箱はそもそも扱わない。 */
    private static final int[] CELL_LADDER = {DEFAULT_CELL_BLOCKS, 6, 8, 12, 16, 24, 32};

    /**
     * 始点・目的地の周りへ広げる幅（ブロック）。
     *
     * <p>両端を結ぶ帯だけでは足りない——ネザーの正しい道は横へ大きく膨らむ。実測で効果を確認した
     * 試作は、324ブロックのルートに対して512ブロック四方の地形を丸ごと見ていた。
     */
    public static final int MARGIN_BLOCKS = 128;

    private final SearchBounds box;
    private final int cellBlocks;
    private final boolean lavaPassable;
    private final int nx;
    private final int ny;
    private final int nz;
    private final byte[] kind;
    private int floorMarks;

    private VoxelTerrain(SearchBounds box, int cellBlocks, boolean lavaPassable, int nx, int ny, int nz) {
        this.box = box;
        this.cellBlocks = cellBlocks;
        this.lavaPassable = lavaPassable;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.kind = new byte[nx * ny * nz];
    }

    /**
     * 床の上下に残す余白（ブロック）。床そのものだけでは足りない——経路は床の上を通るし、
     * 地図に無い床が少し上下にあることもある。
     */
    public static final int VERTICAL_MARGIN_BLOCKS = 24;

    /**
     * 始点と目的地を覆う箱を作る。Yは<b>地図に床が実在する範囲</b>に余白を足したもので、
     * <b>次元の全高ではない</b>。
     *
     * <p><b>次元の全高を使ってはいけない。</b>天井のある次元で縦に積まれた通路を表すのが
     * この層の目的なので「Yを絞ってはいけない」と考えたくなるが、絞る基準を<b>次元</b>に取るか
     * <b>地図</b>に取るかは別の話。ネザーの高さが128より高い環境（実機ログから逆算した箱は
     * <b>Y幅256</b>だった）では、岩盤天井より上の空きが格子の<b>半分</b>を占める。害は2つ:
     *
     * <ul>
     *   <li>その空きは全部「床の無いセル」なので、ガイドが<b>天井の上を橋で走る道</b>を
     *       描く。実測（0..255の箱）では歩き通せなくなり、探索がy=95・経路から100ブロック
     *       西へ引きずられた——実機ログの「繋ぎ目の大回り(x=-416)」と同じ形</li>
     *   <li>{@link #MAX_CELLS}の枠を空きが食うので格子が粗くなる。実機は辺6まで粗くなっていた
     *       （床のある範囲に絞れば同じ経路で辺4に収まる）</li>
     * </ul>
     *
     * @param lowestFloorY  地図から読めたいちばん低い床のY
     * @param highestFloorY 同じくいちばん高い床のY
     */
    public static SearchBounds boxFor(LevelHeightAccessor level, BlockPos start, BlockPos goal,
            int lowestFloorY, int highestFloorY) {
        // 始点と目的地は必ず箱の中に入れる。目的地が外だとガイドの起点が決まらず表が空になり、
        // 始点が外だと見積もりが縁の値で頭打ちになる
        int low = Math.min(lowestFloorY, Math.min(start.getY(), goal.getY()));
        int high = Math.max(highestFloorY, Math.max(start.getY(), goal.getY()));
        return new SearchBounds(
                Math.min(start.getX(), goal.getX()) - MARGIN_BLOCKS,
                Math.max(level.getMinBuildHeight(), low - VERTICAL_MARGIN_BLOCKS),
                Math.min(start.getZ(), goal.getZ()) - MARGIN_BLOCKS,
                Math.max(start.getX(), goal.getX()) + MARGIN_BLOCKS,
                Math.min(level.getMaxBuildHeight() - 1, high + VERTICAL_MARGIN_BLOCKS),
                Math.max(start.getZ(), goal.getZ()) + MARGIN_BLOCKS);
    }

    /** この箱を{@link #MAX_CELLS}に収める最小のセル辺。収まらなければ0。 */
    public static int cellBlocksFor(SearchBounds box) {
        for (int candidate : CELL_LADDER) {
            long cells = (long) axisCells(box.minX(), box.maxX(), candidate)
                    * axisCells(box.minY(), box.maxY(), candidate)
                    * axisCells(box.minZ(), box.maxZ(), candidate);
            if (cells <= MAX_CELLS) {
                return candidate;
            }
        }
        return 0;
    }

    /**
     * 箱に合わせてセル辺を決めた格子。箱が広すぎて扱えなければ{@code null}。
     *
     * @param lavaPassable 溶岩に足場を置いて渡ってよいか（{@code CellSource#lavaBridgingEnabled}）。
     *                     渡れない設定なら{@link #LAVA}の値段が跳ね上がる（{@link VoxelCostToGo}）
     */
    public static VoxelTerrain of(SearchBounds box, boolean lavaPassable) {
        int cellBlocks = cellBlocksFor(box);
        return cellBlocks == 0 ? null : of(box, cellBlocks, lavaPassable);
    }

    public static VoxelTerrain of(SearchBounds box, int cellBlocks, boolean lavaPassable) {
        return new VoxelTerrain(box, cellBlocks, lavaPassable,
                axisCells(box.minX(), box.maxX(), cellBlocks),
                axisCells(box.minY(), box.maxY(), cellBlocks),
                axisCells(box.minZ(), box.maxZ(), cellBlocks));
    }

    private static int axisCells(int min, int max, int cellBlocks) {
        return (max - min) / cellBlocks + 1;
    }

    /**
     * Xaeroの地図が1本の柱について記録している床を1つ写す。<b>ブロック解像度・レイヤーごとに
     * 呼ぶこと</b>——チャンク平均の床（層1の{@link CoarseMap}）に落として渡すと、ネザーでは
     * 通路がほとんど埋まらず、ガイドは「知らない場所をまっすぐ橋で渡る方が安い」と答える
     * （実測: チャンク平均だと歩ける床が全セルの6%にしかならず、探索が天井へ吸い寄せられた）。
     *
     * <p>床の<b>上</b>のセルに印を付ける。{@code floorTopY}はXaeroの{@code MapBlock#getHeight}
     * と同じ「いちばん上の固体ブロックのY」で、立つのはその1つ上。
     *
     * @param lava 溶岩の面。立てないので{@link #LAVA}。橋を架けて渡る設定でも印は付ける——
     *             溶岩の海と「地図が無いだけの場所」を同じ値段にすると、迂回すべき向きが消える
     */
    public void markFloor(int x, int z, int floorTopY, boolean lava) {
        int standY = floorTopY + 1;
        if (!box.contains(x, standY, z)) {
            return;
        }
        floorMarks++;
        int index = indexOfBlock(x, standY, z);
        byte mark = lava ? LAVA : STANDABLE;
        if (kind[index] < mark) {
            kind[index] = mark;
        }
    }

    /** {@link #markFloor}で箱の中へ写せた床の数（診断用）。0なら地図から何も取れていない。 */
    public int floorMarks() {
        return floorMarks;
    }

    public SearchBounds box() {
        return box;
    }

    public int cellBlocks() {
        return cellBlocks;
    }

    /** 溶岩に足場を置いて渡ってよいか（{@code CellSource#lavaBridgingEnabled}）。 */
    boolean lavaPassable() {
        return lavaPassable;
    }

    public int cellCount() {
        return kind.length;
    }

    int nx() {
        return nx;
    }

    int ny() {
        return ny;
    }

    int nz() {
        return nz;
    }

    byte kindAt(int index) {
        return kind[index];
    }

    /** 種別ごとのセル数（診断用）。床の割合がそのままこの層の効きを表す。 */
    public String breakdown() {
        int lava = 0;
        int standable = 0;
        for (byte value : kind) {
            if (value == LAVA) {
                lava++;
            } else if (value == STANDABLE) {
                standable++;
            }
        }
        return "床=" + standable + ", 溶岩=" + lava + ", 空洞=" + (kind.length - lava - standable);
    }

    /** ブロック座標が箱の中にあるか。 */
    public boolean contains(int x, int y, int z) {
        return box.contains(x, y, z);
    }

    int indexOfBlock(int x, int y, int z) {
        return index(cellIndex(x, box.minX()), cellIndex(y, box.minY()), cellIndex(z, box.minZ()));
    }

    /** 箱の外の座標は、いちばん近い縁のセルへ丸める。 */
    int clampedIndexOfBlock(int x, int y, int z) {
        return index(clamp(cellIndex(clampBlock(x, box.minX(), box.maxX()), box.minX()), nx),
                clamp(cellIndex(clampBlock(y, box.minY(), box.maxY()), box.minY()), ny),
                clamp(cellIndex(clampBlock(z, box.minZ(), box.maxZ()), box.minZ()), nz));
    }

    static int clampBlock(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static int clamp(int value, int size) {
        return value < 0 ? 0 : Math.min(value, size - 1);
    }

    private int cellIndex(int block, int min) {
        return (block - min) / cellBlocks;
    }

    int index(int i, int j, int k) {
        return (j * nz + k) * nx + i;
    }

    int cellX(int index) {
        return index % nx;
    }

    int cellZ(int index) {
        return (index / nx) % nz;
    }

    int cellY(int index) {
        return index / (nx * nz);
    }
}
