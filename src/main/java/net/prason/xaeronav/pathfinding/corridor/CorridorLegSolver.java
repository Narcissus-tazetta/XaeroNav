package net.prason.xaeronav.pathfinding.corridor;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.CellSource;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import net.prason.xaeronav.pathfinding.world.SurfaceCellSource;
import net.prason.xaeronav.xaero.XaeroMapReader;

/**
 * 長距離ルート層1のwaypoint線分1本を、層2（ブロック解像度のXaero地表データ）で解決する準備を行う。
 * {@code /xaeronav corridor}診断コマンドと{@link net.prason.xaeronav.client.PathfindingState}の
 * waypoint精緻化が共有するロジック。
 *
 * <p><b>スレッド契約:</b> {@link #prepare}は{@link XaeroMapReader}経由でXaeroの地図データを読むため
 * メインスレッド専用（{@link XaeroMapReader}のクラスJavadoc参照）。結果の{@link PreparedLeg#view()}は
 * 不変な{@link SurfaceCellSource}なので、それを使ったA*探索自体はワーカースレッドで行ってよい
 * （探索の実行は呼び出し側に委ねる——診断コマンドは同期実行、ライブナビは非同期実行したいため）。
 */
public final class CorridorLegSolver {

    /** 区間のバウンディングボックスに足す水平マージン（ブロック）。長距離ルート層2の設計値。 */
    public static final int HORIZONTAL_MARGIN_BLOCKS = 48;

    /**
     * 区間のバウンディングボックスに足す垂直マージン（ブロック）。{@link SurfaceCellSource#cell}は
     * 実際にはY方向の範囲を見ない（列ごとの地表高さだけで通行可否が決まる）ので、ここは
     * {@code bounds()}の体裁を整える以上の意味を持たない。
     */
    public static final int VERTICAL_MARGIN_BLOCKS = 64;

    /**
     * 区間ごとの探索時間上限（ミリ秒）。層2は掘削・ドア・蜘蛛の巣を扱わずノード単価が軽いので、
     * 上限を切り詰めても大抵の区間は十分な時間で解ける。診断コマンドは同期実行なので特に重要
     * （waypointの多い長いルートで合計が数十秒に膨らむのを防ぐ）。
     */
    public static final long LEG_TIME_LIMIT_MILLIS = 300;

    public static final SearchLimits SEARCH_LIMITS = new SearchLimits(
            AStarPathfinder.DEFAULT_MAX_EXPANDED_NODES,
            LEG_TIME_LIMIT_MILLIS,
            AStarPathfinder.DEFAULT_HEURISTIC_WEIGHT);

    private CorridorLegSolver() {
    }

    /**
     * {@code from}から{@code to}への区間を層2で探索できる状態に準備する。両端どちらかで地表データが
     * 無ければ{@link PreparedLeg#view()}が{@code null}になる（呼び出し側が読み込みを待つか、
     * 生のwaypointへフォールバックする）。{@link PreparedLeg#pendingRegions()}はデータの有無に
     * かかわらず常に埋まる——失敗の報告にも「あと何リージョン読めば解決しうるか」を出すため。
     *
     * <p>{@code readSurfaceDetailed}はcreate=falseで読むため、この区間のリージョンがXaeroの
     * メモリにまだ無ければ黙ってNO_DATA扱いになる。訪問済みでも今メモリに無いだけのことは
     * 珍しくないため、未読み込みリージョンがあれば{@link XaeroMapReader#requestLoad}で読み込みを
     * 要求する（次回の呼び出しで解決する可能性を残す）。
     */
    public static PreparedLeg prepare(BlockPos from, BlockPos to) {
        int minBlockX = Math.min(from.getX(), to.getX()) - HORIZONTAL_MARGIN_BLOCKS;
        int maxBlockX = Math.max(from.getX(), to.getX()) + HORIZONTAL_MARGIN_BLOCKS;
        int minBlockZ = Math.min(from.getZ(), to.getZ()) - HORIZONTAL_MARGIN_BLOCKS;
        int maxBlockZ = Math.max(from.getZ(), to.getZ()) + HORIZONTAL_MARGIN_BLOCKS;
        int sizeX = maxBlockX - minBlockX + 1;
        int sizeZ = maxBlockZ - minBlockZ + 1;

        int minChunkX = minBlockX >> 4;
        int maxChunkX = maxBlockX >> 4;
        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = maxBlockZ >> 4;
        int chunksX = maxChunkX - minChunkX + 1;
        int chunksZ = maxChunkZ - minChunkZ + 1;
        XaeroMapReader.RegionStats regionStats = XaeroMapReader.surveyRegions(minChunkX, minChunkZ, chunksX, chunksZ);
        int pendingRegions = regionStats.pendingLoad();
        if (pendingRegions > 0) {
            XaeroMapReader.requestLoad(minChunkX, minChunkZ, chunksX, chunksZ);
        }

        SurfaceGrid grid = XaeroMapReader.readSurfaceDetailed(minBlockX, minBlockZ, sizeX, sizeZ);
        BlockPos resolvedFrom = grid.resolveStandable(from.getX(), from.getZ());
        BlockPos resolvedTo = grid.resolveStandable(to.getX(), to.getZ());
        if (resolvedFrom == null || resolvedTo == null) {
            return new PreparedLeg(null, null, null, pendingRegions);
        }

        SearchBounds bounds = new SearchBounds(minBlockX, resolvedFrom.getY() - VERTICAL_MARGIN_BLOCKS, minBlockZ,
                maxBlockX, resolvedFrom.getY() + VERTICAL_MARGIN_BLOCKS, maxBlockZ);
        CellSource view = new SurfaceCellSource(grid, bounds);
        return new PreparedLeg(view, resolvedFrom, resolvedTo, pendingRegions);
    }

    /**
     * {@link #prepare}の結果。{@code view}は不変なのでワーカースレッドから探索してよい。
     * {@code view}が{@code null}なら地表データが無かったことを表し、{@code from}/{@code to}も
     * 意味を持たない。
     */
    public record PreparedLeg(CellSource view, BlockPos from, BlockPos to, int pendingRegions) {
    }
}
