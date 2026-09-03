package net.prason.xaeronav.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.prason.xaeronav.pathfinding.flight.FlightLineRouter;
import net.prason.xaeronav.pathfinding.world.ChunkView;
import net.prason.xaeronav.pathfinding.world.MovementOptions;
import net.prason.xaeronav.pathfinding.world.SearchBounds;

/**
 * 完全に水没して進んでいる間の案内。<b>水面まで上がって、水面に沿って目的地へ</b>向かう線を
 * プレイヤーに追従させる軽量な追尾ナビで、歩行A\*も3D経路探索も持たない。
 *
 * <p>「水中は自分で見て泳げるので障害物回避の経路は要らない」という判断はエリトラ滑空
 * （{@link FlightNavState}）と同じだが、あちらのパイプラインとは<b>混ぜない</b>——必要なのは
 * {@link FlightLineRouter}の曲げ点線1本だけ。滑空中か水没中かの判断と目的地は
 * {@link PathfindingState}が持ち、ここは「その目的地への追尾線」だけを受け持つ。
 *
 * <p>線は水面の高さで組む。水面より上に突き出た島や地形だけを避ければよく、海底の起伏は
 * 泳いで越える相手なので見なくてよい。描画側が「現在地→水面→(この線)→目的地」と繋ぐ。
 *
 * <p>{@code volatile}はワーカースレッドが書いてクライアントスレッドが読む。それ以外はクライアント専用。
 */
final class SwimNavState {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 探索の投入間隔の下限（tick）。きっかけが立て続けに成立してもここで頭打ちにする。 */
    private static final int MIN_RECALC_INTERVAL_TICKS = 10;

    /** 定期的な引き直しの間隔（tick）。約1秒ごとに、十分動いていれば線を引き直して追従させる。 */
    private static final int RECALC_INTERVAL_TICKS = 20;

    /** 定期引き直しに要る移動距離（ブロック）。動いていなければ同じ線が出るだけなので投げない。 */
    private static final double RECALC_MOVE_BLOCKS = 8.0;

    /** 水面を探して上へ見ていく最大の高さ（ブロック）。これを超えたら「水面なし」（洞窟の水没など）。 */
    private static final int SURFACE_SCAN_LIMIT = 128;

    /** 水面が見つからなかったことを表す番兵。 */
    static final double NO_SURFACE = Double.NaN;

    /**
     * 非同期の結果を適用してよいかを所有者に問い合わせる。鮮度は目的地と次元の一致で見る
     * （歩行A\*の{@code generation}とは無関係）。
     */
    @FunctionalInterface
    interface Current {
        boolean stillSwimmingTo(BlockPos goal, ResourceKey<Level> dimension);
    }

    private final Current current;

    /** 曲げ点線の計算専用。A\*とはライフサイクルが無関係なので素のスレッドを1本持つ。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xaeronav-swim-line");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 水面の高さで組んだ追尾線——曲がり点＋（目的地が水中なら）目的地の真上の水面点。<b>現在地も
     * 目的地も含まない</b>（描画側が両端を持つ）。引けていなければ空。
     */
    private volatile List<Vec3> alongSurface = List.of();

    /** 追尾線を組んだときの水面の高さ。{@link #NO_SURFACE}なら水面が見つからなかった。 */
    private volatile double surfaceY = NO_SURFACE;

    private volatile boolean computing;
    private volatile BlockPos computedFrom;
    private volatile BlockPos computedGoal;

    /** クライアントスレッド専用。 */
    private int ticksSinceRecalc;

    SwimNavState(Current current) {
        this.current = current;
    }

    /** 水面の高さに沿った中間点。始点も目的地も含まない。 */
    List<Vec3> alongSurface() {
        return alongSurface;
    }

    /** 追尾線を組んだときの水面の高さ。{@link #NO_SURFACE}なら水面が見つからなかった。 */
    double surfaceY() {
        return surfaceY;
    }

    /** 水没中の1tick。十分動いた・目的地が変わった・まだ線が無いなら引き直す。 */
    void tick(Level level, Player player, BlockPos currentGoal) {
        ticksSinceRecalc++;
        if (computing || ticksSinceRecalc < MIN_RECALC_INTERVAL_TICKS) {
            return;
        }
        BlockPos from = computedFrom;
        if (from == null || !currentGoal.equals(computedGoal)) {
            recalculate(currentGoal);
            return;
        }
        if (ticksSinceRecalc >= RECALC_INTERVAL_TICKS
                && Math.sqrt(from.distSqr(player.blockPosition())) >= RECALC_MOVE_BLOCKS) {
            recalculate(currentGoal);
        }
    }

    /**
     * 追尾線を引き直す。{@code ChunkView.capture}・地形の読み取りはメインスレッド専用なのでここで
     * 済ませ、不変のビューだけをワーカーへ渡す。
     */
    void recalculate(BlockPos currentGoal) {
        ticksSinceRecalc = 0;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        Player player = mc.player;
        if (level == null || player == null || currentGoal == null) {
            return;
        }

        BlockPos from = player.blockPosition();
        ResourceKey<Level> dimension = level.dimension();
        double surface = waterSurfaceAbove(level, from);
        // 水面が見つからなければプレイヤーの高さで組む（洞窟の水没など。線は上がらないが方角は出る）
        double lineY = Double.isNaN(surface) ? player.getY() : surface;
        Vec3 lineStart = new Vec3(player.getX(), lineY, player.getZ());
        Vec3 lineGoal = new Vec3(currentGoal.getX() + 0.5, lineY, currentGoal.getZ() + 0.5);
        boolean goalInWater = level.getFluidState(currentGoal).is(FluidTags.WATER);

        int renderRadius = mc.options.getEffectiveRenderDistance() * 16;
        SearchBounds bounds = SearchBounds.around(level, from, currentGoal,
                FlightLineRouter.HORIZONTAL_MARGIN_BLOCKS, FlightLineRouter.VERTICAL_MARGIN_BLOCKS,
                renderRadius);
        // 掘削・設置・跳躍・落下ダメージはどれも無関係
        ChunkView view = ChunkView.capture(level, player, bounds, MovementOptions.NONE);
        computing = true;

        CompletableFuture
                .supplyAsync(() -> {
                    List<Vec3> line = new FlightLineRouter(view, true).findGuideLine(lineStart, lineGoal);
                    // findGuideLineは[始点, 曲がり点, 終点]（曲げないときは[始点, 終点]）を返す。
                    // 中間の曲がり点だけを採り、目的地が水中なら「目的地の真上の水面点」を足して、
                    // 描画側が「水面に沿って進んでから最後に潜る」形に繋げられるようにする
                    List<Vec3> points = new ArrayList<>(2);
                    if (line.size() >= 3) {
                        points.addAll(line.subList(1, line.size() - 1));
                    }
                    if (goalInWater) {
                        points.add(lineGoal);
                    }
                    return points;
                }, executor)
                .whenComplete((points, error) -> {
                    computing = false;
                    if (error != null) {
                        LOGGER.error("XaeroNav: 潜水中の追尾線の計算に失敗しました", error);
                        return;
                    }
                    if (current.stillSwimmingTo(currentGoal, dimension)) {
                        alongSurface = List.copyOf(points);
                        surfaceY = surface;
                        computedFrom = from;
                        computedGoal = currentGoal;
                    }
                });
    }

    /** 目的地ごと捨てる。{@link #computing}は下ろさない——走っている計算の結果は{@link Current}が弾く。 */
    void reset() {
        alongSurface = List.of();
        surfaceY = NO_SURFACE;
        computedFrom = null;
        computedGoal = null;
    }

    /**
     * 足元から上へ水が何ブロック続いているか。{@code limit}まで数えたら打ち切って{@code limit}を返す。
     *
     * <p>{@link #waterSurfaceAbove}と違って線を組むためではなく「深く潜っているか」を見るためだけの
     * ものなので、水面まで辿らず必要な深さだけ数える（追尾ナビの判定は毎tick走る）。
     */
    static int waterDepthAbove(Level level, Player player, int limit) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos().set(player.blockPosition());
        int depth = 0;
        while (depth < limit && level.getFluidState(cursor).is(FluidTags.WATER)) {
            depth++;
            cursor.setY(cursor.getY() + 1);
        }
        return depth;
    }

    /**
     * {@code from}の列を上へ辿って水と空気の境目のYを返す。水中でなければ、または
     * {@link #SURFACE_SCAN_LIMIT}以内に水面が無ければ{@link #NO_SURFACE}。
     */
    private static double waterSurfaceAbove(Level level, BlockPos from) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos().set(from);
        if (!level.getFluidState(cursor).is(FluidTags.WATER)) {
            return NO_SURFACE;
        }
        int ceiling = Math.min(from.getY() + SURFACE_SCAN_LIMIT, level.getMaxBuildHeight());
        for (int y = from.getY() + 1; y <= ceiling; y++) {
            cursor.setY(y);
            if (!level.getFluidState(cursor).is(FluidTags.WATER)) {
                return y;
            }
        }
        return NO_SURFACE;
    }
}
