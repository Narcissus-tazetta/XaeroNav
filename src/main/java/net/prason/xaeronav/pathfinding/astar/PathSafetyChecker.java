package net.prason.xaeronav.pathfinding.astar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.prason.xaeronav.pathfinding.world.CellData;
import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * コスト計算はあくまで事前見積もりなので、経路を提示する直前に
 * 掘削区間の安全性を再チェックする（溶岩隣接・水の流入・深い縦穴への露出）。
 * A*の探索コストには影響させず、結果に対する事後アノテーションとして分離する。
 */
public final class PathSafetyChecker {

    private static final int VOID_SCAN_DEPTH = 5;
    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * 設置区間の足元を溶岩まで見通す走査深さ。{@code AStarPathfinder#COLUMN_SCAN_DEPTH}と揃える——
     * ここが浅いと、開けた空洞（ネザーの3D迷路）の遥か下が溶岩でも「足元は空気」としか見えず、
     * 隣接判定（{@link #hasAdjacent}、足元1マス下しか見ない）をすり抜けて警告色が付かない。
     */
    private static final int BRIDGE_LAVA_SCAN_DEPTH = 128;

    /**
     * 息継ぎなしで進んでよい水中の歩数。空気は{@code AIR_SUPPLY_TICKS}で尽き、そこからは1秒ごとに
     * ダメージが入る。うつ伏せ泳ぎ（{@code SWIM_ONE_BLOCK}＝約5.6tick）なら約54マス、疾走しない
     * 泳ぎ（約12.5tick）なら約24マス。
     *
     * <p>{@code CellSource#maxSubmergedTicks}（既定250tick）より手前に置いてあるのは、
     * こちらが<b>警告</b>だから。あちらが移動時間で数える（採掘の重さが効く）のに対し、
     * こちらは歩数で数える粗い目安に留める。上限のほうは移動を生成しない硬い線で、
     * 超える潜水は詰み回避のフォールバックでしか現れない——その区間には必ず色が付く一方、
     * 上限の内側でも潜り始めに空気が満タンとは限らないので、手前から注意を出す。
     */
    private static final int SUBMERGED_STEP_LIMIT = 20;

    private PathSafetyChecker() {
    }

    public static PathResult annotate(CellSource view, PathResult result) {
        List<PathStep> steps = result.steps();
        boolean[] drowning = drowningRuns(view, steps);
        List<PathStep> annotated = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            PathStep step = steps.get(i);
            PathRisk risk = assessRisk(view, step);
            if (risk == PathRisk.NONE && drowning[i]) {
                risk = PathRisk.DROWNING;
            }
            // 大半の区間は危険なし＝入力のまま。作り直す必要があるものだけ差し替える
            annotated.add(risk == step.risk() ? step
                    : new PathStep(step.pos(), step.movement(), step.cost(), step.bodyCells(), step.digCells(),
                            risk, step.placedBlockPos()));
        }
        return new PathResult(annotated, result.termination(), result.expandedNodes(), result.distinctNodes());
    }

    /**
     * 頭まで水に浸かったまま{@link #SUBMERGED_STEP_LIMIT}歩を超えて続く区間に印を付ける。
     *
     * <p>1歩ずつ見ても分からない危険なので、連続する潜水区間の長さで判定する。短い潜水は
     * 息継ぎで足りるし、水面を泳ぐ区間（足は水中でも頭は水面上）はいくら長くても溺れない。
     */
    private static boolean[] drowningRuns(CellSource view, List<PathStep> steps) {
        boolean[] flagged = new boolean[steps.size()];
        int runStart = -1;
        for (int i = 0; i <= steps.size(); i++) {
            if (i < steps.size() && headUnderwater(view, steps.get(i))) {
                if (runStart < 0) {
                    runStart = i;
                }
                continue;
            }
            if (runStart >= 0 && i - runStart > SUBMERGED_STEP_LIMIT) {
                for (int submerged = runStart; submerged < i; submerged++) {
                    flagged[submerged] = true;
                }
            }
            runStart = -1;
        }
        return flagged;
    }

    private static boolean headUnderwater(CellSource view, PathStep step) {
        BlockPos pos = step.pos();
        return CellData.water(view.cell(pos.getX(), pos.getY() + 1, pos.getZ()));
    }

    private static PathRisk assessRisk(CellSource view, PathStep step) {
        if (step.bridging()) {
            // 置いた足場は渡っている間ずっと身体の真下にある。1マスの溝の上を渡るだけなら警告は要らないが、
            // 溶岩の上と底の無い空虚の上では、足場を1つ外した結末が死になる。
            // 隣接だけでなく、開けた空洞の遥か下が溶岩の場合も見る
            // （AStarPathfinder#addBridgeが同じ判定でペナルティと上限を決めているのと対になる）
            if (hasAdjacent(view, step.placedBlockPos(), CellData::lava)) {
                return PathRisk.LAVA_ADJACENT;
            }
            return switch (footingUnder(view, step.placedBlockPos())) {
                case LAVA -> PathRisk.LAVA_ADJACENT;
                // 致死落差は奈落と同じ扱い。床が在るかどうかではなく「外したら死ぬか」が警告の基準
                case VOID, FATAL_DROP -> PathRisk.VOID_BELOW;
                case GROUND -> PathRisk.NONE;
            };
        }
        if (CellData.sneakRequired(view.cell(step.pos().getX(), step.pos().getY() - 1, step.pos().getZ()))) {
            // 足元がマグマブロック。通行可にした以上、スニークが要ることを伝えないと案内として不完全
            return PathRisk.SNEAK_OVER_MAGMA;
        }
        if (step.movement() == MovementType.FALL_DAMAGE) {
            return PathRisk.FALL_DAMAGE;
        }
        if (step.movement() == MovementType.FALL_MLG) {
            return PathRisk.MLG_REQUIRED;
        }
        if (step.movement() == MovementType.JUMP) {
            // 跳ぶ区間は、失敗したときに落ちる先が問題になる。溶岩なら即死、深い縦穴なら大怪我なので、
            // 掘削区間と同じように色を変えて「ここは落ちたら終わり」と分かるようにする
            return assessJumpRisk(view, step.bodyCells());
        }
        return step.digging() ? assessDigRisk(view, step.digCells()) : PathRisk.NONE;
    }

    private static PathRisk assessJumpRisk(CellSource view, List<BlockPos> bodyCells) {
        for (BlockPos cell : bodyCells) {
            if (hasAdjacent(view, cell, CellData::lava)) {
                return PathRisk.LAVA_ADJACENT;
            }
        }
        for (BlockPos cell : bodyCells) {
            if (isVoidBelow(view, cell)) {
                return PathRisk.VOID_BELOW;
            }
        }
        return PathRisk.NONE;
    }

    /**
     * 到着地点だけでなく、この移動で掘る全セル（例: Traverseならbody上下2マス、Descendなら3マス、
     * さらに頭上の落下ブロック連鎖）をチェックする。到着地点1マスだけを見ると、頭上側だけが
     * 溶岩隣接、といったケースを見逃す。
     */
    private static PathRisk assessDigRisk(CellSource view, List<BlockPos> digCells) {
        for (BlockPos cell : digCells) {
            if (hasAdjacent(view, cell, CellData::lava)) {
                return PathRisk.LAVA_ADJACENT;
            }
        }
        for (BlockPos cell : digCells) {
            if (hasAdjacent(view, cell, CellData::water)) {
                return PathRisk.WATER_INFLOW;
            }
        }
        for (BlockPos cell : digCells) {
            if (isVoidBelow(view, cell)) {
                return PathRisk.VOID_BELOW;
            }
        }
        return PathRisk.NONE;
    }

    private static boolean hasAdjacent(CellSource view, BlockPos pos, LongPredicate test) {
        for (Direction dir : DIRECTIONS) {
            long neighbor = view.cell(pos.getX() + dir.getStepX(), pos.getY() + dir.getStepY(), pos.getZ() + dir.getStepZ());
            if (test.test(neighbor)) {
                return true;
            }
        }
        return false;
    }

    /** 置いた足場を外したときに落ちる先。 */
    private enum Footing {
        /** 遠くても構わず溶岩が見通せる。 */
        LAVA,
        /** 読めるセルだけを辿って何にも当たらなかった＝底が無い。 */
        VOID,
        /**
         * 床は在るが、そこまでの落差が{@link CellSource#fatalFallBlocks()}以上＝外せば死ぬ。
         * 「床が在るか」ではなく「外したときに死ぬか」が警告の基準なので、{@link #VOID}と同じ扱い。
         */
        FATAL_DROP,
        /** 溶岩でも空虚でもない、落ちても助かりうる床。読めなかった場合もここへ倒す。 */
        GROUND
    }

    /**
     * この座標の真下に何があるか。空気が続く間だけ下へ辿る。
     *
     * <p>判定は{@code AStarPathfinder#addBridge}が上限とペナルティを決めるのに使う分類と揃えてある。
     * ここが食い違うと、探索が架けた橋に警告色が付かない（あるいは付きすぎる）。
     * 未ロードチャンクで走査が止まった場合だけは{@link Footing#GROUND}へ倒す——探索側はその橋を
     * そもそも作らないので、ここへ来るのは「経路を作った後にチャンクが外れた」場合だけで、
     * 分からないことを危険として描くと経路全体が警告色で埋まる。
     */
    private static Footing footingUnder(CellSource view, BlockPos pos) {
        for (int depth = 1; depth <= BRIDGE_LAVA_SCAN_DEPTH; depth++) {
            int y = pos.getY() - depth;
            long cell = view.cell(pos.getX(), y, pos.getZ());
            if (CellData.lava(cell)) {
                return Footing.LAVA;
            }
            if (CellData.present(cell)) {
                if (CellData.water(cell)) {
                    // 着水はバニラが落下距離をリセットするので、どれだけ落ちても死なない
                    return Footing.GROUND;
                }
                if (!CellData.passableEmpty(cell)) {
                    // 床は在る。あとは<b>何マス下か</b>——致死落差なら足場を外した結末は奈落と同じ
                    return depth >= view.fatalFallBlocks() ? Footing.FATAL_DROP : Footing.GROUND;
                }
                continue;
            }
            return view.isInBounds(pos.getX(), y, pos.getZ()) ? Footing.GROUND : Footing.VOID;
        }
        return Footing.VOID;
    }

    private static boolean isVoidBelow(CellSource view, BlockPos pos) {
        for (int depth = 1; depth <= VOID_SCAN_DEPTH; depth++) {
            // 見るのは本当の空虚だけ。水も梯子も落下を止めてくれるので、
            // occupiableWithoutDiggingで見ると水面の上を掘るたびに「下は奈落」と言い出す
            if (!CellData.passableEmpty(view.cell(pos.getX(), pos.getY() - depth, pos.getZ()))) {
                return false;
            }
        }
        return true;
    }
}
