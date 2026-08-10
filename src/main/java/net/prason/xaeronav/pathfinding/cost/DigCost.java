package net.prason.xaeronav.pathfinding.cost;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 掘削コスト計算（design doc §3）。1セル分の素の破壊コストのみを扱う。
 * 落下ブロック連鎖（§3-3）は複数セルにまたがる話なので、必須セル群を知っている
 * {@code AStarPathfinder}側で一度だけスキャンする（ここで各セル個別に足すと二重計上になる）。
 *
 * <p>硬度・ツール速度は独自テーブルではなく、Minecraft本体が実際に使っている
 * {@link BlockState#getDestroySpeed} / {@link ItemStack#getDestroySpeed} をそのまま使う。
 * これにより硬度早見表やエンチャント補正を手で再実装せずに済み、バニラの挙動と常に一致する。
 *
 * <p><b>呼び出しはメインスレッド限定。</b>{@code player}/{@code level}はライブオブジェクトであり、
 * ワーカースレッドから触ると競合例外の原因になる（design doc §4-5）。ワールドスナップショット構築時に
 * ここで計算した結果（tick数のdouble）だけをスナップショットへ格納し、以降はワーカースレッドが
 * そのdoubleだけを読む。
 */
public final class DigCost {

    private DigCost() {
    }

    public static double compute(Player player, BlockGetter level, BlockPos pos, BlockState state) {
        if (ForbiddenBlocks.isForbidden(state)) {
            return ActionCosts.INFEASIBLE;
        }

        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0f) {
            return ActionCosts.INFEASIBLE;
        }

        double bestEffort = bestToolEffort(player.getInventory(), state);
        if (Double.isInfinite(bestEffort)) {
            return ActionCosts.INFEASIBLE;
        }

        return hardness * bestEffort + ActionCosts.DIG_OVERHEAD_TICKS;
    }

    /**
     * ホットバー内の各アイテム（+素手）について「divisor / 速度」を計算し最小値を返す。
     * hardnessは全候補で共通なので、比較にhardnessを含める必要はない。
     */
    private static double bestToolEffort(Inventory inventory, BlockState state) {
        double best = effort(ItemStack.EMPTY, state);
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            double e = effort(inventory.getItem(slot), state);
            if (e < best) {
                best = e;
            }
        }
        return best;
    }

    private static double effort(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        if (speed <= 0f) {
            return ActionCosts.INFEASIBLE;
        }
        boolean correctTool = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        double divisor = correctTool ? 30.0 : 100.0;
        return divisor / speed;
    }
}
