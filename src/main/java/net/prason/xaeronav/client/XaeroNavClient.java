package net.prason.xaeronav.client;

import net.prason.xaeronav.config.XaeroNavConfig;
import net.prason.xaeronav.pathfinding.cost.DiggableBlocks;

/**
 * ローダーのイベントから呼ばれる側の実体。ローダー固有のエントリポイントは、
 * 自分のイベントをここに置いた3つへ繋ぐだけにする。
 */
public final class XaeroNavClient {

    public static final PathRenderer PATH_RENDERER = new PathRenderer();
    public static final NavHud HUD = new NavHud();
    public static final ClientTickHandler TICK_HANDLER = new ClientTickHandler();

    private XaeroNavClient() {
    }

    /**
     * 掘削可否のブロックリストだけはIDからBlockへの解決結果を保持するので、設定ファイルの
     * 読み込み・再読み込みに自分で追随する必要がある（他の設定値は参照のたびに読むので何もしなくてよい）。
     */
    public static void reloadBlockLists() {
        DiggableBlocks.reloadFromConfig(XaeroNavConfig.INSTANCE.additionalDiggableBlocks(),
                XaeroNavConfig.INSTANCE.additionalForbiddenBlocks());
    }
}
