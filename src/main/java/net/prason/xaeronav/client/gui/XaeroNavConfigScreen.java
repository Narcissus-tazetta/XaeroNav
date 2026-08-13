package net.prason.xaeronav.client.gui;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.prason.xaeronav.config.XaeroNavConfig;

/**
 * design doc §6 Phase3項目15の続き。「土台」として既存TOML項目のうちトグル系だけを並べる画面にし、
 * 以降の機能追加のたびにここへ項目を足していく方式にする（[[xaeronav-dimension-nav-plan]] 実装順序案1）。
 *
 * <p>探索範囲・逸脱閾値・地上高さ等の数値系パラメータはユーザーとの相談の結果、GUI化の対象から外した
 * （2026-08-13）。たまにしか触らない・TOMLの直接編集で十分という判断。掘削禁止ブロック追加リストも同様の
 * 理由で対象外——GUIに置くほど頻繁に変える設定ではない。
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
        this.list.addBig(boolOption("gui.xaeronav.config.hud_enabled",
                cfg.hudEnabled(), cfg::setHudEnabled));
        this.list.addBig(boolOption("gui.xaeronav.config.straight_line_enabled",
                cfg.straightLineEnabled(), cfg::setStraightLineEnabled));
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
