package net.prason.xaeronav.client;

import java.util.List;
import java.util.function.IntBinaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.prason.xaeronav.pathfinding.coarse.CoarseMap;

/**
 * 空の下を滑空している間の案内。空中経路を引かず、降りる地点に光の柱を立ててHUDの矢印で示す。
 *
 * <p>空が見えている所では、障害物を避ける線より「どこへ向かえばいいか」の方が要る。空中経路は
 * 地形の上を高く飛べば済む場面でも数百ブロック先まで読み込み済みの範囲を探索し、線は描画距離で
 * 切れる。天井のある次元（ネザー）と屋根の下（洞窟）は従来どおり空中経路を引く。
 *
 * <p>柱は<b>降りるべき地点</b>に立てる。目的地が地表なら目的地、歩行の長距離ルートが途中から地下へ
 * 潜るならその手前の地表の点（例: 要塞へ向かうルートが洞窟に入る所）。
 */
final class SkyGuide {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * 空が見えている／見えていないが、これだけ続いたら切り替える（tick）。木の下や橋の下を
     * くぐるたびに空中経路を引き直すと、そのたびに探索が走って表示が入れ替わる。
     */
    private static final int SWITCH_TICKS = 40;

    /** 地表よりこれだけ下にある点は地下とみなす（ブロック）。地表の起伏と地図の平均の誤差を吸う。 */
    static final int UNDERGROUND_MARGIN_BLOCKS = 4;

    /** 柱の場所を選び直す間隔（tick）。チャンクが読み込まれると地表の高さが地図の推定から実測へ変わる。 */
    private static final int PILLAR_REFRESH_TICKS = 40;

    private boolean active;
    private int pendingTicks;

    private @Nullable BlockPos pillar;
    private @Nullable BlockPos pillarGoal;
    private @Nullable List<BlockPos> pillarRoute;
    private int pillarAge;

    /** 空の下の案内に切り替わっているか。 */
    boolean active() {
        return active;
    }

    /** 離陸した瞬間。最初の判定は猶予なしで決める——ここで待つと、空の下でも数秒だけ空中経路を探索する。 */
    void begin(Level level, Player player) {
        active = openSky(level, player);
        pendingTicks = 0;
    }

    void reset() {
        active = false;
        pendingTicks = 0;
        pillar = null;
        pillarGoal = null;
        pillarRoute = null;
    }

    /** 滑空中の1tick。切り替わったら真。 */
    boolean tick(Level level, Player player) {
        boolean open = openSky(level, player);
        if (open == active) {
            pendingTicks = 0;
            return false;
        }
        if (++pendingTicks < SWITCH_TICKS) {
            return false;
        }
        active = open;
        pendingTicks = 0;
        return true;
    }

    private static boolean openSky(Level level, Player player) {
        return !level.dimensionType().hasCeiling() && level.canSeeSky(player.blockPosition().above());
    }

    /**
     * 柱を立てる地点。{@code route}は歩行の長距離ルートの中間目標（通過済みも含む全部、無ければ空）。
     * 結果は{@link #PILLAR_REFRESH_TICKS}ごとに選び直す。
     */
    BlockPos pillar(Level level, BlockPos goal, List<BlockPos> route, @Nullable CoarseMap map) {
        if (pillar != null && goal.equals(pillarGoal) && route == pillarRoute && ++pillarAge < PILLAR_REFRESH_TICKS) {
            return pillar;
        }
        BlockPos chosen = descentPoint(goal, route, (x, z) -> surfaceY(level, map, x, z));
        if (!chosen.equals(pillar)) {
            LOGGER.debug("XaeroNav: 光の柱の地点 ({}, {}, {}, 目的地={}, {}, {}, 中間目標{}本, 地図{})",
                    chosen.getX(), chosen.getY(), chosen.getZ(), goal.getX(), goal.getY(), goal.getZ(),
                    route.size(), map == null ? "なし" : "あり");
        }
        pillar = chosen;
        pillarGoal = goal;
        pillarRoute = route;
        pillarAge = 0;
        return pillar;
    }

    /**
     * 降りる地点。ルートを始点側から辿り、最初に地下へ潜る点の<b>1つ手前</b>（地表の点）を返す。
     * 潜らなければ目的地。返す座標のYは、その列の地表（柱の根元）。
     *
     * @param surface 列の地表の高さ。分からなければ{@link Integer#MIN_VALUE}（地下とは判定しない）
     */
    static BlockPos descentPoint(BlockPos goal, List<BlockPos> route, IntBinaryOperator surface) {
        BlockPos previous = null;
        for (BlockPos point : route) {
            if (underground(point, surface)) {
                return previous == null ? atSurface(point, surface) : atSurface(previous, surface);
            }
            previous = point;
        }
        return atSurface(goal, surface);
    }

    private static boolean underground(BlockPos point, IntBinaryOperator surface) {
        int top = surface.applyAsInt(point.getX(), point.getZ());
        return top != Integer.MIN_VALUE && point.getY() < top - UNDERGROUND_MARGIN_BLOCKS;
    }

    private static BlockPos atSurface(BlockPos point, IntBinaryOperator surface) {
        int top = surface.applyAsInt(point.getX(), point.getZ());
        return top == Integer.MIN_VALUE ? point : new BlockPos(point.getX(), Math.max(top, point.getY()), point.getZ());
    }

    /**
     * 列の地表の高さ。読み込み済みならワールドの高さマップ、そうでなければXaeroの地図の最も高い床。
     * どちらも無ければ{@link Integer#MIN_VALUE}。
     */
    private static int surfaceY(Level level, @Nullable CoarseMap map, int x, int z) {
        if (level.hasChunk(x >> 4, z >> 4)) {
            // 葉を除く。森の上を通る中間目標が葉の高さより下になり、地下と誤判定して手前に柱が立つ
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        if (map == null) {
            return Integer.MIN_VALUE;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        int top = Integer.MIN_VALUE;
        for (int floor = 0; floor < map.floorCount(chunkX, chunkZ); floor++) {
            if (map.kindAtFloor(chunkX, chunkZ, floor) != CoarseMap.VOID) {
                top = Math.max(top, map.heightAtFloor(chunkX, chunkZ, floor));
            }
        }
        return top;
    }
}
