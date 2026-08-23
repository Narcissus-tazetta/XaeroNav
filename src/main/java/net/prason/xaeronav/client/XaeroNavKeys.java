package net.prason.xaeronav.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.prason.xaeronav.XaeroNav;
import net.prason.xaeronav.client.gui.XaeroNavConfigScreen;
import net.prason.xaeronav.config.XaeroNavConfig;

/**
 * キーバインド。既定はすべて未割り当てにしてある — 他のMODと取り合いになる操作ではないので、
 * 使いたい人が空いているキーへ自分で割り当てる方が事故が少ない。
 *
 * <p>「見ているブロックへ経路探索」は、Xaeroを入れていない環境で唯一まともな目的地の指定手段になる
 * （それ以外は{@code /xaeronav goto <座標>}で座標を打ち込むしかない）。
 */
public final class XaeroNavKeys {

    private static final String CATEGORY = "key.categories.xaeronav";

    public static final KeyMapping GOTO_LOOKING_AT = unbound("key.xaeronav.goto_looking_at");
    public static final KeyMapping CLEAR = unbound("key.xaeronav.clear");
    public static final KeyMapping TOGGLE_HUD = unbound("key.xaeronav.toggle_hud");
    public static final KeyMapping OPEN_CONFIG_SCREEN = unbound("key.xaeronav.open_config_screen");

    /**
     * Xaeroの世界地図画面（{@code GuiMap}）が開いている間だけ効く、カーソル位置への経路探索キー。
     * Xaero自身の地図内ショートカット（B=ウェイポイント作成 等）と同じ、Controls画面から設定する
     * 通常のKeyMappingだが、{@link #handleInput}（通常プレイ中に毎tick消費するループ）では扱わない
     * ——地図画面はMinecraftのキーイベントを自分で先取りするため、判定は
     * {@code mixin.xaero.GuiMapKeyMixin}がGuiMap#keyPressedへの注入から直接{@code matches}で行う。
     */
    public static final KeyMapping GOTO_MAP_CURSOR = unbound("key.xaeronav.goto_map_cursor");

    private XaeroNavKeys() {
    }

    private static KeyMapping unbound(String name) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(GOTO_LOOKING_AT);
        event.register(CLEAR);
        event.register(TOGGLE_HUD);
        event.register(OPEN_CONFIG_SCREEN);
        event.register(GOTO_MAP_CURSOR);
    }

    /**
     * 押されたぶんだけ処理する。{@code consumeClick}はキューを1つ取り出すので、
     * 「押しっぱなしで毎tick発火」にはならない。
     */
    static void handleInput() {
        Minecraft mc = Minecraft.getInstance();

        // 設定画面を開くだけの操作はプレイヤー/ワールドの状態を必要としないので、下のガードより前に
        // 消費する。ガードの後ろに置くと、タイトル画面など未ロード中に押した分がキューに残ったまま
        // ワールドへ入った瞬間に（何も押していないのに）画面が開く、という事故になる
        while (OPEN_CONFIG_SCREEN.consumeClick()) {
            mc.setScreen(new XaeroNavConfigScreen(mc.screen));
        }

        if (mc.player == null || mc.level == null) {
            return;
        }
        while (GOTO_LOOKING_AT.consumeClick()) {
            gotoLookingAt(mc);
        }
        while (CLEAR.consumeClick()) {
            PathfindingState.INSTANCE.clear();
            mc.player.displayClientMessage(Component.translatable("commands.xaeronav.cleared"), true);
        }
        while (TOGGLE_HUD.consumeClick()) {
            boolean enabled = !XaeroNavConfig.INSTANCE.hudEnabled();
            XaeroNavConfig.INSTANCE.setHudEnabled(enabled);
            XaeroNavConfig.SPEC.save();
            mc.player.displayClientMessage(Component.translatable(enabled
                    ? "hud.xaeronav.hud_on"
                    : "hud.xaeronav.hud_off"), true);
        }
    }

    /** ブロック操作用のリーチ距離（4.5〜5マス程度）ではなく、描画距離相当まで狙えるようにする */
    private static final double LOOK_PICK_DISTANCE = 512.0;

    private static void gotoLookingAt(Minecraft mc) {
        HitResult hit = mc.player.pick(LOOK_PICK_DISTANCE, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            mc.player.displayClientMessage(Component.translatable("hud.xaeronav.no_block_in_view"), true);
            return;
        }
        // 狙ったブロックの中ではなく、その上に立ちたい。地面を見て指定するのが普通の使い方なので、
        // 1マス上を渡す（実際に立てるかどうかはStanceFinderが寄せ直す）
        PathfindingState.INSTANCE.setGoal(blockHit.getBlockPos().above());
        mc.player.displayClientMessage(Component.translatable("commands.xaeronav.goal_walk",
                blockHit.getBlockPos().toShortString()), true);
        XaeroNav.LOGGER.debug("XaeroNav: 見ているブロックへ経路探索 {}", blockHit.getBlockPos());
    }
}
