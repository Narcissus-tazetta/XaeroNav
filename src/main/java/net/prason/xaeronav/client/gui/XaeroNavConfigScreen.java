package net.prason.xaeronav.client.gui;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.prason.xaeronav.config.XaeroNavConfig;

/**
 * {@link XaeroNavConfig}のうちトグル系の項目だけを並べる設定画面。
 *
 * <p>探索範囲・逸脱閾値・地上高さ等の数値系パラメータと掘削禁止ブロックの追加リストはここに置かない。
 * たまにしか触らない設定で、TOMLの直接編集で足りるため。
 *
 * <p>{@link OptionsSubScreen}はバニラのビデオ設定画面などと同じ土台（1列レイアウト・スクロール・
 * Doneボタン）を提供する。{@code options}引数はバニラの{@link net.minecraft.client.Options}に
 * 触れる場合にだけ使うフックで、このMODでは使わない。
 */
public final class XaeroNavConfigScreen extends OptionsSubScreen {

    public XaeroNavConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("gui.xaeronav.config.title"));
    }

    @Override
    protected void addOptions() {
        XaeroNavConfig cfg = XaeroNavConfig.INSTANCE;

        // 日本語ラベルは長く、2列（addSmall）だと見切れるため全項目1列（addBig）で並べる
        this.list.addBig(boolOption("gui.xaeronav.config.digging_enabled",
                cfg.diggingEnabled(), cfg::setDiggingEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.bridging_enabled",
                cfg.bridgingEnabled(), cfg::setBridgingEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.lava_bridging_enabled",
                cfg.lavaBridgingEnabled(), cfg::setLavaBridgingEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.block_budget_enabled",
                cfg.blockBudgetEnabled(), cfg::setBlockBudgetEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.jump_gap_enabled",
                cfg.jumpGapEnabled(), cfg::setJumpGapEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.fall_damage_tolerance_enabled",
                cfg.fallDamageToleranceEnabled(), cfg::setFallDamageToleranceEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.deep_look_ahead_enabled",
                cfg.deepLookAheadEnabled(), cfg::setDeepLookAheadEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.flight_routing_enabled",
                cfg.flightRoutingEnabled(), cfg::setFlightRoutingEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.flight_clearance",
                cfg.flightClearanceDetourBlocks() > 0, cfg::setFlightClearanceEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.hud_enabled",
                cfg.hudEnabled(), cfg::setHudEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.straight_line_enabled",
                cfg.straightLineEnabled(), cfg::setStraightLineEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.goal_marker_enabled",
                cfg.goalMarkerEnabled(), cfg::setGoalMarkerEnabled));
    }

    private static OptionInstance<Boolean> boolOption(String key, boolean initial, Consumer<Boolean> setter) {
        return OptionInstance.createBoolean(key, initial, setter::accept);
    }

    /**
     * {@link OptionsSubScreen#onClose}がDoneボタン・Escの両方から呼ばれる。superの中で
     * {@code list.applyUnsavedChanges()}が走り、全項目が{@link XaeroNavConfig}へ{@code set}済みに
     * なった後で、まとめて1回だけディスクへ書き出す。
     */
    @Override
    public void onClose() {
        super.onClose();
        XaeroNavConfig.SPEC.save();
    }
}
