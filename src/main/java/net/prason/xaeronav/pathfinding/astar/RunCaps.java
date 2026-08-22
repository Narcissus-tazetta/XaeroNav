package net.prason.xaeronav.pathfinding.astar;

import net.prason.xaeronav.pathfinding.world.CellSource;

/**
 * 「危ないことを何マス／何tickまで続けてよいか」の上限。どれも0が無制限を表す。
 *
 * <p>コストの重みではなく<b>移動の生成そのもの</b>で切るための値をまとめたもの。重みで抑えると、
 * A*は安い辺から展開するので危ない道に手を伸ばす前に周囲を展開し尽くし、展開ノード数を焼く
 * （{@code ActionCosts#LAVA_BRIDGE_PENALTY_TICKS}に記録された実測）。
 *
 * @param maxLavaBridgeRunBlocks 溶岩の上の橋だけに掛かる、より厳しい上限。橋の連続長そのものは
 *                               {@code maxBridgeRunBlocks}と共通なので、実際に効くのは両者の小さい方
 */
public record RunCaps(int maxBridgeRunBlocks, int maxLavaBridgeRunBlocks, int maxSubmergedTicks) {

    /**
     * 上限なし。上限のせいで探索範囲内に道が一本も無くなったときの、詰み回避の探し直しに使う
     * （「長い橋も溺れる危険も最後の手段だが、詰みよりはマシ」という優先順）。
     */
    public static final RunCaps NONE = new RunCaps(0, 0, 0);

    public static RunCaps of(CellSource view) {
        return new RunCaps(view.maxBridgeRunBlocks(), view.maxLavaBridgeRunBlocks(), view.maxSubmergedTicks());
    }

    /** 溶岩の上で実際に効く上限。橋の長さの上限も同時に掛かるので、厳しい方が勝つ。 */
    public int effectiveLavaBridgeRun() {
        if (maxBridgeRunBlocks == 0 || maxLavaBridgeRunBlocks == 0) {
            return Math.max(maxBridgeRunBlocks, maxLavaBridgeRunBlocks);
        }
        return Math.min(maxBridgeRunBlocks, maxLavaBridgeRunBlocks);
    }
}
