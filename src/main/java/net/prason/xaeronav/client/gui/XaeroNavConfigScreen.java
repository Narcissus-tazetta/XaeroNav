package net.prason.xaeronav.client.gui;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21 {
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
//?} else {
/*import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
*///?}
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
 *
 * <p>1.20.1の{@code OptionsSubScreen}（{@code net.minecraft.client.gui.screens}直下、1.21.1とは
 * パッケージが違う）は{@code addOptions()}フックを持たず、リスト・Doneボタンの組み立てを
 * 自前の{@code init()}で行う必要がある（{@code SimpleOptionsSubScreen}は2列(addSmall)固定の
 * レイアウトを強制するため使わない——日本語ラベルは長く1列(addBig)が必須）。
 */
public final class XaeroNavConfigScreen extends OptionsSubScreen {

    //? if <1.21 {
    /*private OptionsList list;
    *///?}

    public XaeroNavConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("gui.xaeronav.config.title"));
    }

    //? if >=1.21 {
    @Override
    protected void addOptions() {
        addAllOptions(this.list::addBig);
    }
    //?} else {
    /*@Override
    protected void init() {
        this.list = new OptionsList(this.minecraft, this.width, this.height, 32, this.height - 32, 25);
        addAllOptions(this.list::addBig);
        this.addWidget(this.list);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 27, 200, 20)
                .build());
    }
    *///?}

    // 日本語ラベルは長く、2列（addSmall）だと見切れるため全項目1列（addBig）で並べる。
    // 呼び出し方（this.list.addBig の参照先）だけがバージョンで違うので、一覧そのものは1箇所にする
    private static void addAllOptions(Consumer<OptionInstance<?>> addBig) {
        XaeroNavConfig cfg = XaeroNavConfig.INSTANCE;

        addBig.accept(boolOption("gui.xaeronav.config.digging_enabled",
                cfg.diggingEnabled(), cfg::setDiggingEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.bridging_enabled",
                cfg.bridgingEnabled(), cfg::setBridgingEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.lava_bridging_enabled",
                cfg.lavaBridgingEnabled(), cfg::setLavaBridgingEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.block_budget_enabled",
                cfg.blockBudgetEnabled(), cfg::setBlockBudgetEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.jump_gap_enabled",
                cfg.jumpGapEnabled(), cfg::setJumpGapEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.fall_damage_tolerance_enabled",
                cfg.fallDamageToleranceEnabled(), cfg::setFallDamageToleranceEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.deep_look_ahead_enabled",
                cfg.deepLookAheadEnabled(), cfg::setDeepLookAheadEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.flight_routing_enabled",
                cfg.flightRoutingEnabled(), cfg::setFlightRoutingEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.flight_clearance",
                cfg.flightClearanceDetourBlocks() > 0, cfg::setFlightClearanceEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.hud_enabled",
                cfg.hudEnabled(), cfg::setHudEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.straight_line_enabled",
                cfg.straightLineEnabled(), cfg::setStraightLineEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.goal_marker_enabled",
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
        XaeroNavConfig.save();
    }
}
