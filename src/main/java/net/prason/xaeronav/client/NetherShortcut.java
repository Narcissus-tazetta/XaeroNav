package net.prason.xaeronav.client;

import java.util.OptionalInt;

/**
 * オーバーワールドで遠い目的地へ向かうときの、ネザー側の換算距離。ネザーは水平距離が1/8になる。
 *
 * <p>ポータルの位置・行き先・ネザー内の道は見ないので、所要時間の比較にはしない。出すのは距離の換算だけで、
 * 判断はプレイヤーに返す。
 */
final class NetherShortcut {

    /**
     * これより近い目的地には出さない。ポータルを作る・潜る手間が、1/8になる分より大きいと見なす足切りで、
     * 実測した値ではない。
     */
    static final int MIN_OVERWORLD_BLOCKS = 1000;

    private static final int NETHER_SCALE = 8;

    private NetherShortcut() {
    }

    /** {@code horizontalBlocks}は目的地までの水平距離。足切りより近ければ空。 */
    static OptionalInt netherBlocks(double horizontalBlocks) {
        if (horizontalBlocks < MIN_OVERWORLD_BLOCKS) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.round(horizontalBlocks / NETHER_SCALE));
    }
}
