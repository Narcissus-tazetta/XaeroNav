package net.prason.xaeronav.pathfinding.cost;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 掘削コスト計算。1セル分の素の破壊コストのみを扱う。
 * 落下ブロック連鎖は複数セルにまたがる話なので、必須セル群を知っている
 * {@code AStarPathfinder}側で一度だけスキャンする（ここで各セル個別に足すと二重計上になる）。
 *
 * <p>硬度・ツール速度は独自テーブルではなく、Minecraft本体が実際に使っている
 * {@link BlockState#getDestroySpeed} / {@link ItemStack#getDestroySpeed} をそのまま使う。
 * これにより硬度早見表を手で再実装せずに済み、バニラの挙動と常に一致する。
 * 例外は効率強化で、これだけはプレイヤー側の属性なので{@link ItemStack}からは取れず、
 * ここで{@code Player#getDigSpeed}と同じ式を再現している。
 *
 * <p>{@code BlockState#getDestroySpeed}は事前計算済みのフィールドを返すだけでlevelを参照しないため、
 * {@link EmptyBlockGetter}を渡してワーカースレッドから呼べる。ホットバーは
 * {@code ChunkView}がメインスレッドで複製したものを受け取る（ライブの{@code Inventory}を
 * ワーカースレッドから触ると競合するため）。
 */
public final class DigCost {

    private DigCost() {
    }

    public static double compute(ItemStack[] hotbar, int[] hotbarEfficiency, BlockState state) {
        if (!DiggableBlocks.isDiggable(state)) {
            return ActionCosts.INFEASIBLE;
        }

        float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0f) {
            return ActionCosts.INFEASIBLE;
        }

        double bestEffort = bestToolEffort(hotbar, hotbarEfficiency, state);
        if (Double.isInfinite(bestEffort)) {
            return ActionCosts.INFEASIBLE;
        }

        return hardness * bestEffort + ActionCosts.DIG_OVERHEAD_TICKS;
    }

    /**
     * ホットバー内の各アイテム（+素手）について「divisor / 速度」を計算し最小値を返す。
     * hardnessは全候補で共通なので、比較にhardnessを含める必要はない。
     */
    private static double bestToolEffort(ItemStack[] hotbar, int[] hotbarEfficiency, BlockState state) {
        double best = effort(ItemStack.EMPTY, 0, state);
        for (int slot = 0; slot < hotbar.length; slot++) {
            double e = effort(hotbar[slot], hotbarEfficiency[slot], state);
            if (e < best) {
                best = e;
            }
        }
        return best;
    }

    private static double effort(ItemStack stack, int efficiencyLevel, BlockState state) {
        double speed = stack.getDestroySpeed(state);
        if (speed <= 0.0) {
            return ActionCosts.INFEASIBLE;
        }
        // 効率強化は道具側の速度ではなくプレイヤーのMINING_EFFICIENCY属性として加算されるため、
        // ItemStack#getDestroySpeedには含まれない。加算条件（素の速度が1を超えるとき、
        // ＝その道具で掘れる対象のとき）もPlayer#getDigSpeedに合わせる
        if (speed > 1.0 && efficiencyLevel > 0) {
            speed += (double) efficiencyLevel * efficiencyLevel + 1.0;
        }
        boolean correctTool = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        double divisor = correctTool ? 30.0 : 100.0;
        return divisor / speed;
    }
}
