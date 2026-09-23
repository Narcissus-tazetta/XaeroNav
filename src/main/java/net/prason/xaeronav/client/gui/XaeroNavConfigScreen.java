package net.prason.xaeronav.client.gui;

//? if >=1.17 {
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
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.prason.xaeronav.config.XaeroNavConfig;
*///?}

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
//? if >=1.17 {
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
        addAllOptions(XaeroNavConfig.INSTANCE, this.list::addBig);
    }
    //?} else {
    /*@Override
    protected void init() {
        this.list = new OptionsList(this.minecraft, this.width, this.height, 32, this.height - 32, 25);
        addAllOptions(XaeroNavConfig.INSTANCE, this.list::addBig);
        this.addWidget(this.list);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 27, 200, 20)
                .build());
    }
    *///?}

    // 日本語ラベルは長く、2列（addSmall）だと見切れるため全項目1列（addBig）で並べる。
    // 呼び出し方（this.list.addBig の参照先）だけがバージョンで違うので、一覧そのものは1箇所にする。
    // cfgを引数化しているのはテスト用（XaeroNavConfigScreenTest）——本番はXaeroNavConfig.INSTANCEを渡すだけ。
    // publicなのは、load済みのXaeroNavConfigをNightConfigStore経由で作るテストがconfigパッケージ側にあるため
    public static void addAllOptions(XaeroNavConfig cfg, Consumer<OptionInstance<?>> addBig) {
        addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.digging_enabled",
                "gui.xaeronav.config.digging_enabled.tooltip", cfg.diggingEnabled(), cfg::setDiggingEnabled));
        addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.bridging_enabled",
                "gui.xaeronav.config.bridging_enabled.tooltip", cfg.bridgingEnabled(), cfg::setBridgingEnabled));
        addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.lava_bridging_enabled",
                "gui.xaeronav.config.lava_bridging_enabled.tooltip",
                cfg.lavaBridgingEnabled(), cfg::setLavaBridgingEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.block_budget_enabled",
                cfg.blockBudgetEnabled(), cfg::setBlockBudgetEnabled));
        addBig.accept(boolOption("gui.xaeronav.config.jump_gap_enabled",
                cfg.jumpGapEnabled(), cfg::setJumpGapEnabled));
        addBig.accept(boolOptionWithTooltip("gui.xaeronav.config.fall_damage_tolerance_enabled",
                "gui.xaeronav.config.fall_damage_tolerance_enabled.tooltip",
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
        addBig.accept(boolOption("gui.xaeronav.config.danger_dashed_enabled",
                cfg.dangerDashedEnabled(), cfg::setDangerDashedEnabled));
    }

    private static OptionInstance<Boolean> boolOption(String key, boolean initial, Consumer<Boolean> setter) {
        return OptionInstance.createBoolean(key, initial, setter::accept);
    }

    /**
     * 安全性・所持品への影響がある項目にだけ付ける短い補足。全項目に付けると
     * どれも同じ重みに見えて読み飛ばされるので、実際に結果が変わる項目に絞る。
     */
    private static OptionInstance<Boolean> boolOptionWithTooltip(String key, String tooltipKey, boolean initial,
                                                                  Consumer<Boolean> setter) {
        return OptionInstance.createBoolean(key,
                OptionInstance.cachedConstantTooltip(Component.translatable(tooltipKey)),
                initial, setter::accept);
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
//?} else {
/*public final class XaeroNavConfigScreen extends Screen {
    private static final int PAGE_SIZE = 7;
    private final Screen parent;
    private final List<Toggle> toggles = new ArrayList<>();
    private int page;

    public XaeroNavConfigScreen(Screen parent) {
        super(new TranslatableComponent("gui.xaeronav.config.title"));
        this.parent = parent;
        XaeroNavConfig cfg = XaeroNavConfig.INSTANCE;
        add("gui.xaeronav.config.digging_enabled", cfg::diggingEnabled, cfg::setDiggingEnabled);
        add("gui.xaeronav.config.bridging_enabled", cfg::bridgingEnabled, cfg::setBridgingEnabled);
        add("gui.xaeronav.config.lava_bridging_enabled", cfg::lavaBridgingEnabled, cfg::setLavaBridgingEnabled);
        add("gui.xaeronav.config.block_budget_enabled", cfg::blockBudgetEnabled, cfg::setBlockBudgetEnabled);
        add("gui.xaeronav.config.jump_gap_enabled", cfg::jumpGapEnabled, cfg::setJumpGapEnabled);
        add("gui.xaeronav.config.fall_damage_tolerance_enabled", cfg::fallDamageToleranceEnabled, cfg::setFallDamageToleranceEnabled);
        add("gui.xaeronav.config.deep_look_ahead_enabled", cfg::deepLookAheadEnabled, cfg::setDeepLookAheadEnabled);
        add("gui.xaeronav.config.flight_routing_enabled", cfg::flightRoutingEnabled, cfg::setFlightRoutingEnabled);
        add("gui.xaeronav.config.flight_clearance", () -> cfg.flightClearanceDetourBlocks() > 0, cfg::setFlightClearanceEnabled);
        add("gui.xaeronav.config.hud_enabled", cfg::hudEnabled, cfg::setHudEnabled);
        add("gui.xaeronav.config.straight_line_enabled", cfg::straightLineEnabled, cfg::setStraightLineEnabled);
        add("gui.xaeronav.config.goal_marker_enabled", cfg::goalMarkerEnabled, cfg::setGoalMarkerEnabled);
        add("gui.xaeronav.config.danger_dashed_enabled", cfg::dangerDashedEnabled, cfg::setDangerDashedEnabled);
    }

    private void add(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
        toggles.add(new Toggle(key, getter, setter));
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        for (int i = page * PAGE_SIZE; i < Math.min(toggles.size(), (page + 1) * PAGE_SIZE); i++) {
            Toggle toggle = toggles.get(i);
            int y = 38 + (i % PAGE_SIZE) * 25;
            addButton(new Button(left, y, 300, 20, toggle.label(), button -> {
                toggle.setter.accept(!toggle.getter.getAsBoolean());
                button.setMessage(toggle.label());
            }));
        }
        if (page > 0) {
            addButton(new Button(left, height - 52, 95, 20, new TranslatableComponent("gui.back"), button -> changePage(-1)));
        }
        if ((page + 1) * PAGE_SIZE < toggles.size()) {
            addButton(new Button(left + 205, height - 52, 95, 20, new TranslatableComponent("gui.next"), button -> changePage(1)));
        }
        addButton(new Button(width / 2 - 100, height - 27, 200, 20,
                new TranslatableComponent("gui.done"), button -> onClose()));
    }

    private void changePage(int delta) {
        page += delta;
        init(minecraft, width, height);
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        drawCenteredString(pose, font, title, width / 2, 15, 0xFFFFFF);
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        XaeroNavConfig.save();
        minecraft.setScreen(parent);
    }

    private static final class Toggle {
        final String key;
        final BooleanSupplier getter;
        final Consumer<Boolean> setter;

        Toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
            this.key = key;
            this.getter = getter;
            this.setter = setter;
        }

        Component label() {
            return new TranslatableComponent(key).append(": " + (getter.getAsBoolean() ? "ON" : "OFF"));
        }
    }
}
*///?}
