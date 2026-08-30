package net.prason.xaeronav.pathfinding.cost;

import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.prason.xaeronav.pathfinding.astar.AStarPathfinder;
import net.prason.xaeronav.pathfinding.astar.Heuristic;
import net.prason.xaeronav.pathfinding.astar.MovementType;
import net.prason.xaeronav.pathfinding.astar.PathResult;
import net.prason.xaeronav.pathfinding.astar.PathStep;
import net.prason.xaeronav.pathfinding.astar.SearchLimits;
import net.prason.xaeronav.pathfinding.world.FakeCells;
import net.prason.xaeronav.pathfinding.world.SearchBounds;
import org.junit.jupiter.api.Test;

/**
 * 下降コスト（{@link ActionCosts#DESCEND_ONE_BLOCK}）が実際の所要時間とどれだけ食い違い、
 * それが経路の形をどう歪めるかを測る。診断専用（アサートは現状を固定するものだけ）。
 */
class DescendCostReproTest {

    private static final BooleanSupplier NEVER = () -> false;

    /**
     * 走りながら1マスずつ下り続けるときの1マスあたりの実時間。平地のsprint(3.564)とほぼ同じ。
     *
     * <p>{@code LivingEntity#travel}の非流体分岐をそのまま回して求めた。毎tick
     * 「{@code moveRelative(加速)} → {@code move()} → 重力と減衰」で、加速は地上なら
     * {@code getSpeed()×(0.216/f²·f)}（{@code f}=0.6なので疾走の0.13そのもの）・空中なら
     * {@code getFlyingSpeed()}=0.026（sprint中）、減衰は地上{@code 0.6×0.91}=0.546・空中0.91。
     * 定常速度は{@code 加速/(1−減衰)}なので<b>空中0.2889 b/t &gt; 地上0.2863 b/t</b>——
     * 縁を踏み出しても水平は減速しない。
     *
     * <p>プレイヤー幅0.6のAABBで衝突を取り、階段状の地形（1マスごとに1マス下る）を64マス走らせた
     * 結果が3.45〜3.54 tick/マス。着地までの落下距離は最大2.69マスで{@link ActionCosts#SAFE_FALL_BLOCKS}
     * の内側＝無傷。
     */
    private static final double MEASURED_DESCEND_TICKS = 3.536;

    @Test
    void modelOverstatesDescendByFactorOfTwoAndAHalf() {
        double model = ActionCosts.DESCEND_ONE_BLOCK;
        System.out.printf("DESCEND_ONE_BLOCK   = %.3f tick%n", model);
        System.out.printf("実測（走り下り）      = %.3f tick%n", MEASURED_DESCEND_TICKS);
        System.out.printf("過大評価             = %.2f 倍%n", model / MEASURED_DESCEND_TICKS);
        System.out.printf("SPRINT_ONE_BLOCK    = %.3f tick（実測はこれとほぼ同じ）%n",
                ActionCosts.SPRINT_ONE_BLOCK);
    }

    /**
     * ネザー相当（{@code deepFallPossible=false}・落下ダメージ許容off）では下降の下限が
     * {@code descentBoundForMaxDrop(3)}=4.392まで締まる。このとき斜め下降1手の見積もりが
     * 実コストを上回っていないか。
     */
    @Test
    void diagonalDescendHeuristicUnderNetherBound() {
        double bound = ActionCosts.descentBoundForMaxDrop(ActionCosts.SAFE_FALL_BLOCKS);
        double estimate = Heuristic.estimate(0, 64, 0, 1, 63, 1, bound);
        double actual = ActionCosts.DIAGONAL_DESCEND_ONE_BLOCK;
        System.out.printf("%n=== 斜め下降1手（水平√2・1マス降下）ネザー相当の下限 %.3f ===%n", bound);
        System.out.printf("見積もり h = %.3f%n", estimate);
        System.out.printf("実コスト   = %.3f%n", actual);
        System.out.printf("%s（h %s 実コスト）%n", estimate > actual ? "非許容" : "許容",
                estimate > actual ? ">" : "<=");

        double cardinalEstimate = Heuristic.estimate(0, 64, 0, 1, 63, 0, bound);
        System.out.printf("カーディナル下降: h = %.3f / 実コスト = %.3f → %s%n",
                cardinalEstimate, ActionCosts.DESCEND_ONE_BLOCK,
                cardinalEstimate > ActionCosts.DESCEND_ONE_BLOCK ? "非許容" : "許容");
    }

    /**
     * なだらかな下り斜面（1マスずつ降りる）と、同じ高さを保ったまま回り込む平坦な道。
     * 現在のコストでは斜面1マスが徒歩2.6マス相当なので、平坦な迂回が実際より魅力的に見える。
     */
    @Test
    void slopeVersusFlatDetour() {
        // 東西に伸びる帯。z=0 は1マスずつ下る斜面、z=8..N は高さを保った平坦な棚で、
        // 終点の手前でまとめて降りる。どちらも同じ終点へ届く。
        int length = 40;
        int drop = 20;
        SearchBounds bounds = new SearchBounds(-8, 20, -8, length + 8, 80, 40);
        FakeCells cells = FakeCells.empty(bounds).fillWith(FakeCells.AIR).maxFallDamagePoints(0);

        int topY = 60;
        for (int x = 0; x <= length; x++) {
            // 斜面（z=0）: 2マス進むごとに1マス下がる
            int slopeY = topY - Math.min(drop, x / 2);
            cells.set(x, slopeY, 0, FakeCells.STONE);
            // 平坦な棚（z=1..20）: 高さを保つ
            for (int z = 1; z <= 20; z++) {
                cells.set(x, topY, z, FakeCells.STONE);
            }
        }
        // 棚の終端から斜面の終点へ降りる階段（x=length の列）
        for (int z = 1; z <= 20; z++) {
            cells.set(length, topY, z, FakeCells.STONE);
        }
        for (int d = 0; d <= drop; d++) {
            cells.set(length, topY - d, 1, FakeCells.STONE);
        }

        BlockPos start = new BlockPos(0, topY + 1, 0);
        BlockPos goal = new BlockPos(length, topY - Math.min(drop, length / 2) + 1, 0);
        PathResult result = new AStarPathfinder(cells, new SearchLimits(500_000, 20_000, 1.5))
                .search(start, goal, NEVER, 0);

        int maxZ = 0;
        double total = 0;
        int descends = 0;
        for (PathStep step : result.steps()) {
            maxZ = Math.max(maxZ, Math.abs(step.pos().getZ()));
            total += step.cost();
            if (step.movement() == MovementType.DESCEND) {
                descends++;
            }
        }
        System.out.printf("%n=== 斜面 vs 平坦な迂回 ===%n");
        System.out.printf("到達=%s steps=%d 総コスト=%.1f 下降手数=%d 棚へ逸れた最大z=%d%n",
                result.complete(), result.steps().size(), total, descends, maxZ);
        System.out.printf("下降が総コストに占める割合 = %.1f%%（実測コストなら %.1f%%）%n",
                descends * ActionCosts.DESCEND_ONE_BLOCK / total * 100.0,
                descends * MEASURED_DESCEND_TICKS
                        / (total - descends * (ActionCosts.DESCEND_ONE_BLOCK - MEASURED_DESCEND_TICKS)) * 100.0);
    }

    /**
     * 「谷を越える」——降りて登り返す道と、高さを保ったまま回り込む道。下降が2.6倍高いぶん、
     * 谷越えが実際より嫌われる。何マスの迂回と釣り合うかを測る。
     */
    @Test
    void valleyCrossingVersusRim() {
        int depth = 12;
        System.out.printf("%n=== 谷越えの値段（深さ%dマス） ===%n", depth);
        double modelDown = depth * ActionCosts.DESCEND_ONE_BLOCK;
        double realDown = depth * MEASURED_DESCEND_TICKS;
        double up = depth * ActionCosts.ASCEND_ONE_BLOCK;
        System.out.printf("降りる: モデル %.1f / 実測 %.1f tick%n", modelDown, realDown);
        System.out.printf("登る  : %.1f tick（ジャンプが要るので実際に遅い）%n", up);
        System.out.printf("往復のモデル %.1f → 実測 %.1f。差 %.1f tick = 平坦な迂回 %.1f マス分%n",
                modelDown + up, realDown + up, modelDown - realDown,
                (modelDown - realDown) / ActionCosts.SPRINT_ONE_BLOCK);
    }
}
