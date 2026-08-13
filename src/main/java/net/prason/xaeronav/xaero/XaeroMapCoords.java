package net.prason.xaeronav.xaero;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * {@code GuiMapRightClickMixin}・{@code GuiMapKeyMixin}が共有する、地図上の座標にまつわる小さな判定。
 * どちらも「地図上の1点（右クリック位置／マウスカーソル位置）から経路探索の目的地を作る」という
 * 同じ処理をXaeroの別々のフック（右クリックメニュー／キー入力）から行うため、判定ロジックが重複する。
 *
 * <p>mixin適用後は対象クラス側（mixinパッケージの外）から呼ばれるため、Mixinのクラスローダーに
 * 弾かれないようmixinパッケージの外に置く必要がある。
 */
public final class XaeroMapCoords {

    /**
     * 地図に高さの情報が無い座標であることを表す番兵値。Xaero自身も、この値のときは
     * 座標表示からYを省いている。
     */
    public static final int UNKNOWN_HEIGHT = 32767;

    private XaeroMapCoords() {
    }

    /**
     * 地図側の次元情報（{@code null}なら未取得）が、プレイヤーが今いる次元と食い違っていないか。
     * 別次元の地図を見ているときは、座標が縮尺変換された値になるうえ、そもそも歩いて行けない。
     */
    public static boolean isSameDimensionAsPlayer(ResourceKey<Level> mapDim, Level playerLevel) {
        return mapDim == null || mapDim == playerLevel.dimension();
    }

    /** 高さが分からない座標はプレイヤーと同じ高さを狙う（探索範囲は上下にも広がるため近くまでは届く）。 */
    public static int resolveGoalY(int mapY, Player player) {
        return mapY == UNKNOWN_HEIGHT ? player.blockPosition().getY() : mapY;
    }
}
