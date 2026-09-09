package net.prason.xaeronav.pathfinding.coarse;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.CostToGo;
import net.prason.xaeronav.pathfinding.cost.ActionCosts;

/**
 * {@link VoxelTerrain}から作る、目的地までの残りコストの見積もり。天井のある次元の遠距離ルートで
 * {@code AStarPathfinder}へ渡す唯一のガイド。
 *
 * <p>{@link CoarseRouter#costToGo}との違いは2つだけだが、どちらもネザーでは決定的だった:
 *
 * <ul>
 *   <li><b>3次元</b>。柱ごとに床を数枚持つ表現では、縦に積まれたトンネルを区別できない</li>
 *   <li><b>探索の箱の外まで覆う</b>。目的地が箱の外にあると{@code CoarseRouter#costToGo}は
 *       全コストを無限にし、どこでも0を返す表になる＝ガイド無しと同じ</li>
 * </ul>
 *
 * <p>実測（ユーザーの停止ルート）: 現行の2.5D層1ガイドは0ステップ、ガイド無しは全世界が見えていても
 * 300万ノードで未到達、ここが作るガイドは到達（682手）。「3次元であること」と「箱の外まで
 * 覆っていること」は<b>両方</b>必要で、片方だけだとどちらも0ステップになる。
 *
 * <p><b>これは意図的に非許容（non-admissible）。</b>{@code AStarPathfinder}は幾何学的な
 * {@code Heuristic}とのmaxを取るので、下限を破っても幾何側が救う。{@code GuideAdmissibilityTest}の
 * 対象にしないのはそのため——あちらが守っているのは層1の{@code costToGo}の許容性で、役割が違う。
 */
public final class VoxelCostToGo implements CostToGo {

    /**
     * 床の無いセルを1ブロック渡る値段（疾走の何倍か）＝実費の橋。
     *
     * <p><b>実費のまま使うこと。</b>この表は幾何{@code Heuristic}とのmaxで採られるので、
     * 直線距離の何倍かがそのまま探索の重みになる——一見「地図が薄いと膨らみすぎて貪欲になる」
     * ように見えるが、<b>そう見えるだけで実際には正しい</b>。実測（ユーザーの停止ルート、
     * 洞窟レイヤー1枚・訪問68%という実機と同じ薄さの地図）では、膨らみ5.84倍の表がそのまま
     * 549手で歩き通す。ここを縮めると（3.0倍・2.0倍へ）<b>逆に歩けなくなった</b>——
     * 縮めた表は幾何ヒューリスティックに埋もれ、迂回すべき向きを言わなくなる。
     *
     * <p>比を落とす側も測ってある: 11.1→3.0→1.5倍で1回の探索の前進は5→5→4ブロックで、
     * どのみち改善しない。<b>この表の値段はいじる場所ではない。</b>
     */
    private static final double OPEN_PENALTY =
            (ActionCosts.PLACE_BLOCK_OVERHEAD_TICKS + ActionCosts.SPRINT_ONE_BLOCK)
                    / ActionCosts.SPRINT_ONE_BLOCK;

    /**
     * 溶岩の面を1ブロック渡る値段（疾走の何倍か）。橋を架けて渡ってよい設定なら、床の無いセルと
     * <b>同じ</b>——実際に払うのも同じ「橋を架ける」だから。
     *
     * <p>「溶岩に架けられる橋は空中より短い（{@code CellSource#maxLavaBridgeRunBlocks}は既定30、
     * 空中は96）ぶん高いはず」という理屈は立つが、<b>割り増して良くなることを測っていない</b>ので
     * 揃えてある。この表の役目は大まかな向きを出すことで、溶岩を1マス単位で避けるのは層3の仕事。
     */
    private static final double LAVA_PENALTY = OPEN_PENALTY;

    /**
     * 溶岩に橋を架けない設定のときの、溶岩の面の値段。渡れないので<b>床の無いセルより高く
     * なければならない</b>——ここを掘削の実費（疾走の約7倍）に置いていた実装は、溶岩を
     * 空洞（約11倍）より<b>安く</b>見積もっていた。
     *
     * <p>倍率そのものは測っていない。回帰のフィクスチャは全て溶岩橋ありの設定で、
     * こちらの枝を通らない。「渡れないものは渡れる場所より高い」という順序だけが根拠。
     */
    private static final double BLOCKED_LAVA_PENALTY = OPEN_PENALTY * 8.0;

    /**
     * 目的地のセルに床が見つからないときに、代わりの起点を探す範囲（ブロック）。
     *
     * <p><b>ここがこの設計の急所。</b>起点を1つも決められないとコスト表が丸ごと空になり、
     * {@link #estimate}がどこでも0を返す＝ガイド無しと同じ＝「経路が1本も出ない」現象に戻る。
     * 実測では、床を58%落とした地図で起点を床に限ると到達がコインフリップになった。
     * 垂直の幅は領域ゴールの垂直許容({@code AStarPathfinder#goalVerticalRadius})と同じ考え方。
     */
    private static final int ANCHOR_VERTICAL_BLOCKS = 24;

    private static final int ANCHOR_HORIZONTAL_BLOCKS = 16;

    // 優先度キューの鍵は投入後に変わってはいけない。cost[]を経由して比べると、後の緩和が
    // ヒープの順序を壊したまま優先度だけ変える
    private record Entry(int index, double cost) {
    }

    private final VoxelTerrain terrain;
    private final double[] cost;
    private final double slack;
    private final int reachableCells;

    private VoxelCostToGo(VoxelTerrain terrain, double[] cost, int reachableCells) {
        this.terrain = terrain;
        this.cost = cost;
        this.reachableCells = reachableCells;
        this.slack = terrain.cellBlocks() * Math.sqrt(3.0) * ActionCosts.SPRINT_ONE_BLOCK;
    }

    /**
     * 目的地から逆向きにDijkstraを回してコスト表を作る。<b>ワーカースレッドで呼んでよい</b>
     * （{@link VoxelTerrain}を組み終えていれば、ここはXaeroにもワールドにも触らない）。
     *
     * @param goal 立てる座標へ寄せ終えた目的地（{@code StanceFinder#resolveGoal}の後）
     * @return 起点を決められなければ{@code null}。呼び出し側は<b>黙ってガイド無しへ落とさず</b>、
     *         それを記録すること——ガイドが無いことこそが遠距離ネザーの失敗そのものなので、
     *         区別が付かないと同じ調査をもう一度やることになる
     */
    public static VoxelCostToGo build(VoxelTerrain terrain, BlockPos goal, BooleanSupplier cancelled) {
        int goalIndex = anchor(terrain, goal);
        if (goalIndex < 0) {
            return null;
        }
        double[] cost = new double[terrain.cellCount()];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        cost[goalIndex] = 0.0;
        boolean[] closed = new boolean[cost.length];
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
        open.add(new Entry(goalIndex, 0.0));
        int nx = terrain.nx();
        int ny = terrain.ny();
        int nz = terrain.nz();
        int reached = 0;
        boolean lavaPassable = terrain.lavaPassable();
        while (!open.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            Entry entry = open.poll();
            int current = entry.index();
            if (closed[current] || entry.cost() != cost[current]) {
                continue;
            }
            closed[current] = true;
            reached++;
            int i = terrain.cellX(current);
            int j = terrain.cellY(current);
            int k = terrain.cellZ(current);
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    for (int dk = -1; dk <= 1; dk++) {
                        if (di == 0 && dj == 0 && dk == 0) {
                            continue;
                        }
                        int ni = i + di;
                        int nj = j + dj;
                        int nk = k + dk;
                        if (ni < 0 || ni >= nx || nj < 0 || nj >= ny || nk < 0 || nk >= nz) {
                            continue;
                        }
                        int neighbor = terrain.index(ni, nj, nk);
                        if (closed[neighbor]) {
                            continue;
                        }
                        double blocks = terrain.cellBlocks() * Math.sqrt(di * di + dj * dj + dk * dk);
                        double next = cost[current] + blocks * rate(terrain.kindAt(neighbor), lavaPassable);
                        if (next < cost[neighbor]) {
                            cost[neighbor] = next;
                            open.add(new Entry(neighbor, next));
                        }
                    }
                }
            }
        }
        return new VoxelCostToGo(terrain, cost, reached);
    }

    /**
     * 辺1ブロックあたりの値段。<b>一律「疾走の何倍」にしてはいけない。</b>立てない格子を
     * 同じ値段で束ねた試作は、溶岩だらけのネザーで「壁を突っ切る方が安い」と言ってしまい、
     * 展開ノードを減らしたまま経路だけ悪くなった。
     *
     * <p>逆に、地図が薄いと見積もりが直線距離の5倍以上になるのを見て<b>縮めたくもなるが、
     * それも測って外してある</b>（{@link #OPEN_PENALTY}）。
     */
    private static double rate(byte kind, boolean lavaPassable) {
        double penalty = switch (kind) {
            case VoxelTerrain.STANDABLE -> 1.0;
            case VoxelTerrain.LAVA -> lavaPassable ? LAVA_PENALTY : BLOCKED_LAVA_PENALTY;
            default -> OPEN_PENALTY;
        };
        return ActionCosts.SPRINT_ONE_BLOCK * penalty;
    }

    /**
     * 目的地のセル、無ければその周りで逆向きDijkstraの起点にできるセルを探す。
     * 床を優先し、床が無ければ空洞、それも無ければ目的地のセルそのもの。
     *
     * <p><b>最後の手段まで用意するのが要点。</b>「起点が見つからない」は表全体を無効にするので、
     * 岩の中の座標であっても起点として使う方が、ガイドを丸ごと諦めるよりはるかにましになる。
     */
    private static int anchor(VoxelTerrain terrain, BlockPos goal) {
        if (!terrain.contains(goal.getX(), goal.getY(), goal.getZ())) {
            return -1;
        }
        int cell = terrain.cellBlocks();
        int verticalSpread = ANCHOR_VERTICAL_BLOCKS / cell;
        int horizontalSpread = ANCHOR_HORIZONTAL_BLOCKS / cell;
        int fallback = -1;
        for (int spread = 0; spread <= Math.max(verticalSpread, horizontalSpread); spread++) {
            int dyLimit = Math.min(spread, verticalSpread);
            int dxLimit = Math.min(spread, horizontalSpread);
            for (int dj = -dyLimit; dj <= dyLimit; dj++) {
                for (int di = -dxLimit; di <= dxLimit; di++) {
                    for (int dk = -dxLimit; dk <= dxLimit; dk++) {
                        if (Math.max(Math.abs(dj), Math.max(Math.abs(di), Math.abs(dk))) != spread) {
                            continue;
                        }
                        int x = goal.getX() + di * cell;
                        int y = goal.getY() + dj * cell;
                        int z = goal.getZ() + dk * cell;
                        if (!terrain.contains(x, y, z)) {
                            continue;
                        }
                        int index = terrain.indexOfBlock(x, y, z);
                        byte kind = terrain.kindAt(index);
                        if (kind == VoxelTerrain.STANDABLE) {
                            return index;
                        }
                        if (kind == VoxelTerrain.OPEN && fallback < 0) {
                            fallback = index;
                        }
                    }
                }
            }
        }
        return fallback >= 0 ? fallback : terrain.indexOfBlock(goal.getX(), goal.getY(), goal.getZ());
    }

    /** Dijkstraが届いたセルの数（診断用）。 */
    public int reachableCells() {
        return reachableCells;
    }

    public int cellCount() {
        return terrain.cellCount();
    }

    /**
     * {@inheritDoc}
     *
     * <p>箱の外は<b>いちばん近い縁のセルの値＋そこまでの直線</b>で答える。0を返すと箱の縁が
     * 崖になり、A*は「箱の外の方が安い」と読んで経路から離れる向きに展開してしまう。
     */
    @Override
    public double estimate(int x, int y, int z) {
        double approach = 0.0;
        int index;
        if (terrain.contains(x, y, z)) {
            index = terrain.indexOfBlock(x, y, z);
        } else {
            index = terrain.clampedIndexOfBlock(x, y, z);
            approach = distanceToBox(x, y, z) * ActionCosts.SPRINT_ONE_BLOCK;
        }
        double value = cost[index];
        if (Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value + approach - slack);
    }

    private double distanceToBox(int x, int y, int z) {
        double dx = x - VoxelTerrain.clampBlock(x, terrain.box().minX(), terrain.box().maxX());
        double dy = y - VoxelTerrain.clampBlock(y, terrain.box().minY(), terrain.box().maxY());
        double dz = z - VoxelTerrain.clampBlock(z, terrain.box().minZ(), terrain.box().maxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
